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
            // 等待页面加载完成
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { htmlDeferred.await() }

            // 自动展开 + 滚动触发懒加载，等待脚本与页面稳定
            evalOnMain(webView, AUTO_EXPAND_JS)
            delay(2_000)
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

    private suspend fun exportDom(webView: WebView): String {
        val exportDeferred = CompletableDeferred<String>()
        postOnMain {
            webView.addJavascriptInterface(ExportBridge(exportDeferred), "W2EBridge")
            webView.evaluateJavascript(
                "(function(){return document.documentElement.outerHTML;})()", null
            )
        }
        return withTimeout(EXPORT_TIMEOUT_MS) { exportDeferred.await() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(
        webView: WebView,
        userscript: String?,
        htmlDeferred: CompletableDeferred<String>,
        desktopUa: Boolean,
    ) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = if (desktopUa) WebFetcher.DESKTOP_UA else WebFetcher.MOBILE_UA
            loadWithOverviewMode = true
            useWideViewPort = false
            setSupportZoom(false)
            builtInZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
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
