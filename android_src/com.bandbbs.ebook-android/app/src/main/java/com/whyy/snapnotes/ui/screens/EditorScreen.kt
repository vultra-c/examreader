package com.whyy.snapnotes.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.logic.FormulaPngRenderer
import com.whyy.snapnotes.logic.RawToLatexConverter
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.components.AppDialog
import com.whyy.snapnotes.ui.viewmodel.EditorEntry
import com.whyy.snapnotes.ui.viewmodel.EditorSubject
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

@Composable
fun EditorScreen(
    subjects: List<EditorSubject>,
    formulaRenderer: FormulaPngRenderer?,
    onAddSubject: () -> Unit,
    onRemoveSubject: (Int) -> Unit,
    onUpdateSubjectName: (Int, String) -> Unit,
    onAddEntry: (Int) -> Unit,
    onRemoveEntry: (Int, Int) -> Unit,
    onUpdateEntry: (Int, Int, EditorEntry) -> Unit,
    onLoadFile: () -> Unit,
    onExportToFile: () -> Unit,
    onPushFile: () -> Unit,
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "编辑 JSON 文件",
                largeTitle = "编辑 JSON 文件",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(paddingValues)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EditorContent(
                subjects = subjects,
                formulaRenderer = formulaRenderer,
                onAddSubject = onAddSubject,
                onRemoveSubject = onRemoveSubject,
                onUpdateSubjectName = onUpdateSubjectName,
                onAddEntry = onAddEntry,
                onRemoveEntry = onRemoveEntry,
                onUpdateEntry = onUpdateEntry,
                onLoadFile = onLoadFile,
                onExportToFile = onExportToFile,
                onPushFile = onPushFile
            )
        }
    }
}

/**
 * 编辑器内容区：包含加载文件、科目列表、导出、推送等操作。
 * 可独立嵌入其他页面的滚动列表中（如合并到主页）。
 */
