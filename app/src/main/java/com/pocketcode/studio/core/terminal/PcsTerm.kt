package com.pocketcode.studio.core.terminal

/**
 * JNI 绑定层：与 app/src/main/cpp/term.cpp 一一对应。
 *
 * 为什么单独抽一个 [object]：
 *  - external 方法若声明在 `companion object` 中，其 JNI 符号名在某些 Kotlin 版本会变成
 *    `Java_pkg_Outer_00024Companion_method`（`$` 编码为 `_00024`），极易导致 UnsatisfiedLinkError；
 *  - 放在单例 `object PcsTerm` 中，符号名稳定为 `Java_com_pocketcode_studio_core_terminal_PcsTerm_<method>`，
 *    与 term.cpp 中的 C 函数名严格一致，无歧义。
 *
 * C 侧函数：
 *  nativeCreateSubprocess(cmd, cwd) -> 返回 master fd（<0 失败）
 *  nativeRead(fd, buf)              -> 读到字节数（<=0 表示结束/出错）
 *  nativeWrite(fd, data)            -> 写入
 *  nativeResize(fd, rows, cols)     -> 调整窗口
 *  nativeKill(fd)                   -> 结束子进程并关闭 fd
 */
object PcsTerm {

    /** 库名需与 CMake 中 add_library(pcs_term SHARED ...) 一致。 */
    private const val LIB = "pcs_term"

    @Volatile
    private var loaded = false

    init {
        loaded = runCatching { System.loadLibrary(LIB) }.isSuccess
    }

    /** 库是否加载成功，供上层降级判断。 */
    fun isLoaded(): Boolean = loaded

    external fun nativeCreateSubprocess(cmd: String, cwd: String): Int
    external fun nativeRead(fd: Int, buf: ByteArray): Int
    external fun nativeWrite(fd: Int, data: ByteArray)
    external fun nativeResize(fd: Int, rows: Int, cols: Int)
    external fun nativeKill(fd: Int)
}