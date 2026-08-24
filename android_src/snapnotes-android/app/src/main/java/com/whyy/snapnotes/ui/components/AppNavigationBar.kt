package com.whyy.snapnotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class AppNavTab(
    val icon: ImageVector,
    val label: String
)

/**
 * 底部导航栏：包装 miuix 的 [NavigationBar] 与 [NavigationBarItem]。
 *
 * miuix [NavigationBar] 默认启用 `defaultWindowInsetsPadding`，会自动在底部
 * 补上系统导航栏高度，因此经典三键导航（手势条关闭）时不会被导航键遮挡。
 */
@Composable
fun AppNavigationBar(
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    tabs: List<AppNavTab>,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
) {
    NavigationBar(
        modifier = modifier,
        color = color,
        showDivider = true,
        defaultWindowInsetsPadding = true,
    ) {
        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selectedTabIndex() == index,
                onClick = { onTabSelected(index) },
                icon = tab.icon,
                label = tab.label,
            )
        }
    }
}
