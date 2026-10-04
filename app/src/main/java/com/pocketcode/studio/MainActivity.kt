package com.pocketcode.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.pocketcode.studio.ui.EditorScreen
import com.pocketcode.studio.ui.EditorViewModel
import com.pocketcode.studio.ui.theme.PocketCodeTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * 唯一 Activity：整个 IDE 用 Compose 单页 + overlay 面板实现。
 * 主题由 PocketCodeTheme（MiuiX）统一提供。
 */
class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketCodeTheme {
                EditorScreen(vm)
            }
        }
        // 关键修复：处理「文件管理器 → 打开方式」传入的 content:// ，
        // 把外部代码文件复制进工作区并打开，避免 intent.data 被忽略。
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        // 仅处理 content:// 或 file:// 的可读文本
        if (uri.scheme !in setOf("content", "file")) return
        lifecycleScope.launch {
            val name = readDisplayName(uri) ?: "external_${System.currentTimeMillis()}.c"
            vm.importExternal(uri, name)
        }
    }

    private fun readDisplayName(uri: Uri): String? =
        runCatching {
            if (uri.scheme == "file") {
                File(uri.path ?: return null).name
            } else {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }
        }.getOrNull()
}