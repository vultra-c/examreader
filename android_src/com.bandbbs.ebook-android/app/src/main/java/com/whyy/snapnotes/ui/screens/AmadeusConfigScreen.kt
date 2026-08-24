package com.whyy.snapnotes.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.logic.ModelCatalog
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.components.AppDialog
import com.whyy.snapnotes.ui.components.ModelPickerDialog
import com.whyy.snapnotes.ui.viewmodel.AmadeusConfig
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手环端 Amadeus AI 聊天助手的配置页（手机端）。
 *
 * - 全部字段只存手机端 SharedPreferences（见 [com.whyy.snapnotes.ui.viewmodel.SnapNotesViewModel] 的
 *   Amadeus 段），手环不传不存不感知。首次启动内置 NVIDIA NIM 配置（baseUrl/apiKey/model），
 *   开箱即可用；用户在配置页覆盖后以用户值为准。
 * - 「启用」关闭时下方 API 项依旧可见可填，但 [AmadeusConfig.isReady] 在未启用时永远为 false。
 * - Model 支持内置模型目录（带品牌图标）+ 自动获取（GET /v1/models）+ 手动输入三种来源。
 * - API Key 编辑框默认掩码显示，可点右侧眼睛图标临时明文查看。
 * - TopAppBar 右上角图标进「上下文管理菜单」（[AmadeusContextScreen]）。
 *
 * @param onBackClick 返回主页（由 NavDisplay 的 onBack 兜底，本回调用于 TopAppBar 返回箭头）。
 * @param onOpenContext 打开上下文管理菜单。
 * @param availableModels 自动获取到的可用模型列表（null=未获取，空=获取中或失败）
 * @param onFetchModels 触发获取可用模型
 */
