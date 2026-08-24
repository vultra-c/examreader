package com.whyy.snapnotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.whyy.snapnotes.ui.components.AppDialog
import kotlinx.coroutines.delay


@Composable
fun FirstSyncConfirmDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var countdown by remember(show) { mutableIntStateOf(10) }
    val isCountingDown = countdown > 0

    LaunchedEffect(show) {
        if (show) {
            countdown = 10
            while (show && countdown > 0) {
                delay(1_000)
                countdown--
            }
        }
    }

    AppDialog(
        show = show,
        title = "同步确认",
        summary = "由于 Vela 优化问题，同步时手环重启为正常现象，开机后继续同步即可。\n\n首次同步报错为正常现象。\n若某文件同步一直报错，可重新选择并推送。" +
                if (isCountingDown) "\n\n请仔细阅读以上内容（${countdown} 秒后可继续）" else "",
        onDismissRequest = { /* 禁止外部关闭 */ },
        dismissText = "取消",
        confirmText = if (isCountingDown) "确认 ($countdown)" else "确认",
        confirmEnabled = !isCountingDown,
        onDismiss = onCancel,
        onConfirm = onConfirm
    )
}