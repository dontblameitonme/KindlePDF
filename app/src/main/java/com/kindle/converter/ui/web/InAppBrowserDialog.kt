package com.kindle.converter.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.kindle.converter.web.fetcher.WebFetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Via 风格轻量级内置浏览器：
 * 1. 真实前台 Chromium 渲染，天然绕过 0x0 无头特征与反爬节流；
 * 2. 遇到极严反爬（如验证码滑块、强制扫码登录）时，用户可直接交互浏览；
 * 3. 彻底消除深色模式页面闪白（Canvas底色预置 + onPageStarted根样式预注入）；
 * 4. 全屏沉浸式状态栏与导航栏自适应；
 * 5. 悬浮胶囊支持「⚡ 自动剪藏: 开/关」及防抖手动剪藏。
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
    val coroutineScope = rememberCoroutineScope()
    var currentUrl by remember { mutableStateOf(initialUrl.ifBlank { "https://www.zhihu.com" }) }
    var inputUrlText by remember { mutableStateOf(currentUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isDesktopUa by remember { mutableStateOf(false) }
    val defaultNativeUa = remember {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(WebFetcher.MOBILE_UA)
    }
    var isDarkMode by remember { mutableStateOf(initialDarkMode) }
    var clippedCount by remember { mutableIntStateOf(0) }
    var isAutoClipEnabled by remember { mutableStateOf(false) }
    var isClipping by remember { mutableStateOf(false) }
    var justClipped by remember { mutableStateOf(false) }

    val clippedUrls = remember { mutableSetOf<String>() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 核心剪藏执行逻辑（支持手动与自动两种模式）
    fun executeClip(wv: WebView, isAuto: Boolean = false) {
        val activeUrl = wv.url ?: currentUrl
        if (clippedUrls.contains(activeUrl) && isAuto) return
        if (isAuto && !isClipCandidate(activeUrl)) return

        if (!isAuto) {
            if (isClipping) return
            isClipping = true
        }

        wv.evaluateJavascript(
            """
            (function(){
                var text = (document.body && document.body.innerText) ? document.body.innerText.trim() : '';
                var title = document.title || '';
                var html = document.documentElement ? document.documentElement.outerHTML : '';
                return JSON.stringify({
                    textLen: text.length,
                    title: title,
                    html: html
                });
            })()
            """.trimIndent()
        ) { rawResult ->
            if (!isAuto) {
                isClipping = false
            }
            if (rawResult.isNullOrBlank() || rawResult == "null") {
                if (!isAuto) Toast.makeText(context, "页面尚未加载完毕", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }

            try {
                val unquoted = try {
                    JSONTokener(rawResult).nextValue() as? String ?: rawResult
                } catch (_: Exception) {
                    rawResult
                }
                val json = JSONObject(unquoted)
                val textLen = json.optInt("textLen", 0)
                val title = json.optString("title", "").ifBlank { wv.title ?: pageTitle }
                val html = json.optString("html", "")

                if (isAuto && textLen < 80) {
                    // 正文过短（可能仍处于骨架屏、验证码或广告页），不自动剪藏
                    return@evaluateJavascript
                }

                if (html.isBlank()) {
                    if (!isAuto) Toast.makeText(context, "正文提取为空", Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }

                clippedUrls.add(activeUrl)
                onClipCurrentPage(activeUrl, html, title)
                clippedCount++
                justClipped = true

                if (isAuto) {
                    Toast.makeText(context, "⚡ [自动剪藏] 已收录：${title.take(18)}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "已成功剪藏此页！可继续浏览或关闭", Toast.LENGTH_SHORT).show()
                }

                coroutineScope.launch {
                    delay(2000)
                    justClipped = false
                }
            } catch (e: Exception) {
                if (!isAuto) {
                    val fallbackTitle = wv.title ?: pageTitle
                    clippedUrls.add(activeUrl)
                    onClipCurrentPage(activeUrl, rawResult, fallbackTitle)
                    clippedCount++
                    justClipped = true
                    Toast.makeText(context, "已剪藏此页！", Toast.LENGTH_SHORT).show()
                    coroutineScope.launch {
                        delay(2000)
                        justClipped = false
                    }
                }
            }
        }
    }

    BackHandler {
        if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // 设置 Dialog 独立 Window 的透明状态栏与导航栏沉浸
        val view = LocalView.current
        DisposableEffect(view, isDarkMode) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Color.TRANSPARENT
                val controller = WindowCompat.getInsetsController(w, view)
                controller.isAppearanceLightStatusBars = !isDarkMode
                controller.isAppearanceLightNavigationBars = !isDarkMode
            }
            onDispose {}
        }

        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
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
                                    val targetCanvasColor = if (nextMode) Color.parseColor("#121212") else Color.WHITE
                                    webViewRef?.setBackgroundColor(targetCanvasColor)
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
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            val nextMode = !isDesktopUa
                                            isDesktopUa = nextMode
                                            val targetUa = if (nextMode) WebFetcher.DESKTOP_UA else defaultNativeUa
                                            webViewRef?.settings?.userAgentString = targetUa
                                            webViewRef?.reload()
                                            Toast.makeText(context, if (nextMode) "已切换为电脑 PC UA" else "已切换为移动原生 UA", Toast.LENGTH_SHORT).show()
                                        }
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
                // 悬浮交互胶囊：自动剪藏开关 + 手动防抖剪藏按钮
                Surface(
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp, end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 自动剪藏开关胶囊
                        FilterChip(
                            selected = isAutoClipEnabled,
                            onClick = {
                                val next = !isAutoClipEnabled
                                isAutoClipEnabled = next
                                Toast.makeText(
                                    context,
                                    if (next) "⚡ 已开启自动剪藏：每进入新正文页将自动收录" else "已关闭自动剪藏",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (next) {
                                    webViewRef?.let { executeClip(it, isAuto = true) }
                                }
                            },
                            label = {
                                Text(
                                    if (isAutoClipEnabled) "⚡ 自动剪藏: 开" else "⚡ 自动剪藏: 关",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isAutoClipEnabled) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )

                        // 手动剪藏按钮（带成功防抖状态）
                        Button(
                            onClick = {
                                webViewRef?.let { executeClip(it, isAuto = false) }
                                    ?: Toast.makeText(context, "网页尚未初始化", Toast.LENGTH_SHORT).show()
                            },
                            enabled = !isClipping,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (justClipped) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                if (justClipped) Icons.Default.Check else Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    justClipped -> "已剪藏！"
                                    clippedCount > 0 -> "剪藏此页 ($clippedCount)"
                                    else -> "剪藏此页"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            val containerColor = if (isDarkMode) androidx.compose.ui.graphics.Color(0xFF121212) else MaterialTheme.colorScheme.background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(containerColor)
                    .padding(paddingValues)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // 初始化底色直接置为深色/浅色，杜绝初始 Canvas 白屏闪烁
                            val initialCanvasColor = if (isDarkMode) Color.parseColor("#121212") else Color.WHITE
                            setBackgroundColor(initialCanvasColor)

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
                                userAgentString = if (isDesktopUa) WebFetcher.DESKTOP_UA else defaultNativeUa
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }

                            applyDarkModeToWebView(this, isDarkMode)

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url?.scheme?.lowercase() ?: ""
                                    if (scheme == "http" || scheme == "https") {
                                        return false
                                    }
                                    // 拦截第三方客户端唤醒协议（如 zhihu://, intent://），坚决留在 WebView 内
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    isLoading = true
                                    currentUrl = url
                                    inputUrlText = url
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                    if (isDarkMode) {
                                        // 第一时间预注入暗黑根样式，消除白屏瞬间
                                        view.evaluateJavascript(
                                            """
                                            (function() {
                                                var pre = document.getElementById('__kindle_dark_preinject__');
                                                if (!pre) {
                                                    var s = document.createElement('style');
                                                    s.id = '__kindle_dark_preinject__';
                                                    s.textContent = 'html, body { background-color: #121212 !important; color: #e0e0e0 !important; }';
                                                    (document.head || document.documentElement).appendChild(s);
                                                }
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    isLoading = false
                                    currentUrl = url
                                    inputUrlText = url
                                    pageTitle = view.title ?: ""
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                    applyDarkModeToWebView(view, isDarkMode)
                                    runCatching { CookieManager.getInstance().flush() }

                                    // 自动剪藏检查
                                    if (isAutoClipEnabled) {
                                        executeClip(view, isAuto = true)
                                    }
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    currentUrl = url
                                    inputUrlText = url
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                    if (isAutoClipEnabled && !isReload) {
                                        view.postDelayed({
                                            if (isAutoClipEnabled) {
                                                executeClip(view, isAuto = true)
                                            }
                                        }, 800)
                                    }
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
                        val targetCanvasColor = if (isDarkMode) Color.parseColor("#121212") else Color.WHITE
                        view.setBackgroundColor(targetCanvasColor)
                        applyDarkModeToWebView(view, isDarkMode)
                    }
                )
            }
        }
    }
}

/** 过滤非正文页面（登录页、滑块验证码、中间过渡跳转页等） */
private fun isClipCandidate(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.startsWith("about:") || lower.startsWith("chrome://") || lower.startsWith("javascript:")) return false
    val blacklistedKeywords = listOf(
        "login", "signin", "passport", "account", "unhuman",
        "captcha", "verify", "security", "search?", "/search"
    )
    return blacklistedKeywords.none { lower.contains(it) }
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
            var pre = document.getElementById('__kindle_dark_preinject__');
            if (pre) pre.remove();
            var id = '__kindle_dark_filter__';
            var el = document.getElementById(id);
            if (!el) {
                el = document.createElement('style');
                el.id = id;
                el.textContent = `
                    html {
                        filter: invert(90%) hue-rotate(180deg) !important;
                        background-color: #121212 !important;
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
            var pre = document.getElementById('__kindle_dark_preinject__');
            if (pre) pre.remove();
            var el = document.getElementById('__kindle_dark_filter__');
            if (el) el.remove();
        })();
        """.trimIndent()
    }
    webView.evaluateJavascript(cssScript, null)
}
