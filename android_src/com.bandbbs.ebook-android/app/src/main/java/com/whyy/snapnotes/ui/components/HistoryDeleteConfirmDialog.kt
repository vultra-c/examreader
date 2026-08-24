package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.AppDialog
import com.whyy.snapnotes.ui.viewmodel.PushRecord
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 删除一条推送历史记录的确认框。
 *
 * 文案明确：删的是本机缓存与记录，不删手环上已导入的内容。
 */
@Composable
fun HistoryDeleteConfirmDialog(
    record: PushRecord?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // 显示/隐藏交给 AppDialog 的 show；隐藏时 summary 用占位文案。
    val visible = record != null
    val rec = record
    AppDialog(
        title = "删除这条记录？",
        summary = if (rec != null) {
            "「${rec.fileName}」将从本机推送历史中删除，本地缓存文件也会清掉。" +
                "这不会删除手环上已经导入的内容。"
        } else "",
        show = visible,
        onDismissRequest = onDismiss,
        confirmText = "仍然删除",
        onConfirm = onConfirm,
        content = {
            if (rec != null) {
                Text(
                    text = "若仍想清掉手环上对应内容，请转至手环端导入知识点页手动删除。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }
        }
    )
}