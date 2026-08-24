package com.whyy.snapnotes.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.viewmodel.CheckResult
import com.whyy.snapnotes.ui.viewmodel.TroubleshootState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 连接排查页：逐项检查「蓝牙已开启 → 小米运动健康已连接手环 → 手环已装闪念小抄」。
 * - 已解决：绿勾；未解决：红差；检测中：转圈；当前条件无法检测（前置项未过）：灰态问号。
 * - 三项全绿时 VM 自动触发重连，连上后本页 [onBackClick] 退出回主页。
 * - 未授权 BLUETOOTH_CONNECT（Android 12+）时蓝牙项停 Checking，并提示需授权 → [onRequestBluetooth]。
 */
@Composable
fun TroubleshootScreen(
    state: TroubleshootState,
    isConnected: Boolean,
    onBackClick: () -> Unit,
    onRequestBluetooth: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 自动重连成功（autoRetrying 期间 isConnected 翻 true）→ 自动回主页。
    LaunchedEffect(state.autoRetrying, isConnected) {
        if (state.autoRetrying && isConnected) onBackClick()
    }

    // 进入页面时若蓝牙权限未授予（Android 12+），触发一次运行时申请。
    LaunchedEffect(state.bluetoothPermissionGranted) {
        if (!state.bluetoothPermissionGranted) onRequestBluetooth()
    }

    // 返回键回主页；VM 的轮询/广播在 onBackClick 触发的 stopTroubleshoot 里收尾。
    BackHandler { onBackClick() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "连接排查",
                largeTitle = "连接排查",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
                        )
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
            item {
                SmallTitle(text = "连接链路", modifier = Modifier.padding(top = 12.dp))
                AppCard(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth(),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    // AppCard 内容为 Box 布局，多个条目必须用 Column 纵向排布，否则会重叠
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TroubleshootItem(
                            title = "蓝牙已开启",
                            result = state.bluetooth,
                            failSummary = "请开启手机蓝牙后返回",
                            checkingSummary = "正在检测蓝牙状态…",
                            notGrantedHint = state.bluetoothPermissionGranted.not(),
                            onGrantedHint = "需授权蓝牙权限才能检测，请允许权限后继续"
                        )
                        TroubleshootItem(
                            title = "小米运动健康已连接手环",
                            result = state.deviceConnected,
                            failSummary = "请打开小米运动健康新建连接、确保后台运行并在其内连上设备",
                            checkingSummary = "正在查找已连接的设备…",
                            dependSummary = "需先开启蓝牙后才能检测"
                        )
                        TroubleshootItem(
                            title = "手环已装闪念小抄",
                            result = state.appInstalled,
                            failSummary = "请先在手环上安装闪念小抄快应用",
                            checkingSummary = "正在检测手环应用安装状态…",
                            dependSummary = "需先连接手环后才能检测"
                        )
                    }
                }
            }
            item {
                Text(
                    text = if (state.autoRetrying) {
                        "三项检查均已通过，正在自动重连…"
                    } else {
                        "三项全部通过后将自动发起重连，连上后自动返回主页。"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * 单项排查行：左侧状态图标（绿勾/红差/转圈/灰问号），右侧标题 + summary。
 * - [dependSummary] 仅当 [result] == [CheckResult.NotApplicable] 时展示（前置项未过）。
 * - [notGrantedHint]/[onGrantedHint]：蓝牙项未授权时的特殊提示。
 */
@Composable
private fun TroubleshootItem(
    title: String,
    result: CheckResult,
    failSummary: String,
    checkingSummary: String,
    dependSummary: String = "",
    notGrantedHint: Boolean = false,
    onGrantedHint: String = ""
) {
    val (iconContent, iconTint, summary) = when (result) {
        CheckResult.Pass -> Triple(StatusIcon.Ok, MiuixTheme.colorScheme.primary, "已解决")
        CheckResult.Fail ->
            Triple(StatusIcon.Close, MiuixTheme.colorScheme.error, failSummary)
        CheckResult.Checking ->
            Triple(StatusIcon.Spinner, MiuixTheme.colorScheme.primary, checkingSummary)
        CheckResult.NotApplicable ->
            Triple(
                StatusIcon.Question,
                MiuixTheme.colorScheme.onSurfaceVariantSummary,
                dependSummary
            )
    }
    val displaySummary = if (notGrantedHint && result == CheckResult.Checking) onGrantedHint else summary

    BasicComponent(
        title = title,
        summary = displaySummary,
        startAction = {
            Row(
                modifier = Modifier.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (iconContent) {
                    StatusIcon.Ok -> Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = "已解决",
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    StatusIcon.Close -> Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = "未解决",
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    StatusIcon.Question -> Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = "无法检测",
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    StatusIcon.Spinner -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(0.dp))
            }
        }
    )
}

/** 左侧状态图标枚举，便于在 [TroubleshootItem] 里把「图标种类」与文案/颜色解耦。 */
private enum class StatusIcon { Ok, Close, Question, Spinner }
