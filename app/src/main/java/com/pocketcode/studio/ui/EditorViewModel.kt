package com.pocketcode.studio.ui

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketcode.studio.core.build.BuildRunService
import com.pocketcode.studio.core.plugin.PluginHost
import com.pocketcode.studio.core.root.RootService
import com.pocketcode.studio.core.terminal.TerminalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 一个打开的标签。 */
data class Tab(val file: File, var text: String, var dirty: Boolean = false)

/**
 * 用户可选的「默认运行语言」。
 * value 存进 SharedPreferences；label 显示在设置页。
 * AUTO 表示仍按文件后缀自动推断。
 */
object Languages {
    const val AUTO = "auto"

    val all: List<Pair<String, String>> = listOf(
        AUTO to "自动（按文件后缀）",
        "python" to "Python",
        "javascript" to "JavaScript",
        "typescript" to "TypeScript",
        "c" to "C",
        "cpp" to "C++",
        "go" to "Go",
        "rust" to "Rust",
        "java" to "Java",
        "text" to "纯文本",
    )

    fun label(value: String): String =
        all.firstOrNull { it.first == value }?.second ?: value
}

data class UiState(
    val tabs: List<Tab> = emptyList(),
    val activeIndex: Int = 0,
    val terminalVisible: Boolean = false,
    val fileTreeVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    val terminalLines: List<String> = emptyList(),
    val rootAvailable: Boolean = false,
    val status: String = "就绪",
    val defaultLanguage: String = Languages.AUTO,
    val workspacePath: String = "",
) {
    val active: Tab? get() = tabs.getOrNull(activeIndex)
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val build = BuildRunService(app)
    private val root = RootService()
    private val prefs = app.getSharedPreferences("pcs_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 工作区目录：由 App 自主创建并维护，用户无需手动放入文件。 */
    val workspace: File = File("/sdcard/PocketCodeStudio/workspaces")

    init {
        _state.value = _state.value.copy(
            defaultLanguage = prefs.getString("default_language", Languages.AUTO) ?: Languages.AUTO,
            workspacePath = workspace.absolutePath,
        )

        // 向插件系统注入编辑器 API（PluginHost 未注入时插件调用为安全空操作）。
        PluginHost.getText = { _state.value.active?.text ?: "" }
        PluginHost.setText = { updateActive(it) }
        PluginHost.toast = { msg ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        // 首次启动：自动创建工作区 + 内置示例文件，并自动打开示例文件。
        viewModelScope.launch { seedWorkspace(force = false) }

        viewModelScope.launch {
            val lvl = root.detect()
            _state.value = _state.value.copy(
                rootAvailable = lvl == RootService.Level.ROOT,
                status = if (lvl == RootService.Level.ROOT) "Root 已授权" else "普通模式",
            )
        }
        viewModelScope.launch {
            TerminalService.output.collect { line ->
                _state.value = _state.value.copy(terminalLines = _state.value.terminalLines + line)
            }
        }
    }

    /**
     * 工作区自我初始化：
     * 1. 确保目录存在；
     * 2. 若尚未播种（或 force=true）则写入内置示例文件（不覆盖已存在的同名文件）；
     * 3. 自动打开 main.py，让用户一进来就有东西可看。
     */
    suspend fun seedWorkspace(force: Boolean) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!workspace.exists()) workspace.mkdirs()
                val seeded = prefs.getBoolean("workspace_seeded", false)
                if (force || !seeded) {
                    writeIfAbsent("main.py", SAMPLE_PYTHON)
                    writeIfAbsent("hello.c", SAMPLE_C)
                    writeIfAbsent("hello.js", SAMPLE_JS)
                    writeIfAbsent("README.md", SAMPLE_README)
                    prefs.edit().putBoolean("workspace_seeded", true).apply()
                }
            }
        }
        _state.value = _state.value.copy(status = "工作区已就绪：${workspace.absolutePath}")
        val first = File(workspace, "main.py")
        if (first.exists()) openFile(first)
    }

    private fun writeIfAbsent(name: String, content: String) {
        val f = File(workspace, name)
        if (!f.exists()) runCatching { f.writeText(content) }
    }

    /** 设置页里的「重建示例文件」按钮。 */
    fun reseedWorkspace() {
        viewModelScope.launch {
            _state.value = _state.value.copy(status = "正在写入示例文件…")
            seedWorkspace(force = true)
            _state.value = _state.value.copy(status = "示例文件已就绪")
        }
    }

    fun openFile(f: File) {
        if (_state.value.tabs.any { it.file == f }) {
            _state.value = _state.value.copy(activeIndex = _state.value.tabs.indexOfFirst { it.file == f })
            return
        }
        viewModelScope.launch {
            val text = runCatching { f.readText() }.getOrDefault("")
            _state.value = _state.value.copy(
                tabs = _state.value.tabs + Tab(f, text),
                activeIndex = _state.value.tabs.size,
            )
        }
    }

    fun closeTab(i: Int) {
        val list = _state.value.tabs.toMutableList().also { it.removeAt(i) }
        _state.value = _state.value.copy(
            tabs = list,
            activeIndex = (_state.value.activeIndex).coerceAtMost((list.size - 1).coerceAtLeast(0)),
        )
    }

    fun updateActive(text: String) {
        val s = _state.value
        val t = s.active ?: return
        val newTabs = s.tabs.toMutableList()
        newTabs[s.activeIndex] = t.copy(text = text, dirty = true)
        _state.value = s.copy(tabs = newTabs, status = "未保存")
    }

    fun saveActive() {
        val s = _state.value
        val t = s.active ?: return
        viewModelScope.launch {
            t.file.writeText(t.text)
            val newTabs = s.tabs.toMutableList()
            newTabs[s.activeIndex] = t.copy(dirty = false)
            _state.value = s.copy(tabs = newTabs, status = "已保存")
        }
    }

    /** 一键运行：委托 BuildRunService 生成命令并送入终端。 */
    fun runActive() {
        val t = _state.value.active ?: return
        val rootMode = _state.value.rootAvailable
        val lang = _state.value.defaultLanguage
        viewModelScope.launch {
            _state.value = _state.value.copy(terminalVisible = true, status = "运行中…")
            val code = build.run(t.file.parentFile, t.file, root = rootMode, language = lang) { line ->
                TerminalService.output.tryEmit(line)
            }
            _state.value = _state.value.copy(status = if (code == 0) "已提交执行" else "运行失败($code)")
        }
    }

    fun toggleTerminal() = _state.value.let {
        _state.value = it.copy(terminalVisible = !it.terminalVisible)
    }

    fun toggleFileTree() = _state.value.let {
        _state.value = it.copy(fileTreeVisible = !it.fileTreeVisible)
    }

    fun toggleSettings() = _state.value.let {
        _state.value = it.copy(settingsVisible = !it.settingsVisible)
    }

    /** 设置页：用户自主选择默认运行语言。 */
    fun setDefaultLanguage(value: String) {
        prefs.edit().putString("default_language", value).apply()
        _state.value = _state.value.copy(
            defaultLanguage = value,
            status = "默认运行语言：${Languages.label(value)}",
        )
    }

    /**
     * 终端输入的指令：优先写入交互式 PTY（保留 cwd/环境）；
     * PTY 不可用时回退到 libsu 的 root.stream 一次性执行。
     */
    fun execRoot(cmd: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(terminalVisible = true)
            val fd = runCatching { TerminalService.ensureSession(root = _state.value.rootAvailable) }
                .getOrDefault(-1)
            if (fd >= 0) {
                TerminalService.write(cmd)
            } else {
                TerminalService.output.tryEmit("\u001b[35m$ $cmd\u001b[0m")
                root.stream(cmd).collect { TerminalService.output.tryEmit(it) }
            }
        }
    }

    // ---------- 内置示例文件 ----------

    private companion object {
        val SAMPLE_PYTHON = """
            #!/usr/bin/env python3
            # PocketCode Studio 内置示例 —— 点右上角 ▶ 即可运行
            # 运行语言可在「设置 → 默认运行语言」里手动指定，默认按后缀自动识别。


            def main():
                print("Hello, PocketCode Studio!")
                for i in range(1, 6):
                    print(f"  {i} 的平方是 {i * i}")


            if __name__ == "__main__":
                main()
        """.trimIndent()

        val SAMPLE_C = """
            // PocketCode Studio 内置示例（C 语言）
            // 点 ▶ 将执行：gcc hello.c -o a.out && ./a.out
            #include <stdio.h>

            int main(void) {
                printf("Hello from C!\n");
                return 0;
            }
        """.trimIndent()

        val SAMPLE_JS = """
            // PocketCode Studio 内置示例（JavaScript）
            // 点 ▶ 将执行：node hello.js
            console.log("Hello from JavaScript!");
            [1, 2, 3, 4, 5].forEach(n => console.log(`  ${'$'}{n} x 2 = ${'$'}{n * 2}`));
        """.trimIndent()

        val SAMPLE_README = """
            # 欢迎使用 PocketCode Studio

            这是 App 为你自动创建的工作区，里面已经内置了几个示例文件。

            - `main.py`   —— Python 示例（默认打开）
            - `hello.c`   —— C 语言示例
            - `hello.js`  —— JavaScript 示例
            - `README.md` —— 本说明

            ## 怎么用

            1. 点顶部 📂 打开文件树，选择任意文件开始编辑；
            2. 改完点 💾 保存；
            3. 点 ▶ 一键运行，底部终端会显示结果；
            4. 点 ⚙ 进入设置，可手动指定「默认运行语言」。

            完整教程见项目内的 `docs/09-使用指南.md`。
        """.trimIndent()
    }
}