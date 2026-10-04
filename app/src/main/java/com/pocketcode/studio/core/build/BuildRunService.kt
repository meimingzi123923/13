package com.pocketcode.studio.core.build

import android.content.Context
import com.pocketcode.studio.core.sandbox.NativeToolchain
import com.pocketcode.studio.core.terminal.TerminalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 构建/运行服务（仅 C 语言）。
 *
 * 使用 APK 内置的 Android 原生 clang 工具链（[NativeToolchain]）编译并运行 C 源码，
 * 无需 proot、无 rootfs、不依赖系统 shell 之外的任何外部运行时。
 *
 * 工作区位于应用私有目录（filesDir/workspace），天然可执行，
 * 不再受 /sdcard 的 noexec 挂载与 FUSE 权限问题困扰。
 */
class BuildRunService(private val context: Context) {

    /** 内置原生 C 工具链（clang / lld / libc++ …，无 proot）。 */
    private val native = NativeToolchain(context)

    /** 生成实际命令字符串：编译 + 运行。 */
    fun buildCommand(projectDir: File, sourceFile: File): String {
        // clang 包装脚本已注入 Android 原生编译参数，产物放在工作区（私有目录，可执行）
        val cmd = "clang ${sourceFile.name} -o a.out && ./a.out"
        return "cd ${sh(projectDir.absolutePath)} && ${native.envPrefix()}$cmd"
    }

    private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 编译并运行 C 源码。
     * @return 0 表示已提交到 PTY 执行，-1 表示工具链/PTY 不可用。
     */
    suspend fun run(
        projectDir: File,
        sourceFile: File,
        terminal: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        // 首次使用时释放工具链
        if (!native.available()) {
            terminal("\u001b[36m\u25b6 首次释放内置 C 工具链（clang / lld）…\u001b[0m")
        }
        if (!runCatching { native.ensure(log = terminal) }.getOrDefault(false)) {
            terminal("\u001b[31m✗ 工具链释放失败\u001b[0m")
            return@withContext -1
        }

        val cmd = buildCommand(projectDir, sourceFile)
        terminal("\u001b[90m$ clang ${sourceFile.name} -o a.out && ./a.out\u001b[0m")
        runPty(cmd, terminal)
    }

    /** 把命令写入 PTY 执行；返回 0 表示已提交，-1 表示 PTY 不可用。 */
    private fun runPty(cmd: String, terminal: (String) -> Unit): Int {
        val fd = TerminalService.ensureSession(root = false)
        if (fd >= 0) {
            TerminalService.write(cmd)
            return 0
        }
        terminal("\u001b[31m\u2717 终端不可用，请确认 libpcs_term 已编译加载\u001b[0m")
        return -1
    }
}
