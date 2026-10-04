package com.pocketcode.studio

import android.app.Application
import com.pocketcode.studio.core.plugin.PluginManager
import com.pocketcode.studio.core.terminal.TerminalService
import java.io.File
import kotlin.concurrent.thread

/**
 * 应用入口。
 *
 * 这里不使用 Hilt 注解（避免注释处理器未启用导致编译失败），
 * 采用轻量单例持有全局依赖；如后续启用 Hilt，把 @HiltAndroidApp 加回即可。
 */
class App : Application() {

    companion object {
        lateinit var instance: App
            private set

        /** 全局插件管理器（扫描 plugins/ 目录）。 */
        lateinit var plugins: PluginManager
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 插件目录（应用私有目录，无需存储权限）
        File(filesDir.parentFile, "plugins").mkdirs()

        plugins = PluginManager(this)

        // 关键修复：把插件扫描（含 QuickJS 引擎初始化，慢机型需数百 ms）
        // 与终端预热（forkpty 创建 PTY 会话）移出主线程，放到后台执行，
        // 避免 Application.onCreate 主线程重活导致启动闪退 / ANR。
        thread(name = "pcs-init", isDaemon = true) {
            runCatching { plugins.scan() }        // 启动即扫描已装插件（失败不阻断启动）
            runCatching { TerminalService.createSession() } // 预热 PTY 会话
        }
    }
}