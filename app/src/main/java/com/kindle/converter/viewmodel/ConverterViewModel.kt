package com.kindle.converter.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.PageLayout
import com.kindle.converter.data.ParseContext
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.parser.DocumentParser
import com.kindle.converter.parser.DownloadedImage
import com.kindle.converter.parser.ImageDownloader
import com.kindle.converter.pdf.FontManager
import com.kindle.converter.pdf.PdfGenerator
import com.kindle.converter.pdf.PresetRepository
import com.kindle.converter.typeset.LineBreaker
import com.kindle.converter.typeset.Paginator
import com.kindle.converter.typeset.TextMeasurer
import com.kindle.converter.typeset.TypesettingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import android.graphics.Typeface
import java.io.File

class ConverterViewModel(application: Application) : AndroidViewModel(application) {

    data class FileItem(
        val uri: Uri,
        val name: String,
        val size: Long = 0
    )

    data class ConversionResult(
        val fileName: String,
        val success: Boolean,
        val outputPath: String? = null,
        val error: String? = null,
        val pageCount: Int = 0
    )

    /** 待用户选择保存位置的文件（同目录写入失败时的回退） */
    data class PendingSave(
        val tempFilePath: String,
        val suggestedName: String,
        val originalName: String
    )

    data class UiState(
        val files: List<FileItem> = emptyList(),
        val params: TypesettingParams = TypesettingParams(),
        val selectedPresetIndex: Int = 0,
        val pages: List<PageLayout> = emptyList(),
        val previewPageIndex: Int = 0,
        val showPreviewDialog: Boolean = false,
        val fontName: String? = null,
        val fontTypeface: Typeface? = null,
        val fonts: List<FontManager.FontEntry> = emptyList(),
        val selectedFontId: String? = null,
        val presets: List<PresetRepository.ParamPreset> = emptyList(),
        val selectedPresetName: String? = null,
        val pendingSaves: List<PendingSave> = emptyList(),
        val isTypesetting: Boolean = false,
        val isConverting: Boolean = false,
        val conversionProgress: Float = 0f,
        val conversionResults: List<ConversionResult> = emptyList(),
        val errorMessage: String? = null,
        val statusMessage: String? = null,
        /** 各区域折叠状态。key: "fonts" | "params" | "presets"，缺失/true=展开 */
        val expandedSections: Map<String, Boolean> = emptyMap()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val fontManager = FontManager(application)
    private val presetRepo = PresetRepository(application)
    private val pdfGenerator = PdfGenerator(application, fontManager)

    /** 远程/本地图片解析：把 md/html 中的 <img> URL 下载为字节 */
    private val imageResolver: suspend (String) -> DownloadedImage? = { url -> ImageDownloader.download(url) }

    private var cachedDocument: Document? = null

    init {
        val ok = fontManager.autoSelect()
        val lastParams = presetRepo.loadLastParams()
        val presets = presetRepo.list()
        val lastPresetName = presetRepo.loadLastPresetName()
        val presetExists = lastPresetName != null && presets.any { it.name == lastPresetName }
        _uiState.update {
            it.copy(
                params = lastParams ?: it.params,
                fonts = fontManager.listFonts(),
                selectedFontId = fontManager.getCurrentEntry()?.id,
                presets = presets,
                selectedPresetName = if (presetExists) lastPresetName else null,
                fontName = fontManager.getDisplayName(),
                fontTypeface = if (ok) fontManager.getCurrentTypeface() else null,
                expandedSections = presetRepo.loadSectionExpanded(),
                statusMessage = if (ok) null else "尚未导入字体，请先导入字体再转换"
            )
        }
    }

    // --- 区域折叠状态管理（持久化） ---

    fun toggleSectionExpanded(key: String) {
        val current = _uiState.value.expandedSections
        val newValue = !(current[key] ?: true)
        val updated = current.toMutableMap().apply { put(key, newValue) }
        presetRepo.saveSectionExpanded(updated)
        _uiState.update { it.copy(expandedSections = updated) }
    }

    // --- 文件管理 ---

    fun addFiles(uris: List<Uri>) {
        val app = getApplication<Application>()
        val newFiles = uris.map { uri ->
            // 持久化读写权限，保证稍后能在同目录创建输出文件
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { /* 部分提供器不支持持久化写权限，忽略 */ }
            val name = getFileName(uri)
            val size = getFileSize(uri)
            FileItem(uri, name, size)
        }
        _uiState.update { it.copy(files = it.files + newFiles) }
    }

    fun removeFile(index: Int) {
        _uiState.update { it.copy(files = it.files.toMutableList().apply { removeAt(index) }) }
    }

    fun clearFiles() {
        _uiState.update { it.copy(files = emptyList()) }
    }

    // --- 字体管理 ---

    fun refreshFonts() {
        _uiState.update {
            it.copy(
                fonts = fontManager.listFonts(),
                selectedFontId = fontManager.getCurrentEntry()?.id,
                fontName = fontManager.getDisplayName(),
                fontTypeface = fontManager.getCurrentTypeface()
            )
        }
    }

    fun importCustomFont(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = fontManager.importFont(uri)
            if (entry != null) {
                _uiState.update {
                    it.copy(
                        fonts = fontManager.listFonts(),
                        selectedFontId = entry.id,
                        fontName = entry.name,
                        fontTypeface = fontManager.getCurrentTypeface(),
                        errorMessage = null,
                        statusMessage = "已导入字体：${entry.name}"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(errorMessage = "字体导入失败，请确认是有效的 TTF/OTF 文件")
                }
            }
        }
    }

    fun selectFont(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = fontManager.selectFont(id)
            if (ok) {
                _uiState.update {
                    it.copy(
                        fonts = fontManager.listFonts(),
                        selectedFontId = id,
                        fontName = fontManager.getDisplayName(),
                        fontTypeface = fontManager.getCurrentTypeface(),
                        errorMessage = null,
                        statusMessage = "已切换字体：${fontManager.getDisplayName()}"
                    )
                }
            }
        }
    }

