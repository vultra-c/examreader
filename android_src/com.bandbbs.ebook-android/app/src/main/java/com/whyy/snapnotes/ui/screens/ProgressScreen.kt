package com.whyy.snapnotes.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.viewmodel.PushState
import com.whyy.snapnotes.ui.viewmodel.toReadableBytes
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

@Composable
fun ProgressScreen(
    pushState: PushState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = pushState.progress.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "PushProgressAnimation"
    )

    // 顶部图标对应的推送阶段：等待 → 传输中 → 成功 / 失败。
    val statusKind = when {
        pushState.isFinished && pushState.isSuccess -> ProgressStatusKind.Success
        pushState.isFinished && !pushState.isSuccess -> ProgressStatusKind.Failed
        pushState.isTransferring -> ProgressStatusKind.Transferring
        else -> ProgressStatusKind.Waiting
    }

    // ── 传输速度 / ETA 估算：按 pushState.progress 的变化采样，EMA 平滑 ──
    var lastProgress by remember { mutableStateOf(0.0) }
    var lastNanos by remember { mutableStateOf(0L) }
    var smoothedSpeed by remember { mutableStateOf(0.0) }
    LaunchedEffect(pushState.progress, pushState.isTransferring) {
        if (pushState.isTransferring && pushState.fileSize > 0) {
            val now = System.nanoTime()
            if (lastNanos != 0L && pushState.progress >= lastProgress) {
                val dt = (now - lastNanos) / 1_000_000_000.0
                if (dt > 0) {
                    val instant = (pushState.progress - lastProgress) * pushState.fileSize / dt
                    if (instant > 0) smoothedSpeed = smoothedSpeed * 0.6 + instant * 0.4
                }
            }
            if (pushState.progress < lastProgress) smoothedSpeed = 0.0
            lastProgress = pushState.progress
            lastNanos = now
        } else {
            // 非传输态：清空采样，避免下次复用旧时间戳
            lastProgress = 0.0
            lastNanos = 0L
            smoothedSpeed = 0.0
        }
    }

    val percent = (pushState.progress * 100).toInt().coerceIn(0, 100)
    val transferred = (pushState.progress * pushState.fileSize).toLong()
    val remainingBytes = (1.0 - pushState.progress) * pushState.fileSize
    val speedText = if (pushState.isTransferring) formatSpeed(smoothedSpeed) else "—"
    val etaText = if (pushState.isTransferring && smoothedSpeed > 0)
        formatEta(remainingBytes / smoothedSpeed) else "—"

    // ── 百分比文字脉冲（仅传输中）──
    val pulseTransition = rememberInfiniteTransition(label = "PercentPulse")
    val pulseAlphaState = pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier,
        topBar = {
            SmallTopAppBar(title = "推送进度")
        },
        bottomBar = {
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .pressable(interactionSource = null, indication = SinkFeedback()),
                colors = ButtonDefaults.buttonColors()
            ) {
                Text(text = "取消", color = MiuixTheme.colorScheme.onSecondaryVariant)
            }
        },
        popupHost = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 顶部状态图标：按 push 状态切换（等待 / 传输中 / 成功 / 失败）──
            val iconBg = when (statusKind) {
                ProgressStatusKind.Success -> primary.copy(alpha = 0.12f)
                ProgressStatusKind.Failed -> MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
                else -> primary.copy(alpha = 0.10f)
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(color = iconBg, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(
                    targetState = statusKind,
                    animationSpec = tween(durationMillis = 400, easing = LinearEasing),
                    label = "StatusIcon"
                ) { kind ->
                    when (kind) {
                        ProgressStatusKind.Transferring -> CircularProgressIndicator(
                            modifier = Modifier.size(36.dp)
                        )
                        ProgressStatusKind.Success -> Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = "推送成功",
                            tint = primary,
                            modifier = Modifier.size(40.dp)
                        )
                        ProgressStatusKind.Failed -> Icon(
                            imageVector = MiuixIcons.Close,
                            contentDescription = "推送失败",
                            tint = MiuixTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp)
                        )
                        ProgressStatusKind.Waiting -> Icon(
                            imageVector = MiuixIcons.Send,
                            contentDescription = "等待推送",
                            tint = primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            // ── 文件信息卡片 ──
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MiuixTheme.colorScheme.surfaceContainer
            ) {
                BasicComponent(
                    title = pushState.fileName.ifBlank { "未知文件" },
                    summary = pushState.fileSize.toReadableBytes()
                )
            }

            // ── 进度卡片 ──
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MiuixTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 状态文字（切换动画） + 百分比（传输中脉冲）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedContent(
                            targetState = pushState.statusText,
                            transitionSpec = {
                                (slideInVertically(
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                ) { it / 4 } +
                                        fadeIn(
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                        )) togetherWith
                                        (slideOutVertically(
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                        ) { -it / 4 } +
                                                fadeOut(
                                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                ))
                            },
                            label = "StatusText",
                            modifier = Modifier.weight(1f)
                        ) { text ->
                            Text(
                                text = text,
                                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                                color = onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "$percent%",
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.Bold,
                            color = primary,
                            modifier = Modifier.graphicsLayer {
                                alpha = if (pushState.isTransferring) pulseAlphaState.value else 1f
                            }
                        )
                    }

                    // 带 shimmer 流光的进度条
                    ShimmerLinearProgress(
                        progress = animatedProgress,
                        transferring = pushState.isTransferring
                    )

                    // 传输速度 / ETA / 已传输
                    if (pushState.fileSize > 0 && (pushState.isTransferring || pushState.isFinished)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TransferMetric(
                                label = "已传输",
                                value = "${transferred.toReadableBytes()} / ${pushState.fileSize.toReadableBytes()}",
                                modifier = Modifier.weight(1f)
                            )
                            TransferMetric(
                                label = "速度",
                                value = speedText,
                                modifier = Modifier.weight(1f),
                                centerAligned = true
                            )
                            TransferMetric(
                                label = "剩余",
                                value = etaText,
                                modifier = Modifier.weight(1f),
                                endAligned = true
                            )
                        }
                    }

                    if (pushState.preview.isNotEmpty()) {
                        Text(
                            text = pushState.preview,
                            style = MiuixTheme.textStyles.footnote1,
                            color = primary
                        )
                    }
                }
            }
        }
    }
}