@Composable
fun EditorContent(
    subjects: List<EditorSubject>,
    formulaRenderer: FormulaPngRenderer?,
    onAddSubject: () -> Unit,
    onRemoveSubject: (Int) -> Unit,
    onUpdateSubjectName: (Int, String) -> Unit,
    onAddEntry: (Int) -> Unit,
    onRemoveEntry: (Int, Int) -> Unit,
    onUpdateEntry: (Int, Int, EditorEntry) -> Unit,
    onLoadFile: () -> Unit,
    onExportToFile: () -> Unit,
    onPushFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onLoadFile,
            modifier = Modifier
                .fillMaxWidth()
                .pressable(interactionSource = null, indication = SinkFeedback()),
            colors = ButtonDefaults.buttonColors()
        ) {
            Icon(imageVector = MiuixIcons.File, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("加载现有 JSON 文件", color = MiuixTheme.colorScheme.onSecondaryVariant)
        }

        SmallTitle(text = "科目列表", modifier = Modifier.padding(top = 4.dp))

        if (subjects.isEmpty()) {
            EmptyPlaceholderCard(
                text = "还没有任何科目，点击下方按钮添加第一个科目",
                icon = MiuixIcons.Edit
            )
        }

        subjects.forEachIndexed { subjectIndex, subject ->
            SubjectCard(
                subject = subject,
                subjectIndex = subjectIndex,
                formulaRenderer = formulaRenderer,
                onRemoveSubject = { onRemoveSubject(subjectIndex) },
                onUpdateSubjectName = { onUpdateSubjectName(subjectIndex, it) },
                onAddEntry = { onAddEntry(subjectIndex) },
                onRemoveEntry = { entryIndex -> onRemoveEntry(subjectIndex, entryIndex) },
                onUpdateEntry = { entryIndex, entry -> onUpdateEntry(subjectIndex, entryIndex, entry) }
            )
        }

        Button(
            onClick = onAddSubject,
            modifier = Modifier
                .fillMaxWidth()
                .pressable(interactionSource = null, indication = SinkFeedback()),
            colors = ButtonDefaults.buttonColors()
        ) {
            Icon(imageVector = MiuixIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加科目", color = MiuixTheme.colorScheme.onSecondaryVariant)
        }

        Button(
            onClick = onExportToFile,
            modifier = Modifier
                .fillMaxWidth()
                .pressable(interactionSource = null, indication = SinkFeedback()),
            colors = ButtonDefaults.buttonColors()
        ) {
            Icon(imageVector = MiuixIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("导出 JSON 文件", color = MiuixTheme.colorScheme.onSecondaryVariant)
        }

        Button(
            onClick = onPushFile,
            modifier = Modifier
                .fillMaxWidth()
                .pressable(interactionSource = null, indication = SinkFeedback()),
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Icon(imageVector = MiuixIcons.Send, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("推送 JSON 文件到手环", color = MiuixTheme.colorScheme.onPrimary)
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SubjectCard(
    subject: EditorSubject,
    subjectIndex: Int,
    formulaRenderer: FormulaPngRenderer?,
    onRemoveSubject: () -> Unit,
    onUpdateSubjectName: (String) -> Unit,
    onAddEntry: () -> Unit,
    onRemoveEntry: (Int) -> Unit,
    onUpdateEntry: (Int, EditorEntry) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "SubjectChevron"
    )

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MiuixTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Edit,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                TextField(
                    value = subject.name,
                    onValueChange = onUpdateSubjectName,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = "科目名"
                )
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "删除科目",
                    tint = MiuixTheme.colorScheme.error,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onRemoveSubject() }
                )
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevronRotation)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                ) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    subject.entries.forEachIndexed { entryIndex, entry ->
                        EntryCard(
                            entry = entry,
                            formulaRenderer = formulaRenderer,
                            idWarning = entryIdWarning(subject.name, entry, subject.entries),
                            onRemoveEntry = { onRemoveEntry(entryIndex) },
                            onUpdateEntry = { onUpdateEntry(entryIndex, it) }
                        )
                    }

                    Button(
                        onClick = onAddEntry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressable(interactionSource = null, indication = SinkFeedback()),
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Icon(imageVector = MiuixIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加条目", color = MiuixTheme.colorScheme.onSecondaryVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: EditorEntry,
    formulaRenderer: FormulaPngRenderer?,
    idWarning: String?,
    onRemoveEntry: () -> Unit,
    onUpdateEntry: (EditorEntry) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "EntryChevron"
    )
    val e = entry

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MiuixTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = e.title,
                    onValueChange = { onUpdateEntry(e.copy(title = it)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = "标题 *"
                )
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "删除条目",
                    tint = MiuixTheme.colorScheme.error,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onRemoveEntry() }
                )
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp).rotate(chevronRotation)
                )
            }

            // 简介字段始终可见，方便快速编辑
            TextField(
                value = e.desc,
                onValueChange = { onUpdateEntry(e.copy(desc = it)) },
                label = "简介",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp),
                singleLine = false,
                minLines = 1,
                maxLines = 3
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = Spring.DampingRatioNoBouncy)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = e.id,
                        onValueChange = { onUpdateEntry(e.copy(id = it)) },
                        label = "编号（可选，留空自动分配）",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        useLabelAsPlaceholder = false
                    )
                    if (idWarning != null) {
                        Text(
                            text = idWarning,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    TextField(
                        value = e.raw,
                        onValueChange = { onUpdateEntry(e.copy(raw = it)) },
                        label = "原文",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 8
                    )

                    SmallTitle(text = "要点 (points)", modifier = Modifier.padding(top = 4.dp))

                    e.points.forEachIndexed { pIdx, point ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = point,
                                onValueChange = { newValue ->
                                    val newPoints = e.points.toMutableList()
                                    newPoints[pIdx] = newValue
                                    onUpdateEntry(e.copy(points = newPoints))
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = "要点 ${pIdx + 1}"
                            )
                            if (e.points.size > 1) {
                                TextButton(
                                    text = "删除",
                                    onClick = {
                                        val newPoints = e.points.toMutableList().also { it.removeAt(pIdx) }
                                        onUpdateEntry(e.copy(points = newPoints))
                                    }
                                )
                            }
                        }
                    }

                    TextButton(
                        text = "+ 添加要点",
                        onClick = { onUpdateEntry(e.copy(points = e.points + "")) },
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    SmallTitle(text = "公式 (formulas)", modifier = Modifier.padding(top = 4.dp))

                    e.formulas.forEachIndexed { fIdx, formula ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = formula,
                                    onValueChange = { newValue ->
                                        val newFormulas = e.formulas.toMutableList()
                                        newFormulas[fIdx] = newValue
                                        onUpdateEntry(e.copy(formulas = newFormulas))
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = "公式 ${fIdx + 1}"
                                )
                                if (e.formulas.size > 1) {
                                    TextButton(
                                        text = "删除",
                                        onClick = {
                                            val newFormulas = e.formulas.toMutableList().also { it.removeAt(fIdx) }
                                            onUpdateEntry(e.copy(formulas = newFormulas))
                                        }
                                    )
                                }
                            }
                            FormulaPreview(
                                raw = formula,
                                renderer = formulaRenderer,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }

                    TextButton(
                        text = "+ 添加公式",
                        onClick = { onUpdateEntry(e.copy(formulas = e.formulas + "")) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPlaceholderCard(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MiuixTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

private enum class FormulaPreviewState { Idle, Rendering, Ready, Failed }

@Composable
private fun FormulaPreview(
    raw: String,
    renderer: FormulaPngRenderer?,
    modifier: Modifier = Modifier
) {
    var state by remember(raw) { mutableStateOf(FormulaPreviewState.Idle) }
    var pngBytes by remember(raw) { mutableStateOf<ByteArray?>(null) }
    var errorMessage by remember(raw) { mutableStateOf("") }

    LaunchedEffect(raw, renderer) {
        pngBytes = null
        errorMessage = ""
        if (raw.isBlank() || renderer == null) {
            state = FormulaPreviewState.Idle
            return@LaunchedEffect
        }
        state = FormulaPreviewState.Rendering
        delay(400)
        val latex = RawToLatexConverter.convert(raw)
        val detail = renderer.renderDetail(listOf(latex), previewMode = true)
        if (detail == null || detail.png == null) {
            errorMessage = detail?.errorMessages?.firstOrNull() ?: "渲染失败"
            state = FormulaPreviewState.Failed
        } else {
            pngBytes = detail.png.bytes
            state = FormulaPreviewState.Ready
        }
    }

    when (state) {
        FormulaPreviewState.Idle -> Unit
        FormulaPreviewState.Rendering -> {
            Text(
                text = "渲染中…",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = modifier
            )
        }
        FormulaPreviewState.Failed -> {
            Text(
                text = "无法渲染：$errorMessage",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
                modifier = modifier
            )
        }
        FormulaPreviewState.Ready -> {
            val bytes = pngBytes ?: return
            val bitmap = remember(bytes) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (bitmap != null) {
                // 预览渲染已按内容收缩 + 放大字号（36px×2 分辨率），这里按内容尺寸显示并
                // 封顶到屏宽：短公式保持大字号，超宽公式等比缩放进屏。不再强制拉伸。
                val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
                val displayWidth = minOf(bitmap.width.dp, screenWidthDp)
                val displayHeight =
                    displayWidth * (bitmap.height.toFloat() / bitmap.width.toFloat())
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color(0xFF161618))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "公式渲染预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(displayWidth)
                            .height(displayHeight)
                    )
                }
            }
        }
    }
}

/**
 * 手环内置知识点：各科目独立编号（语文1-68、数学1-12、英语1-8、物理1-15、
 * 化学1-10、生物1-11、历史1-6、地理1-6、政治1-7、信息技术1-16，合计159条）。
 * 用户给内置科目补充内容时，编号落在该科目内置区间内会被手环跳过（不覆盖）。
 */
private val BUILTIN_SUBJECT_ID_RANGES = mapOf(
    "语文" to 1..68,
    "数学" to 1..12,
    "英语" to 1..8,
    "物理" to 1..15,
    "化学" to 1..10,
    "生物" to 1..11,
    "历史" to 1..6,
    "地理" to 1..6,
    "政治" to 1..7,
    "信息技术" to 1..16,
)

/**
 * 计算某条目的编号冲突提示：
 * - 若科目名是手环内置科目，编号落在该科目内置区间内：推送后同编号条目不会被覆盖更新；
 * - 同科目内编号重复：手环按编号合并，重复条目不会新增。
 * 空编号不提示；两条可叠加。返回 null 表示无冲突。
 */
private fun entryIdWarning(
    subjectName: String,
    entry: EditorEntry,
    entries: List<EditorEntry>
): String? {
    val id = entry.id.trim()
    if (id.isEmpty()) return null
    val idNum = id.toIntOrNull() ?: return null
    val builtinRange = BUILTIN_SUBJECT_ID_RANGES[subjectName.trim()]
    val inBuiltinRange = builtinRange?.contains(idNum) == true
    val duplicated = entries.count { it.id.trim() == id } > 1
    if (!inBuiltinRange && !duplicated) return null
    return buildString {
        if (inBuiltinRange) {
            append("编号落在内置「$subjectName」已占用区间（${builtinRange!!.first}-${builtinRange.last}）内，推送后同编号条目不会被覆盖更新")
        }
        if (inBuiltinRange && duplicated) append("；")
        if (duplicated) append("该科目内编号重复，重复条目不会新增")
    }
}

@Composable
fun JsonPreviewDialog(
    jsonString: String,
    show: Boolean,
    onDismiss: () -> Unit
) {
    AppDialog(
        show = show,
        title = "JSON 预览",
        summary = "预览即将导出的 JSON 内容：",
        confirmText = "关闭",
        dismissText = "",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .verticalScroll(rememberScrollState())
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Text(
                text = jsonString,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface
            )
        }
    }
}
