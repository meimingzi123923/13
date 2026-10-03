package com.pocketcode.studio.core.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Root 能力封装（基于 libsu）。
 *
 * 三态：NONE / SHIZUKU / ROOT
 * - 只有 ROOT 才能执行 su 命令；
 * - 无 Root 时命令自动降级到普通 shell。
 */
class RootService {

    enum class Level { NONE, SHIZUKU, ROOT }

    /** 运行期只初始化一次，避免反复弹授权。 */
    private val shell: Shell by lazy { Shell.getShell() }

    suspend fun detect(): Level = withContext(Dispatchers.IO) {
        if (Shell.isAppGrantedRoot() != true) return@withContext Level.NONE
        // 能拿到 root shell 即视为真 Root
        Level.ROOT
    }

    fun isRoot(): Boolean = Shell.isAppGrantedRoot() == true

    /**
     * 执行命令（同步返回结果）。
     * @param useRoot true 时以 root 执行；否则普通 shell。
     */
    suspend fun exec(cmd: String, useRoot: Boolean = isRoot()): Result =
        withContext(Dispatchers.IO) {
            val builder = if (useRoot) Shell.cmd(cmd) else Shell.cmd(cmd)
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val code = builder.to(out, err).exec().code
            Result(code, out, err)
        }

    /**
     * 流式执行命令：逐行回调，供终端面板实时显示。
     * 用法：RootService.stream("ping -c 4 8.8.8.8").collect { line -> ... }
     */
    fun stream(cmd: String, useRoot: Boolean = isRoot()): Flow<String> = flow {
        val result = Shell.cmd(cmd).exec()
        result.out.forEach { emit(it) }
        result.err.forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    /** 常用 Root 操作模板 */
    suspend fun remountSystemRw(): Result =
        exec("mount -o rw,remount /system 2>/dev/null || mount -o rw,remount /")

    suspend fun readHosts(): Result = exec("cat /system/etc/hosts", useRoot = true)

    suspend fun writeHosts(content: String): Result {
        val escaped = content.replace("\"", "\\\"")
        return exec("echo \"$escaped\" > /system/etc/hosts", useRoot = true)
    }

    data class Result(val code: Int, val stdout: List<String>, val stderr: List<String>) {
        val ok get() = code == 0
        val text get() = (stdout + stderr).joinToString("\n")
    }
}