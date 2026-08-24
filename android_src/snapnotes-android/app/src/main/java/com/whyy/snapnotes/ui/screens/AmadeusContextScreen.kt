package com.whyy.snapnotes.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.logic.AmadeusChat.CallStatus
import com.whyy.snapnotes.logic.AmadeusChat.SessionDetail
import com.whyy.snapnotes.logic.AmadeusChat.SessionSnapshot
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.components.AppDialog
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Amadeus 上下文管理菜单。端到端观测/调试 Amadeus 聊天：
 *
 * ① 最近一次调用 [lastCall]：Running 旋转 / Success 绿 / Failed 红，附 HTTP 码、耗时、字符数、错误信息。
 *    失败时点卡片复制错误信息到剪贴板。
 * ② 会话列表 [snapshots]：每行 sessionId 短前缀 + 消息条数；点开看完整 user/assistant 往来（下钻弹窗），
 *    行末「清空此会话」；列表底部「清空全部会话」。
 * ③ 测试发送 [onTestSend]：填文本点发送，手机端本地跑完整 SSE（不发 BLE），回复落进 test_ 会话 + 刷 lastCall。
 *    调试网络/key/model 不依赖手环。
 * ④ 导出最近回复：把 lastCall 关联会话最后一条 assistant 文本复制到剪贴板。
 *
 * 全部数据/回调来自 ViewModel 转发的 [com.whyy.snapnotes.logic.AmadeusChat]。
 */
@Composable
fun AmadeusContextScreen(
    lastCall: CallStatus,
    snapshots: List<SessionSnapshot>,
    onDetail: (sessionId: String) -> SessionDetail?,
    onClearSession: (sessionId: String) -> Unit,
    onClearAll: () -> Unit,
    onTestSend: (String) -> Unit,
    onExportLastReply: () -> String?,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var detailFor by remember { mutableStateOf<SessionDetail?>(null) }
    var testInput by remember { mutableStateOf("") }

    BackHandler { onBackClick() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "Amadeus 上下文",
                largeTitle = "Amadeus 上下文",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                }
            )
        },
        popupHost = {}
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 40.dp
            )
        ) {
            // ① 最近一次调用
            item {
                SmallTitle(text = "最近一次调用", modifier = Modifier.padding(top = 12.dp))
                AppCard(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    onClick = {
                        if (lastCall is CallStatus.Failed) {
                            copyToClipboard(context, lastCall.msg)
                            Toast.makeText(context, "错误信息已复制", Toast.LENGTH_SHORT).show()
                        }
                    },
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (lastCall) {
                                is CallStatus.Running -> {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                                    Spacer(Modifier.padding(start = 8.dp))
                                    Text("进行中…", style = MiuixTheme.textStyles.title4)
                                }
                                is CallStatus.Success -> Text("✓ 成功", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.primary)
                                is CallStatus.Failed -> Text("✗ 失败", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onError)
                                CallStatus.Idle -> Text("尚无调用", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(callStatusSummary(lastCall), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (lastCall is CallStatus.Failed) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = lastCall.msg,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onError
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("点此复制错误信息", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }

            // ③ 测试发送
            item {
                SmallTitle(text = "测试发送", modifier = Modifier.padding(top = 12.dp))
                AppCard(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val focusRequester = remember { FocusRequester() }
                        TextField(
                            value = testInput,
                            onValueChange = { testInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .imePadding(),
                            singleLine = true,
                            label = "请输入..."
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { onTestSend(testInput.trim()) },
                                enabled = testInput.isNotBlank() && lastCall !is CallStatus.Running,
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.weight(1f)
                            ) { Text(if (lastCall is CallStatus.Running) "调用中…" else "发送", color = MiuixTheme.colorScheme.onPrimary) }
                            Button(
                                onClick = {
                                    val text = onExportLastReply()
                                    if (text.isNullOrBlank()) {
                                        Toast.makeText(context, "暂无可导出的回复", Toast.LENGTH_SHORT).show()
                                    } else {
                                        copyToClipboard(context, text)
                                        Toast.makeText(context, "最近回复已复制", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("导出回复") }
                        }
                    }
                }
            }

            // ② 会话列表
            item {
                SmallTitle(text = "会话（${snapshots.size}）", modifier = Modifier.padding(top = 12.dp))
            }
            if (snapshots.isEmpty()) {
                item {
                    AppCard(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        containerColor = MiuixTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            "暂无会话。手环发对话或上方测试发送后会出现在这里。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(snapshots, key = { it.sessionId }) { snap ->
                    AppCard(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        onClick = { detailFor = onDetail(snap.sessionId) },
                        containerColor = MiuixTheme.colorScheme.surfaceContainer
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    sessionIdLabel(snap),
                                    style = MiuixTheme.textStyles.title4,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text("${snap.messageCount} 条消息", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            IconButton(onClick = {
                                onClearSession(snap.sessionId)
                                Toast.makeText(context, "已清空会话", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(MiuixIcons.Delete, contentDescription = "清空此会话")
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showClearAllConfirm = true },
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                    ) { Text("清空全部会话") }
                }
            }
        }
    }

    // 会话详情下钻弹窗
    val detail = detailFor
    if (detail != null) {
        val visible = remember(detail) { mutableStateOf(true) }
        AppDialog(
            title = sessionIdLabel(SessionSnapshot(detail.sessionId, detail.messages.size, detail.sessionId.startsWith("test_"))),
            show = visible.value && detailFor != null,
            onDismissRequest = {
                visible.value = false
                detailFor = null
            },
            dismissText = "",
            confirmText = "关闭",
            onConfirm = { detailFor = null },
            content = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    detail.messages.forEachIndexed { i, msg ->
                        Text(
                            text = if (msg.first == "user") "你" else "Amadeus",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            text = msg.second,
                            style = MiuixTheme.textStyles.body2,
                            color = if (msg.first == "user") MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.primary
                        )
                        if (i < detail.messages.lastIndex) Spacer(Modifier.height(12.dp))
                    }
                }
            }
        )
    }

    // 清空全部确认——液态玻璃风格
    AppDialog(
        title = "清空全部会话？",
        summary = "将删除所有 chat 历史，手环新对话仍会建新会话。",
        show = showClearAllConfirm,
        onDismissRequest = { showClearAllConfirm = false },
        dismissText = "取消",
        confirmText = "仍然清空",
        onDismiss = { showClearAllConfirm = false },
        onConfirm = {
            onClearAll()
            showClearAllConfirm = false
            Toast.makeText(context, "已清空全部", Toast.LENGTH_SHORT).show()
        }
    )
}

private fun callStatusSummary(status: CallStatus): String = when (status) {
    CallStatus.Idle -> "尚未发起调用"
    is CallStatus.Running -> "正在调用 LLM…"
    is CallStatus.Success -> "HTTP ${status.http} · ${status.chars} 字符 · ${status.ms}ms"
    is CallStatus.Failed -> {
        val http = status.http?.let { "HTTP $it · " } ?: ""
        "${http}耗时 ${status.ms}ms"
    }
}

private fun sessionIdLabel(snap: SessionSnapshot): String {
    val short = snap.sessionId.take(8)
    return if (snap.isTest) "测试会话 $short" else "会话 $short"
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("amadeus", text))
}
