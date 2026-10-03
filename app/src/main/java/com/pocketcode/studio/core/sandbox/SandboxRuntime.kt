package com.pocketcode.studio.core.sandbox

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * 通用「运行时导入」沙盒。
 *
 * 注意：自 v1.1 起，C/C++ 编译由 [NativeToolchain]（APK 内置 Android 原生 clang，
 * 无 proot、无 rootfs）负责；原先的 proot + Alpine 方案已整体移除。
 *
 * 本类只保留「用户自备解释器 / 运行时」的导入与解析能力：
 * 用户可以离线导入一个 zip（bin/ 与 lib/）或单个可执行文件，之后
 * python / node / go … 这类语言就能在沙盒 bin/ 中被找到并执行。
 *
 * 沙盒目录结构（filesDir/sandbox/）：
 *   bin/   可执行文件（python3、node …）
 *   lib/   运行库（.so / 依赖库），运行时通过 LD_LIBRARY_PATH 指向它
 *   tmp/   临时目录，通过 TMPDIR 指向它
 *
 * 详见 docs/10-应用沙盒运行时.md。
 */
class SandboxRuntime(private val context: Context) {

    companion object {
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
}
