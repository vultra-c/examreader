package com.whyy.snapnotes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页「Amadeus 配置」入口卡片，与连接状态卡并排放一行。
 *
 * - 中性底色（`surfaceContainer`），不随连接态变色——与左侧连接卡的彩色态形成对比。
 * - 右上角状态圆点：绿 = 已启用且配齐 key+model；黄 = 已启用但缺 key 或 model；灰 = 未启用。
 * - 中间 summary：未启用显「未启用」；启用且 ready 显「已配置 · {model}」；启用但缺项显「配置不完整」。
 * - 整卡点击进入 Amadeus 配置页。窄列下保持与左侧连接卡等高、信息不溢出。
 */
@Composable
fun AmadeusConfigCard(
    enabled: Boolean,
    ready: Boolean,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dotColor = when {
        enabled && ready -> Color(0xFF4CAF50)
        enabled -> Color(0xFFFFC107)
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    AppCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
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
                    text = "Amadeus",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).basicMarquee()
                )
                Canvas(modifier = Modifier.size(10.dp)) {
                    drawCircle(color = dotColor, radius = size.minDimension / 2f, center = Offset(center.x, center.y))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = summaryColor,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
        }
    }
}