/** 顶部状态图标对应的推送阶段。 */
private enum class ProgressStatusKind { Waiting, Transferring, Success, Failed }

/**
 * 带 shimmer 流光的线性进度条：底层用 Miuix [LinearProgressIndicator] 画轨道与填充，
 * 传输中时在已填充区域叠加一道循环流光（横向白色渐变扫过）。
 */
@Composable
private fun ShimmerLinearProgress(
    progress: Float,
    transferring: Boolean,
    modifier: Modifier = Modifier
) {
    val shimmerTransition = rememberInfiniteTransition(label = "Shimmer")
    val shimmerPosState = shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerPos"
    )
    Box(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth()
        )
        if (transferring) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val fillWidth = size.width * progress
                if (fillWidth > 1f) {
                    val sweepCenter = fillWidth * (shimmerPosState.value * 1.4f - 0.2f)
                    val sweepHalf = fillWidth * 0.22f
                    val brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.55f),
                            Color.Transparent
                        ),
                        startX = sweepCenter - sweepHalf,
                        endX = sweepCenter + sweepHalf
                    )
                    drawRoundRect(
                        brush = brush,
                        size = Size(fillWidth, size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
            }
        }
    }
}

/** 传输明细小字段：标签 + 值，可左 / 中 / 右对齐。 */
@Composable
private fun TransferMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    centerAligned: Boolean = false,
    endAligned: Boolean = false
) {
    val horizontal = when {
        endAligned -> Alignment.End
        centerAligned -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }
    Column(modifier = modifier, horizontalAlignment = horizontal) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

/** 把字节/秒格式化为 B/s · KB/s · MB/s；无效值返回「—」。 */
private fun formatSpeed(bytesPerSec: Double): String {
    if (bytesPerSec <= 0 || bytesPerSec.isNaN() || bytesPerSec.isInfinite()) return "—"
    return when {
        bytesPerSec >= 1024.0 * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024))
        bytesPerSec >= 1024.0 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        else -> "%.0f B/s".format(bytesPerSec)
    }
}

/** 把剩余秒数格式化为 12s / 1:05 / 1:02:03；无效值返回「—」。 */
private fun formatEta(seconds: Double): String {
    if (seconds <= 0 || seconds.isNaN() || seconds.isInfinite()) return "—"
    val s = seconds.toLong()
    return when {
        s >= 3600 -> "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        s >= 60 -> "%d:%02d".format(s / 60, s % 60)
        else -> "${s}s"
    }
}
