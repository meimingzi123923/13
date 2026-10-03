package com.pocketcode.studio.core.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pocketcode.studio.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * 终端服务（前台服务，保证编译/长任务不被杀）。
 *
 * 通过 JNI 调用 forkpty() 拿到伪终端 master fd，
 * 子进程跑 shell，App 读写该 fd 实现真正的交互式终端。
 * C 侧实现见 app/src/main/cpp/term.cpp 与 docs/06-Root与终端.md。
 */
class TerminalService : Service() {

    companion object {
        /** 子进程输出逐行推送，UI 收集后渲染。 */
        val output = MutableSharedFlow<String>(extraBufferCapacity = 4096)

        @Volatile private var fd: Int = -1
        @Volatile private var readerThread: Thread? = null

        /** 创建 PTY 会话；返回 master fd（<0 表示失败）。 */
        @Synchronized
        fun createSession(shell: String = "/system/bin/sh", root: Boolean = false): Int {
            destroy()
            if (!PcsTerm.isLoaded()) {
                fd = -1
                return fd
            }
            val cmd = if (root) "su" else shell
            fd = PcsTerm.nativeCreateSubprocess(cmd, File("/").absolutePath)
            if (fd >= 0) startReader(fd)
            return fd
        }

        /** 确保会话存在（首次调用自动创建）；返回 master fd（<0 表示 PTY 不可用）。 */
        fun ensureSession(root: Boolean = false): Int {
            if (fd < 0) createSession(root = root)
            return fd
        }

        /** 当前 PTY 是否可用。 */
        fun isAlive(): Boolean = fd >= 0

        /** 向终端写入命令（自动补换行）。 */
        fun write(cmd: String) {
            val f = fd
            if (f >= 0) PcsTerm.nativeWrite(f, (cmd + "\n").toByteArray(Charsets.UTF_8))
        }

        fun resize(rows: Int, cols: Int) {
            val f = fd
            if (f >= 0) PcsTerm.nativeResize(f, rows, cols)
        }

        fun destroy() {
            readerThread?.interrupt()
            readerThread = null
            val f = fd
            if (f >= 0) PcsTerm.nativeKill(f)
            fd = -1
        }

        private fun startReader(f: Int) {
            val t = Thread {
                val buf = ByteArray(4096)
                val sb = StringBuilder()
                while (!Thread.currentThread().isInterrupted) {
                    val n = PcsTerm.nativeRead(f, buf)
                    if (n <= 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                    // 按行切分并推送
                    var idx = sb.indexOf("\n")
                    while (idx >= 0) {
                        val line = sb.substring(0, idx)
                        sb.delete(0, idx + 1)
                        output.tryEmit(line)
                        idx = sb.indexOf("\n")
                    }
                }
            }.apply { isDaemon = true; name = "pcs-pty-reader"; start() }
            readerThread = t
        }

        // JNI 绑定统一走 object PcsTerm（见 PcsTerm.kt），
        // 避免 external 声明在 companion object 中导致符号名带 _$Companion 而 UnsatisfiedLinkError。
        // C 侧实现见 app/src/main/cpp/term.cpp。
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        ensureSession()
        return START_STICKY
    }

    override fun onDestroy() {
        destroy()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.terminal_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.terminal_channel_desc) }
            nm.createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.terminal_channel))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "pcs_terminal"
        const val NOTIF_ID = 1001
    }
}