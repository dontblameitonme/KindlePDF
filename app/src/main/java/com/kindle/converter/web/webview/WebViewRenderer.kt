package com.kindle.converter.web.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kindle.converter.web.extractor.ExtractException
import com.kindle.converter.web.fetcher.WebFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebView 渲染兜底：对依赖 JS 渲染、或存在"展开全文/打开 App"弹窗的页面，
 * 用真实 WebView 加载，注入用户脚本（骚扰拦截）与自动展开脚本，
 * 待页面稳定后导出完整 DOM 交给正文提取器。
 *
 * WebView 必须在主线程创建与销毁。
 */
class WebViewRenderer(private val context: Context) {

    /** 串行互斥锁：确保后台 WebView 渲染单实例排队执行，防止多任务并发导致 Chromium 内存暴涨 OOM */
    private val renderMutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        /** 总体超时：加载 + 脚本执行 + 导出 */
        const val TOTAL_TIMEOUT_MS = 45_000L
        private const val LOAD_TIMEOUT_MS = 25_000L
        private const val SCRIPT_SETTLE_MS = 1_500L
        private const val EXPORT_TIMEOUT_MS = 5_000L

        /** 页面早期注入的脚本执行框架（移除任何 visibilityState 属性覆盖，杜绝反爬探针检测） */
        private val HARNESS_JS = """
            (function () {
              if (window.__w2e__) return;
              window.__w2e__ = {
                run: function (code) {
                  try {
                    var exec = function () {
                      try { new Function(code)(); } catch (e) {}
                    };
                    if (document.readyState === 'loading') {
                      document.addEventListener('DOMContentLoaded', exec);
                    } else { exec(); }
                  } catch (e) {}
                }
              };
            })();
        """.trimIndent()

