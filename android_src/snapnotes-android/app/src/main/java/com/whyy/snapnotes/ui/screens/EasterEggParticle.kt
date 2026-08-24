package com.whyy.snapnotes.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 一条世界线：代号、主题色、官方变动率展示串、自编闪念台词集。
 * 变动率与结局设定严格按《命运石之门》官方；台词为自编、贴官方结局意象（已去学科谐音梗）。
 */
private data class WorldLine(
    val code: String,         // Ω / α / 命运石之门 / β / γ / δ（定格展示用全称）
    val shortCode: String,    // 滚动快闪用短码：S;G 等（其余与 code 同）
    val color: Color,
    val divLo: Double,        // 变动率下界（%）。
    val divHi: Double,        // 变动率上界（%）。
    val divergenceExact: String? = null, // 仅命运石之门线：精确 1.048596%（非随机）。
    val ending: String,       // 官方结局简述（一句话）
    val isSg: Boolean = false, // 命运石之门线：唯一不受收束范围影响、特殊出场。
    val lines: List<String>
) {
    /** 在该世界线变动率区间内随机生成 6 位精度展示串，如 "0.342187%"。精确线返回 divergenceExact。 */
    fun rollDivergence(): String = if (divergenceExact != null) divergenceExact
    else "%.6f".format(divLo + Math.random() * (divHi - divLo))
}

// ── 世界线（官方顺序：Ω → α → 命运石之门 → β → γ → δ；8 条中只上轮盘这 6 条，去掉 Χ/ε）──
private val WORLD_LINES = listOf(
    WorldLine(
        code = "Ω",
        shortCode = "Ω",
        color = Color(0xFFBB66FF),
        divLo = -1.0, divHi = 0.0,
        ending = "未来道具研究所不复存在，与所有人疏远",
        lines = listOf(
            "你与世界擦肩而过，没人知道你背过哪些题。",
            "这条线里，连一张小抄都没人肯传给你。",
            "最远的收束，是连该抄什么都忘了。",
            "未来道具研究所不存在于此线，笔记也无处安放。",
            "你和所有人疏远，包括从前的那个自己。",
            "Organization？ Organization 已与你无关。"
        )
    ),
    WorldLine(
        code = "α",
        shortCode = "α",
        color = Color(0xFFFF4444),
        divLo = 0.0, divHi = 1.0,
        ending = "SERN 统治的绝望乡，真由理于 8 月死亡",
        lines = listOf(
            "你抄了又抄，影子却在阴影里收束这条线。",
            "8 月的钟声响过，那份小抄再也送不到她手里。",
            "三百人委员会的绝望乡，连错题都成了规定动作。",
            "你按下「确定」，世界便悄悄换了档。",
            "笔记救不了的人，小抄也救不了。",
            "绝望乡里，没人再问「这个考点今年考不考」。"
        )
    ),
    WorldLine(
        code = "命运石之门",
        shortCode = "S;G",
        color = Color(0xFFFFD700),
        divLo = 1.048596, divHi = 1.048596,
        divergenceExact = "1.048596",
        ending = "唯一不受收束影响的真结局",
        isSg = true,
        lines = listOf(
            "你把所有知识点抄进了手环，世界终于收束。",
            "已抵达真结局 · El Psy Kongroo。",
            "这条线不属于任何收束范围，小抄也终于冗余。",
            "绕过 α 的绝望、避开 β 的战火，笔记完整无缺。",
            "命运石之门，是唯一可以自己选择抄什么的终点。",
            "你长按了 1.0，世界开始转动 —— 而你，记得全部。"
        )
    ),
    WorldLine(
        code = "β",
        shortCode = "β",
        color = Color(0xFF4488FF),
        divLo = 1.0, divHi = 2.0,
        ending = "三战爆发 57 亿人死亡，红莉栖于 7 月死亡",
        lines = listOf(
            "2010 年 7 月，世界为一个人停下你的手。",
            "57 亿人是这条线开出的账单，附一张抄不完的表。",
            "第三次世界大战已写入这条线的未来提纲。",
            "你赌上时间机器，只为换她那次默写不交白卷。",
            "战火下的考点，是「怎样再写一遍同一道题」。",
            "你抄给她的，最后一句没写完。"
        )
    ),
    WorldLine(
        code = "γ",
        shortCode = "γ",
        color = Color(0xFF44DD88),
        divLo = 2.0, divHi = 3.0,
        ending = "冈部成为三百人委员会独裁者",
        lines = listOf(
            "镜中那个统治者，背的是你抄过的题。",
            "这条线里，你成了你曾对抗的人，连笔记都抄了一份。",
            "暗黑次元的海德，在等你赴一场必抄的小测。",
            "三百人委员会的座次为你空着，桌上放着你当年的错题本。",
            "你的独裁，从「重新排版这张小抄」开始。",
            "你掌握了一切，却记不起为什么要抄。"
        )
    ),
    WorldLine(
        code = "δ",
        shortCode = "δ",
        color = Color(0xFFE08A00),
        divLo = 3.0, divHi = 4.0,
        ending = "相对和平的世界，《比翼恋理的爱人》舞台",
        lines = listOf(
            "比翼恋理，这条线留着温热的小抄。",
            "和平是这条线最奢侈的常态，考前也不熬夜。",
            "战火未起，未来道具研究所还热闹着、还在传纸条。",
            "相对安稳的世界，也有它该抄的笔记。",
            "这里的小抄，只为让人少走一步。",
            "你抄的不是救命的题，是一只好看的草稿。"
        )
    )
)

