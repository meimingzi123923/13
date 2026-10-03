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
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
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

    val ctx = LocalContext.current
    // 首次启动自动弹出引导；此后可通过工具栏 ❓ 随时唤出。
    var showGuide by remember {
        mutableStateOf(
            !ctx.getSharedPreferences("pcs_prefs", 0).getBoolean("guide_shown", false)
        )
    }

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
            IconButton(onClick = { showGuide = true }) {
                Icon(Icons.Default.HelpOutline, contentDescription = "使用帮助")
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

        // 首次启动引导 / ❓ 帮助弹窗
        if (showGuide) {
            GuideDialog(
                onDismiss = {
                    showGuide = false
                    ctx.getSharedPreferences("pcs_prefs", 0)
                        .edit().putBoolean("guide_shown", true).apply()
                },
            )
        }
    }
}

/**
 * 内置使用引导：首次启动自动弹出，也可由工具栏 ❓ 唤出。
 * 纯 Compose 实现，不依赖任何外部资源。
 */
@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    val sections = listOf(
        "👋 欢迎使用 PocketCode Studio" to
            "手机上的「口袋版 VSCode」：写代码 → 保存 → 一键运行 → 看终端输出。",
        "① 打开 / 新建文件" to
            "点顶部 📂 唤出文件树；工作区在 /sdcard/PocketCodeStudio/workspaces。" +
            "用手机文件管理器把 .py / .js / .c 等文件放进去，回到 App 即可看到。",
        "② 编辑与保存" to
            "直接在中间编辑区打字，改完点 💾 保存。标签上出现 ● 表示尚未保存。",
        "③ 一键运行 ▶" to
            "点 ▶ 会按当前文件后缀自动挑命令并在终端执行：\n" +
            "  .py → python3    .js → node    .c → gcc    .cpp → g++\n" +
            "  .go → go run     .rs → cargo   .java → javac + java\n" +
            "能否跑起来取决于设备上是否装了对应运行时（见下条）。",
        "④ 终端 🖥" to
            "点 🖥 打开底部终端，在 \$ 后敲命令，点 ↵ 提交。这是真 shell，可用 Root。",
        "⑤ Root 权限" to
            "授予 Root 后顶栏出现 🔒 Root，可执行 mount、改 /system 等系统级命令；" +
            "未授权则为普通模式，仍可正常编辑文件。",
        "⑥ 运行时去哪装" to
            "若终端报 command not found，说明缺运行时。可在终端用 apt/pip/npm 安装，" +
            "或等待后续版本的「proot 发行版一键安装」入口。",
        "⑦ 更多帮助" to
            "完整教程见项目 docs/09-使用指南.md；随时点顶栏 ❓ 重新打开本引导。",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("开始使用") }
        },
        title = { Text("使用引导") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                sections.forEach { (title, body) ->
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        body,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
    )
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