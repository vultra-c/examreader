package com.whyy.snapnotes.ui.components

import androidx.compose.runtime.Composable
import com.whyy.snapnotes.ui.components.AppDialog
import com.whyy.snapnotes.ui.viewmodel.ExportResult

@Composable
fun ExportResultDialog(
    result: ExportResult?,
    onDismiss: () -> Unit
) {
    // 显示/隐藏交给 AppDialog 的 show；隐藏时 title/summary 用占位值。
    val visible = result != null
    val r = result
    AppDialog(
        title = if (r != null) {
            if (r.success) "导出成功" else "导出失败"
        } else "",
        summary = r?.message ?: "",
        show = visible,
        onDismissRequest = onDismiss,
        dismissText = "",
        confirmText = "知道了",
        onConfirm = onDismiss
    )
}
