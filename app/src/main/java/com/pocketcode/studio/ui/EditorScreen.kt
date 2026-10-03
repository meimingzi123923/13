package com.pocketcode.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 主界面：默认仅「标签栏 + 编辑器 + 状态栏」三区（精简原则）。
 * 文件树与终端均为 overlay，按需出现，不常驻占屏。
 */
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val state by vm.state.collectAsState()
    val active = state.active

    Column(Modifier.fillMaxSize()) {

        // ---------- 顶部工具条 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.toggleFileTree() }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "文件树")
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
            Spacer(Modifier.weight(1f))
            if (state.rootAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Root",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Root", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // ---------- 标签栏 ----------
        if (state.tabs.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = state.activeIndex,
                edgePadding = 0.dp,
            ) {
                state.tabs.forEachIndexed { i, tab ->
                    Tab(
                        selected = i == state.activeIndex,
                        onClick = { vm.openFile(tab.file) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (tab.dirty) "● " else "") + tab.file.name,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "关闭",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { vm.closeTab(i) },
                                )
                            }
                        },
                    )
                }
            }
        }

        // ---------- 编辑区 + overlay ----------
        Box(Modifier.weight(1f).fillMaxWidth()) {

            if (active == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "打开左侧文件树，或从文件管理器选择代码文件开始编辑",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            } else {
                EditorPane(
                    text = active.text,
                    onTextChange = { vm.updateActive(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 文件树 overlay（左侧抽屉）
            if (state.fileTreeVisible) {
                FileTreeOverlay(
                    root = vm.workspace,
                    onPick = { vm.openFile(it) },
                    onDismiss = { vm.toggleFileTree() },
                )
            }

            // 终端 overlay（底部面板）
            if (state.terminalVisible) {
                TerminalOverlay(
                    lines = state.terminalLines,
                    onCommand = { vm.execRoot(it) },
                    onDismiss = { vm.toggleTerminal() },
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        // ---------- 状态栏 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.status, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            active?.let {
                Text(
                    it.file.extension.ifBlank { "text" }.uppercase(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** 纯 Compose 文本编辑区；生产环境可替换为 Sora Editor 的 CodeEditor（AndroidView 包裹）。 */
@Composable
private fun EditorPane(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vs = rememberScrollState()
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(vs)
            .padding(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

/** 文件树 overlay：列出工作区内的文件，点击打开。 */
@Composable
private fun FileTreeOverlay(
    root: File,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val files = remember(root) {
        root.walkTopDown()
            .filter { it.isFile && it.name != ".DS_Store" }
            .take(500)
            .toList()
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(240.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("工作区", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "收起")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        LazyColumn(Modifier.fillMaxSize()) {
            if (files.isEmpty()) {
                item { Text("（空）请先在工作区创建文件", Modifier.padding(12.dp), fontSize = 12.sp) }
            }
            items(files) { f ->
                val rel = f.relativeTo(root).path
                Text(
                    rel,
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(f) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 终端 overlay：显示输出并可输入命令。 */
@Composable
private fun TerminalOverlay(
    lines: List<String>,
    onCommand: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("终端", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), fontSize = 13.sp)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "收起")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines) { line ->
                Text(
                    line.trimEnd(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$ ", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
            Text(
                "↵",
                Modifier
                    .clickable {
                        if (input.isNotBlank()) {
                            onCommand(input)
                            input = ""
                        }
                    }
                    .padding(horizontal = 8.dp),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}