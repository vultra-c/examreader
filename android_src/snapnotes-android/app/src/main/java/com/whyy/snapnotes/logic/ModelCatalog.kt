package com.whyy.snapnotes.logic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Amadeus 内置（开箱即用）的 NVIDIA NIM 配置。
 *
 * 用户明确要求「内置」该配置，因此作为 prefs 的默认值写入：首次启动即可在手机端
 * Amadeus 直聊中直接发送消息、生成知识点 JSON，无需手动填 API Key / Base URL / Model。
 * 用户仍可在配置页覆盖这些值。
 *
 * [API_KEY] 为 NVIDIA NIM（build.nvidia.com）的 OpenAI 兼容密钥。
 * [BASE_URL] 是 API 根地址（**不带** `/v1`）：[AmadeusChat] 会按约定拼接
 * `/v1/chat/completions` 与 `/v1/models`，最终请求 `https://integrate.api.nvidia.com/v1/...`。
 */
object AmadeusDefaults {
    const val BASE_URL = "https://integrate.api.nvidia.com"
    const val API_KEY = "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"
    const val MODEL = "meta/llama-3.3-70b-instruct"
}

/**
 * 一个可选模型。用于「模型切换」页：id 是真正发给服务端的模型名，displayName 是
 * 列表里的友好名称，provider 是 lobehub 图标 slug（用于左侧品牌图标）。
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val provider: String
)

/**
 * 内置推荐模型目录（NVIDIA NIM 托管模型）。
 * 列表始终可用，不依赖 `/v1/models` 拉取结果；拉取到的模型会去重后追加在后面。
 */
object ModelCatalog {

    val builtIn: List<ModelInfo> = listOf(
        ModelInfo("meta/llama-3.3-70b-instruct", "Llama 3.3 70B Instruct", "meta"),
        ModelInfo("nvidia/llama-3.3-nemotron-super-49b-v1", "Llama 3.3 Nemotron Super 49B", "nvidia"),
        ModelInfo("nvidia/llama-3.1-nemotron-70b-instruct", "Llama 3.1 Nemotron 70B", "nvidia"),
        ModelInfo("meta/llama-3.1-405b-instruct", "Llama 3.1 405B Instruct", "meta"),
        ModelInfo("deepseek-ai/deepseek-r1", "DeepSeek R1", "deepseek"),
        ModelInfo("qwen/qwen2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B", "qwen"),
        ModelInfo("mistralai/mistral-small-24b-instruct-2501", "Mistral Small 24B", "mistral"),
        ModelInfo("google/gemma-2-9b-it", "Gemma 2 9B IT", "google"),
        ModelInfo("microsoft/phi-3-mini-4k-instruct", "Phi 3 Mini 4K", "microsoft")
    )

    /** 把任意模型 id（含 /v1/models 拉回来的）映射到 lobehub provider slug。 */
    fun providerFor(modelId: String): String {
        val id = modelId.lowercase()
        return when {
            id.contains("nvidia") || id.contains("nemotron") -> "nvidia"
            id.contains("deepseek") -> "deepseek"
            id.contains("qwen") -> "qwen"
            id.contains("moonshot") || id.contains("kimi") -> "moonshot"
            id.contains("mistral") || id.contains("mixtral") -> "mistral"
            id.contains("gemma") || id.contains("gemini") || id.contains("palm") || id.contains("google") -> "google"
            id.contains("phi") || id.contains("microsoft") -> "microsoft"
            id.contains("llama") || id.contains("meta") -> "meta"
            id.contains("openai") || id.contains("gpt") -> "openai"
            id.contains("claude") || id.contains("anthropic") -> "anthropic"
            id.contains("glm") || id.contains("zhipu") || id.contains("chatglm") -> "zhipu"
            else -> "nvidia" // 本应用默认走 NVIDIA NIM
        }
    }

    /** 模型列表里的友好显示名：内置目录优先，否则取 id 最后一段（去掉厂商前缀）。 */
    fun displayNameFor(modelId: String): String =
        builtIn.firstOrNull { it.id == modelId }?.displayName ?: modelId.substringAfterLast('/')

    /** 从拉取到的模型列表合并出「内置 + 去重后的服务端模型」。 */
    fun merge(fetched: List<String>?): List<ModelInfo> {
        val result = builtIn.toMutableList()
        val known = builtIn.map { it.id }.toMutableSet()
        fetched.orEmpty().forEach { id ->
            if (id.isNotBlank() && known.add(id)) {
                result += ModelInfo(id, displayNameFor(id), providerFor(id))
            }
        }
        return result
    }
}

/**
 * 从 @lobehub/icons 的静态 CDN 加载模型品牌图标（color 变体，PNG）。
 *
 * 遵循 https://lobehub.com/icons/skill.md 的 CDN URL 约定：
 * `packages/static-png/{light|dark}/{id}-color.png`。这里固定用 light（彩色品牌标），
 * UI 侧放进白色圆形底里，Gemini 风格、明暗主题下都有足够对比度。
 *
 * 图标很小且数量有限，用进程内 [ConcurrentHashMap] 缓存即可，不需要引入图片加载库。
 */
object ModelIconLoader {
    private const val TAG = "ModelIconLoader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun iconUrl(provider: String): String =
        "https://raw.githubusercontent.com/lobehub/lobe-icons/refs/heads/master/packages/static-png/light/${provider}-color.png"

    /** 阻塞加载（必须在 IO 线程调用）。失败返回 null，UI 回退为品牌首字母。 */
    fun load(provider: String): Bitmap? {
        cache[provider]?.let { return it }
        return try {
            val request = Request.Builder().url(iconUrl(provider)).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache[provider] = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "load $provider icon fail: ${e.message}")
            null
        }
    }
}
