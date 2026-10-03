package com.pocketcode.studio.core.sandbox

import android.content.Context
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 原生工具链（无 proot）。
 *
 * 把一份「Android 原生（bionic）Clang 工具链」直接跑在 App 私有目录里，让 C/C++ 项目
 * 能在手机上编译运行，**完全不需要 proot 容器和 Linux rootfs**。
 *
 * 来源：Termux aarch64 官方 .deb（已实测可在 Android 真机原生直跑）：
 *  - PT_INTERP = /system/bin/linker64（设备自带）；
 *  - 动态库靠 LD_LIBRARY_PATH 指向本目录 lib/；
 *  - clang/lld 对 Termux 前缀的硬编码只有 RUNPATH 一处，可被环境变量覆盖。
 *
 * 为什么把 .deb 原样打进 assets、而不是解包成散文件：
 *  1) 单文件体积小（合计 83MB 小于 GitHub 100MB 单文件上限，可直接进 git，无需 LFS）；
 *  2) deb 内部是 tar，**天然保留符号链接与权限位**——散文件放 assets 会丢失这两者
 *     （实测：/sdcard FUSE 不保留 symlink 与 exec 位，会导致 clang 起不来）。
 *
 * 详见 docs/12-原生工具链.md
 */
class NativeToolchain(private val context: Context) {

    companion object {
        /** APK 内置工具链所在 assets 目录。 */
        const val ASSET_DIR = "toolchain"

        /** Termux 前缀（deb 内路径），解包时剥掉。 */
        private const val TERMUX_PREFIX = "data/data/com.termux/files/usr/"

        /** 需要解包的 deb 清单（clangd 由 clang.deb 提供，已保留）。 */
        val DEB_FILES = listOf(
            "clang.deb", "libllvm.deb", "lld.deb", "llvm.deb",
            "libcxx.deb", "libcompiler-rt.deb", "ndk-sysroot.deb",
            "zlib.deb", "zstd.deb", "liblzma.deb", "libxml2.deb",
            "libffi.deb", "libiconv.deb", "libandroid-glob.deb", "ncurses.deb",
        )

        fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }

    /** 工具链解压根目录。 */
    val root: File get() = File(context.filesDir, "sandbox/tc")
    val binDir: File get() = File(root, "bin")
    val libDir: File get() = File(root, "lib")
    val includeDir: File get() = File(root, "include")
    val tmpDir: File get() = File(context.filesDir, "sandbox/tmp")

    /** 解压完成标记。 */
    private val stamp: File get() = File(root, ".ready")

    /** clang 主程序（不存在返回 null）。 */
    fun clang(): File? = File(binDir, "clang-21").takeIf { it.isFile }

    /** 工具链是否已就绪。 */
    fun available(): Boolean = stamp.isFile && clang() != null

    /**
     * 幂等释放工具链。
     * @param log 进度回调（会显示到终端）。
     * @return 是否可用。
     */
    fun ensure(force: Boolean = false, log: (String) -> Unit = {}): Boolean {
        if (!force && available()) return true
        if (force) root.deleteRecursively()
        root.mkdirs()
        binDir.mkdirs()
        libDir.mkdirs()
        includeDir.mkdirs()
        tmpDir.mkdirs()
        for (name in DEB_FILES) {
            val ok = runCatching { extractDeb(name) }
                .onFailure { log("\u001b[31m✗ $name 解包失败：${it.message}\u001b[0m") }
                .getOrDefault(false)
            if (!ok) return false
            log("\u001b[90m  · $name\u001b[0m")
        }
        fixups()
        runCatching { stamp.writeText("ok") }
        return available()
    }

    // ============================================================
    // 解包：deb(ar) → data.tar.xz → tar
    // ============================================================

