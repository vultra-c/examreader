package com.whyy.snapnotes.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.data.StoreEntry
import com.whyy.snapnotes.data.StorePack
import com.whyy.snapnotes.data.StoreSubject
import com.whyy.snapnotes.ui.components.FolderCreationDialog
import com.whyy.snapnotes.ui.components.MoreMenu
import com.whyy.snapnotes.ui.components.AppCard
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
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 知识点包详情页（全屏页面）。
 *
 * 取代原 [StoreScreen] 中弹窗形式的 `StorePackDetailDialog`，以完整页面展示某个
 * [StorePack] 的全部内容，并提供：
 *
 * - 在线搜索：输入关键词后通过浏览器跳转至搜索引擎查找在线资源；
 * - 包头部信息：名称、作者、简介、科目数 / 知识点条数、免费徽章；
 * - 科目列表：每个科目为独立卡片，可展开查看全部条目（标题 + 描述）；
 * - 选择导入：勾选部分科目后批量导入，或单科目一键导入；
 * - 一键导入全部：导入整个知识点包。
 *
 * 动画细节：
 * - 展开箭头使用 [animateFloatAsState] + [spring] 做弹性旋转；
 * - 条目列表在有界滚动容器中展示，避免长内容参与逐帧高度动画；
 * - 卡片尺寸变化使用 [animateContentSize] 的短 tween 平滑过渡。
 *
 * @param pack 知识点包
 * @param onBackClick 返回上一页
 * @param onImportAll 一键导入全部科目
 * @param onImportSelected 导入选中的科目列表
 * @param onImportSingle 导入单个科目
 * @param onCreateFolder 创建文件夹（参数为文件夹名称）
 * @param modifier Modifier
 */
@Composable
fun StoreDetailScreen(
    pack: StorePack,
    onBackClick: () -> Unit,
    onImportAll: () -> Unit,
    onImportSelected: (List<StoreSubject>) -> Unit,
    onImportSingle: (StoreSubject) -> Unit,
    onEditSubject: (StoreSubject) -> Unit = {},
    onCreateFolder: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBackClick() }

    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val scrollState = rememberScrollState()

    // 展开状态：按科目名记录是否展开
    val expandedSubjects = remember { mutableStateMapOf<String, Boolean>() }
    // 选择状态：按科目名记录是否选中
    val selectedSubjects = remember { mutableStateMapOf<String, Boolean>() }

    var searchQuery by remember { mutableStateOf("") }
    var showFolderDialog by remember { mutableStateOf(false) }

    val selectedCount = selectedSubjects.count { it.value }
    val allSelected = selectedCount == pack.subjects.size && pack.subjects.isNotEmpty()

    // 在线搜索：通过浏览器跳转至搜索引擎
    fun openOnlineSearch() {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) return
        val url = "https://www.google.com/search?q=" + Uri.encode(keyword)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    if (showFolderDialog) {
        FolderCreationDialog(
            show = true,
            onConfirm = { name ->
                showFolderDialog = false
                onCreateFolder(name)
            },
            onDismiss = { showFolderDialog = false }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = pack.name,
                largeTitle = pack.name,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    MoreMenu(onCreateFolder = { showFolderDialog = true })
                }
            )
        },
        popupHost = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(scrollState)
                .overScrollVertical()
                .scrollEndHaptic()
                .padding(horizontal = 12.dp)
        ) {
            // ── 在线搜索卡片 ──────────────────────────────────────────
            OnlineSearchCard(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { openOnlineSearch() }
            )

            // ── 包头部信息卡片 ────────────────────────────────────────
            PackHeaderCard(pack = pack)

            // ── 科目列表标题 + 全选 / 取消全选 ─────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallTitle(
                    text = "科目列表（${pack.subjects.size}）",
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = if (allSelected) "取消全选" else "全选",
                    onClick = {
                        if (allSelected) {
                            selectedSubjects.clear()
                        } else {
                            pack.subjects.forEach { selectedSubjects[it.name] = true }
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }

            // ── 科目卡片列表 ──────────────────────────────────────────
            pack.subjects.forEach { subject ->
                SubjectCard(
                    subject = subject,
                    isExpanded = expandedSubjects[subject.name] == true,
                    isSelected = selectedSubjects[subject.name] == true,
                    onToggleExpand = {
                        if (expandedSubjects[subject.name] == true) {
                            expandedSubjects.remove(subject.name)
                        } else {
                            expandedSubjects[subject.name] = true
                        }
                    },
                    onToggleSelect = {
                        if (selectedSubjects[subject.name] == true) {
                            selectedSubjects.remove(subject.name)
                        } else {
                            selectedSubjects[subject.name] = true
                        }
                    },
                    onImport = { onImportSingle(subject) },
                    onEdit = { onEditSubject(subject) }
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── 一键导入全部 ──────────────────────────────────────────
            Button(
                onClick = onImportAll,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Icon(
                    imageVector = MiuixIcons.Download,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "一键导入全部 ${pack.totalEntries} 条",
                    color = MiuixTheme.colorScheme.onPrimary
                )
            }

            // ── 导入选中科目（有选中时出现） ──────────────────────────
            if (selectedCount > 0) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val picked = pack.subjects.filter { selectedSubjects[it.name] == true }
                        onImportSelected(picked)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "导入选中 $selectedCount 个科目",
                        color = MiuixTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 在线搜索卡片：输入关键词后点击「搜索在线资源」通过浏览器跳转。
 */
@Composable
private fun OnlineSearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        containerColor = MiuixTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Search,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "在线搜索知识点",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
            }

            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = "输入关键词搜索在线资源…",
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = "搜索",
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Close,
                                contentDescription = "清除"
                            )
                        }
                    }
                } else null,
                singleLine = true
            )

            Button(
                onClick = onSearch,
                modifier = Modifier.fillMaxWidth(),
                enabled = query.isNotBlank(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Icon(
                    imageVector = MiuixIcons.Send,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "搜索在线资源",
                    color = MiuixTheme.colorScheme.onPrimary
                )
            }

            Text(
                text = "提示：点击搜索将打开浏览器跳转至在线网页。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

/**
 * 包头部信息卡片：名称、免费徽章、作者、简介、科目数 / 知识点条数。
 */
@Composable
private fun PackHeaderCard(pack: StorePack) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        containerColor = MiuixTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = pack.name,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (pack.isFree) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "免费",
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "开发者：${pack.author}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Text(
                text = pack.description,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PackStat(text = "${pack.subjects.size} 科目")
                PackStat(text = "${pack.totalEntries} 条知识点")
            }
        }
    }
}

