package com.kindle.converter.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kindle.converter.KindleConverterApp
import com.kindle.converter.data.Document
import com.kindle.converter.data.PageLayout
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.data.stripFrontMatterToc
import com.kindle.converter.epub.BookMeta
import com.kindle.converter.epub.EpubBuilder
import com.kindle.converter.parser.DownloadedImage
import com.kindle.converter.parser.ImageDownloader
import com.kindle.converter.parser.WebArticleParser
import com.kindle.converter.pdf.FontManager
import com.kindle.converter.pdf.PdfGenerator
import com.kindle.converter.typeset.LineBreaker
import com.kindle.converter.typeset.Paginator
import com.kindle.converter.typeset.TextMeasurer
import com.kindle.converter.typeset.TypesettingEngine
import com.kindle.converter.web.WebContainer
import com.kindle.converter.web.model.FetchMode
import com.kindle.converter.web.model.LinkItem
import com.kindle.converter.web.model.LinkStatus
import com.kindle.converter.web.model.WebArticle
import com.kindle.converter.web.script.ScriptInfo
import com.kindle.converter.web.settings.UaMode
import com.kindle.converter.web.settings.WebSettings
import com.kindle.converter.pdf.PresetRepository
import com.kindle.converter.settings.SettingsBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

class WebClipperViewModel(application: Application) : AndroidViewModel(application) {

    private val container: WebContainer = (application as KindleConverterApp).webContainer
    private val backupManager = SettingsBackupManager(
        context = application,
        presetRepo = PresetRepository(application),
        webContainer = container
    )

    data class PendingWebExport(
        val tempFile: File,
        val suggestedName: String,
        val isPdf: Boolean,
        val pageCount: Int = 0
    )

    data class UiState(
        val links: List<LinkItem> = emptyList(),
        val isWorking: Boolean = false,
        val workingMessage: String = "",
        val bookTitle: String = "",
        val previewArticle: WebArticle? = null,
        val snackbar: String? = null,
        val pendingExport: PendingWebExport? = null,
        val generatedPdfFile: File? = null,
        val generatedEpubFile: File? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    val scriptInfo: Flow<ScriptInfo> = container.scriptRepository.info
    val settings: Flow<WebSettings> = container.settingsRepository.settings

    companion object {
        private val TRACKING_PARAMS = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "utm_id", "utm_sharing", "chksm", "exportkey", "pass_ticket", "sc_sid",
            "source_type", "scene", "share_source", "sub_source", "mid", "sn"
        )

        fun sanitizeUrl(rawUrl: String): String {
            return try {
                val uri = java.net.URI(rawUrl)
                val query = uri.query
                if (query.isNullOrBlank()) return rawUrl

                val host = uri.host?.lowercase() ?: ""
                // 知乎问答与专栏直接清除追踪参数，保留纯净文章路径
                if (host.contains("zhihu.com")) {
                    val path = uri.path ?: ""
                    if (path.contains("/answer/") || path.startsWith("/p/")) {
                        return java.net.URI(uri.scheme, uri.authority, uri.path, null, null).toString()
                    }
                }

                val cleanedParams = query.split("&").filter { param ->
                    val key = param.substringBefore("=").lowercase().trim()
                    key !in TRACKING_PARAMS
                }
                val newQuery = if (cleanedParams.isEmpty()) null else cleanedParams.joinToString("&")
                java.net.URI(uri.scheme, uri.authority, uri.path, newQuery, uri.fragment).toString()
            } catch (_: Exception) {
                rawUrl
            }
        }
    }

    private val idGenerator = AtomicLong(0)

    val articles: List<WebArticle>
        get() = _uiState.value.links.mapNotNull {
            (it.status as? LinkStatus.Success)?.article
        }

    /** 解析并添加粘贴文本中的链接（自动提取与去重，剥离中文分享杂质与跟踪参数） */
    fun addUrls(raw: String) {
        val urlRegex = Regex("https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'*+,;=%]+")
        val urls = urlRegex.findAll(raw)
            .map { it.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '，', '。', '；', '）', '」', '】', '、', '？', '！', '”', '’') }
            .map { sanitizeUrl(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        if (urls.isEmpty()) {
            _uiState.update { it.copy(snackbar = "未识别到有效链接（需以 http:// 或 https:// 开头）") }
            return
        }

        _uiState.update { state ->
            val existing = state.links.mapTo(HashSet()) { it.url }
            val newItems = urls.filterNot { it in existing }.map {
                LinkItem(id = idGenerator.incrementAndGet(), url = it)
            }
            val skipped = urls.size - newItems.size
            state.copy(
                links = state.links + newItems,
                snackbar = if (skipped > 0) "已添加 ${newItems.size} 条，跳过 $skipped 条重复链接" else "已添加 ${newItems.size} 条链接",
            )
        }
    }

    fun removeLink(id: Long) {
        _uiState.update { it.copy(links = it.links.filterNot { l -> l.id == id }) }
    }

    fun clearAll() {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(links = emptyList()) }
    }

