package com.pocketcode.studio.ui

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketcode.studio.core.editor.CLanguage
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面（精简版，仅 C 语言）。
 * - 顶栏：文件树 / 保存 / 运行 / 终端
 * - 中部：标签页 + 代码编辑区（Sora Editor，带 C 关键字补全）
 * - 底部：可折叠终端面板 + 状态栏
 */
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "PocketCode Studio",
                actions = {
                    IconButton(onClick = { vm.toggleFileTree() }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "文件")
                    }
                    IconButton(onClick = { vm.saveActive() }) {
                        Icon(Icons.Default.Save, contentDescription = "保存")
                    }
                    IconButton(onClick = { vm.runActive() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "运行")
                    }
                    IconButton(onClick = { vm.toggleTerminal() }) {
                        Icon(Icons.Default.Terminal, contentDescription = "终端")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MainEditor(vm, state)

            // 文件树浮层（覆盖整个编辑区，切换流畅）
            AnimatedVisibility(
                visible = state.fileTreeVisible,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(120)),
            ) {
                FileTreeOverlay(vm)
            }
        }
    }
}

@Composable
private fun MainEditor(vm: EditorViewModel, state: UiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(vm, state)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val tab = state.active
            if (tab == null) {
                Text(
                    "没有打开的文件",
                    modifier = Modifier.align(Alignment.Center),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            } else {
                CodeEditorView(tab = tab, onTextChange = { vm.updateActive(it) })
            }
        }
        // 终端面板（底部滑入）
        AnimatedVisibility(
            visible = state.terminalVisible,
            enter = slideInVertically(tween(180)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
        ) {
            TerminalPanel(vm, state)
        }
        StatusBar(state)
    }
}

/**
 * Sora Editor 的 Compose 封装。
 * - 按 tab.file 作为 key，切换标签时重建编辑器，避免跨标签状态串扰；
 * - 文本变更通过 [onTextChange] 回传 ViewModel，标记未保存。
 */
@Composable
private fun CodeEditorView(tab: Tab, onTextChange: (String) -> Unit) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            CodeEditor(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // C 语言 + 关键字补全（补全由 CLanguage.requireAutoComplete 提供）
                setEditorLanguage(CLanguage())
                setText(tab.text)
                // 文本变更回传
                val listener = object : ContentListener {
                    override fun beforeReplace(content: Content) {}
                    override fun afterInsert(
                        content: Content,
                        startLine: Int,
                        startColumn: Int,
                        endLine: Int,
                        endColumn: Int,
                        insertedContent: CharSequence,
                    ) {
                        onTextChange(content.toString())
                    }
                    override fun afterDelete(
                        content: Content,
                        startLine: Int,
                        startColumn: Int,
                        endLine: Int,
                        endColumn: Int,
                        deletedContent: CharSequence,
                    ) {
                        onTextChange(content.toString())
                    }
                }
                text.addContentListener(listener)
            }
        },
        update = { editor ->
            // tab 切换时 key 变化会触发重建，此处仅同步外部可能的文本覆盖
            if (editor.text.toString() != tab.text && !tab.dirty) {
                editor.setText(tab.text)
            }
        },
    )
}

@Composable
private fun TabStrip(vm: EditorViewModel, state: UiState) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.tabs.forEachIndexed { i, tab ->
            val selected = i == state.activeIndex
            Row(
                modifier = Modifier
                    .clickable { vm.openFile(tab.file) }
                    .background(
                        if (selected) MiuixTheme.colorScheme.secondaryContainer else Color.Transparent
                    )
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (tab.dirty) "* " else "") + tab.file.name,
                    fontSize = 13.sp,
                    color = if (selected) {
                        MiuixTheme.colorScheme.onSecondaryContainer
                    } else {
                        MiuixTheme.colorScheme.onBackgroundVariant
                    },
                )
                IconButton(
                    onClick = { vm.closeTab(i) },
                    minWidth = 26.dp,
                    minHeight = 26.dp,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalPanel(vm: EditorViewModel, state: UiState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.terminalLines.size) {
        if (state.terminalLines.isNotEmpty()) {
            listState.scrollToItem(state.terminalLines.size - 1)
        }
    }
    var cmd by remember { mutableStateOf(TextFieldValue("")) }

    Card(
        modifier = Modifier.fillMaxWidth().height(240.dp).padding(6.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                items(state.terminalLines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = "输入命令",
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = {
                    if (cmd.text.isNotBlank()) {
                        vm.execCmd(cmd.text)
                        cmd = TextFieldValue("")
                    }
                }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "执行")
                }
            }
        }
    }
}

@Composable
private fun StatusBar(state: UiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = state.status,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(
            text = "C 语言 | ${state.workspacePath.substringAfterLast('/')}",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun FileTreeOverlay(vm: EditorViewModel) {
    val files = remember { vm.workspace.listFiles()?.sortedBy { it.name } ?: emptyList() }
    Box(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = "文件",
                navigationIcon = {
                    IconButton(onClick = { vm.toggleFileTree() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                defaultWindowInsetsPadding = false,
            )
            if (files.isEmpty()) {
                Text(
                    "工作区为空",
                    modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { f ->
                        ArrowPreference(
                            title = f.name,
                            summary = if (f.isDirectory) "目录" else "${f.length()} B",
                            onClick = {
                                if (!f.isDirectory) vm.openFile(f)
                                vm.toggleFileTree()
                            },
                        )
                    }
                }
            }
        }
    }
}
