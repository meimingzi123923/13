package com.pocketcode.studio.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketcode.studio.core.build.BuildRunService
import com.pocketcode.studio.core.plugin.PluginHost
import com.pocketcode.studio.core.root.RootService
import com.pocketcode.studio.core.terminal.TerminalService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 一个打开的标签。 */
data class Tab(val file: File, var text: String, var dirty: Boolean = false)

data class UiState(
    val tabs: List<Tab> = emptyList(),
    val activeIndex: Int = 0,
    val terminalVisible: Boolean = false,
    val fileTreeVisible: Boolean = false,
    val terminalLines: List<String> = emptyList(),
    val rootAvailable: Boolean = false,
    val status: String = "就绪",
) {
    val active: Tab? get() = tabs.getOrNull(activeIndex)
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val build = BuildRunService(app)
    private val root = RootService()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val workspace: File = File("/sdcard/PocketCodeStudio/workspaces").apply { mkdirs() }

    init {
        // 向插件系统注入编辑器 API（PluginHost 未注入时插件调用为安全空操作）。
        PluginHost.getText = { _state.value.active?.text ?: "" }
        PluginHost.setText = { updateActive(it) }
        PluginHost.toast = { msg ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            }
        }
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
        viewModelScope.launch {
            _state.value = _state.value.copy(terminalVisible = true, status = "运行中…")
            val code = build.run(t.file.parentFile, t.file, root = rootMode) { line ->
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
}