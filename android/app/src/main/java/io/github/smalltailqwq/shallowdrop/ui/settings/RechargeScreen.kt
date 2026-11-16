package io.github.smalltailqwq.shallowdrop.ui.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.smalltailqwq.shallowdrop.R
import io.github.smalltailqwq.shallowdrop.ui.common.TOP_UP_URL
import io.github.smalltailqwq.shallowdrop.ui.components.BackButton
import io.github.smalltailqwq.shallowdrop.ui.motion.TextButton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 充值和登录只能在此官方源中加载；其他地址交由用户在浏览器自行打开。 */
internal fun isOfficialPlatformUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.host == "platform.deepseek.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeScreen(onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onBack() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text(stringResource(R.string.top_up)); Text("platform.deepseek.com", style = MaterialTheme.typography.labelSmall) } },
            navigationIcon = { BackButton(onBack, Modifier.padding(start = 12.dp)) },
            actions = { TextButton(onClick = { uriHandler.openUri(TOP_UP_URL) }) { Text(stringResource(R.string.open_browser)) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.recharge_check_account), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) {
                Text(stringResource(R.string.recharge_page_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                TextButton(onClick = { failed = false; loading = true; webView?.loadUrl(TOP_UP_URL) }) { Text(stringResource(R.string.retry)) }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val blocked = request.isForMainFrame && !isOfficialPlatformUrl(request.url.toString())
                                if (blocked) { failed = true; loading = false }
                                return blocked
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (!isOfficialPlatformUrl(url)) { view.stopLoading(); failed = true; loading = false }
                                else loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String) { loading = false }

                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel()
                                view.stopLoading()
                                failed = true
                                loading = false
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) { failed = true; loading = false }
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) { failed = true; loading = false }
                            }
                        }
                        loadUrl(TOP_UP_URL)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy(); webView = null },
            )
        }
    }
}
