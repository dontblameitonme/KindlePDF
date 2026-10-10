package com.kindle.converter.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kindle.converter.web.fetcher.WebFetcher
import org.json.JSONTokener

/**
 * Via 风格轻量级内置浏览器：
 * 1. 真实前台 Chromium 渲染，天然绕过 0x0 无头特征与反爬节流；
 * 2. 遇到极严反爬（如验证码滑块、强制扫码登录）时，用户可直接交互浏览；
 * 3. 悬浮「📥 剪藏此页」一键从活生生的 WebView 抓取已渲染 DOM 并存入排版队列；
 * 4. 支持网页全局深色模式并永久保存状态。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppBrowserDialog(
    initialUrl: String,
    initialDarkMode: Boolean,
    onDismiss: () -> Unit,
    onToggleDarkMode: (Boolean) -> Unit,
    onClipCurrentPage: (url: String, html: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(initialUrl.ifBlank { "https://www.zhihu.com" }) }
    var inputUrlText by remember { mutableStateOf(currentUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isDesktopUa by remember { mutableStateOf(true) }
    var isDarkMode by remember { mutableStateOf(initialDarkMode) }
    var clippedCount by remember { mutableIntStateOf(0) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        // 顶部地址栏与主要控制按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(onClick = onDismiss, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "关闭浏览器")
                            }

                            // 地址输入框
                            OutlinedTextField(
                                value = inputUrlText,
                                onValueChange = { inputUrlText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                placeholder = { Text("输入网址或搜索…", style = MaterialTheme.typography.bodySmall) },
                                shape = RoundedCornerShape(24.dp),
                                trailingIcon = {
                                    if (inputUrlText.isNotBlank()) {
                                        IconButton(
                                            onClick = { inputUrlText = "" },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Clear, contentDescription = "清空", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        var target = inputUrlText.trim()
                                        if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                            target = if (target.contains(".") && !target.contains(" ")) {
                                                "https://$target"
                                            } else {
                                                "https://www.bing.com/search?q=$target"
                                            }
                                        }
                                        inputUrlText = target
                                        webViewRef?.loadUrl(target)
                                    }
                                )
                            )

                            // 深色模式切换按钮（可永久保存选择状态）
                            IconButton(
                                onClick = {
                                    val nextMode = !isDarkMode
                                    isDarkMode = nextMode
                                    onToggleDarkMode(nextMode)
                                    applyDarkModeToWebView(webViewRef, nextMode)
                                    Toast.makeText(context, if (nextMode) "已开启网页深色模式" else "已切换为正常模式", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = if (isDarkMode) "深色模式" else "浅色模式",
                                    tint = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 前进后退与刷新
                            IconButton(
                                onClick = { webViewRef?.goBack() },
                                enabled = canGoBack,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "后退", modifier = Modifier.size(20.dp))
                            }

                            IconButton(
                                onClick = { webViewRef?.goForward() },
                                enabled = canGoForward,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ArrowForward, contentDescription = "前进", modifier = Modifier.size(20.dp))
                            }

                            IconButton(
                                onClick = { webViewRef?.reload() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(20.dp))
                            }
                        }

                        // 页面标题与 UA / 深色模式提示条
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pageTitle.ifBlank { currentUrl },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (isDarkMode) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    ) {
                                        Text(
                                            text = "深色模式",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Surface(
                                    color = if (isDesktopUa) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                ) {
                                    Text(
                                        text = if (isDesktopUa) "PC端UA" else "移动UA",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isDesktopUa) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // 加载进度条
                        if (isLoading && loadingProgress in 1..99) {
                            LinearProgressIndicator(
                                progress = { loadingProgress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        val wv = webViewRef
                        if (wv == null) {
                            Toast.makeText(context, "网页尚未初始化", Toast.LENGTH_SHORT).show()
                            return@ExtendedFloatingActionButton
                        }

                        Toast.makeText(context, "正在提取网页正文…", Toast.LENGTH_SHORT).show()
                        wv.evaluateJavascript(
                            "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })()"
                        ) { rawResult ->
                            if (rawResult == null || rawResult == "null" || rawResult.isBlank()) {
                                Toast.makeText(context, "页面 DOM 提取为空，请等待加载完毕", Toast.LENGTH_SHORT).show()
                                return@evaluateJavascript
                            }
                            val html = try {
                                JSONTokener(rawResult).nextValue() as? String ?: rawResult
                            } catch (_: Exception) {
                                rawResult
                            }

                            val activeUrl = wv.url ?: currentUrl
                            val activeTitle = wv.title ?: pageTitle
                            onClipCurrentPage(activeUrl, html, activeTitle)
                            clippedCount++
                            Toast.makeText(context, "已成功剪藏此页！可继续浏览或关闭", Toast.LENGTH_SHORT).show()
                        }
                    },
                    icon = {
                        Icon(Icons.Default.Download, contentDescription = "剪藏")
                    },
                    text = {
                        Text(
                            if (clippedCount > 0) "📥 剪藏此页 (已存 $clippedCount 篇)" else "📥 剪藏此页",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                userAgentString = if (isDesktopUa) WebFetcher.DESKTOP_UA else WebFetcher.MOBILE_UA
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }

                            applyDarkModeToWebView(this, isDarkMode)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    isLoading = true
                                    currentUrl = url
                                    inputUrlText = url
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                    applyDarkModeToWebView(view, isDarkMode)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    isLoading = false
                                    currentUrl = url
                                    inputUrlText = url
                                    pageTitle = view.title ?: ""
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                    applyDarkModeToWebView(view, isDarkMode)
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    loadingProgress = newProgress
                                    isLoading = newProgress < 100
                                }

                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    if (!title.isNullOrBlank()) {
                                        pageTitle = title
                                    }
                                }
                            }

                            loadUrl(currentUrl)
                            webViewRef = this
                        }
                    },
                    update = { view ->
                        webViewRef = view
                        applyDarkModeToWebView(view, isDarkMode)
                    }
                )
            }
        }
    }
}

/** 注入全局深色模式样式，实现网页内容舒适夜间反色，同时保持图片等媒体资源原色不变 */
private fun applyDarkModeToWebView(webView: WebView?, isDark: Boolean) {
    if (webView == null) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            @Suppress("DEPRECATION")
            webView.settings.forceDark = if (isDark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }
    val cssScript = if (isDark) {
        """
        (function() {
            var id = '__kindle_dark_filter__';
            var el = document.getElementById(id);
            if (!el) {
                el = document.createElement('style');
                el.id = id;
                el.textContent = `
                    html {
                        filter: invert(90%) hue-rotate(180deg) !important;
                        background-color: #1a1a1a !important;
                    }
                    img, video, canvas, svg, [style*="background-image"] {
                        filter: invert(100%) hue-rotate(180deg) !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(el);
            }
        })();
        """.trimIndent()
    } else {
        """
        (function() {
            var el = document.getElementById('__kindle_dark_filter__');
            if (el) el.remove();
        })();
        """.trimIndent()
    }
    webView.evaluateJavascript(cssScript, null)
}
