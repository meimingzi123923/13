package com.pocketcode.studio.ui

import android.content.Context
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面（MiuiX 版）。
 * - 顶部：文件 / 保存 / 运行 / 终端 / 设置
 * - 中部：标签页 + 代码编辑区
 * - 底部：可折叠终端 + 状态栏
 * - 浮层：文件树、设置页、首次使用引导
 */
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pcs_prefs", Context.MODE_PRIVATE) }
    var guideShown by remember { mutableStateOf(prefs.getBoolean("guide_shown", false)) }

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
                    IconButton(onClick = { vm.toggleSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MainEditor(vm, state)

            if (state.fileTreeVisible) FileTreeOverlay(vm, state)
            if (state.settingsVisible) SettingsOverlay(vm, state)

            val guide = remember { mutableStateOf(!guideShown) }
            SuperDialog(
                show = guide,
                title = "欢迎使用 PocketCode Studio",
                summary = "工作区已由 App 自动创建，并内置了示例文件。",
                onDismissRequest = {
                    guide.value = false
                    guideShown = true
                    prefs.edit().putBoolean("guide_shown", true).apply()
                },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("1. 顶部 📂 打开文件树，选择文件开始编辑", fontSize = 14.sp)
                    Text("2. 💾 保存后，点 ▶ 一键运行", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Text("3. ⚙ 进入设置，可自选默认运行语言", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
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
                EditorPane(vm, tab)
            }
        }
        if (state.terminalVisible) TerminalPanel(vm, state)
        StatusBar(state)
    }
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
                    text = (if (tab.dirty) "● " else "") + tab.file.name,
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
private fun EditorPane(vm: EditorViewModel, tab: Tab) {
    var value by remember(tab.file) { mutableStateOf(TextFieldValue(tab.text)) }
    TextField(
        value = value,
        onValueChange = {
            value = it
            vm.updateActive(it.text)
        },
        modifier = Modifier.fillMaxSize().padding(8.dp),
        textStyle = MiuixTheme.textStyles.main.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        ),
        label = "",
    )
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
        modifier = Modifier.fillMaxWidth().height(220.dp).padding(6.dp),
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
                        vm.execRoot(cmd.text)
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
            text = Languages.label(state.defaultLanguage) +
                " · " + (if (state.rootAvailable) "Root" else "普通"),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun FileTreeOverlay(vm: EditorViewModel, state: UiState) {
    val files = remember(state.workspacePath) {
        vm.workspace.listFiles()?.sortedBy { it.name } ?: emptyList()
    }
    Box(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = "文件",
                navigationIcon = {
                    IconButton(onClick = { vm.toggleFileTree() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
                        SuperArrow(
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

@Composable
private fun SettingsOverlay(vm: EditorViewModel, state: UiState) {
    Box(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = "设置",
                navigationIcon = {
                    IconButton(onClick = { vm.toggleSettings() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                defaultWindowInsetsPadding = false,
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "默认运行语言",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                items(Languages.all) { pair ->
                    SuperArrow(
                        title = pair.second,
                        rightText = if (state.defaultLanguage == pair.first) "已选择" else null,
                        onClick = { vm.setDefaultLanguage(pair.first) },
                    )
                }
                item {
                    Text(
                        "工作区",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                    SuperArrow(
                        title = "重建示例文件",
                        summary = state.workspacePath,
                        onClick = { vm.reseedWorkspace() },
                    )
                }
            }
        }
    }
}
