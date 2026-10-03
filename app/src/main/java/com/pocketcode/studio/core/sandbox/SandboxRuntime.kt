package com.pocketcode.studio.core.sandbox

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * 应用沙盒运行时管理器。
 *
 * 目标：让编写的程序在 App 私有目录（filesDir/sandbox）中的「自带运行时」上执行，
 * 不依赖手机 /system/bin/sh 里是否安装了 python/node/gcc —— 这正是原来
 * BuildRunService 直接调用 python3/node 却必然失败的原因。
 *
 * 沙盒目录结构：
 *   filesDir/sandbox/
 *     bin/   可执行文件（python3、node、gcc、g++、go …）
 *     lib/   运行库（.so / 依赖库），运行时通过 LD_LIBRARY_PATH 指向它
 *     tmp/   临时目录，通过 TMPDIR 指向它
 *
 * 运行时来源（按优先级）：
 *   1) APK 内置：assets/runtimes/bin、assets/runtimes/lib 在运行时释放到沙盒；
 *   2) 运行时安装包：installFromZip() 导入 zip（bin/ 与 lib/ 原样解出）；
 *   3) 单文件导入：importExecutable()。
 *
 * 详见 docs/10-应用沙盒运行时.md。
 */
class SandboxRuntime(private val context: Context) {

    companion object {
        /** APK 内置运行时所在 assets 目录。 */
        const val ASSET_ROOT = "runtimes"

        /** 沙盒目录名（位于 filesDir 下）。 */
        const val SANDBOX_DIR = "sandbox"

        /** 语言 -> 主可执行文件名。 */
        fun toolFor(language: String): String? = when (language.lowercase()) {
            "python" -> "python3"
            "javascript", "js", "node" -> "node"
            "typescript", "ts" -> "npx"
            "c" -> "gcc"
            "cpp", "c++" -> "g++"
            "go" -> "go"
            "rust" -> "cargo"
            "java" -> "java"
            else -> null
        }

        /** 是否属于「可在 App 进程内沙盒直接运行」的语言（无需外部二进制）。 */
        fun runsInProcess(language: String): Boolean =
            language.lowercase() in setOf("javascript", "js")

        private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }

    val root: File get() = File(context.filesDir, SANDBOX_DIR)
    val binDir: File get() = File(root, "bin")
    val libDir: File get() = File(root, "lib")
    val tmpDir: File get() = File(root, "tmp")

    /** 创建沙盒目录结构。 */
    fun ensureLayout() {
        binDir.mkdirs()
        libDir.mkdirs()
        tmpDir.mkdirs()
    }
    /**
     * 把 APK 内置的 assets/runtimes/{bin,lib} 释放到沙盒目录。
     * assets 中的文件没有执行位，复制后需补 setExecutable。
     * 已存在且非空的文件默认跳过，避免每次启动重复 IO。
     *
     * @return 本次实际写出的文件数。
     */
    fun ensureExtracted(force: Boolean = false): Int {
        ensureLayout()
        var n = 0
        n += copyAssetTree("$ASSET_ROOT/bin", binDir, executable = true, force = force)
        n += copyAssetTree("$ASSET_ROOT/lib", libDir, executable = false, force = force)
        return n
    }

