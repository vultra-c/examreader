package com.whyy.snapnotes.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.logic.BandStorageInfoData
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.viewmodel.toReadableBytes
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页手环存储空间圆环卡片。
 *
 * - 圆环背景灰 + 前景主色按「已用/总容量」比例画弧，动画推进。
 * - 圆心叠「已用占比」百分比与「已用 / 总容量」两行字。
 * - 未连接 / 未拿到数据时虚位占位（圆环空、文字占位）。
 * - 右上刷新按钮：isRefreshing 时转圈。
 *
 * 数据来源 [BandStorageInfoData]，与手环端 storage_info 回包字段对齐。
 */
@Composable
fun StorageRingCard(
    storageInfo: BandStorageInfoData?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val total = storageInfo?.totalStorage ?: 0L
    val used = (storageInfo?.usedStorage ?: 0L).coerceAtLeast(0L)
    val available = (storageInfo?.availableStorage ?: 0L).coerceAtLeast(0L)
    // 已用占比驱动圆环动画；可用段由 1 - 已用占比决定，两段拼成完整圆环。
    val rawRatio = if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    // 圆环弧动画：数据变化时平滑过渡。
    val animatedRatio by animateFloatAsState(
        targetValue = rawRatio.toFloat(),
        animationSpec = tween(durationMillis = 600),
        label = "storageRatio"
    )
    val hasValidData = storageInfo?.hasValidData == true

    val primary = MiuixTheme.colorScheme.primary
    val usedColor = Color(0xFFFF6B35) // 已用段 + 「已用」文字：橙红
    val availableColor = primary      // 可用段 + 「可用」文字：主色
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    AppCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MiuixTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "手环存储空间",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface
                )
                Spacer(Modifier.weight(1f))
                if (hasValidData && isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = (isConnected && !isRefreshing),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = "刷新存储空间",
                        tint = MiuixTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!isConnected) {
                Text(
                    text = "连接手环后显示存储空间",
                    style = MiuixTheme.textStyles.body2,
                    color = summaryColor
                )
            } else if (!hasValidData) {
                Text(
                    text = if (isRefreshing) "正在查询…" else "暂无存储数据，点击刷新",
                    style = MiuixTheme.textStyles.body2,
                    color = summaryColor
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Box(
                        modifier = Modifier.size(108.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(108.dp)) {
                            val stroke = 14.dp.toPx()
                            val diameter = size.minDimension - stroke
                            val topLeft = Offset(
                                x = (size.width - diameter) / 2f,
                                y = (size.height - diameter) / 2f
                            )
                            val arcSize = Size(diameter, diameter)
                            // 两段拼成完整圆环（从 12 点顺时针）：
                            //   可用段（主色 primary）先扫「可用占比」
                            //   已用段（橙红）接着尾部扫「已用占比」
                            // 端头用 Butt 平接，避免 Round 端头在拼接处重叠出鼓包。
                            val usedSweep = animatedRatio * 360f
                            val availableSweep = 360f - usedSweep
                            if (availableSweep > 0f) {
                                drawArc(
                                    color = availableColor,
                                    startAngle = -90f,
                                    sweepAngle = availableSweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                                )
                            }
                            if (usedSweep > 0f) {
                                drawArc(
                                    color = usedColor,
                                    startAngle = -90f + availableSweep,
                                    sweepAngle = usedSweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                                )
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "%.0f%%".format(rawRatio * 100.0),
                                style = MiuixTheme.textStyles.title2,
                                fontWeight = FontWeight.Bold,
                                color = onSurface
                            )
                            Text(
                                text = "已用",
                                style = MiuixTheme.textStyles.footnote2,
                                color = summaryColor
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StorageMetric(label = "已用", value = used.toReadableBytes(), color = usedColor)
                        StorageMetric(label = "可用", value = available.toReadableBytes(), color = availableColor)
                        StorageMetric(label = "总容量", value = total.toReadableBytes(), color = summaryColor)
                    }
                }
                Spacer(Modifier.height(4.dp))
                storageInfo.product?.takeIf { it.isNotBlank() }?.let { product ->
                    Text(
                        text = product,
                        style = MiuixTheme.textStyles.footnote2,
                        color = summaryColor
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageMetric(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = color
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}
