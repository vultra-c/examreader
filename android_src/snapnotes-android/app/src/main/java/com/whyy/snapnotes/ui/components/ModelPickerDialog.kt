package com.whyy.snapnotes.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.logic.ModelCatalog
import com.whyy.snapnotes.logic.ModelIconLoader
import com.whyy.snapnotes.logic.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型选择对话框（配置页与 AI 对话页共用）：
 * 内置推荐模型（带品牌图标）+ 服务端获取模型 + 手动输入。
 *
 * - [autoFetchOnOpen] 为 true 且从未获取过（[availableModels] == null）时，打开即自动拉取一次。
 * - 模型超过 5 个时顶部出现搜索框，按名称/id 实时过滤。
 * - 每个模型条目左侧是 Gemini 风格的圆形品牌图标（@lobehub/icons CDN），右侧选中态打勾。
 */
@Composable
fun ModelPickerDialog(
    show: Boolean,
    currentModel: String,
    availableModels: List<String>?,
    loading: Boolean,
    autoFetchOnOpen: Boolean = false,
    onSelect: (String) -> Unit,
    onManualInput: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var manualText by remember { mutableStateOf(currentModel) }
    var showManualInput by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(show) {
        if (show) {
            manualText = currentModel
            showManualInput = false
            searchQuery = ""
            if (availableModels == null && autoFetchOnOpen) {
                onRefresh()
            }
        }
    }

    // 内置目录 + 服务端模型（去重后追加），始终有可选内容。
    val allModels = remember(availableModels) { ModelCatalog.merge(availableModels) }
    val showSearch = allModels.size > 5
    val filteredModels = remember(allModels, searchQuery) {
        if (searchQuery.isBlank()) allModels
        else allModels.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
                it.displayName.contains(searchQuery, ignoreCase = true)
        }
    }

    AppDialog(
        title = "选择模型",
        summary = "从列表选择，或手动输入模型名",
        show = show,
        onDismissRequest = onDismiss,
        dismissText = "关闭",
        confirmText = "",
        onDismiss = onDismiss,
        onConfirm = onDismiss,
        content = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "模型（${allModels.size}）",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f)
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "刷新",
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (showSearch) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        label = "搜索模型",
                        leadingIcon = {
                            Icon(
                                imageVector = MiuixIcons.Search,
                                contentDescription = "搜索",
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(
                                    onClick = { searchQuery = "" },
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
                }

                LazyColumn(modifier = Modifier.height(260.dp)) {
                    if (filteredModels.isEmpty()) {
                        item {
                            Text(
                                text = if (searchQuery.isBlank()) "暂无模型"
                                else "未找到匹配「$searchQuery」的模型",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(vertical = 20.dp)
                            )
                        }
                    } else {
                        items(filteredModels, key = { it.id }) { info ->
                            ModelListItem(
                                info = info,
                                isSelected = info.id == currentModel,
                                onClick = { onSelect(info.id) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                AnimatedVisibility(
                    visible = showManualInput,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        TextField(
                            value = manualText,
                            onValueChange = { manualText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = "手动输入模型名"
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                text = "取消",
                                onClick = { showManualInput = false },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = "确定",
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = { onManualInput(manualText.trim()) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (!showManualInput) {
                    TextButton(
                        text = "手动输入模型名",
                        onClick = { showManualInput = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    )
}

/**
 * Gemini 风格的模型品牌图标：白色圆形底 + 彩色品牌 logo。
 * 图标来自 @lobehub/icons 静态 CDN（见 [ModelIconLoader]）；加载失败时回退为品牌首字母。
 */
@Composable
fun ModelIcon(
    provider: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(provider) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(provider) {
        bitmap = withContext(Dispatchers.IO) { ModelIconLoader.load(provider)?.asImageBitmap() }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.7f)
            )
        } else {
            Text(
                text = provider.take(1).uppercase(),
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Bold,
                color = providerAccent(provider)
            )
        }
    }
}

/**
 * 单个模型条目：左侧 Gemini 风格圆形品牌图标，中间友好名 + 模型 id，
 * 选中时以 primaryContainer 高亮、右侧打勾。
 */
@Composable
private fun ModelListItem(
    info: ModelInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (isSelected) MiuixTheme.colorScheme.primaryContainer else Color.Transparent
    val titleColor = if (isSelected) {
        MiuixTheme.colorScheme.onPrimaryContainer
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelIcon(provider = info.provider, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.displayName,
                style = MiuixTheme.textStyles.body2,
                color = titleColor,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (info.displayName != info.id) {
                Text(
                    text = info.id,
                    style = MiuixTheme.textStyles.footnote2,
                    color = titleColor.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = MiuixIcons.Ok,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 品牌首字母回退色（logo 加载失败 / 加载中时用）。 */
private fun providerAccent(provider: String): Color = when (provider) {
    "nvidia" -> Color(0xFF76B900)
    "meta" -> Color(0xFF0866FF)
    "deepseek" -> Color(0xFF4D6BFE)
    "qwen" -> Color(0xFF615CED)
    "moonshot" -> Color(0xFF0D0E12)
    "mistral" -> Color(0xFFFA520F)
    "google" -> Color(0xFF4285F4)
    "microsoft" -> Color(0xFF00A4EF)
    "openai" -> Color(0xFF10A37F)
    "anthropic" -> Color(0xFFD97757)
    "zhipu" -> Color(0xFF3859FF)
    else -> Color(0xFF76B900)
}
