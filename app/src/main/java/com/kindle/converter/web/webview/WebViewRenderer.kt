package com.kindle.converter.web.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kindle.converter.web.fetcher.WebFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    companion object {
        /** 总体超时：加载 + 脚本执行 + 导出 */
        const val TOTAL_TIMEOUT_MS = 45_000L
        private const val LOAD_TIMEOUT_MS = 25_000L
        private const val SCRIPT_SETTLE_MS = 1_500L
        private const val EXPORT_TIMEOUT_MS = 5_000L

        /** 页面早期注入的脚本执行框架 */
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

    /**
     * 渲染页面并返回最终 DOM。
     * @param userscript 当前启用的用户脚本全文（可为 null）
     * @param desktopUa 是否使用桌面 UA
     */
    suspend fun render(
        url: String,
        userscript: String?,
        desktopUa: Boolean,
    ): String = withTimeout(TOTAL_TIMEOUT_MS) {
        val htmlDeferred = CompletableDeferred<String>()

        val webView = createWebView()
        configure(webView, userscript, htmlDeferred, desktopUa)
        postOnMain { webView.loadUrl(url) }

        try {
            // 等待首轮加载完成
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { htmlDeferred.await() }

            // 检测是否存在知乎 WAF 挑战 (zh-zse-ck) 或类似反爬验证
            // 若存在，轮询等待其在 Chromium 中执行脚本并刷新重载完成
            repeat(12) {
                val isChallenge = evalJsSync(webView, "(function(){ return !!document.getElementById('zh-zse-ck'); })()")
                if (isChallenge == "true") {
                    delay(1_000)
                } else {
                    return@repeat
                }
            }

            // 自动展开 + 滚动触发懒加载，等待脚本与页面稳定
            evalOnMain(webView, AUTO_EXPAND_JS)
            delay(1_500)
            evalOnMain(webView, AUTO_EXPAND_JS)
            delay(SCRIPT_SETTLE_MS)

            // 导出渲染后的完整 DOM
            exportDom(webView)
        } finally {
            destroyOnMain(webView)
        }
    }

    /** 在主线程创建 WebView */
    private suspend fun createWebView(): WebView = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            try {
                cont.resume(WebView(context))
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    /** 导出渲染后的完整 DOM（通过 evaluateJavascript 直接获取 JSON 反序列化串） */
    private suspend fun exportDom(webView: WebView): String = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript("(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })()") { rawResult ->
                if (rawResult == null || rawResult == "null") {
                    cont.resumeWithException(Exception("DOM 导出为空"))
                    return@evaluateJavascript
                }
                val html = try {
                    org.json.JSONTokener(rawResult).nextValue() as? String ?: rawResult
                } catch (_: Exception) {
                    rawResult
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
        htmlDeferred: CompletableDeferred<String>,
        desktopUa: Boolean,
    ) {
        // 允许 Cookie 管理，确保知乎 __zse_ck 等反爬 Cookie 能在挑战完成后被写入
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = if (desktopUa) WebFetcher.DESKTOP_UA else WebFetcher.MOBILE_UA
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                // 尽早注入执行框架
                view.evaluateJavascript(HARNESS_JS, null)
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
                // document-idle 时机：注入匹配的用户脚本
                if (userscript != null && UserscriptRunner.matchesUrl(userscript, pageUrl)) {
                    view.evaluateJavascript(buildInjectExpression(userscript), null)
                }
                if (!htmlDeferred.isCompleted) htmlDeferred.complete("")
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                // 关键：知乎等站点的 WAF 挑战返回 HTTP 403，但其实带有挑战 HTML 和 JS 脚本，
                // 切勿在此抛出异常中断，让 Chromium 继续解析运行该 HTML 中的 challenge 脚本！
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame && !htmlDeferred.isCompleted) {
                    val desc = runCatching { error.description }.getOrNull() ?: "网络异常"
                    htmlDeferred.completeExceptionally(
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
