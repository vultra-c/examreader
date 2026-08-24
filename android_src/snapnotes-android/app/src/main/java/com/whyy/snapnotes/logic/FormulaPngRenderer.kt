package com.whyy.snapnotes.logic

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.resume

/**
 * 公式 PNG 离屏渲染器：WebView + KaTeX 0.18.1（与手环内置公式图同源同版本）。
 *
 * 渲染 HTML 结构与手环端 `scripts/gen_formulas.js` 对齐：
 * - 336px 设计宽（按需由内容撑宽，与内置一致）
 * - 透明底 + 白字（.katex 强制白色，适配手环深色内容页）
 * - formula-block padding 8px 24px 16px 24px，一条公式一个块，垂直堆叠
 * - KaTeX 资源从 assets/katex 加载（file:///android_asset/katex/）
 *
 * 截图方式：页面内用 html2canvas 把已渲染好的公式 DOM 直接绘制成 canvas，
 * 再 `canvas.toDataURL('image/png')` 把 PNG 以 base64 回传。
 * 全程不依赖窗口合成/SurfaceFlinger，彻底绕开 PixelCopy 在离屏窗口上的
 * buffer 为空问题（旧方案实测成功率约 1/29）。
 *
 * 输出：PNG 字节 + 原始像素宽高（w/h 随 startFormula 发给手环做等比缩放）。
 * 渲染失败（KaTeX throwOnError 或超时）返回 null，由调用方跳过该知识点，不阻塞推送。
 */
class FormulaPngRenderer(private val activityContext: Context) {

    companion object {
        private const val TAG = "FormulaPngRenderer"
        const val DEFAULT_WIDTH_PX = 336
        private const val MAX_HEIGHT_PX = 3000
        private const val FONT_READY_TIMEOUT_MS = 8_000L
        private const val PNG_DATA_PREFIX = "data:image/png;base64,"
    }

    data class PngResult(val bytes: ByteArray, val width: Int, val height: Int)

    /** 渲染详情：成功时 png 非空；失败时 errorMessages 携带 KaTeX 具体报错（供编辑预览提示）。 */
    data class RenderDetail(val png: PngResult?, val errorMessages: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    /** 串行化所有渲染（编辑预览与推送共用同一个 renderer，WebView 复用需互斥）。 */
    private val renderMutex = Mutex()

    /** density=1 的 Context 缓存（WebView 必须用它创建，CSS px 才等于物理 px）。 */
    private val fixedContext: Context by lazy { createFixedDensityContext(activityContext) }

    private var webView: WebView? = null
    /** 供 JS 把测量/截图结果写入 `window.__snapResult`，Kotlin 侧用 evaluateJavascript 轮询读取
     *  （不用 addJavascriptInterface，避免把可注入的 JS 桥暴露给页面）。 */

    /**
     * 把多条 LaTeX 公式渲染成一张 PNG（垂直堆叠）。
     *
     * @param latexList 已转换好的 LaTeX 列表（一条公式一个元素）
     * @return 渲染结果；任一条公式 KaTeX 渲染失败、或超时/异常时返回 null
     */
    suspend fun render(latexList: List<String>): PngResult? =
        renderDetail(latexList)?.png

    /**
     * 渲染并返回详情（含错误消息）。预览/推送共用同一实现。
     *
     * @param previewMode true 为手机端预览：收缩到公式内容宽度 + 放大字号 + 提高分辨率，
     *   手机上按屏幕宽自适应铺满，公式大而清晰；
     *   false 为推送到手环：固定 336px 宽（手环屏宽），与内置 gen_formulas.js 规格一致。
     *
     * 全程无感：WebView 无需挂窗口（html2canvas 纯页面内绘制），用户侧零弹窗零遮罩零闪现。
     */
    suspend fun renderDetail(latexList: List<String>, previewMode: Boolean = false): RenderDetail? = renderMutex.withLock {
        if (latexList.isEmpty()) return@withLock null
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            try {
                val webView = webView ?: WebView(fixedContext).also {
                    configureWebView(it)
                    webView = it
                }
                // 安全视口尺寸：336dp 宽、足够高的文档，按内容自动撑开。
                webView.layout(0, 0, DEFAULT_WIDTH_PX, MAX_HEIGHT_PX)

                val html = buildHtml(latexList, previewMode)
                val result = awaitRenderResult(webView) {
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/katex/", html, "text/html", "utf-8", null
                    )
                }
                if (result == null) {
                    Log.e(TAG, "renderDetail: measure result null (timeout)")
                    RenderDetail(null, listOf("渲染超时，请重试"))
                } else if (result.errors.isNotEmpty()) {
                    Log.e(TAG, "katex render errors on formulas: ${result.errors.size} 条")
                    RenderDetail(null, result.errors)
                } else {
                    val w = result.w
                    val h = result.h
                    val dataUrl = result.dataUrl
                    if (w <= 0 || h <= 0 || h > MAX_HEIGHT_PX || dataUrl.isNullOrEmpty()) {
                        Log.e(TAG, "render size/data invalid: ${w}x${h}")
                        RenderDetail(null, listOf("渲染尺寸异常，请重试"))
                    } else {
                        val bytes = decodePng(dataUrl)
                        if (bytes.isEmpty()) {
                            Log.e(TAG, "png base64 decode empty, skip")
                            RenderDetail(null, listOf("PNG 解码失败"))
                        } else {
                            Log.e(TAG, "formula png rendered ${w}x${h} ${bytes.size}B")
                            RenderDetail(PngResult(bytes, w, h), emptyList())
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "formula render fail", e)
                RenderDetail(null, listOf(e.message ?: "渲染异常"))
            }
        }
    }

    /** 释放复用的 WebView（Activity 销毁时调用，防泄漏）。 */
    fun release() {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            runCatching { webView?.destroy() }
            webView = null
        }
    }

