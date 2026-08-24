package com.whyy.snapnotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Switch

/**
 * 项目内开关统一入口：直接委托给 miuix 的 [Switch]，
 * 拖拽、按压缩放、触感反馈与选中态颜色均与 HyperOS 保持一致。
 */
@Composable
fun AppToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier
    )
}
