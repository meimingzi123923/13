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

data class UiState(
    val tabs: List<Tab> = emptyList(),
    val activeIndex: Int = 0,
    val terminalVisible: Boolean = false,
    val fileTreeVisible: Boolean = false,
    val terminalLines: List<String> = emptyList(),
    val status: String = "就绪",
    val workspacePath: String = "",
) {
    val active: Tab? get() = tabs.getOrNull(activeIndex)
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val build = BuildRunService(app)
    private val prefs = app.getSharedPreferences("pcs_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 工作区放在应用私有目录（filesDir/workspace）：
     *  - 无需任何存储权限；
     *  - 私有目录为 f2fs/ext4，可执行，彻底避免 /sdcard 的 noexec 与 FUSE 权限问题。
     */
    val workspace: File = File(app.filesDir, "workspace")

    init {
        _state.value = _state.value.copy(workspacePath = workspace.absolutePath)

        // 向插件系统注入编辑器 API
        PluginHost.getText = { _state.value.active?.text ?: "" }
        PluginHost.setText = { updateActive(it) }
        PluginHost.toast = { msg ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        // 首次启动：播种示例 C 文件并打开
        viewModelScope.launch { seedWorkspace(force = false) }

        viewModelScope.launch {
            TerminalService.output.collect { line ->
                _state.value = _state.value.copy(terminalLines = _state.value.terminalLines + line)
            }
        }
    }

    /** 创建工作区并写入示例文件（不覆盖已存在文件）。 */
    suspend fun seedWorkspace(force: Boolean) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!workspace.exists()) workspace.mkdirs()
                val seeded = prefs.getBoolean("workspace_seeded", false)
                if (force || !seeded) {
                    writeIfAbsent("hello.c", SAMPLE_C)
                    prefs.edit().putBoolean("workspace_seeded", true).apply()
                }
            }
        }
        _state.value = _state.value.copy(status = "工作区就绪：${workspace.name}")
        val first = File(workspace, "hello.c")
        if (first.exists()) openFile(first)
    }

    private fun writeIfAbsent(name: String, content: String) {
        val f = File(workspace, name)
        if (!f.exists()) runCatching { f.writeText(content) }
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

    /** 一键编译并运行当前 C 文件。 */
    fun runActive() {
        val t = _state.value.active ?: return
        // 先保存，确保运行的是最新内容
        val file = t.file
        viewModelScope.launch {
            if (t.dirty) {
                runCatching { file.writeText(t.text) }
                val s = _state.value
                val newTabs = s.tabs.toMutableList()
                newTabs[s.activeIndex] = t.copy(dirty = false)
                _state.value = s.copy(tabs = newTabs)
            }
            _state.value = _state.value.copy(terminalVisible = true, status = "编译运行中…")
            val code = build.run(file.parentFile ?: workspace, file) { line ->
                TerminalService.output.tryEmit(line)
            }
            _state.value = _state.value.copy(
                status = if (code == 0) "已执行" else "执行失败($code)"
            )
        }
    }

    fun toggleTerminal() = _state.value.let {
        _state.value = it.copy(terminalVisible = !it.terminalVisible)
    }

    fun toggleFileTree() = _state.value.let {
        _state.value = it.copy(fileTreeVisible = !it.fileTreeVisible)
    }

    /** 终端输入的指令：写入交互式 PTY。 */
    fun execCmd(cmd: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(terminalVisible = true)
            val fd = runCatching { TerminalService.ensureSession(root = false) }.getOrDefault(-1)
            if (fd >= 0) {
                TerminalService.write(cmd)
            } else {
                TerminalService.output.tryEmit("\u001b[31m✗ 终端不可用\u001b[0m")
            }
        }
    }

    private companion object {
        val SAMPLE_C = """
            // PocketCode Studio — C 语言示例
            // 点顶部 ▶ 编译并运行
            #include <stdio.h>

            int main(void) {
                printf("Hello, PocketCode Studio!\n");
                for (int i = 1; i <= 5; i++) {
                    printf("  %d * %d = %d\n", i, i, i * i);
                }
                return 0;
            }
        """.trimIndent()
    }
}