    fun deleteFont(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            fontManager.deleteFont(id)
            val fonts = fontManager.listFonts()
            val entry = fontManager.getCurrentEntry()
            _uiState.update {
                it.copy(
                    fonts = fonts,
                    selectedFontId = entry?.id,
                    fontName = fontManager.getDisplayName(),
                    fontTypeface = fontManager.getCurrentTypeface(),
                    statusMessage = if (fonts.isEmpty()) "尚未导入字体，请先导入字体再转换" else null
                )
            }
        }
    }

    // --- 参数管理 ---

    fun updateParams(params: TypesettingParams) {
        // 手动调整参数会脱离当前预设，同步清除“上次选中预设”记录，保持 UI 一致
        if (_uiState.value.selectedPresetName != null) presetRepo.saveLastPresetName(null)
        _uiState.update { it.copy(params = params, selectedPresetName = null) }
    }

    fun selectPreset(index: Int) {
        val presets = TypesettingParams.KINDLE_PRESETS
        if (index in presets.indices) {
            val preset = presets[index]
            val newParams = _uiState.value.params.copy(
                pageWidth = TypesettingParams.mmToPoints(preset.widthMm),
                pageHeight = TypesettingParams.mmToPoints(preset.heightMm)
            )
            _uiState.update {
                it.copy(params = newParams, selectedPresetIndex = index, selectedPresetName = null)
            }
        }
    }

    // --- 参数预设管理 ---

    fun savePreset(name: String) {
        if (name.isBlank()) return
        presetRepo.save(name.trim(), _uiState.value.params)
        presetRepo.saveLastPresetName(name.trim())
        _uiState.update {
            it.copy(presets = presetRepo.list(), selectedPresetName = name.trim())
        }
    }

    fun loadPreset(name: String) {
        val params = presetRepo.get(name) ?: return
        presetRepo.saveLastPresetName(name)
        // 同步页面尺寸下拉索引（若恰好匹配某个预设）
        val idx = TypesettingParams.KINDLE_PRESETS.indexOfFirst { p ->
            TypesettingParams.mmToPoints(p.widthMm) == params.pageWidth &&
                TypesettingParams.mmToPoints(p.heightMm) == params.pageHeight
        }
        _uiState.update {
            it.copy(
                params = params,
                selectedPresetIndex = if (idx >= 0) idx else 0,
                selectedPresetName = name
            )
        }
    }

    fun deletePreset(name: String) {
        presetRepo.delete(name)
        if (_uiState.value.selectedPresetName == name) presetRepo.saveLastPresetName(null)
        _uiState.update {
            it.copy(
                presets = presetRepo.list(),
                selectedPresetName = if (it.selectedPresetName == name) null else it.selectedPresetName
            )
        }
    }

    // --- 预览 ---

    fun openPreviewDialog() {
        _uiState.update { it.copy(showPreviewDialog = true) }
        generatePreview()
    }

    fun closePreviewDialog() {
        _uiState.update { it.copy(showPreviewDialog = false) }
    }

    fun generatePreview() {
        val state = _uiState.value
        if (state.files.isEmpty()) return
        if (!fontManager.hasFont()) {
            _uiState.update { it.copy(errorMessage = "尚未导入字体，请先导入字体") }
            return
        }

        _uiState.update { it.copy(isTypesetting = true, errorMessage = null, showPreviewDialog = true) }
        presetRepo.saveLastParams(state.params)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val fileItem = state.files.first()
                val bytes = readUriBytes(fileItem.uri)
                val ctx = ParseContext(
                    stripHeadersFooters = state.params.stripHeadersFooters,
                    imageWrapMode = state.params.imageWrapMode
                )
                val document = DocumentParser.parse(fileItem.name, bytes, ctx, imageResolver)
                cachedDocument = document

                val fontInfo = fontManager.getCurrentFontInfo()!!
                val measurer = TextMeasurer(fontInfo.font, fontInfo.boldFont)
                val engine = TypesettingEngine(measurer, LineBreaker(measurer), Paginator())
                val pages = engine.typeset(document, state.params)

                _uiState.update {
                    it.copy(
                        pages = pages,
                        previewPageIndex = 0,
                        isTypesetting = false,
                        statusMessage = "预览：${pages.size} 页"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTypesetting = false, errorMessage = "预览失败：${e.message}")
                }
            }
        }
    }

    fun updatePreviewPage(index: Int) {
        val pages = _uiState.value.pages
        if (index in pages.indices) {
            _uiState.update { it.copy(previewPageIndex = index) }
        }
    }

    /**
     * 预览内置示例文字（铺满多页），实时展示当前排版参数。
     * 用于解决"预览白屏看不到排版"的问题——无需导入文件也能看到效果。
     */
    fun previewSample() {
        val state = _uiState.value
        if (!fontManager.hasFont()) {
            _uiState.update { it.copy(errorMessage = "尚未导入字体，请先导入字体再预览") }
            return
        }
        _uiState.update { it.copy(showPreviewDialog = true, isTypesetting = true, errorMessage = null) }
        presetRepo.saveLastParams(state.params)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val document = buildSampleDocument()
                val fontInfo = fontManager.getCurrentFontInfo()!!
                val measurer = TextMeasurer(fontInfo.font, fontInfo.boldFont)
                val engine = TypesettingEngine(measurer, LineBreaker(measurer), Paginator())
                val pages = engine.typeset(document, state.params)
                _uiState.update {
                    it.copy(
                        pages = pages,
                        previewPageIndex = 0,
                        isTypesetting = false,
                        statusMessage = "示例预览：${pages.size} 页（当前参数）"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTypesetting = false, errorMessage = "示例预览失败：${e.message}")
                }
            }
        }
    }

    private fun buildSampleDocument(): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()
        blocks.add(Block.Heading(1, listOf(TextRun("排版示例预览"))))
        blocks.add(
            Block.Paragraph(
                listOf(
                    TextRun(
                        "以下文字用于实时预览当前排版参数（页面尺寸、边距、字号、行距、对齐方式与段间距）。" +
                            "它是内置示例，无需导入文件即可查看效果。"
                    )
                )
            )
        )
        val paragraphs = SAMPLE_TEXT.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        paragraphs.forEach { p ->
            blocks.add(Block.Paragraph(listOf(TextRun(p))))
        }
        toc.add(TocEntry("排版示例预览", 1, 0))
        return Document(blocks, toc)
    }

    private val SAMPLE_TEXT = """
        故乡（节选）
        我冒了严寒，回到相隔二千余里，别了二十余年的故乡去。时候既然是深秋，渐近故乡时，天气又阴晦了，冷风吹进船舱中，呜呜的响，从篷隙向外一望，苍黄的天底下，远近横着几个萧索的荒村，没有一些活气。
        阿！这不是我二十年来时时记得的故乡？
        我所记得的故乡全不如此。我的故乡好得多了。但要我记起他的美丽，说出他的佳处来，却又没有影像，没有言辞了。仿佛也就如此。于是我自己解释说：故乡本也如此，——虽然没有进步，也未必有如我所感的悲凉，这只是我自己心情的改变罢了，因为我这次回乡，本没有什么好心绪。
        The quick brown fox jumps over the lazy dog. 中英文混排时，English words should sit naturally among 汉字，既不过分拥挤也不过分松散。
        我这次是专为了别他而来的。我们多年聚族而居的老屋，已经公同卖给别姓了，交屋的期限，只在本年，所以必须赶在正月初一以前，永别了熟识的老屋，而且远离了熟识的故乡，搬家到我在谋食的异地去。
        In the middle of the journey, we found a quiet inn. 旅途中的小店总能让人想起家的味道，a sense of belonging that transcends language and borders.
        他出去了；母亲和我都叹息他的景况：多子，饥荒，苛税，兵，匪，官，绅，都苦得他像一个木偶人了。母亲对我说，凡是不必搬走的东西，尽可以送他，可以听他自己去拣择。
        雪，是早已下得大起来了；屋檐上，树枝上，都积了厚厚的一层。Children laughed and ran outside, their voices mixing with the crunch of snow underfoot. 孩子们的笑声和脚下积雪的咯吱声混在一起。
        第二天清晨我到了我家的门口了。瓦楞上许多枯草的断茎当风抖着，正说明这老屋难免易主的原因。几房的本家大约已经搬走了，所以很寂静。
        We packed the last of our belongings into wooden crates. 我们把最后一点家当装进木箱，准备迎接一段全然陌生的旅程，a journey that would reshape everything we thought we knew.
        时候既然是深秋，渐近故乡时，天气又阴晦了。但我知道，无论走到哪里，故乡的影子总会悄悄跟在身后，like a shadow that never quite leaves.
        这来的便是闰土。虽然我一见便知道是闰土，但又不是我这记忆上的闰土了。他身材增加了一倍；先前的紫色的圆脸，已经变作灰黄，而且加上了很深的皱纹；眼睛也像他父亲一样，周围都肿得通红，这我知道，在海边种地的人，终日吹着海风，大抵是这样的。
    """.trimIndent()

    // --- PDF 转换（输出到源文件同目录） ---

    fun convertAll() {
        val state = _uiState.value
        if (state.files.isEmpty()) return
        if (!fontManager.hasFont()) {
            _uiState.update { it.copy(errorMessage = "尚未导入字体，请先导入字体再转换") }
            return
        }

        _uiState.update {
            it.copy(isConverting = true, conversionProgress = 0f, conversionResults = emptyList(), errorMessage = null)
        }
        presetRepo.saveLastParams(state.params)

        viewModelScope.launch(Dispatchers.Default) {
            val results = mutableListOf<ConversionResult>()
            val fontInfo = fontManager.getCurrentFontInfo()!!
            val params = state.params
            val app = getApplication<Application>()
            val ctx = ParseContext(
                stripHeadersFooters = params.stripHeadersFooters,
                imageWrapMode = params.imageWrapMode
            )

            for ((index, fileItem) in state.files.withIndex()) {
                try {
                    val bytes = readUriBytes(fileItem.uri)
                    val document = DocumentParser.parse(fileItem.name, bytes, ctx, imageResolver)

                    val measurer = TextMeasurer(fontInfo.font, fontInfo.boldFont)
                    val engine = TypesettingEngine(measurer, LineBreaker(measurer), Paginator())
                    val pages = engine.typeset(document, params)

                    val bookmarks = createBookmarks(document, pages, params)

                    // 先生成到缓存临时文件
                    val tempFile = File(app.cacheDir, "out_${System.currentTimeMillis()}_$index.pdf")
                    val generated = pdfGenerator.generate(pages, bookmarks, tempFile)

                    if (generated && tempFile.exists()) {
                        val outName = fileItem.name.substringBeforeLast('.') + ".pdf"
                        val outUri = resolveOutputUri(fileItem.uri, outName)
                        if (outUri != null) {
                            var written = false
                            try {
                                app.contentResolver.openOutputStream(outUri)?.use { os ->
                                    tempFile.inputStream().use { it.copyTo(os) }
                                }
                                written = true
                                results.add(
                                    ConversionResult(
                                        fileName = fileItem.name,
                                        success = true,
                                        outputPath = outName,
                                        pageCount = pages.size
                                    )
                                )
                            } catch (e: Exception) {
                                // 同目录写入失败，回退到让用户选择位置
                                results.add(
                                    ConversionResult(
                                        fileName = fileItem.name, success = true,
                                        outputPath = "请在弹窗中选择保存位置", pageCount = pages.size
                                    )
                                )
                                _uiState.update {
                                    it.copy(pendingSaves = it.pendingSaves +
                                        PendingSave(tempFile.absolutePath, outName, fileItem.name))
                                }
                            }
                            if (written) tempFile.delete()
                        } else {
                            // 无法解析同目录 Uri，回退
                            results.add(
                                ConversionResult(
                                    fileName = fileItem.name, success = true,
                                    outputPath = "请在弹窗中选择保存位置", pageCount = pages.size
                                )
                            )
                            _uiState.update {
                                it.copy(pendingSaves = it.pendingSaves +
                                    PendingSave(tempFile.absolutePath, outName, fileItem.name))
                            }
                        }
                    } else {
                        results.add(
                            ConversionResult(
                                fileName = fileItem.name, success = false,
                                error = "PDF 生成失败"
                            )
                        )
                    }
                } catch (e: Exception) {
                    results.add(
                        ConversionResult(fileName = fileItem.name, success = false, error = e.message)
                    )
                }

                _uiState.update {
                    it.copy(conversionProgress = (index + 1).toFloat() / state.files.size)
                }
            }

            _uiState.update {
                it.copy(isConverting = false, conversionResults = results)
            }
        }
    }

    /** 用户在“保存位置”弹窗中取消，丢弃待保存的临时文件 */
    fun cancelPendingSave() {
        val pending = _uiState.value.pendingSaves.firstOrNull() ?: return
        try { File(pending.tempFilePath).delete() } catch (_: Exception) {}
        _uiState.update { it.copy(pendingSaves = it.pendingSaves.drop(1)) }
    }

    /** 用户在“保存位置”弹窗中选择后，把临时文件写入所选 Uri */
    fun resolvePendingSave(uri: Uri) {
        val pending = _uiState.value.pendingSaves.firstOrNull() ?: return
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { os ->
                File(pending.tempFilePath).inputStream().use { it.copyTo(os) }
            }
            File(pending.tempFilePath).delete()
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "保存失败：${e.message}") }
        }
        _uiState.update { it.copy(pendingSaves = it.pendingSaves.drop(1)) }
    }

    /** 在源文件所在目录创建同名 .pdf 的输出 Uri；失败返回 null（回退到让用户选择） */
    private fun resolveOutputUri(sourceUri: Uri, suggestedName: String): Uri? {
        return try {
            val authority = sourceUri.authority ?: return null
            val docId = DocumentsContract.getDocumentId(sourceUri)
            val slash = docId.lastIndexOf('/')
            val parentId = if (slash > 0) docId.substring(0, slash) else docId
            val treeUri = DocumentsContract.buildTreeDocumentUri(authority, parentId)
            DocumentsContract.createDocument(
                getApplication<Application>().contentResolver,
                treeUri,
                "application/pdf",
                suggestedName
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun createBookmarks(
        document: Document,
        pages: List<PageLayout>,
        params: TypesettingParams
    ): List<PdfGenerator.Bookmark> {
        if (document.toc.isEmpty()) return emptyList()

        val bookmarks = mutableListOf<PdfGenerator.Bookmark>()
        for (entry in document.toc) {
            val pageIndex = pages.indexOfFirst { page ->
                page.elements.any { el ->
                    val text = when (el) {
                        is com.kindle.converter.data.LayoutElement.TextLine ->
                            el.segments.joinToString("") { it.text }
                        else -> ""
                    }
                    text.contains(entry.title.take(10))
                }
            }
            if (pageIndex >= 0) {
                bookmarks.add(PdfGenerator.Bookmark(entry.title, entry.level, pageIndex))
            }
        }
        return bookmarks
    }

    private fun readUriBytes(uri: Uri): ByteArray {
        return getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: ByteArray(0)
    }

    private fun getFileName(uri: Uri): String {
        val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) return it.getString(nameIndex)
        }
        return uri.lastPathSegment ?: "未知文件"
    }

    private fun getFileSize(uri: Uri): Long {
        val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && it.moveToFirst()) return it.getLong(sizeIndex)
        }
        return 0
    }

    override fun onCleared() {
        fontManager.close()
        super.onCleared()
    }
}
