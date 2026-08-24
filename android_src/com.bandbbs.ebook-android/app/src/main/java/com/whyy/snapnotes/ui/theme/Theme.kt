package com.whyy.snapnotes.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * @param appearanceMode 主题模式（浅色 / 深色 / 跟随系统）
 * @param dynamicColor 是否启用动态取色（按系统壁纸生成整套配色）；与 [appearanceMode] 正交组合
 *
 * 把「主题模式」与「是否动态取色」拆成两个独立设置：模式由下拉选、动态取色由开关控。
 * 二者组合映射到 Miuix 的 [ColorSchemeMode]：
 * - dynamicColor=false → System / Light / Dark
 * - dynamicColor=true  → MonetSystem / MonetLight / MonetDark
 */
@Composable
fun SnapNotesTheme(
    appearanceMode: AppearanceMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val mode = when (appearanceMode) {
        AppearanceMode.System -> if (dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
        AppearanceMode.Light -> if (dynamicColor) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        AppearanceMode.Dark -> if (dynamicColor) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
    }
    val controller = remember(mode) { ThemeController(mode) }
    MiuixTheme(controller = controller, content = content)
}
