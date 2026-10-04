package com.pocketcode.studio.core.build

import android.content.Context
import com.pocketcode.studio.core.sandbox.NativeToolchain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 构建/运行服务（仅 C 语言）。
 *
 * 使用 APK 内置的 Android 原生 clang 工具链（[NativeToolchain]）编译并运行 C 源码。
 * 编译与运行均通过 ProcessBuilder 直接执行，**不经过 PTY**，
 * 因此终端只显示编译报错与程序输出，不会回显命令或环境变量。
 */
class BuildRunService(private val context: Context) {

    private val native = NativeToolchain(context)

    /** 子进程执行结果。 */
    private data class ExecResult(val exit: Int, val stdout: String, val stderr: String)

    /**
     * 通过 ProcessBuilder 执行命令，捕获输出（不回显命令）。
     *
     * 关键修复：stdout / stderr 必须**并发消费**，绝不能「先 readText(stdout) 再 readText(stderr)」。
     * 否则当子进程向 stderr 写了超过管道缓冲（典型 64KB）的数据时，子进程会阻塞在 write，
     * 而父进程仍卡在 readText(stdout) 上，形成死锁——表现为编译/运行时应用卡死或 ANR。
     * 这里用两个后台线程并发读取，并在 waitFor 前先 join，彻底消除死锁。
     */
    private fun exec(args: List<String>, dir: File, env: Map<String, String>): ExecResult {
        var proc: Process? = null
        try {
            // 用 sh -c 执行，兼容 clang 等 shell 包装脚本（shebang）
            val cmdLine = args.joinToString(" ") { shArg(it) }
            val pb = ProcessBuilder("sh", "-c", cmdLine).directory(dir)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            proc = pb.start()

            // 并发读取 stdout / stderr，避免管道缓冲写满导致的死锁
            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val outThread = Thread({
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { outBuf.append(it).append('\n') }
                }
            }, "pcs-exec-out").apply { isDaemon = true }
            val errThread = Thread({
                runCatching {
                    proc.errorStream.bufferedReader().forEachLine { errBuf.append(it).append('\n') }
                }
            }, "pcs-exec-err").apply { isDaemon = true }
            outThread.start()
            errThread.start()

            val code = proc.waitFor()
            // waitFor 返回后，确保两个读取线程都结束再加入
            outThread.join(5000)
            errThread.join(5000)
            return ExecResult(code, outBuf.toString(), errBuf.toString())
        } catch (e: Exception) {
            runCatching { proc?.destroy() }
            return ExecResult(-1, "", "执行异常：${e.message}\n")
        }
    }

    private fun shArg(s: String): String {
        // 简单参数直接返回，含特殊字符的加单引号
        return if (s.any { it.isWhitespace() || it in "\"'\\$`" }) {
            "'" + s.replace("'", "'\\''") + "'"
        } else s
    }

    /**
     * 仅编译 C 源码（不运行），通过 [terminal] 回调输出编译报错。
     * @return 0 编译成功，非 0 失败码。
     */
    suspend fun compile(
        projectDir: File,
        sourceFile: File,
        terminal: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        try {
            if (!ensureToolchain(terminal)) return@withContext -1

            val dir = sourceFile.parentFile ?: projectDir
            val env = native.envMap()

            // 编译（关闭彩色诊断，输出干净文本）
            val compile = exec(
                listOf("clang", "-fno-color-diagnostics", sourceFile.name, "-o", "a.out"),
                dir,
                env,
            )
            if (compile.exit != 0) {
                terminal(compile.stderr.ifEmpty { "编译失败（exit ${compile.exit}）\n" })
            } else {
                terminal("✓ 编译成功\n")
            }
            compile.exit
        } catch (e: Throwable) {
            terminal("编译异常：${e.message ?: e.javaClass.simpleName}\n")
            -1
        }
    }

    /**
     * 运行已编译产物（a.out），通过 [terminal] 回调输出程序输出。
     * @return 0 成功，非 0 失败码。
     */
    suspend fun run(
        projectDir: File,
        sourceFile: File,
        terminal: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        try {
            val dir = sourceFile.parentFile ?: projectDir
            val out = File(dir, "a.out")
            if (!out.exists()) {
                terminal("✗ 尚未编译，请先编译\n")
                return@withContext -1
            }
            val env = native.envMap()
            val run = exec(listOf("./a.out"), dir, env)
            if (run.stdout.isNotEmpty()) terminal(run.stdout)
            if (run.stderr.isNotEmpty()) terminal(run.stderr)
            run.exit
        } catch (e: Throwable) {
            terminal("运行异常：${e.message ?: e.javaClass.simpleName}\n")
            -1
        }
    }

    /** 确保内置工具链已释放（把解包进度透传到终端，避免首次解包 80MB 时误以为卡死）。 */
    private suspend fun ensureToolchain(terminal: (String) -> Unit): Boolean {
        if (!native.available()) {
            terminal("▸ 首次释放内置 C 工具链（clang / lld）…\n")
        }
        if (!runCatching { native.ensure(log = { terminal(it + "\n") }) }.getOrDefault(false)) {
            terminal("✗ 工具链释放失败\n")
            return false
        }
        return true
    }

    /**
     * 编译并运行 C 源码（一键），通过 [terminal] 回调输出编译报错与程序运行结果。
     * @return 0 成功，非 0 失败码。
     */
    suspend fun runWithCompile(
        projectDir: File,
        sourceFile: File,
        terminal: (String) -> Unit,
    ): Int {
        val compCode = compile(projectDir, sourceFile, terminal)
        if (compCode != 0) return compCode
        return run(projectDir, sourceFile, terminal)
    }

    // ─────────────────────────────────────────────
    // 实时语法检查
    // ─────────────────────────────────────────────

    data class SyntaxError(
        val line: Int,
        val column: Int,
        val message: String,
        val isError: Boolean,
    )

    suspend fun syntaxCheck(sourceFile: File): List<SyntaxError> = withContext(Dispatchers.IO) {
        if (!runCatching { native.ensure(log = { android.util.Log.i("PcsToolchain", it) }) }.getOrDefault(false)) {
            return@withContext emptyList()
        }
        val dir = sourceFile.parentFile ?: return@withContext emptyList()
        val env = native.envMap()
        runCatching {
            val result = exec(
                listOf("clang", "-fsyntax-only", "-Wall", "-fno-color-diagnostics", sourceFile.name),
                dir,
                env,
            )
            parseClangOutput(result.stderr)
        }.getOrDefault(emptyList())
    }

    private fun parseClangOutput(output: String): List<SyntaxError> {
        val errors = mutableListOf<SyntaxError>()
        val regex = Regex("""^[^:]+:(\d+):(\d+):\s+(error|warning):\s+(.+)$""")
        output.lineSequence().forEach { line ->
            regex.find(line)?.let { m ->
                errors.add(
                    SyntaxError(
                        line = m.groupValues[1].toInt(),
                        column = m.groupValues[2].toInt(),
                        message = m.groupValues[4].trim(),
                        isError = m.groupValues[3] == "error",
                    )
                )
            }
        }
        return errors
    }
}