    private fun copyAssetTree(assetPath: String, destDir: File, executable: Boolean, force: Boolean): Int {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull() ?: return 0
        if (children.isEmpty()) return 0
        var n = 0
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val grandchildren = runCatching { context.assets.list(childAsset) }.getOrNull() ?: emptyArray()
            if (grandchildren.isNotEmpty()) {
                n += copyAssetTree(childAsset, File(destDir, name), executable, force)
                continue
            }
            val out = File(destDir, name)
            out.parentFile?.mkdirs()
            if (!force && out.isFile && out.length() > 0) continue
            runCatching {
                context.assets.open(childAsset).use { ins ->
                    out.outputStream().use { ins.copyTo(it) }
                }
                if (executable) out.setExecutable(true, false)
                n++
            }
        }
        return n
    }

    /** 解析某个工具的可执行文件；沙盒中不存在返回 null。 */
    fun resolve(tool: String): File? {
        val f = File(binDir, tool)
        if (!f.isFile) return null
        if (!f.canExecute()) f.setExecutable(true, false)
        return f
    }

    /** 指定语言在沙盒中是否可用。 */
    fun isAvailable(language: String): Boolean = toolFor(language)?.let { resolve(it) != null } == true

    /** 沙盒中已就绪的可执行文件列表。 */
    fun installedTools(): List<String> =
        binDir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    /**
     * 生成命令环境前缀：让 `<tool> …` 解析到沙盒 bin，
     * 动态库走沙盒 lib，临时文件落在沙盒 tmp。
     */
    fun envPrefix(): String = buildString {
        append("PATH=").append(sh(binDir.absolutePath)).append(":\$PATH ")
        append("LD_LIBRARY_PATH=").append(sh(libDir.absolutePath)).append(":\$LD_LIBRARY_PATH ")
        append("TMPDIR=").append(sh(tmpDir.absolutePath)).append(" ")
        append("HOME=").append(sh(root.absolutePath)).append(" ")
    }

    /**
     * 从 zip 导入运行时（离线安装 / 在线下载后安装）。
     * zip 内 bin/ 下的文件会被赋予可执行权限。
     */
    fun installFromZip(zip: File, force: Boolean = true): Int {
        ensureLayout()
        var n = 0
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val rel = e.name.replace('\\', '/').removePrefix("/")
                if (rel.startsWith("bin/") || rel.startsWith("lib/")) {
                    val out = File(root, rel)
                    if (!force && out.isFile && out.length() > 0) return@forEach
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                    if (rel.startsWith("bin/")) out.setExecutable(true, false)
                    n++
                }
            }
        }
        return n
    }

    /** 把单个用户挑选的可执行文件导入沙盒 bin。 */
    fun importExecutable(src: File, name: String = src.name): File {
        ensureLayout()
        val out = File(binDir, name)
        src.inputStream().use { ins -> out.outputStream().use { ins.copyTo(it) } }
        out.setExecutable(true, false)
        return out
    }

    // ============================================================
    // proot + Alpine 根文件系统：让沙盒拥有 gcc / python3 / node 等完整 Linux 工具链
    //
    // Android 的 /system/bin/sh 里没有这些解释器；这里内置一份静态 proot
    // （assets/runtimes/bin/proot）与 Alpine aarch64 minirootfs
    // （assets/runtimes/rootfs/alpine-minirootfs-aarch64.tar.gz），
    // 运行时把程序放进 rootfs 内执行，缺失的编译工具用 apk 现场安装。
    // ============================================================

    private val rootfsArchive = "alpine-minirootfs-aarch64.tar.gz"

    /** Alpine 根文件系统解压根目录。 */
    val rootfsDir: File get() = File(root, "rootfs")

    /** 解压完成标记；存在即视为已就绪。 */
    private val rootfsStamp: File get() = File(rootfsDir, ".ready")

    /** 静态 proot 可执行文件；不存在返回 null。 */
    fun proot(): File? = resolve("proot")

    /** proot + rootfs 是否都已就绪。 */
    fun hasRootfs(): Boolean =
        proot() != null && rootfsStamp.isFile && File(rootfsDir, "bin/sh").exists()

    /**
     * 幂等释放内置 Alpine 根文件系统。
     * @return 是否可用。
     */
    fun ensureRootfs(force: Boolean = false): Boolean {
        ensureExtracted() // 先保证 bin/proot 已释放
        if (!force && hasRootfs()) return true
        val pr = proot() ?: return false
        runCatching { pr.setExecutable(true, false) }
        val input = runCatching { context.assets.open("$ASSET_ROOT/rootfs/$rootfsArchive") }
            .getOrNull() ?: return false
        if (force) rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()
        return runCatching {
            GZIPInputStream(input).use { gz ->
                TarArchiveInputStream(gz).use { tar -> extractTar(tar, rootfsDir) }
            }
            rootfsStamp.writeText("ok")
            true
        }.getOrDefault(false)
    }

    /** 解出 tar 条目（文件 / 目录 / 符号链接 / 硬链接）。 */
    private fun extractTar(tar: TarArchiveInputStream, dest: File) {
        var e = tar.nextEntry
        while (e != null) {
            val name = e.name.trimStart('/')
            if (name.isNotEmpty() && !name.contains("..")) {
                val out = File(dest, name)
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        runCatching { Files.deleteIfExists(out.toPath()) }
                        runCatching { Files.createSymbolicLink(out.toPath(), Paths.get(e.linkName)) }
                    }
                    e.linkName.isNotEmpty() -> { // 硬链接
                        out.parentFile?.mkdirs()
                        val target = File(dest, e.linkName.trimStart('/'))
                        runCatching { Files.deleteIfExists(out.toPath()) }
                        val r = runCatching { Files.createLink(out.toPath(), target.toPath()) }
                        if (r.isFailure && target.isFile) {
                            runCatching { target.copyTo(out, overwrite = true) }
                        }
                    }
                    e.isFile -> {
                        out.parentFile?.mkdirs()
                        runCatching { out.outputStream().use { os -> tar.copyTo(os) } }
                        if (e.mode and 0b001_000_000 != 0) {
                            runCatching { out.setExecutable(true, false) }
                        }
                    }
                }
            }
            e = tar.nextEntry
        }
    }

    /** rootfs 内是否已安装某工具（gcc / python3 / node …）。 */
    fun rootfsHasTool(tool: String): Boolean {
        if (!hasRootfs()) return false
        return listOf("usr/bin", "usr/local/bin", "bin", "sbin", "usr/sbin")
            .any { File(File(rootfsDir, it), tool).isFile }
    }

    /** 指定语言在 rootfs 内是否可用。 */
    fun rootfsAvailable(language: String): Boolean =
        toolFor(language)?.let { rootfsHasTool(it) } == true

    /** 语言 -> Alpine 软件包（运行时缺失则用 apk 安装）。 */
    fun packagesFor(language: String): List<String> = when (language.lowercase()) {
        "python" -> listOf("python3")
        "javascript", "js", "node" -> listOf("nodejs")
        "typescript", "ts" -> listOf("nodejs", "npm")
        "c", "cpp", "c++" -> listOf("build-base")
        "go" -> listOf("go")
        "rust" -> listOf("rust", "cargo")
        "java" -> listOf("openjdk17")
        else -> emptyList()
    }

    /**
     * 把用户命令包进 proot 沙盒执行。
     * -0 伪造 root；-b 绑定 /dev、/proc、/sys 与 /sdcard（工程目录在 rootfs 内可见）；
     * 工作目录 -w /root，命令内部再自行 cd。
     */
    fun prootWrap(userCmd: String, bindSdcard: Boolean = true): String? {
        val pr = proot() ?: return null
        if (!hasRootfs()) return null
        return buildString {
            append(sh(pr.absolutePath)).append(" --link2symlink -0 -r ")
                .append(sh(rootfsDir.absolutePath))
            append(" -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random")
            if (bindSdcard) append(" -b /sdcard -b /storage -b /mnt")
            append(" -w /root /usr/bin/env -i HOME=/root LANG=C.UTF-8 TMPDIR=/tmp")
            append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            append(" /bin/sh -lc ").append(sh(userCmd))
        }
    }

    /** 生成「在 proot 内安装该语言工具链」的完整命令；不可用返回 null。 */
    fun installPackagesCmd(language: String): String? {
        val pkgs = packagesFor(language)
        if (pkgs.isEmpty()) return null
        return prootWrap("apk add --no-cache ${pkgs.joinToString(" ")}")
    }
}