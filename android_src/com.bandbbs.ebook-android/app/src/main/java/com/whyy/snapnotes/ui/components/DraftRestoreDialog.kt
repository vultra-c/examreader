package com.whyy.snapnotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.whyy.snapnotes.ui.components.AppDialog

@Composable
fun DraftRestoreDialog(
    show: Boolean,
    onRestore: () -> Unit,
    onDiscard: () -> Unit
) {
    // 先用本地可见态关弹窗、播退场动画，动画完全结束后（onDismissFinished）再执行动作。
    // 直接执行的话，「恢复」会立刻载入草稿触发编辑器大重组，主线程被占住，
    // 退场动画的帧被全部跳过，表现为弹窗瞬间消失没有动画。
    var visible by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(show) {
        if (show) {
            visible = true
            pendingAction = null
        }
    }
    AppDialog(
        show = visible,
        title = "恢复草稿",
        summary = "检测到上次编辑未保存的内容，是否恢复到编辑器？",
        onDismissRequest = { /* 必须显式选择，禁止外部关闭 */ },
        dismissText = "丢弃",
        confirmText = "恢复",
        onDismiss = {
            visible = false
            pendingAction = onDiscard
        },
        onConfirm = {
            visible = false
            pendingAction = onRestore
        }
    )

    // 退场动画结束后执行延迟动作（直接执行会阻塞主线程导致动画被跳过）。
    androidx.compose.runtime.LaunchedEffect(visible) {
        if (!visible && pendingAction != null) {
            kotlinx.coroutines.delay(220)
            pendingAction?.let { it() }
            pendingAction = null
        }
    }
}