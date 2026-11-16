package com.comicgrabber.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/** Methods the injected JavaScript (inject.js) calls via window.CG. */
class JsBridge(private val vm: MainViewModel) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onToggle(url: String, title: String, on: Boolean) { main.post { vm.toggle(url, title, on) } }

    @JavascriptInterface
    fun onDownload(url: String, title: String) { main.post { vm.requestDownload(url, title) } }

    @JavascriptInterface
    fun onPage(json: String) { main.post { vm.onPage(json) } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserView(modifier: Modifier, vm: MainViewModel, onReady: (WebView) -> Unit) {
    val ctx = LocalContext.current
    val script = remember { ctx.assets.open("inject.js").bufferedReader().use { it.readText() } }

    AndroidView(modifier = modifier, factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            Session.userAgent = settings.userAgentString

            addJavascriptInterface(JsBridge(vm), "CG")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val s = request.url.scheme ?: return true
                    return s != "http" && s != "https" // block intent:// / market:// popups
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    vm.onNavigate(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    view.evaluateJavascript("$script\n;window.__cgInit(${vm.selectedJson()});", null)
                }
            }
            // If the site's own Download link is tapped manually, route it into our queue.
            setDownloadListener { url, _, cd, mime, _ ->
                val name = URLUtil.guessFileName(url, cd, mime).substringBeforeLast('.')
                vm.engine.enqueue(listOf(ComicItem(name, url, direct = true)))
            }
            onReady(this)
            loadUrl(vm.startUrl)
        }
    })
}
