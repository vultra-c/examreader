package com.whyy.snapnotes.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whyy.snapnotes.logic.AmadeusChat
import com.whyy.snapnotes.logic.ModelCatalog
import com.whyy.snapnotes.ui.components.AppCard
import com.whyy.snapnotes.ui.components.AppDialog
import com.whyy.snapnotes.ui.components.ModelIcon
import com.whyy.snapnotes.ui.components.ModelPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.*

/**
 * 手机端 Amadeus AI 直聊界面（不走手环 BLE，面向手机端本地对话场景）。
 *
 * 用户可直接在手机端与 Amadeus 对话、上传文本文件作为上下文，并要求 AI 生成
 * 知识点 JSON 文件。当 AI 回复内容形似 JSON（以 `{` 或 `[` 开头）时，消息下方
 * 出现「导入到手环」按钮，点击后经 [onImportJson] 回调将 JSON 内容导入。
 *
 * 顶部包含两条提示卡：
 * 1. 锁屏后台不可用警告（[LockScreenWarningCard]）——Android 硬限制，息屏后台时
 *    手机端无法新发起网络请求，亮屏后立即恢复。
 * 2. Amadeus 未就绪横幅（[NotReadyBanner]）——未启用或未配置 API Key/Model 时
 *    提示用户去配置，点击按钮跳转 [onOpenConfig]。
 *
 * 聊天区域使用 LazyColumn + overScrollVertical + scrollEndHaptic，新消息到达时
 * 自动滚动到底部。底部固定输入区包含文件上传按钮、多行输入框和发送按钮。
 *
 * @param messages 手机端直聊历史消息列表（role: "user"/"assistant"）。
 * @param chatStatus 当前聊天状态（Idle/Loading/Success/Failed）。
 * @param amadeusEnabled Amadeus 是否已启用（开关状态）。
 * @param amadeusReady Amadeus 是否已就绪（已启用且 API Key/Model 均已配置）。
 * @param currentModel 当前选中的模型 id；底部切换条展示其友好名与品牌图标。
 * @param availableModels 服务端拉取到的模型列表（null=未获取），用于切换弹窗。
 * @param onModelChange 在对话界面内切换模型。
 * @param onSendMessage 发送消息。参数为 (text, fileContent?)，fileContent 非空时
 *        作为文档上下文随消息一并发送。
 * @param onClearChat 清空手机端直聊历史。
 * @param onImportJson 当 AI 回复疑似 JSON 时，将 JSON 内容导入到手环。
 * @param onOpenConfig 打开 Amadeus 配置页。
 * @param onBackClick 返回上一页。
 */
