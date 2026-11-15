package io.github.smalltailqwq.shallowdrop.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.JsonPrimitive

/**
 * 块级公式：离线 KaTeX（assets/markdown/katex，0.16.22，MIT）在一个不可交互的 WebView 里渲染，
 * 尺寸由页面回传；宽公式由外层 Compose 横向滚动。
 * 预览模式、KaTeX 尚未出结果或从未渲染成功时，显示 [TexToUnicode] 的近似文字。
 */
@Composable
internal fun MathBlockView(tex: String, context: MarkdownContext, modifier: Modifier = Modifier) {
    if (tex.isBlank()) return
    if (LocalInspectionMode.current) {
        MathFallback(tex, context, modifier)
        return
    }
    val color = LocalContentColor.current
    val density = LocalDensity.current
    val fontSize = context.style.body.fontSize.takeIf { it.isSpecified } ?: 16.sp
    // WebView 里 1 CSS px = 1 dp；字号按 sp → dp 换算（含系统字体缩放）
    val fontSizeCss = with(density) { fontSize.toDp().value }
    val colorCss = "#%06X".format(0xFFFFFF and color.toArgb())
    var result by remember { mutableStateOf<KatexResult?>(null) }

    Column(modifier.fillMaxWidth()) {
        val rendered = result?.takeIf { it.ok && it.height > 0 }
        if (rendered == null) MathFallback(tex, context, Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxOf(maxWidth, (rendered?.width ?: 0).dp)
            val height = rendered?.height?.dp ?: 1.dp
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                AndroidView(
                    factory = { ctx -> KatexWebView(ctx) { r -> result = r } },
                    update = { it.render(tex, colorCss, fontSizeCss) },
                    onRelease = { it.destroy() },
                    modifier = Modifier.width(width).height(height),
                )
            }
        }
    }
}

@Composable
private fun MathFallback(tex: String, context: MarkdownContext, modifier: Modifier) {
    val text = remember(tex, context.style) {
        buildAnnotatedString { appendMath(TexToUnicode.convert(tex, display = true), context.style.math) }
    }
    Text(text = text, modifier = modifier, style = context.style.body, textAlign = TextAlign.Center)
}

internal data class KatexResult(val width: Int, val height: Int, val ok: Boolean)

/** 加载本地 katex.html 的 WebView：禁止导航与网络，不接收触摸（滚动交给外层 Compose） */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
private class KatexWebView(
    context: Context,
    private val onResult: (KatexResult) -> Unit,
) : WebView(context) {
    private var ready = false
    private var pending: String? = null
    private var lastScript: String? = null

    init {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        isClickable = false
        isLongClickable = false
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                ready = true
                pending?.let { evaluateJavascript(it, null) }
                pending = null
            }
        }
        addJavascriptInterface(Bridge(), "KatexBridge")
        loadUrl("file:///android_asset/markdown/katex.html")
    }

    fun render(tex: String, color: String, fontSize: Float) {
        val script = "render(${JsonPrimitive(tex)}, '$color', $fontSize)"
        if (script == lastScript) return
        lastScript = script
        if (ready) evaluateJavascript(script, null) else pending = script
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

    private inner class Bridge {
        @JavascriptInterface
        fun onRendered(width: Int, height: Int, ok: Boolean) {
            post { onResult(KatexResult(width, height, ok)) }
        }
    }
}
