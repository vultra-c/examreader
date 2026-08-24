package com.whyy.snapnotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可复用的「更多」菜单按钮：显示 More 图标，点击弹出 miuix 下拉菜单。
 *
 * 菜单项按需显示（传入 null 则不显示对应项）：
 * - 创建文件夹（[onCreateFolder] 非 null 时显示）
 * - Amadeus 对话（[onOpenAmadeusChat] 非 null 时显示）
 * - Amadeus 设置（[onOpenAmadeusConfig] 非 null 时显示）
 *
 * 改用 miuix 的 [WindowIconDropdownMenu]：弹出层走独立窗口 + 锚点定位，
 * 不会再被按钮所在的小尺寸容器裁剪，圆角/水波/展开动画均与 HyperOS 一致。
 */
@Composable
fun MoreMenu(
    onCreateFolder: (() -> Unit)? = null,
    onOpenAmadeusChat: (() -> Unit)? = null,
    onOpenAmadeusConfig: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val items = buildList {
        onCreateFolder?.let { add(Triple("创建文件夹", MiuixIcons.Folder, it)) }
        onOpenAmadeusChat?.let { add(Triple("Amadeus 对话", MiuixIcons.Notes, it)) }
        onOpenAmadeusConfig?.let { add(Triple("Amadeus 设置", MiuixIcons.Settings, it)) }
    }
    if (items.isEmpty()) return

    WindowIconDropdownMenu(
        entries = listOf(
            DropdownEntry(
                items = items.map { (text, icon, onClick) ->
                    DropdownItem(
                        text = text,
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = it,
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = onClick
                    )
                }
            )
        ),
        modifier = modifier,
        collapseOnSelection = true
    ) {
        Icon(imageVector = MiuixIcons.More, contentDescription = "更多")
    }
}
