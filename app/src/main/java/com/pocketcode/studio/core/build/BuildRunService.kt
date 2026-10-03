package com.pocketcode.studio.core.build

import android.content.Context
import com.pocketcode.studio.core.sandbox.JsSandbox
import com.pocketcode.studio.core.sandbox.NativeToolchain
import com.pocketcode.studio.core.sandbox.SandboxRuntime
import com.pocketcode.studio.core.terminal.TerminalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 构建/运行服务：读取项目 .pcs/run.json，组装命令并交给终端执行。
 *
 * 执行策略（按语言）：
 *  - C / C++：使用 [NativeToolchain]（APK 内置 Android 原生 clang，无 proot、无 rootfs）；
 *  - JavaScript：App 进程内 [JsSandbox]（QuickJS），不依赖系统 shell；
 *  - 其它语言：回退到用户自备运行时（可用 [SandboxRuntime.installFromZip] 导入）。
 *
 * 支持用户显式指定语言（language 参数）：
 *  - language == "auto"：优先读 .pcs/run.json，其次按文件后缀推断；
 *  - language 为具体语言：忽略后缀，直接使用该语言对应的运行命令。
 */
class BuildRunService(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 用户自备运行时（python / node / …）的导入与解析。 */
    private val sandbox = SandboxRuntime(context)

    /** 内置原生 C/C++ 工具链（clang / lld / libc++ …，无 proot）。 */
    private val native = NativeToolchain(context)

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

    /**
     * 生成实际命令字符串。
     * @param envPrefix 前置环境变量（如 [NativeToolchain.envPrefix] 或 [SandboxRuntime.envPrefix]）。
     */
    fun buildCommand(cfg: RunConfig, projectDir: File, entryFile: File, envPrefix: String = ""): String {
        val out = cfg.output ?: "a.out"
        val vars = mapOf(
            "entry" to entryFile.name,
            "file" to entryFile.absolutePath,
            "out" to out,
        )
        var cmd = cfg.run
        vars.forEach { (k, v) -> cmd = cmd.replace("{$k}", v) }
        val userEnv = cfg.env.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val prefix = envPrefix + if (userEnv.isBlank()) "" else "$userEnv "
        return "cd ${sh(projectDir.absolutePath)} && ${prefix}$cmd"
    }

    private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 真正执行：把组装好的命令写入 TerminalService 的 PTY。
     * - 会话存在（或可创建）时交由 PTY 交互式执行；
     * - PTY 不可用（JNI 未就绪/降级）时返回 -1。
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

        // 1) JS 且沙盒内没有 node：直接在 App 进程内用 QuickJS 沙盒执行，完全不依赖系统 shell。
        if (SandboxRuntime.runsInProcess(cfg.language) && !sandbox.isAvailable(cfg.language)) {
            val file = if (entryFile.isFile) entryFile else File(projectDir, cfg.entry)
            if (!file.isFile) {
                terminal("\u001b[31m✗ 找不到入口文件：${file.name}\u001b[0m")
                return@withContext -1
            }
            terminal("\u001b[36m\u25b6 应用沙盒（QuickJS）执行 ${file.name}\u001b[0m")
            val js = runCatching { JsSandbox { line -> terminal(line) } }.getOrElse {
                terminal("\u001b[31m✗ QuickJS 沙盒不可用：${it.message}\u001b[0m")
                return@withContext -1
            }
            return@withContext try {
                js.eval(file.readText())
            } finally {
                js.close()
            }
        }

        // 2) C / C++：使用 APK 内置的 Android 原生工具链（clang，无 proot）。
        if (native.provides(cfg.language)) {
            if (!native.available()) {
                terminal("\u001b[36m\u25b6 首次释放内置 C/C++ 工具链（clang / lld，约 150MB）…\u001b[0m")
            }
            if (runCatching { native.ensure(log = terminal) }.getOrDefault(false)) {
                cfg.build?.let { b ->
                    terminal("\u001b[90m$ " + b.replace("{entry}", entryFile.name)
                        .replace("{out}", cfg.output ?: "a.out") + "\u001b[0m")
                }
                terminal("\u001b[36m\u25b6 原生工具链（clang）执行\u001b[0m")
                val cmd = buildCommand(cfg, projectDir, entryFile, native.envPrefix())
                return@withContext runPty(cmd, root, terminal)
            }
            terminal("\u001b[31m✗ 工具链释放失败，回退为系统命令\u001b[0m")
        }

        // 3) 其它语言：使用用户自备运行时（沙盒 bin/），否则回退为裸命令（可能提示 command not found）。
        val cmd = buildCommand(cfg, projectDir, entryFile, sandbox.envPrefix())
        terminal("\u001b[36m\u25b6 $cmd\u001b[0m")
        SandboxRuntime.toolFor(cfg.language)?.let { tool ->
            if (!sandbox.isAvailable(cfg.language)) {
                terminal("\u001b[33m\u26a0 应用沙盒中未找到 $tool：该语言需自备运行时（可导入运行时安装包）\u001b[0m")
            }
        }

        // 已有编译步骤（如 C/C++）时先跑构建
        cfg.build?.let { b ->
            val buildCmd = b.replace("{entry}", entryFile.name)
                .replace("{out}", cfg.output ?: "a.out")
            terminal("\u001b[90m$ buildCmd\u001b[0m")
        }

        runPty(cmd, root, terminal)
    }

    /** 把命令写入 PTY 执行；返回 0 表示已提交，-1 表示 PTY 不可用。 */
    private fun runPty(cmd: String, root: Boolean, terminal: (String) -> Unit): Int {
        val fd = TerminalService.ensureSession(root = root)
        if (fd >= 0) {
            TerminalService.write(cmd)
            return 0
        }
        terminal("\u001b[31m\u2717 PTY 不可用，已跳过执行（请确认 libpcs_term 已编译/加载）\u001b[0m")
        return -1
    }
}