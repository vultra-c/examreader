package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 文件夹创建对话框：输入文件夹名称后确认创建。
 */
@Composable
fun FolderCreationDialog(
    show: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (show) {
        var folderName by remember { mutableStateOf("") }

        AppDialog(
            title = "创建文件夹",
            summary = "输入文件夹名称，将在应用知识库目录下创建",
            show = show,
            onDismissRequest = onDismiss,
            dismissText = "取消",
            confirmText = "创建",
            onDismiss = {
                folderName = ""
                onDismiss()
            },
            onConfirm = {
                if (folderName.isNotBlank()) {
                    onConfirm(folderName.trim())
                    folderName = ""
                }
            },
            content = {
                TextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "文件夹名称",
                    singleLine = true
                )
            }
        )
    }
}
