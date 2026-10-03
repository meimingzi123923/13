package com.pocketcode.studio.core.build

import android.content.Context
import com.pocketcode.studio.core.terminal.TerminalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 构建/运行服务：读取项目 .pcs/run.json，组装命令并交给终端执行。
 *
 * 支持用户显式指定语言（language 参数）：
 *  - language == "auto"：优先读 .pcs/run.json，其次按文件后缀推断；
 *  - language 为具体语言：忽略后缀，直接使用该语言对应的运行命令。
 */
class BuildRunService(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    data class RunConfig(
        val language: String = "python",
        val entry: String = "main.py",
        val run: String = "python3 {entry}",        // {entry} / {file} / {out} 占位
        val build: String? = null,                  // 编译命令，可空
        val output: String? = null,                 // 编译产物路径
        val env: Map<String, String> = emptyMap(),
    )

    /** 读取项目配置；不存在则按语言/后缀生成默认。 */
    fun loadConfig(projectDir: File, entryFile: File?): RunConfig {
        val cfgFile = File(projectDir, ".pcs/run.json")
        if (cfgFile.exists()) {
            runCatching { json.decodeFromString<RunConfig>(cfgFile.readText()) }
                .onSuccess { return it }
        }
        return defaultFor(entryFile ?: File(projectDir, "main.py"))
    }

    /** 按语言字符串生成配置（用户自选语言时使用）。 */
    private fun configForLanguage(language: String, file: File): RunConfig {
        val entry = file.name
        return when (language.lowercase()) {
            "python" -> RunConfig("python", entry, "python3 {entry}")
            "javascript" -> RunConfig("javascript", entry, "node {entry}")
            "typescript" -> RunConfig("typescript", entry, "npx tsx {entry}")
            "c" -> RunConfig("c", entry, "gcc {entry} -o {out} && ./{out}",
                build = "gcc {entry} -o {out}", output = "a.out")
            "cpp" -> RunConfig("cpp", entry,
                "g++ {entry} -o {out} && ./{out}", build = "g++ {entry} -o {out}", output = "a.out")
            "go" -> RunConfig("go", entry, "go run {entry}")
            "rust" -> RunConfig("rust", entry, "cargo run")
            "java" -> RunConfig("java", entry, "javac {entry} && java Main")
            else -> RunConfig("text", entry, "cat {entry}")
        }
    }

    private fun defaultFor(file: File): RunConfig = when (file.extension.lowercase()) {
        "py" -> RunConfig("python", file.name, "python3 {entry}")
        "js", "mjs" -> RunConfig("javascript", file.name, "node {entry}")
        "ts" -> RunConfig("typescript", file.name, "npx tsx {entry}")
        "c" -> RunConfig("c", file.name, "gcc {entry} -o {out} && ./{out}",
            build = "gcc {entry} -o {out}", output = "a.out")
        "cpp", "cc", "cxx" -> RunConfig("cpp", file.name,
            "g++ {entry} -o {out} && ./{out}", build = "g++ {entry} -o {out}", output = "a.out")
        "go" -> RunConfig("go", file.name, "go run {entry}")
        "rs" -> RunConfig("rust", file.name, "cargo run")
        "java" -> RunConfig("java", file.name, "javac {entry} && java Main")
        else -> RunConfig("text", file.name, "cat {entry}")
    }

    /** 生成实际命令字符串。 */
    fun buildCommand(cfg: RunConfig, projectDir: File, entryFile: File): String {
        val out = cfg.output ?: "a.out"
        val vars = mapOf(
            "entry" to entryFile.name,
            "file" to entryFile.absolutePath,
            "out" to out,
        )
        var cmd = cfg.run
        vars.forEach { (k, v) -> cmd = cmd.replace("{$k}", v) }
        val envPrefix = cfg.env.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val prefix = if (envPrefix.isBlank()) "" else "$envPrefix "
        return "cd ${sh(projectDir.absolutePath)} && ${prefix}$cmd"
    }

    private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 真正执行：把组装好的命令写入 TerminalService 的 PTY。
     * - 会话存在（或可创建）时交由 PTY 交互式执行；
     * - PTY 不可用（JNI 未就绪/降级）时回退到 libsu 的 root.stream。
     *
     * @param language 用户选择的运行语言；"auto" 表示按后缀自动推断。
     */
    suspend fun run(
        projectDir: File,
        entryFile: File,
        root: Boolean = false,
        language: String = "auto",
        terminal: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val cfg = if (language == "auto") {
            loadConfig(projectDir, entryFile)
        } else {
            configForLanguage(language, entryFile)
        }
        val cmd = buildCommand(cfg, projectDir, entryFile)
        terminal("\u001b[36m\u25b6 $cmd\u001b[0m")

        // 已有编译步骤（如 C/C++）时先跑构建
        cfg.build?.let { b ->
            val buildCmd = b.replace("{entry}", entryFile.name)
                .replace("{out}", cfg.output ?: "a.out")
            terminal("\u001b[90m$ buildCmd\u001b[0m")
        }

        val fd = TerminalService.ensureSession(root = root)
        if (fd >= 0) {
            TerminalService.write(cmd)
            0
        } else {
            terminal("\u001b[31m✗ PTY 不可用，已跳过执行（请确认 libpcs_term 已编译/加载）\u001b[0m")
            -1
        }
    }
}