@Composable
fun AmadeusChatScreen(
    messages: List<AmadeusChat.PhoneChatMessage>,
    chatStatus: AmadeusChat.PhoneChatStatus,
    amadeusEnabled: Boolean,
    amadeusReady: Boolean,
    currentModel: String,
    availableModels: List<String>? = null,
    modelsLoading: Boolean = false,
    onModelChange: (String) -> Unit = {},
    onFetchModels: () -> Unit = {},
    onSendMessage: (String, String?) -> Unit,
    onClearChat: () -> Unit,
    onImportJson: (String) -> Unit,
    onOpenConfig: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var attachedFileContent by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showFileHintDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    val isLoading = chatStatus is AmadeusChat.PhoneChatStatus.Loading
    val isFailed = chatStatus is AmadeusChat.PhoneChatStatus.Failed
    // 有输入文字，或已选文件但未输入文字，都可发送；加载中/未就绪时禁用。
    val canSend = (inputText.isNotBlank() || attachedFileContent != null) && !isLoading && amadeusReady

    BackHandler { onBackClick() }

    // 新消息到达或加载状态变化时，自动滚动到底部。
    // headerCount 需与下方 LazyColumn 中的前置 item 数量保持一致（警告卡 + 可选未就绪横幅）。
    LaunchedEffect(messages.size, isLoading, isFailed) {
        if (messages.isNotEmpty()) {
            val headerCount = 1 + (if (!amadeusReady) 1 else 0)
            listState.animateScrollToItem(headerCount + messages.size - 1)
        }
    }

    // 文件选择器：GetContent("text/*")，拿到 Uri 后在 IO 线程读取全文。
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().readText()
                        }
                    }.getOrNull()
                }
                if (content != null) {
                    attachedFileName = queryDisplayName(context, uri) ?: "未知文件"
                    attachedFileContent = content
                } else {
                    Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleSend() {
        if (inputText.isBlank() && attachedFileContent.isNullOrBlank()) return
        onSendMessage(inputText.trim(), attachedFileContent)
        inputText = ""
        attachedFileName = null
        attachedFileContent = null
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "Amadeus 对话",
                largeTitle = "Amadeus 对话",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }, modifier = Modifier.padding(end = 6.dp)) {
                        Icon(imageVector = MiuixIcons.Delete, contentDescription = "清空对话")
                    }
                    com.whyy.snapnotes.ui.components.MoreMenu(
                        onOpenAmadeusConfig = onOpenConfig
                    )
                }
            )
        },
        popupHost = {}
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 聊天消息区（可滚动，内容从 TopAppBar 下方开始） ──
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .overScrollVertical()
                    .scrollEndHaptic(),
                state = listState,
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 16.dp
                )
            ) {
                // ① 锁屏后台不可用警告卡
                item(key = "warning") {
                    LockScreenWarningCard()
                }

                // ② Amadeus 未就绪横幅
                if (!amadeusReady) {
                    item(key = "not_ready") {
                        NotReadyBanner(
                            message = if (!amadeusEnabled) {
                                "Amadeus 未启用，请先在配置中开启"
                            } else {
                                "Amadeus 未配置 API Key 或 Model"
                            },
                            onOpenConfig = onOpenConfig
                        )
                    }
                }

                // ③ 消息列表 / 空状态
                if (messages.isEmpty() && !isLoading) {
                    item(key = "empty") {
                        EmptyChatState()
                    }
                } else {
                    items(
                        items = messages,
                        key = { msg -> "${msg.timestamp}_${msg.content.hashCode()}" }
                    ) { message ->
                        ChatBubble(
                            message = message,
                            onImportJson = onImportJson,
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                // ④ 加载中打字指示器
                if (isLoading) {
                    item(key = "loading") {
                        TypingIndicator()
                    }
                }

                // ⑤ 错误气泡
                if (isFailed) {
                    item(key = "error") {
                        ErrorBubble(
                            message = (chatStatus as AmadeusChat.PhoneChatStatus.Failed).msg
                        )
                    }
                }
            }

            // ── 附件文件名条 ──
            AnimatedVisibility(
                visible = attachedFileName != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                attachedFileName?.let { name ->
                    FileChip(
                        fileName = name,
                        onRemove = {
                            attachedFileName = null
                            attachedFileContent = null
                        }
                    )
                }
            }

            // ── 模型切换条 ──
            ModelSwitcherBar(
                currentModel = currentModel,
                onClick = { showModelPicker = true }
            )

            // ── 底部输入区 ──
            InputArea(
                text = inputText,
                onTextChange = { inputText = it },
                onSendClick = { handleSend() },
                onFileClick = { showFileHintDialog = true },
                canSend = canSend,
                isLoading = isLoading,
                modifier = Modifier.imePadding().navigationBarsPadding()
            )
        }
    }

    // 清空对话确认弹窗——miuix 风格
    AppDialog(
        title = "清空对话？",
        summary = "将删除所有手机端对话历史，此操作不可撤销。",
        show = showClearConfirm,
        onDismissRequest = { showClearConfirm = false },
        dismissText = "取消",
        confirmText = "清空",
        onDismiss = { showClearConfirm = false },
        onConfirm = {
            onClearChat()
            showClearConfirm = false
            Toast.makeText(context, "已清空对话", Toast.LENGTH_SHORT).show()
        }
    )

    // 文件上传说明弹窗——miuix 风格
    AppDialog(
        title = "上传文件",
        summary = "选择一个文本文件，其内容将作为上下文随消息一并发送给 Amadeus。" +
            "支持 .txt / .md / .json 等纯文本格式。",
        show = showFileHintDialog,
        onDismissRequest = { showFileHintDialog = false },
        dismissText = "取消",
        confirmText = "选择文件",
        onDismiss = { showFileHintDialog = false },
        onConfirm = {
            showFileHintDialog = false
            filePickerLauncher.launch("text/*")
        }
    )

    // 模型选择对话框（与配置页共用，带品牌图标与获取/手动输入）
    if (showModelPicker) {
        ModelPickerDialog(
            show = true,
            currentModel = currentModel,
            availableModels = availableModels,
            loading = modelsLoading,
            autoFetchOnOpen = amadeusReady,
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

// ────────────────────────── 子组件 ──────────────────────────

/**
 * 锁屏后台不可用警告卡。使用 errorContainer 配色，与配置页保持一致的文案与样式。
 */
@Composable
private fun LockScreenWarningCard(modifier: Modifier = Modifier) {
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        containerColor = MiuixTheme.colorScheme.errorContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MiuixIcons.Report,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "锁屏后台不可用",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Android 硬限制：屏幕熄灭后台时手机端无法新发起网络请求；" +
                    "手动亮屏后立即恢复。非锁屏状态下后台正常使用不受影响。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * Amadeus 未就绪横幅。使用 primaryContainer 配色，右侧「去配置」按钮跳转配置页。
 */
@Composable
private fun NotReadyBanner(
    message: String,
    onOpenConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        containerColor = MiuixTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.Info,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = "去配置",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onOpenConfig
            )
        }
    }
}

/**
 * 单条聊天消息气泡。
 *
 * - 用户消息：右对齐，primaryContainer 背景。
 * - AI 消息：左对齐，surfaceContainer 背景。
 * - 气泡上方显示角色标签（"你" / "Amadeus"）。
 * - 当 AI 回复疑似 JSON（以 `{` 或 `[` 开头）时，气泡下方出现「导入到手环」按钮。
 * - 气泡使用 animateContentSize 实现内容变化时的平滑过渡。
 */
@Composable
private fun ChatBubble(
    message: AmadeusChat.PhoneChatMessage,
    onImportJson: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val isJson = !isUser && looksLikeJson(message.content)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // 角色标签
        Text(
            text = if (isUser) "你" else "Amadeus",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
        )

        // 消息气泡
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isUser) 12.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 12.dp
                    )
                )
                .background(
                    if (isUser) MiuixTheme.colorScheme.primaryContainer
                    else MiuixTheme.colorScheme.surfaceContainer
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
        ) {
            Text(
                text = message.content,
                style = MiuixTheme.textStyles.body2,
                color = if (isUser) MiuixTheme.colorScheme.onPrimaryContainer
                        else MiuixTheme.colorScheme.onSurface
            )
        }

        // JSON 导入按钮
        AnimatedVisibility(
            visible = isJson,
            enter = fadeIn(animationSpec = tween(300)) +
                slideInVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { it / 2 },
            exit = fadeOut()
        ) {
            Button(
                onClick = { onImportJson(message.content) },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "导入到手环",
                    color = MiuixTheme.colorScheme.onPrimary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
    }
}

