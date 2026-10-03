package com.pocketcode.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.pocketcode.studio.ui.EditorScreen
import com.pocketcode.studio.ui.EditorViewModel
import com.pocketcode.studio.ui.theme.PocketCodeTheme

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
    }
}