    private fun extractDeb(name: String): Boolean {
        context.assets.open("$ASSET_DIR/$name").use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                var e = ar.nextEntry
                while (e != null) {
                    if (e.name.startsWith("data.tar")) {
                        XZCompressorInputStream(ar).use { xz ->
                            TarArchiveInputStream(xz).use { tar -> extractTar(tar) }
                        }
                        return true
                    }
                    e = ar.nextEntry
                }
            }
        }
        return false
    }

    private fun extractTar(tar: TarArchiveInputStream) {
        var e = tar.nextEntry
        while (e != null) {
            val rel = normalize(e.name)
            if (rel != null) {
                val out = File(root, rel)
                when {
                    e.isDirectory -> out.mkdirs()

                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        runCatching { Files.deleteIfExists(out.toPath()) }
                        val target = linkTarget(e.linkName, out)
                        runCatching { Files.createSymbolicLink(out.toPath(), target) }
                    }

                    e.isLink -> { // 硬链接
                        out.parentFile?.mkdirs()
                        val target = File(root, normalize(e.linkName) ?: e.linkName)
                        runCatching { Files.deleteIfExists(out.toPath()) }
                        val r = runCatching { Files.createLink(out.toPath(), target.toPath()) }
                        if (r.isFailure && target.isFile) {
                            runCatching { target.copyTo(out, overwrite = true) }
                        }
                    }

                    else -> {
                        out.parentFile?.mkdirs()
                        runCatching { out.outputStream().use { os -> tar.copyTo(os) } }
                        if (e.mode and 0b001_001_001 != 0) {
                            runCatching { out.setExecutable(true, false) }
                        }
                    }
                }
            }
            e = tar.nextEntry
        }
    }

    /** 把 deb 内路径转成相对 root 的路径；不在 Termux 前缀下的返回 null。 */
    private fun normalize(name: String): String? {
        var n = name
        if (n.startsWith("./")) n = n.substring(2)
        if (n.startsWith("/")) n = n.substring(1)
        if (!n.startsWith(TERMUX_PREFIX)) return null
        n = n.substring(TERMUX_PREFIX.length)
        if (n.isEmpty() || n.contains("..")) return null
        return n
    }

    /** 解析软链目标：绝对路径且在本工具链内 → 转成相对路径，保证可重定位。 */
    private fun linkTarget(link: String, out: File): Path {
        if (link.startsWith("/")) {
            val rel = normalize(link)
            if (rel != null) {
                val abs = File(root, rel)
                runCatching {
                    return out.parentFile!!.toPath().relativize(abs.toPath())
                }
            }
        }
        return Paths.get(link)
    }

    // ============================================================
    // 解包后修补：软链 + 包装脚本
    // ============================================================

    private fun fixups() {
        // clang 系：Termux 包内没有裸名 `clang`，补上（clang 按 argv[0] 判定 C++ 模式）
        symlink("clang-21", "clang")
        symlink("clang-21", "clang++")
        symlink("lld", "ld.lld")
        symlink("lld", "ld")

        // ndk-sysroot：asm/ 是平台相关目录的软链
        runCatching {
            val asm = File(includeDir, "asm")
            Files.deleteIfExists(asm.toPath())
            Files.createSymbolicLink(asm.toPath(), Paths.get("aarch64-linux-android/asm"))
        }

        // gcc/g++ 包装脚本：自动注入 Android 原生编译所需参数，
        // 这样「gcc main.c -o a.out」这种最朴素的命令也能直接用。
        wrapper("gcc", clangDriver("clang-21"))
        wrapper("cc", clangDriver("clang-21"))
        wrapper("g++", clangDriver("clang++"))
        wrapper("c++", clangDriver("clang++"))
    }

    private fun symlink(target: String, name: String) {
        runCatching {
            val f = File(binDir, name)
            Files.deleteIfExists(f.toPath())
            Files.createSymbolicLink(f.toPath(), Paths.get(target))
        }
    }

    /** 包装脚本里实际调用的 clang 驱动。 */
    private fun clangDriver(name: String) = "${binDir.absolutePath}/$name"

    /** 编译/链接所需参数（Android 原生 bionic 目标）。 */
    fun ccFlags(): String = buildString {
        append("-fuse-ld=lld ")
        append("-B").append(sh(binDir.absolutePath)).append(" ")
        append("-L").append(sh(libDir.absolutePath)).append(" ")
        append("-isystem ").append(sh(includeDir.absolutePath)).append(" ")
        append("-Wl,-rpath,").append(sh(libDir.absolutePath))
    }

    private fun wrapper(name: String, driver: String) {
        val content = buildString {
            append("#!/system/bin/sh\n")
            append("exec ").append(sh(driver)).append(" ").append(ccFlags()).append(" \"$@\"\n")
        }
        runCatching {
            val f = File(binDir, name)
            Files.deleteIfExists(f.toPath())
            f.writeText(content)
            f.setExecutable(true, false)
        }
    }

    // ============================================================
    // 命令装配
    // ============================================================

    /** 让工具链工具被 PATH 解析、动态库被找到、临时文件落私有目录。 */
    fun envPrefix(): String = buildString {
        append("PATH=").append(sh(binDir.absolutePath)).append(":\$PATH ")
        append("LD_LIBRARY_PATH=").append(sh(libDir.absolutePath)).append(":\$LD_LIBRARY_PATH ")
        append("TMPDIR=").append(sh(tmpDir.absolutePath)).append(" ")
        append("HOME=").append(sh(root.absolutePath)).append(" ")
    }

    /** 该语言是否由本工具链提供。 */
    fun provides(language: String): Boolean = language.lowercase() in setOf("c", "cpp", "c++")
}
