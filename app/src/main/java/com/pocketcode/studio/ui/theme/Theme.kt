package com.pocketcode.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 全局主题：改用小米 MiuiX 设计体系（替换原 Material3）。
 *
 * MiuiX 自带亮/暗两套调色板，直接跟随系统深色模式即可；
 * 后续若要手动切换深色或自定义主色，只需在这里构造 Colors 传给 MiuixTheme。
 */
@Composable
fun PocketCodeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MiuixTheme(
        colors = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}