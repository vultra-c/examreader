package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 项目内对话框统一入口：直接委托给 miuix 的 [WindowDialog]。
 *
 * 相比此前的自定义毛玻璃弹层，[WindowDialog] 自带 HyperOS 风格的底部抽屉 +
 * 弹簧入场/出场动画与窗口遮罩，并且即使调用方用 `if (show) Dialog(show = true)`
 * 条件组合，首次出现时也会正常播放入场动画（不会再瞬现）。
 */
@Composable
fun AppDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
    summary: String = "",
    confirmText: String = "确定",
    dismissText: String = "取消",
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit = onDismissRequest,
    content: (@Composable () -> Unit)? = null
) {
    WindowDialog(
        show = show,
        modifier = modifier,
        title = title.ifBlank { null },
        summary = summary.ifBlank { null },
        onDismissRequest = onDismissRequest
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (content != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    content()
                }
                Spacer(Modifier.height(16.dp))
            }
            RowOfButtons(
                dismissText = dismissText,
                confirmText = confirmText,
                confirmEnabled = confirmEnabled,
                onDismiss = onDismiss,
                onConfirm = onConfirm
            )
        }
    }
}

@Composable
private fun RowOfButtons(
    dismissText: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dismissText.isNotBlank()) {
            TextButton(
                text = dismissText,
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
        }
        if (confirmText.isNotBlank()) {
            TextButton(
                text = confirmText,
                enabled = confirmEnabled,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onConfirm,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