    fun moveLink(id: Long, delta: Int) {
        _uiState.update { state ->
            val index = state.links.indexOfFirst { it.id == id }
            val target = index + delta
            if (index < 0 || target < 0 || target >= state.links.size) return@update state
            val list = state.links.toMutableList()
            val item = list.removeAt(index)
            list.add(target, item)
            state.copy(links = list)
        }
    }

    fun setBookTitle(title: String) {
        _uiState.update { it.copy(bookTitle = title) }
    }

    fun showPreview(article: WebArticle) {
        _uiState.update { it.copy(previewArticle = article) }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewArticle = null) }
    }

    /** 抓取所有未抓取或失败的链接 */
    fun fetchRemaining(onDone: ((List<WebArticle>) -> Unit)? = null) {
        if (_uiState.value.isWorking) return
        val targets = _uiState.value.links.filter {
            it.status is LinkStatus.Idle || it.status is LinkStatus.Error
        }
        if (targets.isEmpty()) {
            val list = articles
            if (list.isEmpty()) {
                _uiState.update { it.copy(snackbar = "没有可提取的有效链接") }
            } else {
                onDone?.invoke(list)
            }
            return
        }

        _uiState.update { it.copy(isWorking = true, workingMessage = "正在并发抓取网页正文…") }
        viewModelScope.launch {
            val webSettings = container.settingsRepository.settings.first()
            val semaphore = Semaphore(webSettings.concurrency)
            runCatching {
                coroutineScope {
                    targets.forEach { item ->
                        launch(Dispatchers.IO) {
                            semaphore.withPermit { fetchOne(item.id) }
                        }
                    }
                }
            }
            _uiState.update { it.copy(isWorking = false, workingMessage = "") }
            val successList = articles
            if (successList.isEmpty()) {
                _uiState.update { it.copy(snackbar = "没有成功提取的正文，请检查网络或切换重抓模式") }
            } else {
                onDone?.invoke(successList)
            }
        }
    }

    /** 针对特定链接使用指定策略重抓 */
    fun refetch(id: Long, mode: FetchMode) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true, workingMessage = "正在重新抓取该页面…") }
        viewModelScope.launch(Dispatchers.IO) {
            when (mode) {
                FetchMode.DEFAULT -> fetchOne(id)
                FetchMode.DESKTOP -> fetchOne(id, uaOverride = UaMode.DESKTOP)
                FetchMode.WEBVIEW -> fetchOne(id, useWebView = true)
            }
            _uiState.update { it.copy(isWorking = false, workingMessage = "") }
        }
    }

    private fun isSiteRequiringWebView(url: String, directDomains: String): Boolean {
        val host = try {
            java.net.URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            url.lowercase()
        }
        val domainList = directDomains.split(',', ';', '\n', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }

        return domainList.any { domain ->
            host == domain || host.endsWith(".$domain") || url.lowercase().contains(domain)
        }
    }

    private suspend fun fetchOne(id: Long, uaOverride: UaMode? = null, useWebView: Boolean = false) = withContext(Dispatchers.IO) {
        val item = _uiState.value.links.firstOrNull { it.id == id } ?: return@withContext
        updateStatus(id, LinkStatus.Fetching)
        try {
            val webSettings = container.settingsRepository.settings.first()
            val forceWebView = useWebView || webSettings.alwaysUseWebView || isSiteRequiringWebView(item.url, webSettings.directWebViewDomains)
            val article = if (forceWebView) {
                val script = runCatching { container.scriptRepository.readCurrentScript() }.getOrNull()
                val isDesktop = webSettings.defaultUa == UaMode.DESKTOP
                val html = container.webViewRenderer.render(item.url, script, desktopUa = isDesktop)
                container.contentExtractor.extract(item.url, html)
            } else {
                try {
                    val mode = uaOverride ?: webSettings.defaultUa
                    val ua = container.webFetcher.userAgentFor(mode, webSettings.customUserAgent)
                    val html = container.webFetcher.fetchHtml(item.url, ua)
                    container.contentExtractor.extract(item.url, html)
                } catch (e: Exception) {
                    // 若普通静态抓取遭遇 403 / 401 或反爬提取失败，自动使用真实 WebView 离线渲染兜底
                    val script = runCatching { container.scriptRepository.readCurrentScript() }.getOrNull()
                    val isDesktop = webSettings.defaultUa == UaMode.DESKTOP
                    val html = container.webViewRenderer.render(item.url, script, desktopUa = isDesktop)
                    container.contentExtractor.extract(item.url, html)
                }
            }
            updateStatus(id, LinkStatus.Success(article))
        } catch (e: Exception) {
            updateStatus(id, LinkStatus.Error(e.message ?: "未知错误"))
        }
    }

    private fun updateStatus(id: Long, status: LinkStatus) {
        _uiState.update { state ->
            state.copy(links = state.links.map { if (it.id == id) it.copy(status = status) else it })
        }
    }

    /** 将当前成功提取的文章解析为 KindlePDF 的 Document 模型 */
    suspend fun buildDocument(imageResolver: suspend (String) -> DownloadedImage? = { url ->
        ImageDownloader.download(url, refererHint = articles.firstOrNull()?.url ?: "")
    }): Document {
        val list = articles
        require(list.isNotEmpty()) { "没有提取成功的文章" }
        return WebArticleParser().parse(list, imageResolver)
    }

    /** 导出为标准 EPUB 文件 */
    suspend fun exportEpub(
        title: String,
        author: String = "",
        outputFile: File,
        disableFirstLineIndent: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val list = articles
            if (list.isEmpty()) return@withContext Result.failure(IllegalStateException("没有提取成功的文章"))
            val webSettings = container.settingsRepository.settings.first()
            val meta = BookMeta(title = title.ifBlank { list.first().title }, author = author)
            val ua = container.webFetcher.userAgentFor(webSettings.defaultUa, webSettings.customUserAgent)
            val file = EpubBuilder.build(
                meta = meta,
                articles = list,
                embedImages = webSettings.embedImages,
                userAgent = ua,
                outputFile = outputFile,
                disableFirstLineIndent = disableFirstLineIndent
            )
            Result.success(file)
        } catch (t: Throwable) {
            val ex = if (t is OutOfMemoryError) Exception("导出 EPUB 时内存不足 (OutOfMemory)") else (t as? Exception ?: Exception(t.message))
            Result.failure(ex)
        }
    }

    /** 先在后台生成 PDF 临时文件，成功且非空后再触发系统保存弹窗，避免生成 0 KB 空文件 */
    fun preparePdfExport(
        params: TypesettingParams,
        fontManager: FontManager,
        selectedFontId: String?
    ) {
        if (_uiState.value.isWorking) return
        val list = articles
        if (list.isEmpty()) {
            _uiState.update { it.copy(snackbar = "没有提取成功的文章") }
            return
        }
        val rawTitle = _uiState.value.bookTitle.ifBlank {
            list.firstOrNull()?.title ?: "网页精选"
        }
        val safeTitle = sanitizeFileName(rawTitle.take(24), "网页精选")
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, workingMessage = "正在转换 PDF") }
            val tempPdf = File(getApplication<Application>().cacheDir, "web_kindle_${System.currentTimeMillis()}.pdf")
            val res = withContext(Dispatchers.Default) {
                if (selectedFontId != null && fontManager.getCurrentEntry()?.id != selectedFontId) {
                    fontManager.selectFont(selectedFontId)
                }
                exportKindlePdf(params, fontManager, tempPdf)
            }
            if (res.isSuccess) {
                val (file, pages) = res.getOrThrow()
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        workingMessage = "",
                        pendingExport = PendingWebExport(
                            tempFile = file,
                            suggestedName = "$safeTitle.pdf",
                            isPdf = true,
                            pageCount = pages
                        )
                    )
                }
            } else {
                try { tempPdf.delete() } catch (_: Exception) {}
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        workingMessage = "",
                        snackbar = "PDF 生成失败：${res.exceptionOrNull()?.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    /** 先在后台生成 EPUB 临时文件，成功且非空后再触发系统保存弹窗 */
    fun prepareEpubExport(
        title: String,
        disableFirstLineIndent: Boolean = false
    ) {
        if (_uiState.value.isWorking) return
        val list = articles
        if (list.isEmpty()) {
            _uiState.update { it.copy(snackbar = "没有提取成功的文章") }
            return
        }
        val rawTitle = title.ifBlank { list.firstOrNull()?.title ?: "网页精选集" }
        val safeTitle = sanitizeFileName(rawTitle.take(24), "网页精选")
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, workingMessage = "正在生成 EPUB") }
            val tempEpub = File(getApplication<Application>().cacheDir, "web_${System.currentTimeMillis()}.epub")
            val res = exportEpub(
                title = rawTitle,
                outputFile = tempEpub,
                disableFirstLineIndent = disableFirstLineIndent
            )
            if (res.isSuccess) {
                val file = res.getOrThrow()
                if (file.exists() && file.length() > 0L) {
                    _uiState.update {
                        it.copy(
                            isWorking = false,
                            workingMessage = "",
                            pendingExport = PendingWebExport(
                                tempFile = file,
                                suggestedName = "$safeTitle.epub",
                                isPdf = false
                            )
                        )
                    }
                } else {
                    try { file.delete() } catch (_: Exception) {}
                    _uiState.update {
                        it.copy(
                            isWorking = false,
                            workingMessage = "",
                            snackbar = "EPUB 生成失败：输出文件为空"
                        )
                    }
                }
            } else {
                try { tempEpub.delete() } catch (_: Exception) {}
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        workingMessage = "",
                        snackbar = "EPUB 生成失败：${res.exceptionOrNull()?.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    /** 将已生成的临时导出文件写入用户选择的 SAF Uri；写入失败则清理空文档 */
    fun resolvePendingExport(uri: Uri) {
        val pending = _uiState.value.pendingExport ?: return
        _uiState.update { it.copy(pendingExport = null, isWorking = true, workingMessage = "正在保存文件") }
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val saveRes = withContext(Dispatchers.IO) {
                runCatching {
                    if (!pending.tempFile.exists() || pending.tempFile.length() <= 0L) {
                        error("临时导出文件不存在或为空")
                    }
                    val os = resolver.openOutputStream(uri)
                        ?: error("无法打开目标文件写入流")
                    os.use { out ->
                        pending.tempFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                        out.flush()
                    }
                }
            }
            try { pending.tempFile.delete() } catch (_: Exception) {}
            if (saveRes.isSuccess) {
                val msg = if (pending.isPdf) "导出成功（${pending.pageCount} 页）" else "EPUB 导出成功"
                _uiState.update { it.copy(isWorking = false, workingMessage = "", snackbar = msg) }
            } else {
                // 清理 SAF 已创建的 0 字节占位文件，避免留下无用空文件
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                }
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        workingMessage = "",
                        snackbar = "保存失败：${saveRes.exceptionOrNull()?.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    /** 用户取消保存弹窗时清理临时文件 */
    fun cancelPendingExport() {
        val pending = _uiState.value.pendingExport ?: return
        try { pending.tempFile.delete() } catch (_: Exception) {}
        _uiState.update { it.copy(pendingExport = null) }
    }

    private fun sanitizeFileName(raw: String, fallback: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim().ifBlank { fallback }
    }

    /** 直接导出为 6 英寸 Kindle PDF */
    suspend fun exportKindlePdf(
        params: TypesettingParams,
        fontManager: FontManager,
        outputFile: File
    ): Result<Pair<File, Int>> = withContext(Dispatchers.Default) {
        try {
            val list = articles
            if (list.isEmpty()) return@withContext Result.failure(IllegalStateException("没有提取成功的文章"))
            val webSettings = container.settingsRepository.settings.first()
            val firstUrl = list.first().url
            val rawDocument = buildDocument { url ->
                if (webSettings.embedImages) ImageDownloader.download(url, refererHint = firstUrl) else null
            }
            val document = if (params.hideFrontToc) rawDocument.stripFrontMatterToc() else rawDocument

            val fontInfo = fontManager.getCurrentFontInfo()
                ?: run {
                    fontManager.autoSelect()
                    fontManager.getCurrentFontInfo()
                }
                ?: return@withContext Result.failure(IllegalStateException("尚未检测到可用字体，请在文件转换页导入中文字体"))

            val measurer = TextMeasurer(fontInfo.font, fontInfo.boldFont)
            val engine = TypesettingEngine(measurer, LineBreaker(measurer), Paginator())
            val typesetResult = engine.typesetWithDetails(document, params)
            val pages = typesetResult.pages
            if (pages.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("正文排版结果为空（0 页）"))
            }

            // 构造 PDF 书签树
            val bookmarks = mutableListOf<PdfGenerator.Bookmark>()
            for (entry in document.toc) {
                val pageIndex = typesetResult.blockPageMap[entry.blockIndex] ?: 0
                bookmarks.add(PdfGenerator.Bookmark(entry.title, entry.level, pageIndex))
            }

            outputFile.parentFile?.mkdirs()
            val generator = PdfGenerator(getApplication(), fontManager)
            val genResult = generator.generateResult(pages, bookmarks, outputFile)
            if (genResult.isSuccess && outputFile.exists() && outputFile.length() > 0L) {
                Result.success(outputFile to pages.size)
            } else {
                try { outputFile.delete() } catch (_: Exception) {}
                val reason = genResult.exceptionOrNull()?.message ?: "PDF 文件生成失败或为空"
                Result.failure(Exception(reason))
            }
        } catch (t: Throwable) {
            try { outputFile.delete() } catch (_: Exception) {}
            val ex = if (t is OutOfMemoryError) Exception("排版生成 PDF 时内存不足 (OutOfMemory)") else (t as? Exception ?: Exception(t.message))
            Result.failure(ex)
        }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbar = null) }
    }

    // 设置项便捷方法
    fun setEmbedImages(value: Boolean) = viewModelScope.launch { container.settingsRepository.setEmbedImages(value) }
    fun setDefaultUa(value: UaMode) = viewModelScope.launch { container.settingsRepository.setDefaultUa(value) }
    fun setConcurrency(value: Int) = viewModelScope.launch { container.settingsRepository.setConcurrency(value) }
    fun setAlwaysUseWebView(value: Boolean) = viewModelScope.launch { container.settingsRepository.setAlwaysUseWebView(value) }
    fun setDirectWebViewDomains(value: String) = viewModelScope.launch { container.settingsRepository.setDirectWebViewDomains(value) }
    fun setWebViewDarkMode(value: Boolean) = viewModelScope.launch { container.settingsRepository.setWebViewDarkMode(value) }

    /** 直接从已有 HTML 字符串（如内置浏览器已渲染的 DOM）提取正文并加入列表 */
    fun addFromHtml(url: String, html: String, fallbackTitle: String? = null) {
        val cleanUrl = sanitizeUrl(url)
        viewModelScope.launch(Dispatchers.IO) {
            val article = try {
                container.contentExtractor.extract(cleanUrl, html)
            } catch (e: Exception) {
                val title = fallbackTitle?.ifBlank { null } ?: "网页剪藏正文"
                WebArticle(
                    url = cleanUrl,
                    title = title,
                    contentHtml = html,
                    possiblyTruncated = false
                )
            }
            val item = LinkItem(
                id = idGenerator.incrementAndGet(),
                url = cleanUrl,
                status = LinkStatus.Success(article)
            )
            _uiState.update { state ->
                val existing = state.links.filterNot { it.url == cleanUrl }
                state.copy(
                    links = existing + item,
                    snackbar = "已成功剪藏：《${article.title.take(20)}》"
                )
            }
        }
    }

    fun updateScriptFromUrl() = viewModelScope.launch {
        _uiState.update { it.copy(isWorking = true, workingMessage = "正在从 ScriptCat 检查脚本更新…") }
        val res = container.scriptRepository.updateFromUrl()
        _uiState.update {
            it.copy(
                isWorking = false,
                workingMessage = "",
                snackbar = if (res.isSuccess) "《骚扰拦截》脚本已更新到最新版本！" else "更新失败：${res.exceptionOrNull()?.message}"
            )
        }
    }
    fun resetScriptToAsset() = viewModelScope.launch {
        container.scriptRepository.resetToAsset()
        _uiState.update { it.copy(snackbar = "已恢复为内置《骚扰拦截》脚本") }
    }

    suspend fun exportSettings(out: OutputStream) = backupManager.exportSettings(out)

    suspend fun importSettings(input: InputStream): Result<String> = backupManager.importSettings(input)
}
