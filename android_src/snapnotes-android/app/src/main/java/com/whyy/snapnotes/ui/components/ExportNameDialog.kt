package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.TextField
import kotlinx.coroutines.delay

/**
 * 导出命名对话框：让用户输入文件名后确认。
 * @param defaultName 默认文件名（不含扩展名也可以，函数内补 .json）
 * @param onConfirm 返回最终文件名（保证以 .json 结尾）
 */
@Composable
fun ExportNameDialog(
    show: Boolean,
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (fileName: String) -> Unit
) {
    var name by remember(show) { mutableStateOf(defaultName) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(show) {
        if (show) {
            name = defaultName
            delay(80)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 显示/隐藏完全交给 AppDialog 的 show（内部 AnimatedVisibility）。
    AppDialog(
        title = "导出文件名",
        summary = "为导出的 JSON 文件命名（自动补 .json）",
        show = show,
        onDismissRequest = onDismiss,
        dismissText = "取消",
        confirmText = "下一步",
        onDismiss = onDismiss,
        onConfirm = {
            val clean = name.trim().ifBlank { defaultName }
            val withExt = if (clean.endsWith(".json", ignoreCase = true)) clean else "$clean.json"
            onConfirm(withExt)
        },
        content = {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .imePadding(),  // 键盘弹出时输入框上移
                singleLine = true,
                label = "文件名"
            )
        }
    )
}