        /** 自动点击"展开全文"类按钮并滚动触发懒加载 */
        private val AUTO_EXPAND_JS = """
            (function () {
              var texts = ['展开全文', '显示全部', '加载全文', '查看更多', '展开全部',
                           '展开剩余', '点击阅读全文', '继续浏览', '展开全文免费阅读'];
              for (var i = 0; i < 3; i++) {
                var els = document.querySelectorAll('a,button,span,div,p');
                for (var j = 0; j < els.length; j++) {
                  var el = els[j];
                  var t = (el.innerText || '').trim();
                  if (t.length > 0 && t.length <= 10) {
                    for (var k = 0; k < texts.length; k++) {
                      if (t.indexOf(texts[k]) >= 0) {
                        try { el.click(); } catch (e) {}
                        break;
                      }
                    }
                  }
                }
              }
              window.scrollTo(0, document.body ? document.body.scrollHeight : 0);
              setTimeout(function () {
                window.scrollTo(0, document.body ? document.body.scrollHeight : 0);
              }, 600);
            })();
        """.trimIndent()
    }

    /** 根据目标域名和用户偏好计算实际生效的 User-Agent */
    private fun determineUserAgent(url: String, desktopUa: Boolean): String {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: ""
        val defaultNativeUa = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrDefault(WebFetcher.MOBILE_UA)

        // 知乎等强反爬站点：绝对不能使用 Windows NT 桌面 UA，否则其 WebAssembly 挑战检测平台架构不一致必返回 403 陷阱！
        // 必须使用系统原生真实的 Android UA
        if (host.contains("zhihu.com")) {
            return defaultNativeUa
        }
        return if (desktopUa) WebFetcher.DESKTOP_UA else defaultNativeUa
    }

    /**
     * 渲染页面并返回最终 DOM。
     * @param userscript 当前启用的用户脚本全文（可为 null）
     * @param desktopUa 是否使用桌面 UA（知乎等强反爬站会自动降级为原生移动 UA）
     */
    suspend fun render(
        url: String,
        userscript: String?,
        desktopUa: Boolean,
    ): String = renderMutex.withLock {
        withTimeout(TOTAL_TIMEOUT_MS) {
            val loadDeferred = CompletableDeferred<Unit>()

            val webView = createWebView()
            configure(webView, userscript, loadDeferred, desktopUa, url)
            postOnMain { webView.loadUrl(url) }

            try {
                // 1. 等待首轮加载完成（若返回 403 挑战页，Chromium 也会正常解析并触发 onPageFinished）
                withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadDeferred.await() }

                // 2. 检测知乎等 WAF 反爬挑战 (zh-zse-ck) 或安全验证状态
                val startTime = System.currentTimeMillis()
                var reloadTriggered = false

                while (System.currentTimeMillis() - startTime < 18_000L) {
                    val status = evalJsSync(
                        webView,
                        """
                        (function() {
                            if (document.getElementById('zh-zse-ck') || document.querySelector('meta[id="zh-zse-ck"]')) {
                                return 'challenge';
                            }
                            var href = window.location.href || '';
                            if (href.indexOf('unhuman') >= 0 || href.indexOf('need_login') >= 0) {
                                return 'unhuman';
                            }
                            var title = document.title || '';
                            if (title.indexOf('安全验证') >= 0 || title.indexOf('验证码') >= 0) {
                                return 'captcha';
                            }
                            var textLen = (document.body && document.body.innerText) ? document.body.innerText.length : 0;
                            if (textLen > 60) {
                                return 'ready';
                            }
                            return 'loading';
                        })();
                        """.trimIndent()
                    )

                    if (status == "ready") {
                        break
                    }

                    if (status == "unhuman" || status == "captcha") {
                        throw ExtractException("知乎触发安全验证，请点击下方「在内置浏览器中打开验证」完成验证并剪藏")
                    }

                    if (status == "challenge") {
                        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: ""
                        if (cookies.contains("__zse_ck") && !reloadTriggered) {
                            // Cookie 已由 WebAssembly 挑战计算写入，但若页面未自动刷新，主动触发 reload
                            reloadTriggered = true
                            postOnMain { webView.reload() }
                        }
                    }

                    delay(800)
                }

                // 检查是否仍处于 challenge 挑战页
                val finalCheck = evalJsSync(
                    webView,
                    "(function(){ return !!(document.getElementById('zh-zse-ck') || document.querySelector('meta[id=\"zh-zse-ck\"]')); })()"
                )
                if (finalCheck == "true") {
                    throw ExtractException("知乎触发安全验证，请点击下方「在内置浏览器中打开验证」完成验证并剪藏")
                }

                // 3. 自动展开 + 滚动触发懒加载，等待页面稳定
                evalOnMain(webView, AUTO_EXPAND_JS)
                delay(1_200)
                evalOnMain(webView, AUTO_EXPAND_JS)
                delay(SCRIPT_SETTLE_MS)

                // 将 Cookie 刷入磁盘以备复用
                runCatching { CookieManager.getInstance().flush() }

                // 4. 导出渲染后的完整 DOM
                exportDom(webView)
            } finally {
                destroyOnMain(webView)
            }
        }
    }

    /** 在主线程创建 WebView 并赋予 1080x1920 虚拟视口尺寸（防止 0x0 导致 Chromium 节流及反爬无头检测拦截） */
    private suspend fun createWebView(): WebView = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#121212"))
                }
                val width = 1080
                val height = 1920
                wv.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, width, height)
                cont.resume(wv)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    /** 导出渲染后的完整 DOM（通过 evaluateJavascript 直接获取 JSON 反序列化串） */
    private suspend fun exportDom(webView: WebView): String = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })()") { rawResult ->
                if (rawResult == null || rawResult == "null" || rawResult.isBlank()) {
                    cont.resumeWithException(ExtractException("页面内容为空或加载失败"))
                    return@evaluateJavascript
                }
                val html = try {
                    org.json.JSONTokener(rawResult).nextValue() as? String ?: rawResult
                } catch (_: Exception) {
                    rawResult
                }
                if (html.length < 50) {
                    cont.resumeWithException(ExtractException("知乎触发安全验证，请点击下方「在内置浏览器中打开验证」完成验证并剪藏"))
                    return@evaluateJavascript
                }
                cont.resume(html)
            }
        }
    }

    private suspend fun evalJsSync(webView: WebView, js: String): String = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(js) { res ->
                cont.resume(res?.trim('\"') ?: "")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(
        webView: WebView,
        userscript: String?,
        loadDeferred: CompletableDeferred<Unit>,
        desktopUa: Boolean,
        targetUrl: String,
    ) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val effectiveUa = determineUserAgent(targetUrl, desktopUa)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = effectiveUa
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url?.scheme?.lowercase() ?: ""
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                // 拦截第三方客户端唤醒协议（如 zhihu://），避免外部跳转或 ERR_UNKNOWN_URL_SCHEME
                return true
            }

            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                view.evaluateJavascript(HARNESS_JS, null)
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
                if (userscript != null && UserscriptRunner.matchesUrl(userscript, pageUrl)) {
                    view.evaluateJavascript(buildInjectExpression(userscript), null)
                }
                if (!loadDeferred.isCompleted) loadDeferred.complete(Unit)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                // 关键：知乎等站点的 WAF 挑战返回 HTTP 403，但其实带有挑战 HTML 和 JS 脚本，
                // 切勿在此抛出异常中断，让 Chromium 继续解析运行该 HTML 中的 challenge 脚本！
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame && !loadDeferred.isCompleted) {
                    val desc = runCatching { error.description }.getOrNull() ?: "网络异常"
                    loadDeferred.completeExceptionally(
                        Exception("页面加载失败（$desc）")
                    )
                }
            }
        }
    }

    /** 以 JSON 编码脚本内容，避免注入转义问题 */
    private fun buildInjectExpression(script: String): String {
        val json = JSONObject().put("c", script).toString()
        return "window.__w2e__ && window.__w2e__.run(($json).c);"
    }

    private suspend fun evalOnMain(webView: WebView, js: String) {
        postOnMain { runCatching { webView.evaluateJavascript(js, null) } }
        delay(200)
    }

    private suspend fun postOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                action()
                cont.resume(Unit)
            }
        }
    }

    private fun destroyOnMain(webView: WebView) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    /** JS 桥：接收导出的 DOM 字符串 */
    private class ExportBridge(private val deferred: CompletableDeferred<String>) {
        @JavascriptInterface
        fun onHtml(html: String) {
            deferred.complete(html)
        }
    }
}