    /**
     * 构造 density=1 的 Context：让 336dp 布局 == 336px 物理像素，
     * 渲染出的 PNG 像素尺寸与内置公式图（deviceScaleFactor=1）一致。
     */
    private fun createFixedDensityContext(base: Context): Context {
        val appContext = base.applicationContext
        val original = appContext.resources
        val fixedMetrics = DisplayMetrics().apply {
            setTo(original.displayMetrics)
            density = 1f
            scaledDensity = 1f
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        val fixedResources = Resources(
            appContext.assets,
            fixedMetrics,
            original.configuration
        )
        return object : android.content.ContextWrapper(appContext) {
            override fun getResources(): Resources = fixedResources
        }
    }

    private fun configureWebView(webView: WebView) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.layoutParams = ViewGroup.LayoutParams(
            DEFAULT_WIDTH_PX,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportZoom(false)
        // 安全加固：禁止 WebView 访问任意 file:// 与 content:// 路径。
        // 页面所需的 katex/html2canvas 资源仍可通过 file:///android_asset 加载（不受此开关影响）。
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkImage = true
        settings.blockNetworkLoads = true
        // 渲染结果不经过 JS 注入桥：JS 写入 window.__snapResult，Kotlin 侧 evaluateJavascript 轮询读取。
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                result.confirm()
                return true
            }
        }
    }

    /**
     * HTML 与手环端 gen_formulas.js 输出对齐：katex.min.css + 白字 + formula-block 垂直堆叠。
     * KaTeX 渲染在页面内联 JS 同步执行（throwOnError），失败条目记入 __snapErrors（含消息）。
     * 字体就绪后（html2canvas 内部也等 fonts.ready）用 html2canvas 把 #wrap 绘制成 canvas，
     * toDataURL('image/png') 得 base64，连同 w/h/errors 写入 window.__snapResult，
     * 由 Kotlin 侧 evaluateJavascript 轮询读取（无 JS 注入桥）。
     *
     * @param previewMode true 时 #wrap 收缩到公式内容宽度（inline-block 而非固定 336px）、
     *   字号放大到 36px、canvas 分辨率放大到 2x，手机预览铺满屏宽且清晰；
     *   false 时固定 336px 宽 + 默认字号 + 1x，推送到手环与内置规格一致。
     */
    private fun buildHtml(latexList: List<String>, previewMode: Boolean = false): String {
        val jsonArray = json.encodeToString(latexList)
        val blocks = latexList.indices.joinToString("\n") { i ->
            "<div class=\"formula-block\"><span class=\"formula-slot\" id=\"f$i\"></span></div>"
        }
        val wrapStyle = if (previewMode) {
            "#wrap{display:inline-block;}"
        } else {
            "#wrap{display:block;width:336px;}"
        }
        val bodyFontSize = if (previewMode) "font-size:36px;" else ""
        val canvasScale = if (previewMode) 2 else 1
        return """
<!DOCTYPE html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="katex.min.css">
<style>
html,body{margin:0;padding:0;background:transparent;${bodyFontSize}}
$wrapStyle
.katex{color:#FFFFFF!important;}
.formula-block{margin:0;padding:8px 24px 16px 24px;}
.formula-slot{display:block;}
</style></head>
<body><div id="wrap">$blocks</div>
<script src="katex.min.js"></script>
<script src="html2canvas.min.js"></script>
<script>
window.__snapErrors = [];
window.__snapResult = null;
var FORMULAS = $jsonArray;
(function() {
  for (var i = 0; i < FORMULAS.length; i++) {
    try {
      var el = document.getElementById('f' + i);
      el.innerHTML = katex.renderToString(FORMULAS[i], {displayMode: true, throwOnError: true, output: 'html'});
    } catch (e) {
      window.__snapErrors.push({i: i, m: String(e && e.message || e)});
    }
  }
})();
function snapWrite(r) {
  try { window.__snapResult = r; } catch (e) {}
}
function snapCapture() {
  if (window.__snapErrors.length > 0) {
    snapWrite(JSON.stringify({w: 0, h: 0, dataUrl: '', errors: window.__snapErrors}));
    return;
  }
  var el = document.getElementById('wrap');
  var w = Math.max(1, Math.ceil(el.scrollWidth));
  var h = Math.max(1, Math.ceil(el.scrollHeight));
  if (typeof html2canvas !== 'function') {
    snapWrite(JSON.stringify({w: 0, h: 0, dataUrl: '', errors: [{i: -1, m: 'html2canvas not loaded'}]}));
    return;
  }
  html2canvas(el, {
    scale: $canvasScale,
    backgroundColor: null,
    logging: false,
    windowWidth: Math.max(336, w),
    windowHeight: Math.max(40, h)
  }).then(function(canvas) {
    var dataUrl = canvas.toDataURL('image/png');
    var result = JSON.stringify({w: canvas.width, h: canvas.height, dataUrl: dataUrl, errors: []});
    snapWrite(result);
  }).catch(function(err) {
    var result = JSON.stringify({w: 0, h: 0, dataUrl: '', errors: [{i: -1, m: String(err && err.message || err)}]});
    snapWrite(result);
  });
}
(function() {
  if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
    document.fonts.ready.then(snapCapture).catch(snapCapture);
  } else {
    setTimeout(snapCapture, 300);
  }
})();
</script></body></html>
""".trimIndent()
    }

    private data class MeasureResult(val w: Int, val h: Int, val errors: List<String>, val dataUrl: String?)

    /**
     * 等待页面加载完成后读取测量/截图结果。
     * html2canvas 异步（canvas 绘制 + base64 编码），结果一次性写入 `window.__snapResult`；
     * 这里从 onPageFinished 后每 150ms 轮询一次（evaluateJavascript 读取，无 JS 注入面），
     * 拿到有效结果后静默 700ms 视为完成，超时兜底返回 null。
     */
    private suspend fun awaitRenderResult(
        webView: WebView,
        startLoad: () -> Unit
    ): MeasureResult? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var pending = true
            var latest: MeasureResult? = null
            var settleRunnable: Runnable? = null
            val finish = { result: MeasureResult? ->
                if (pending && cont.isActive) {
                    pending = false
                    cont.resume(result)
                }
            }
            cont.invokeOnCancellation {
                handler.post { pending = false }
            }
            val scheduleSettle = {
                settleRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { finish(latest) }
                settleRunnable = r
                handler.postDelayed(r, 700L)
            }
            val pollRunnable = object : Runnable {
                override fun run() {
                    if (!pending) return
                    webView.evaluateJavascript("window.__snapResult") { value ->
                        if (!pending) return@evaluateJavascript
                        val result = parseMeasure(value)
                        if (result != null) {
                            latest = result
                            scheduleSettle()
                        } else {
                            handler.postDelayed(this, 150L)
                        }
                    }
                }
            }
            // 页面加载兜底：HTML 首帧未完成时读到的都是 null，等 onPageFinished 后再开始轮询。
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    handler.postDelayed(pollRunnable, 150L)
                }
            }
            startLoad()
            // 超时兜底：WebView 卡死/JS 永不写结果时不能挂死推送流程。
            handler.postDelayed({
                if (pending) {
                    Log.e(TAG, "formula measure timeout")
                    finish(latest)
                }
            }, FONT_READY_TIMEOUT_MS)
        }

    private fun parseMeasure(value: String?): MeasureResult? {
        if (value.isNullOrBlank()) return null
        var text = value.trim()
        if (text == "null" || text.isEmpty()) return null
        // evaluateJavascript 兜底返回的 JS 字符串是带引号包裹的：`"{\"w\":336...}"`。
        // JsonPrimitive(text) 不做解析不会剥引号，必须先 parse 成 JsonLiteral 再取 content。
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = try {
                json.parseToJsonElement(text).jsonPrimitive.content
            } catch (e: Exception) {
                Log.e(TAG, "unquote measure fail", e)
                return null
            }
        }
        return try {
            val obj = json.parseToJsonElement(text).jsonObject
            val errors = obj["errors"]?.let { el ->
                el.jsonArray.mapNotNull { item ->
                    item.jsonObject["m"]?.jsonPrimitive?.contentOrNull
                }
            } ?: emptyList()
            MeasureResult(
                w = obj["w"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                h = obj["h"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                errors = errors,
                dataUrl = obj["dataUrl"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            Log.e(TAG, "parse measure fail", e)
            null
        }
    }

    /** 从 `data:image/png;base64,XXXX` 解码出 PNG 字节。 */
    private fun decodePng(dataUrl: String): ByteArray {
        val payload = dataUrl.substringAfter(PNG_DATA_PREFIX, "")
        if (payload.isEmpty()) {
            Log.e(TAG, "png dataUrl prefix mismatch")
            return ByteArray(0)
        }
        return try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "png base64 decode fail", e)
            ByteArray(0)
        }
    }
}