@Composable
fun AmadeusConfigScreen(
    config: AmadeusConfig,
    onEnabledChange: (Boolean) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onOpenContext: () -> Unit,
    availableModels: List<String>? = null,
    modelsLoading: Boolean = false,
    onFetchModels: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 当前正在编辑的文本字段；非 null 时弹出对应编辑对话框。
    var editingField by remember { mutableStateOf<EditField?>(null) }
    // 是否显示模型选择对话框
    var showModelPicker by remember { mutableStateOf(false) }

    // 记录最近一次成功获取模型的时间戳，用于在卡片上提示「上次获取 HH:mm」。
    var lastFetchTime by remember { mutableStateOf<Long?>(null) }
    val wasLoading = remember { mutableStateOf(false) }
    LaunchedEffect(modelsLoading, availableModels) {
        // 仅在「由加载中 -> 加载完成且拿到结果」这一跳变时记录时间，避免每次重组都覆盖。
        if (wasLoading.value && !modelsLoading && !availableModels.isNullOrEmpty()) {
            lastFetchTime = System.currentTimeMillis()
        }
        wasLoading.value = modelsLoading
    }

    BackHandler { onBackClick() }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "Amadeus",
                largeTitle = "Amadeus",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenContext, modifier = Modifier.padding(end = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Notes, contentDescription = "上下文管理")
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
                SmallTitle(text = "基本", modifier = Modifier.padding(top = 12.dp))
                AppCard(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    BasicComponent(
                        title = "启用 Amadeus",
                        summary = "关闭后手环端聊天不会发起调用",
                        endActions = {
                            Switch(
                                checked = config.enabled,
                                onCheckedChange = onEnabledChange
                            )
                        }
                    )
                }
            }
            item {
                SmallTitle(text = "API")
                AppCard(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = MiuixTheme.colorScheme.surfaceContainer
                ) {
                    // AppCard 内容为 Box 布局，多个条目必须用 Column 纵向排布，否则会重叠
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = "Base URL",
                            summary = config.baseUrl.ifBlank { "留空 = 走厂商默认" },
                            onClick = { editingField = EditField.BaseUrl }
                        )
                        BasicComponent(
                            title = "API Key",
                            summary = if (config.apiKey.isNotBlank()) "已设置（点此修改）" else "未设置",
                            onClick = { editingField = EditField.ApiKey }
                        )
                        BasicComponent(
                            title = "Model",
                            summary = if (config.model.isBlank()) "未设置"
                            else ModelCatalog.displayNameFor(config.model),
                            onClick = { showModelPicker = true }
                        )
                    }
                }
            }
            // 模型获取操作卡：用 primaryContainer 着色突出动作属性，左侧带 Refresh 徽标，
            // 加载中替换为小转圈；未填 API Key 时不再静默禁用，而是点击直接跳去填 Key。
            item {
                val apiKeyBlank = config.apiKey.isBlank()
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    containerColor = MiuixTheme.colorScheme.primaryContainer,
                    onClick = {
                        when {
                            apiKeyBlank -> editingField = EditField.ApiKey
                            modelsLoading -> Unit
                            else -> {
                                onFetchModels()
                                if (availableModels.isNullOrEmpty()) showModelPicker = true
                            }
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (modelsLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (modelsLoading) "正在获取可用模型…" else "获取可用模型",
                                style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1
                            )
                            val subtitle = when {
                                modelsLoading -> "请稍候，正在请求 /v1/models"
                                apiKeyBlank -> "请先填写 API Key（点击去填写）"
                                !availableModels.isNullOrEmpty() -> {
                                    val count = "已获取 ${availableModels.size} 个模型"
                                    lastFetchTime?.let {
                                        "$count · 上次更新 ${formatFetchTime(it)}"
                                    } ?: count
                                }
                                availableModels != null -> "未获取到模型，点击重试"
                                else -> "点击从服务端拉取可用模型列表"
                            }
                            Text(
                                text = subtitle,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }

    // 文本编辑对话框（Base URL / API Key）
    AmadeusTextEditDialog(
        show = editingField != null && editingField != EditField.Model,
        title = editingField?.title ?: "",
        label = editingField?.label ?: "",
        hint = editingField?.hint ?: "",
        initial = when (editingField) {
            EditField.BaseUrl -> config.baseUrl
            EditField.ApiKey -> config.apiKey
            else -> ""
        },
        maskInput = editingField == EditField.ApiKey,
        onDismiss = { editingField = null },
        onConfirm = { value ->
            editingField?.let { field ->
                when (field) {
                    EditField.BaseUrl -> onBaseUrlChange(value)
                    EditField.ApiKey -> onApiKeyChange(value)
                    else -> Unit
                }
            }
            editingField = null
        }
    )

    // 模型选择/手动输入对话框
    if (showModelPicker) {
        ModelPickerDialog(
            show = true,
            currentModel = config.model,
            availableModels = availableModels,
            loading = modelsLoading,
            autoFetchOnOpen = config.apiKey.isNotBlank(),
            onSelect = { model ->
                onModelChange(model)
                showModelPicker = false
            },
            onManualInput = { model ->
                onModelChange(model)
                showModelPicker = false
            },
            onRefresh = onFetchModels,
            onDismiss = { showModelPicker = false }
        )
    }
}

/**
 * 文本编辑弹窗，使用 AppDialog（底层走 miuix WindowDialog）控制显隐动画。
 *
 * [maskInput] 为 true 时（API Key）默认掩码显示，右侧眼睛图标可切换明文。
 */
@Composable
private fun AmadeusTextEditDialog(
    show: Boolean,
    title: String,
    label: String,
    hint: String,
    initial: String,
    maskInput: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var reveal by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(show, initial) {
        if (show) {
            text = initial
            reveal = false
            delay(80)
            focusRequester.requestFocus()
        }
    }

    AppDialog(
        title = title,
        summary = hint,
        show = show,
        onDismissRequest = onDismiss,
        dismissText = "取消",
        confirmText = "确定",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(text.trim()) },
        content = {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                label = label,
                visualTransformation = if (maskInput && !reveal) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (maskInput) {
                    {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                imageVector = if (reveal) MiuixIcons.Hide else MiuixIcons.Show,
                                contentDescription = if (reveal) "隐藏密钥" else "显示密钥"
                            )
                        }
                    }
                } else null
            )
        }
    )
}

/** 把时间戳格式化成「HH:mm」用于卡片上的「上次更新」提示。失败返回空串。 */
private fun formatFetchTime(timestamp: Long): String {
    return try {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    } catch (e: Exception) {
        ""
    }
}

/** 可弹窗编辑的文本项；集中管理标题/标签/提示文案。 */
private enum class EditField(val title: String, val label: String, val hint: String) {
    BaseUrl("Base URL", "API 根地址", "留空走厂商默认，如 https://integrate.api.nvidia.com"),
    ApiKey("API Key", "密钥", "请输入API密钥"),
    Model("Model", "模型名", "如 meta/llama-3.3-70b-instruct")
}