/**
 * 头部统计小标签。
 */
@Composable
private fun PackStat(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 科目卡片：展示科目名、知识点条数、选择圆圈、单科导入按钮与展开箭头；
 * 展开后在有界滚动区域展示该科目的全部条目。
 *
 * 交互：
 * - 点击选择圆圈 → 切换选中；
 * - 点击卡片头部其它区域（名称 / 条数 / 箭头）→ 展开 / 收起；
 * - 点击「导入」→ 导入该科目。
 */
@Composable
private fun SubjectCard(
    subject: StoreSubject,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpand: () -> Unit,
    onToggleSelect: () -> Unit,
    onImport: () -> Unit,
    onEdit: () -> Unit = {}
) {
    // 展开/收起箭头旋转：收起指向右（▶），展开指向下（▼）
    val entriesScrollState = rememberScrollState()
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 270f else 180f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "subjectChevron"
    )

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        containerColor = MiuixTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(220, easing = EaseOutExpo))
        ) {
            // ── 头部行 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 选择圆圈（点击切换选中）
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MiuixTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .border(
                            width = 2.dp,
                            color = if (isSelected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            shape = CircleShape
                        )
                        .clickable { onToggleSelect() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // 科目名 + 条数
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subject.name,
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${subject.entries.size} 条知识点",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }

                // 编辑（跳转到编辑器）
                TextButton(
                    text = "编辑",
                    onClick = onEdit,
                    colors = ButtonDefaults.textButtonColors()
                )

                // 单科导入
                TextButton(
                    text = "导入",
                    onClick = onImport,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )

                // 展开 / 收起箭头
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            // ── 展开的条目列表 ──
            // 不再使用 expandVertically：长列表在高度动画期间会被逐帧重测，
            // 容易造成黑屏和掉帧。外层 animateContentSize 负责短促的高度过渡，
            // 内容区域只布局一次并在需要时独立滚动。
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(entriesScrollState)
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    subject.entries.forEach { entry ->
                        EntryItem(entry = entry)
                    }
                }
            }
        }
    }
}

/**
 * 单条知识点条目：序号圆点 + 标题 + 描述。
 */
@Composable
private fun EntryItem(entry: StoreEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 序号圆点
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = entry.id.toString(),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.desc,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}