/**
 * 加载中打字指示器。三个圆点以交错延迟做缩放 + 透明度脉冲，
 * 模拟「Amadeus 正在输入」的效果。
 */
@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // 角色标签
        Text(
            text = "Amadeus",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
        )
        Spacer(Modifier.width(4.dp))

        // 气泡容器
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = i * 200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dot_alpha_$i"
                )
                val scale by transition.animateFloat(
                    initialValue = 0.7f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = i * 200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dot_scale_$i"
                )
                Box(
                    modifier = Modifier
                        .size((8 * scale).dp)
                        .clip(CircleShape)
                        .background(
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha)
                        )
                )
            }
        }
    }
}

/**
 * 错误气泡。使用 errorContainer 配色，居中显示，用于展示 [PhoneChatStatus.Failed] 的错误信息。
 */
@Composable
private fun ErrorBubble(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.Report,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * 空状态占位。无消息且非加载中时显示，引导用户开始对话。
 */
@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = MiuixIcons.Notes,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "和 Amadeus 开始对话吧",
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "可以直接聊天，也可以上传文件让它生成知识点 JSON",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
        )
    }
}

/**
 * 模型切换条：显示当前模型品牌图标 + 友好名，点击打开 [ModelPickerDialog]。
 * 固定在输入区上方，让用户在对话中直接切换模型（Gemini 风格）。
 */
@Composable
private fun ModelSwitcherBar(
    currentModel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val provider = if (currentModel.isBlank()) "nvidia" else ModelCatalog.providerFor(currentModel)
    val label = if (currentModel.isBlank()) "选择模型" else ModelCatalog.displayNameFor(currentModel)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModelIcon(provider = provider, size = 22.dp)
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = MiuixIcons.ExpandMore,
            contentDescription = "切换模型",
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/**
 * 附件文件名条。在输入区上方显示已选中的文件名，右侧带移除按钮。
 */
@Composable
private fun FileChip(
    fileName: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = MiuixIcons.File,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = fileName,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = MiuixIcons.Close,
                contentDescription = "移除附件",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 底部输入区。包含文件上传按钮、多行输入框和发送按钮。
 *
 * - 文件上传按钮在最左侧，点击弹出文件选择说明对话框。
 * - 输入框占满剩余宽度，最多 3 行。
 * - 发送按钮使用 primary 配色，加载中时显示「…」并禁用。
 */
@Composable
private fun InputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onFileClick: () -> Unit,
    canSend: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        // 顶部分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 文件上传按钮
            IconButton(onClick = onFileClick) {
                Icon(
                    imageVector = MiuixIcons.File,
                    contentDescription = "上传文件",
                    tint = MiuixTheme.colorScheme.primary
                )
            }

            // 输入框
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                singleLine = false,
                label = "输入消息..."
            )

            // 发送按钮
            Button(
                onClick = onSendClick,
                enabled = canSend,
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = MiuixIcons.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "发送",
                        color = MiuixTheme.colorScheme.onPrimary,
                        style = MiuixTheme.textStyles.body2
                    )
                }
            }
        }
    }
}

// ────────────────────────── 工具函数 ──────────────────────────

/**
 * 判断文本是否疑似 JSON（trim 后以 `{` 或 `[` 开头）。
 * 用于在 AI 回复下方显示「导入到手环」按钮。
 */
private fun looksLikeJson(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}

/**
 * 从 Uri 查询文件显示名。优先使用 ContentResolver 查 DISPLAY_NAME 列，
 * 查不到则回退到 Uri 的 lastPathSegment。
 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    }.getOrNull() ?: uri.lastPathSegment
}
