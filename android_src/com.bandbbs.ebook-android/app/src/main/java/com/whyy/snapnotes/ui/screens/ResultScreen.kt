package com.whyy.snapnotes.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.viewmodel.PushState
import com.whyy.snapnotes.ui.viewmodel.toReadableBytes
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ResultScreen(
    pushState: PushState,
    onBackHome: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isSuccess = pushState.isSuccess

    val primary = MiuixTheme.colorScheme.primary
    val error = MiuixTheme.colorScheme.error
    val onSurface = MiuixTheme.colorScheme.onSurface
    val accentColor = if (isSuccess) primary else error

    // ── 成功时的扩散涟漪 + 粒子飞溅动画进度（0→1 单次播完）──
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(isSuccess) {
        if (isSuccess) {
            ripple.snapTo(0f)
            ripple.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing)
            )
        } else {
            ripple.snapTo(0f)
        }
    }
    val particleColors = remember(primary) {
        listOf(primary, Color(0xFFFFC107), Color(0xFF4CAF50), Color(0xFF03A9F4))
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            SmallTopAppBar(title = if (isSuccess) "推送完成" else "推送失败")
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                if (!isSuccess) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressable(interactionSource = null, indication = SinkFeedback()),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("重试", color = MiuixTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = onBackHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable(interactionSource = null, indication = SinkFeedback()),
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Text("返回首页", color = MiuixTheme.colorScheme.onSecondaryVariant)
                }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 结果主卡片：图标（弹跳入场 + 成功涟漪/粒子） + 标题 + 副标题 ──
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ),
                containerColor = MiuixTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(168.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // 成功：扩散涟漪 + 粒子飞溅
                        if (isSuccess) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                val p = ripple.value
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val maxR = size.minDimension / 2f
                                // 三道相位错开的扩散环
                                for (i in 0..2) {
                                    val pi = (p - i * 0.12f).coerceIn(0f, 1f)
                                    val r = pi * maxR
                                    val a = (1f - pi) * 0.45f
                                    if (a > 0f && r > 0f) {
                                        drawCircle(
                                            color = primary.copy(alpha = a),
                                            radius = r,
                                            center = center,
                                            style = Stroke(width = 3f)
                                        )
                                    }
                                }
                                // 粒子向四周飞溅
                                val count = 12
                                for (i in 0 until count) {
                                    val angle = (2.0 * PI * i / count).toFloat()
                                    val dist = p * maxR * 0.92f
                                    val px = center.x + cos(angle) * dist
                                    val py = center.y + sin(angle) * dist
                                    val a = (1f - p) * 0.95f
                                    val radius = (1f - p) * 5f + 2f
                                    if (a > 0f) {
                                        drawCircle(
                                            color = particleColors[i % particleColors.size].copy(alpha = a),
                                            radius = radius,
                                            center = Offset(px, py)
                                        )
                                    }
                                }
                            }
                        }
                        // 图标底圈 + 弹跳入场的成功 / 失败图标
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .background(
                                    color = accentColor.copy(alpha = 0.12f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedContent(
                                targetState = isSuccess,
                                transitionSpec = {
                                    (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                                            scaleIn(
                                                initialScale = 0.3f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                )
                                            )) togetherWith
                                            (fadeOut(tween(durationMillis = 120)) +
                                                    scaleOut(targetScale = 0.3f))
                                },
                                label = "ResultIcon"
                            ) { success ->
                                Icon(
                                    imageVector = if (success) MiuixIcons.Ok else MiuixIcons.Close,
                                    contentDescription = if (success) "成功" else "失败",
                                    tint = if (success) primary else error,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = if (isSuccess) "传输完成" else "传输失败",
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    if (pushState.statusText.isNotBlank()) {
                        Text(
                            text = pushState.statusText,
                            style = MiuixTheme.textStyles.body2,
                            color = onSurface
                        )
                    }
                }
            }

            // ── 传输明细卡片：文件名 / 大小 / 状态（失败时附错误信息）──
            val detailPairs: List<Pair<String, String>> = buildList {
                if (pushState.fileName.isNotBlank()) {
                    add("文件名" to pushState.fileName)
                }
                if (pushState.fileSize > 0L) {
                    add("大小" to pushState.fileSize.toReadableBytes())
                }
                if (pushState.statusText.isNotBlank()) {
                    add("状态" to pushState.statusText)
                }
                pushState.errorMessage
                    ?.takeIf { !isSuccess && it.isNotBlank() }
                    ?.let { add("错误信息" to it) }
            }
            if (detailPairs.isNotEmpty()) {
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        detailPairs.forEachIndexed { index, (label, value) ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(
                                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
                                        )
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Text(
                                    text = value,
                                    style = MiuixTheme.textStyles.body2,
                                    fontWeight = FontWeight.Medium,
                                    color = onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(start = 16.dp)
                                        .weight(1f),
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
