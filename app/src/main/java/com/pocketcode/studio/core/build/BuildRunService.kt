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

    /** 通过 ProcessBuilder 执行命令，捕获输出（不回显命令）。 */
    private fun exec(args: List<String>, dir: File, env: Map<String, String>): ExecResult {
        return try {
            // 用 sh -c 执行，兼容 clang 等 shell 包装脚本（shebang）
            val cmdLine = args.joinToString(" ") { shArg(it) }
            val pb = ProcessBuilder("sh", "-c", cmdLine).directory(dir)
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            ExecResult(code, out, err)
        } catch (e: Exception) {
            ExecResult(-1, "", "执行异常：${e.message}\n")
        }
    }

    private fun shArg(s: String): String {
        // 简单参数直接返回，含特殊字符的加单引号
        return if (s.any { it.isWhitespace() || it in "\"'\\$`" }) {
            "'" + s.replace("'", "'\\''") + "'"
        } else s
    }

    /**
     * 编译并运行 C 源码，通过 [terminal] 回调输出编译报错与程序运行结果。
     * @return 0 成功，非 0 失败码。
     */
    suspend fun run(
        projectDir: File,
        sourceFile: File,
        terminal: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        try {
            if (!native.available()) {
                terminal("▸ 首次释放内置 C 工具链（clang / lld）…\n")
            }
            if (!runCatching { native.ensure(log = {}) }.getOrDefault(false)) {
                terminal("✗ 工具链释放失败\n")
                return@withContext -1
            }

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
                return@withContext compile.exit
            }

            // 运行，捕获标准输出与错误
            val run = exec(listOf("./a.out"), dir, env)
            if (run.stdout.isNotEmpty()) terminal(run.stdout)
            if (run.stderr.isNotEmpty()) terminal(run.stderr)
            run.exit
        } catch (e: Throwable) {
            terminal("运行异常：${e.message ?: e.javaClass.simpleName}\n")
            -1
        }
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
        if (!runCatching { native.ensure(log = {}) }.getOrDefault(false)) {
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
