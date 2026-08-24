package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun EditorLoadErrorDialog(
    message: String?,
    onDismiss: () -> Unit
) {
    // Always in composition：显示/隐藏完全交给 AppDialog 的 show。
    AppDialog(
        title = "加载失败",
        summary = message ?: "",
        show = message != null,
        onDismissRequest = onDismiss,
        dismissText = "",
        confirmText = "知道了",
        onConfirm = onDismiss,
        content = {
            if (message != null) {
                Text(
                    text = "请确认文件是 UTF-8 编码、顶层为 { \"科目名\": [条目...] } 结构的合法 JSON。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)
                )
            }
        }
    )
}