package com.pocketcode.studio.core.plugin

import android.content.Context
import app.cash.quickjs.QuickJs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * 插件宿主钩子：由 UI/ViewModel 在运行时注入，解耦插件系统与界面层。
 * 未注入时各回调为 null，插件调用将安全地空操作。
 */
object PluginHost {
    /** 读取当前编辑器文本。 */
    var getText: (() -> String)? = null

    /** 写回编辑器文本。 */
    var setText: ((String) -> Unit)? = null

    /** 弹 Toast（应在主线程调用）。 */
    var toast: ((String) -> Unit)? = null

    /** 插件注册的命令：pluginId -> [commandId]。 */
    val registered: MutableMap<String, MutableList<String>> = linkedMapOf()
}

/**
 * 插件管理器：扫描 plugins/，加载 JS 插件（QuickJS 沙箱）。
 * JVM 插件（.jar/.dex）用 DexClassLoader，见 loadJvmPlugin()。
 */
class PluginManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(context.filesDir.parentFile, "plugins")
    val loaded = linkedMapOf<String, LoadedPlugin>()

    @Serializable
    data class Manifest(
        val id: String,
        val name: String,
        val version: String = "0.0.1",
        val type: String = "js",                 // js | jvm
        val main: String = "main.js",
        val permissions: List<String> = emptyList(),
        val contributes: Contributes = Contributes(),
    )

    @Serializable
    data class Contributes(
        val commands: List<Command> = emptyList(),
        val menus: List<Menu> = emptyList(),
        val languages: List<String> = emptyList(),
    )

    @Serializable data class Command(val id: String, val title: String)
    @Serializable data class Menu(val command: String, val whenExpr: String = "", val group: String = "")
        { @Suppress("unused") val `when` get() = whenExpr }

    data class LoadedPlugin(val manifest: Manifest, val dir: File, val engine: QuickJs?)

    /** 扫描并加载所有插件目录。 */
    fun scan(): List<Manifest> {
        if (!root.exists()) root.mkdirs()
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching { load(dir) }.getOrNull()?.manifest
        } ?: emptyList()
    }

    private fun load(dir: File): LoadedPlugin {
        val manifest = json.decodeFromString<Manifest>(File(dir, "plugin.json").readText())
        return when (manifest.type) {
            "jvm" -> loadJvmPlugin(dir, manifest)
            else -> loadJsPlugin(dir, manifest)
        }
    }

    /**
     * 宿主桥：暴露给 JS 沙箱的 pcs.* 实际落点。
     * QuickJS 只能传基本类型/字符串，函数回调以「命令 id」登记，由 UI 触发。
     */
    inner class HostBridge(private val pluginId: String) {
        fun editorGet(): String = PluginHost.getText?.invoke() ?: ""
        fun editorSet(text: String) { PluginHost.setText?.invoke(text) }
        fun registerCommand(id: String) {
            PluginHost.registered.getOrPut(pluginId) { mutableListOf() }.add(id)
        }
        fun toast(msg: String) {
            PluginHost.toast?.invoke("[$pluginId] $msg")
        }
        @Suppress("unused")
        fun log(msg: String) = android.util.Log.i("PCSPlugin", "[$pluginId] $msg")
    }

    /** JS 插件：QuickJS 沙箱，注入 pcs.* 宿主 API。 */
    private fun loadJsPlugin(dir: File, manifest: Manifest): LoadedPlugin {
        val engine = QuickJs.create()
        val host = HostBridge(manifest.id)
        engine.set("__hostLog", host)   // 兼容旧写法 pcs.log 直接调 __hostLog.log
        engine.set("__host", host)
        val bootstrap = """
            var pcs = {
              editor: { getText: () => __host.editorGet(), setText: (s) => __host.editorSet(s) },
              commands: { register: (id) => __host.registerCommand(id) },
              ui: { showToast: (m) => __host.toast(m) },
              log: (m) => __hostLog.log(m)
            };
        """.trimIndent()
        engine.evaluate(bootstrap)
        engine.evaluate(File(dir, manifest.main).readText())
        // 请求激活
        runCatching { engine.evaluate("activate && activate({})") }
        loaded[manifest.id] = LoadedPlugin(manifest, dir, engine)
        return loaded[manifest.id]!!
    }

    /** JVM 插件：DexClassLoader 动态加载（此处仅骨架）。 */
    private fun loadJvmPlugin(dir: File, manifest: Manifest): LoadedPlugin {
        val jar = File(dir, manifest.main)
        val dexOut = File(context.codeCacheDir, manifest.id).apply { mkdirs() }
        val loader = dalvik.system.DexClassLoader(
            jar.absolutePath, dexOut.absolutePath, null, context.classLoader
        )
        android.util.Log.i("PCSPlugin", "JVM 插件 ${manifest.id} 加载器: $loader")
        val p = LoadedPlugin(manifest, dir, null)
        loaded[manifest.id] = p
        return p
    }

    /** 从 zip 安装插件（离线安装 / 市场安装）。 */
    fun installFromZip(zip: File, verifySha256: String? = null) {
        if (verifySha256 != null) require(zip.sha256() == verifySha256) { "校验失败，可能被篡改" }
        val target = File(root, zip.nameWithoutExtension).apply { mkdirs() }
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().forEach { e ->
                val out = File(target, e.name)
                out.parentFile?.mkdirs()
                if (!e.isDirectory) zf.getInputStream(e).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
        if (!File(target, "plugin.json").exists()) error("缺少 plugin.json")
    }

    fun uninstall(id: String) {
        loaded.remove(id)?.engine?.close()
        File(root, id).deleteRecursively()
    }

    private fun File.sha256(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) { val r = ins.read(buf); if (r <= 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}