private enum class RollState { Idle, Rolling, Settled }

/**
 * 安卓版《闪念骰子》：圆形文字滚动轮盘。
 * - Idle：提示「点击启动 · 长按关」。
 * - Rolling：点中心按钮启动，中心代号/短句高速快闪、外环旋转，再点一次停止。
 * - Settled：定格出一条世界线代号 + 闪念台词，淡入展示。
 *
 * @param show 是否显示（由调用方 remember 持有）。
 * @param onDismiss 关闭回调（点空白或返回键）。
 */
@Composable
fun EasterEggParticle(
    show: Boolean,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = show) { onDismiss() }

    var rollState by remember { mutableStateOf(RollState.Idle) }
    // Rolling 时高频切换的「当前候选」代号；Settled 时定格为最终结果。
    var currentCode by remember { mutableStateOf(WORLD_LINES[0].code) }
    // 最终定格的世界线索引；用 -1 表示未定格。
    var settledIndex by remember { mutableStateOf(-1) }
    // 当前展示的台词（Settled 后赋值）。
    var settledLine by remember { mutableStateOf("") }
    // 定格后展示的变动率串（在该线区间内随机生成 6 位；命运石之门用精确值）。
    var settledDivergence by remember { mutableStateOf("") }
    // 帧时间驱动外环旋转与快闪节奏。
    var frameNanos by remember { mutableStateOf(0L) }
    // 外环旋转角度（弧度，rolling 时累加）。
    val ringRotation = remember { Animatable(0f) }
    // 定格台词淡入进度。
    val lineAlpha by animateFloatAsState(
        targetValue = if (rollState == RollState.Settled) 1f else 0f,
        animationSpec = tween(600),
        label = "LineFade"
    )
    // 命运石之门线专属「金光脉冲」：从定格瞬间由 0→1 推进，驱动金光扩散波。
    val sgPulse = remember { Animatable(0f) }

    // 命运石之门线定格期间：金光脉冲循环扩散（0→1 反复），定格时一直涌金波。
    LaunchedEffect(rollState, settledIndex) {
        if (rollState == RollState.Settled && settledIndex >= 0 && WORLD_LINES[settledIndex].isSg) {
            sgPulse.snapTo(0f)
            sgPulse.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durationMillis = 1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        } else {
            // 离开 S;G 定格（再投或关闭）时收回。
            sgPulse.snapTo(0f)
        }
    }

    // Rolling 推进：逐帧驱动快闪切换 + 外环旋转。
    LaunchedEffect(rollState) {
        if (rollState == RollState.Rolling) {
            var lastSwitchNanos = 0L
            while (rollState == RollState.Rolling) {
                withFrameNanos { frame ->
                    frameNanos = frame
                    // ~60ms 切一次候选代号，制造飞速滚动感。
                    if (lastSwitchNanos == 0L) lastSwitchNanos = frame
                    if (frame - lastSwitchNanos > 60_000_000L && rollState == RollState.Rolling) {
                        currentCode = WORLD_LINES[Random.nextInt(WORLD_LINES.size)].shortCode
                        lastSwitchNanos = frame
                    }
                }
                // 外环匀速旋转（每帧约 +0.06 弧度 ≈ 8 秒一圈）。
                ringRotation.snapTo(ringRotation.value + 0.06f)
            }
        }
    }

    val centerColor = if (rollState == RollState.Settled && settledIndex >= 0) {
        WORLD_LINES[settledIndex].color
    } else {
        Color(0xFFE6E6E6)
    }
    // 纯黑底统一（不透于关于页、不受深浅模式影响）。盘面为暗灰，文字/盘面一律白系，
    // 各世界线 brand 色在黑底上鲜亮；命运石之门金光在黑底上最显眼。
    val onTextColor = Color(0xFFE6E6E6)
    val onSubColor = Color(0xFF9A9AA0)
    val dialColor = Color(0xFF1A1A1E)
    val scrimColor = Color.Black
    val ringTickColor = Color.White.copy(alpha = 0.12f)


    AnimatedVisibility(
        visible = show,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(180))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 轮盘 ──
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .pointerInput(rollState) {
                            // 点击轮盘：Idle→Rolling，Rolling→Settled；Settled 不响应（点空白才关）。
                            detectTapGestures {
                                when (rollState) {
                                    RollState.Idle -> {
                                        // 清成 shortCode 避免上一局定格的全称「命运石之门」用 68sp 撑爆内圈。
                                        currentCode = WORLD_LINES[Random.nextInt(WORLD_LINES.size)].shortCode
                                        rollState = RollState.Rolling
                                    }
                                    RollState.Rolling -> {
                                        val idx = Random.nextInt(WORLD_LINES.size)
                                        settledIndex = idx
                                        val wl = WORLD_LINES[idx]
                                        // 注意：不写 currentCode，定格显示从 settledIndex 派生，避免快闪回调覆盖。
                                        settledLine = wl.lines[Random.nextInt(wl.lines.size)]
                                        settledDivergence = wl.rollDivergence()
                                        rollState = RollState.Settled
                                    }
                                    RollState.Settled -> {
                                        // 再投一次：先清成 shortCode 防闪现上次定格的全称，再进入 Rolling。
                                        currentCode = if (settledIndex >= 0)
                                            WORLD_LINES[settledIndex].shortCode
                                        else WORLD_LINES[Random.nextInt(WORLD_LINES.size)].shortCode
                                        rollState = RollState.Rolling
                                    }
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val center = Offset(w / 2f, h / 2f)
                        val outerR = minOf(w, h) / 2f * 0.95f
                        val ringR = outerR * 0.82f
                        val innerR = outerR * 0.6f

                        // 外环：五色分段（每段 72°），rolling 时整体旋转。
                        val segArc = (2f * Math.PI / WORLD_LINES.size).toFloat()
                        val rotation = ringRotation.value
                        for (i in WORLD_LINES.indices) {
                            val start = rotation + i * segArc - (Math.PI / 2).toFloat()
                            val sweep = segArc
                            drawArc(
                                color = WORLD_LINES[i].color.copy(alpha = if (rollState == RollState.Settled && i == settledIndex) 0.95f else 0.32f),
                                startAngle = Math.toDegrees(start.toDouble()).toFloat(),
                                sweepAngle = Math.toDegrees(sweep.toDouble()).toFloat(),
                                useCenter = false,
                                topLeft = Offset(center.x - ringR, center.y - ringR),
                                size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                                style = Stroke(width = 14f)
                            )
                        }

                        // 内圈底（暗灰盘面，纯黑背景上的一圈暗灰）。
                        drawCircle(
                            color = dialColor,
                            radius = innerR
                        )
                        // 内圈描边（当前世界线色，Settled 后点亮）。
                        drawCircle(
                            color = centerColor.copy(alpha = if (rollState == RollState.Settled) 0.9f else 0.55f),
                            radius = innerR,
                            style = Stroke(width = 4f)
                        )
                        // 内圈刻度（细环）。
                        drawCircle(
                            color = ringTickColor,
                            radius = innerR * 0.85f,
                            style = Stroke(width = 1f)
                        )
                        // 命运石之门线特殊出场：金光扩散波（由 sgPulse 0→1 驱动半径增大、alpha 渐弱）。
                        val isSgSettled = rollState == RollState.Settled && settledIndex >= 0 && WORLD_LINES[settledIndex].isSg
                        if (isSgSettled) {
                            val sgColor = WORLD_LINES[settledIndex].color
                            val p = sgPulse.value
                            // 三道相位错开的扩散环：每道沿 pulse 推进半径 + 降 alpha。
                            for (k in 0..2) {
                                val pk = (p + k * 0.33f).coerceIn(0f, 1f)
                                val r = innerR + pk * 80f
                                val a = (1f - pk) * 0.5f
                                if (a > 0f) {
                                    drawCircle(
                                        color = sgColor.copy(alpha = a),
                                        radius = r,
                                        style = Stroke(width = 3f)
                                    )
                                }
                            }
                            // 中心一道放大变细的冲击波（前半段扩散、后半段淡出）。
                            val shock = (p * 1.2f).coerceIn(0f, 1f)
                            drawCircle(
                                color = sgColor.copy(alpha = (1f - shock) * 0.9f),
                                radius = innerR + shock * 60f,
                                style = Stroke(width = 6f * (1f - shock).coerceAtLeast(0.1f))
                            )
                            // 外环整体一圈金光（定格持续点亮，固定 alpha 避免循环边界跳动）。
                            drawCircle(
                                color = sgColor.copy(alpha = 0.4f),
                                radius = ringR,
                                style = Stroke(width = 3f)
                            )
                        }
                    }

                    // ── 中央文字层（代号 + 状态提示），独立于 Canvas ──
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 代号大字：定格时从 settledIndex 直接派生（与 currentCode 解耦，避免竞态闪错码）。
                        val isSgSettled = rollState == RollState.Settled && settledIndex >= 0 && WORLD_LINES[settledIndex].isSg
                        val displayCode = when {
                            rollState == RollState.Settled && settledIndex >= 0 -> WORLD_LINES[settledIndex].shortCode
                            else -> currentCode
                        }
                        Text(
                            text = displayCode,
                            color = centerColor.copy(alpha = if (rollState == RollState.Settled) 1f else 0.92f),
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isSgSettled) 56.sp else 68.sp,
                            textAlign = TextAlign.Center
                        )
                        // S;G 专属标记：代号下方一行「命运石之门」全称。
                        if (isSgSettled) {
                            Text(
                                text = "命运石之门",
                                color = WORLD_LINES[settledIndex].color.copy(alpha = lineAlpha * 0.95f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        // 状态提示 / 定格后的变动率：除「世界线变动中…」外分两行 \n 展示，水平居中。
                        val subtitle = when (rollState) {
                            RollState.Idle -> "点击启动\n世界线待机"
                            RollState.Rolling -> "世界线变动中…"
                            RollState.Settled -> if (settledIndex >= 0) {
                                "${WORLD_LINES[settledIndex].code} 世界线\n变动率 $settledDivergence%"
                            } else ""
                        }
                        Text(
                            text = subtitle,
                            color = onSubColor,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // ── 中心按钮区（底部说明）──
                    val hint = when (rollState) {
                        RollState.Idle -> "↓ 点击轮盘启动"
                        RollState.Rolling -> "↓ 再点一次，停！"
                        RollState.Settled -> "↓ 点轮盘重投 · 点空白关"
                    }
                    Text(
                        text = hint,
                        color = onTextColor.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )
                }

                // ── 定格台词 ──
                if (rollState == RollState.Settled && settledLine.isNotBlank()) {
                    Text(
                        text = "「$settledLine」",
                        color = onTextColor.copy(alpha = lineAlpha * 0.92f),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 28.dp)
                    )
                    Text(
                        text = if (settledIndex >= 0) "结局 · ${WORLD_LINES[settledIndex].ending}" else "",
                        color = onSubColor.copy(alpha = lineAlpha),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }

            // 顶部标题与小字脚注。
            Text(
                text = "闪念骰子 · Divergence Meter",
                color = onTextColor.copy(alpha = 0.35f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
            )
            Text(
                text = "点空白关闭 · El Psy Kongroo",
                color = onTextColor.copy(alpha = 0.3f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}
