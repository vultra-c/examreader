package com.whyy.snapnotes.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.whyy.snapnotes.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

/**
 * 可折叠的「AI 提示词」卡片。
 *
 * 展开后展示一段完整的 AI 提示词，用户可一键复制后粘贴给任意 AI 工具
 * （如 DeepSeek、ChatGPT），上传学习资料文件，AI 即可帮你生成符合
 * 「闪念小抄」格式要求的知识点 JSON 文件。
 *
 * - 折叠态：仅显示标题 + 副标题 + 展开箭头。
 * - 展开态：显示使用说明、可滚动的提示词全文、一键复制按钮。
 * - 复制成功后按钮文字临时变为「已复制」，2 秒后自动恢复。
 * - Card 使用 PressFeedbackType.Tilt 倾斜按压反馈，与 TutorialCard 风格一致。
 */
@Composable
fun AiPromptCard(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val addRotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "addRotation"
    )

    // 复制成功后 2 秒自动恢复按钮文字
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    AppCard(
        modifier = modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        containerColor = MiuixTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize(animationSpec = tween(180, easing = androidx.compose.animation.core.EaseOutExpo))
        ) {
            // ── 头部：图标 + 标题/副标题 + 展开箭头 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI 帮你写知识点",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "复制提示词，上传文件让 AI 生成 JSON",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(addRotation)
                )
            }

            // ── 展开内容 ──
            if (expanded) {
                Spacer(Modifier.height(12.dp))

                // 使用说明
                Text(
                    text = "将以下提示词复制给任意 AI（如 DeepSeek、ChatGPT），上传你的学习资料文件，AI 即可帮你生成符合格式的知识点 JSON 文件。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )

                Spacer(Modifier.height(12.dp))

                // 可滚动的提示词全文（点击内容区域不会触发卡片折叠）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .heightIn(max = 300.dp)
                        .clickable(interactionSource = null, indication = null) { }
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = AI_PROMPT,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 一键复制按钮（Button 自身消费点击事件，不会触发卡片折叠）
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(AI_PROMPT))
                        copied = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressable(interactionSource = null, indication = SinkFeedback()),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(
                        imageVector = if (copied) MiuixIcons.Ok else MiuixIcons.Info,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (copied) "已复制" else "一键复制",
                        color = MiuixTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * AI 提示词全文。
 *
 * 告诉 AI 这是一个手环学习 App 的知识点 JSON 生成任务，
 * 详细说明 JSON 结构、各字段含义与输出要求。
 */
private val AI_PROMPT = """
你是一个知识点整理助手。我正在使用一款名为「闪念小抄」的手环学习 App，需要将学习资料整理成 JSON 格式的知识点文件，推送到手环上随时复习。请阅读我上传的文件，提取其中的知识点，按照下面的 JSON 格式输出。

━━━ JSON 格式说明 ━━━

整个文件是一个 JSON 对象（大括号 {}），键是科目名称（字符串），值是该科目下的知识点条目数组。

{
  "科目名": [
    {
      "title": "条目标题",
      "id": 1,
      "desc": "简介内容",
      "points": ["要点1", "要点2"],
      "raw": "整段原文",
      "formulas": ["公式1", "公式2"]
    }
  ]
}

━━━ 字段说明 ━━━

title（必填，字符串）
条目标题。这是唯一必填字段，缺失则该条目被丢弃。每个条目都应有清晰、简洁的标题。

id（可选，数字）
条目编号。缺省时自动按数组顺序从 1 开始编号。同科目内按 id 去重。

desc（可选，字符串）
简介内容，显示在标题下方。请保持简洁，适合在手环小屏幕上阅读。

points（可选，字符串数组）
速记要点列表，每个字符串为一条要点。适合提炼关键信息。

raw（可选，字符串）
整段原文内容，系统会自动按行分段、按字数分页显示。适合较长的原文摘录。

formulas（可选，字符串数组）
公式列表，支持数学文本和 LaTeX 语法。例如 "t = t0 / sqrt(1 - v^2/c^2)" 或 "\frac{-b \pm \sqrt{b^2 - 4ac}}{2a}"。

━━━ 输出要求 ━━━

1. 请阅读我上传的文件，提取其中的知识点。
2. 按科目分类，每个知识点作为一个条目。
3. 每个条目必须有清晰的 title。
4. desc 字段请保持简洁，适合在手环小屏幕上阅读。
5. 输出必须是合法的 JSON，不要包含 markdown 代码块标记（不要用 ```json 包裹）。
6. 文件编码必须为 UTF-8。
7. 直接输出 JSON 内容，不要附加任何解释说明文字。
8. 如有公式，放入 formulas 数组，支持 LaTeX 语法。

请现在阅读我上传的文件并生成 JSON。
""".trimIndent()
