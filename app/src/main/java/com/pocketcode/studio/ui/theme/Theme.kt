package com.pocketcode.studio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 设计规范：低饱和蓝紫主色 + 深色背景 [04-UI设计.md] */
private val Primary = Color(0xFF6C8CFF)
private val BgDark = Color(0xFF0F1115)
private val SurfaceDark = Color(0xFF161A22)
private val OnDark = Color(0xFFE6E8EE)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    background = BgDark,
    surface = SurfaceDark,
    onBackground = OnDark,
    onSurface = OnDark,
    outline = Color(0x1AFFFFFF),
)

private val LightColors = lightColorScheme(
    primary = Primary,
    background = Color(0xFFFAFAFC),
)

@Composable
fun PocketCodeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}