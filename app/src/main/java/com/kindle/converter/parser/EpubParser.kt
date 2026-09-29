package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 轻量级 EPUB 解析器（零外部重型依赖，基于标准 DOM + ZipInputStream）。
 *
 * EPUB 内部结构：
 * - META-INF/container.xml → 指向 OPF 主清单文件（如 OEBPS/content.opf）
 * - content.opf → manifest（资源列表）、spine（阅读章节顺序）、metadata（书名/作者）
 * - XHTML 章节文件 → 正文与插图
 * - toc.ncx (EPUB 2) 或 nav.xhtml (EPUB 3) → 目录结构
 *
 * 核心增强与修复说明（便于后续维护）：
 * 1. 相对路径规范化（resolvePath）：
 *    EPUB 章节通常位于子目录（如 `OEBPS/Text/chap01.xhtml`），而图片引用多为 `../Images/pic.jpg`。
 *    旧版直接字符串拼接会产生 `OEBPS/Text/../Images/pic.jpg`，无法匹配 ZIP 条目名 `OEBPS/Images/pic.jpg`。
 *    现已支持 `.` / `..` 路径消解、URL 百分号解码（如 `%20` 空格）以及大小写不敏感兜底查找。
 * 2. 全面提取图片（extractImageBlock / collectInlineItems）：
 *    - 支持顶层及容器内的 `<img src="...">`；
 *    - 支持 EPUB 封面常见的 `<svg><image xlink:href="..." href="..."/></svg>` 矢量包装位图；
 *    - 支持内联在 `<p>`、`<div>`、`<a>`、`<span>`、`<figure>` 内的插图，严格保持图文先后顺序。
 * 3. Calibre 等工具生成的 `<div>` 段落兼容：
 *    若容器节点（如 `<div>`）内部不含子块级元素，而是直接混排文本与 `<span>`/`<b>`/`<img>`，
 *    按段落内联流合并提取，避免旧版将同一段拆碎或丢失 `<span>` 文字。
 */
class EpubParser {

    /** 判定 HTML 标签是否为块级容器标签 */
    private val blockTagNames = setOf(
        "p", "div", "section", "article", "main", "header", "footer",
        "figure", "figcaption", "aside", "center", "blockquote", "pre",
        "ul", "ol", "li", "table", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "hr"
    )

    fun parse(bytes: ByteArray): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()
        var title = ""
        var author = ""

        try {
            val entries = unzipAll(bytes)
            // 构建小写键映射，兼容部分 Windows 制作工具生成的 EPUB 内部路径大小写不一致问题
            val entriesLower = entries.entries.associate { (k, v) -> k.lowercase() to v }

            // 1. 通过 META-INF/container.xml 定位 content.opf
            val containerBytes = findEntryBytes(entries, entriesLower, "META-INF/container.xml")
                ?: return Document(blocks)
            val containerDoc = parseXml(containerBytes.toString(Charsets.UTF_8))
            val rootfileEl = getElementsByTagNameLocal(containerDoc, "rootfile").firstOrNull()
            val rawOpfPath = rootfileEl?.getAttribute("full-path")?.takeIf { it.isNotEmpty() }
                ?: return Document(blocks)
            val opfPath = resolvePath("", rawOpfPath)
            val opfDir = opfPath.substringBeforeLast('/', "")

            // 2. 解析 content.opf
            val opfBytes = findEntryBytes(entries, entriesLower, opfPath) ?: return Document(blocks)
            val opfDoc = parseXml(opfBytes.toString(Charsets.UTF_8))

            // 提取元数据（书名、作者）
            title = getMetaContent(opfDoc, "title")
            author = getMetaContent(opfDoc, "creator")

            // 构建 manifest 映射：id → 规范化后的 ZIP 内路径
            val manifest = mutableMapOf<String, String>()
            for (item in getElementsByTagNameLocal(opfDoc, "item")) {
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isNotEmpty() && href.isNotEmpty()) {
                    manifest[id] = resolvePath(opfDir, href)
                }
            }

            // 构建 spine：章节阅读顺序
            val chapterPaths = mutableListOf<String>()
            for (itemref in getElementsByTagNameLocal(opfDoc, "itemref")) {
                val idref = itemref.getAttribute("idref")
                manifest[idref]?.let { chapterPaths.add(it) }
            }

            // 3. 解析目录（NCX 或 EPUB 3 nav）
            parseToc(entries, entriesLower, opfDir, manifest, toc)

            // 4. 按 spine 顺序逐章解析正文与插图
            for (chapterPath in chapterPaths) {
                val chapterBytes = findEntryBytes(entries, entriesLower, chapterPath) ?: continue
                val chapterXml = chapterBytes.toString(Charsets.UTF_8)
                val chapterDoc = try {
                    parseXml(chapterXml)
                } catch (_: Exception) {
                    continue
                }
                val body = getElementsByTagNameLocal(chapterDoc, "body").firstOrNull()
                val chapterDir = chapterPath.substringBeforeLast('/', "")
                if (body != null) {
                    parseHtmlContent(body, blocks, toc, chapterDir, entries, entriesLower)
                }
            }
        } catch (e: Exception) {
            blocks.add(Block.Paragraph(listOf(TextRun("[EPUB parse error: ${e.message}]"))))
        }

        return Document(blocks, toc, title, author)
    }

    private fun parseToc(
        entries: Map<String, ByteArray>,
        entriesLower: Map<String, ByteArray>,
        opfDir: String,
        manifest: Map<String, String>,
        toc: MutableList<TocEntry>
    ) {
        // 优先尝试 EPUB 2 的 .ncx 目录
        val ncxPath = manifest.values.find { it.endsWith(".ncx", ignoreCase = true) }
        if (ncxPath != null) {
            val ncxXml = findEntryBytes(entries, entriesLower, ncxPath)?.toString(Charsets.UTF_8) ?: return
            val ncxDoc = runCatching { parseXml(ncxXml) }.getOrNull() ?: return
            for (navPoint in getElementsByTagNameLocal(ncxDoc, "navPoint")) {
                val label = getElementsByTagNameLocal(navPoint, "text").firstOrNull()?.textContent?.trim()
                val depth = countAncestors(navPoint, "navPoint")
                if (!label.isNullOrEmpty()) {
                    toc.add(TocEntry(label, depth, -1))
                }
            }
            return
        }

        // 回退尝试 EPUB 3 的 nav.xhtml / nav.html
        val navPath = manifest.values.find {
            it.endsWith("nav.xhtml", ignoreCase = true) || it.endsWith("nav.html", ignoreCase = true)
        }
        if (navPath != null) {
            val navXml = findEntryBytes(entries, entriesLower, navPath)?.toString(Charsets.UTF_8) ?: return
            val navDoc = runCatching { parseXml(navXml) }.getOrNull() ?: return
            val navEl = getElementsByTagNameLocal(navDoc, "nav").firstOrNull()
            if (navEl != null) {
                parseNavList(navEl, toc, 0)
            }
        }
    }

    private fun parseNavList(element: Element, toc: MutableList<TocEntry>, depth: Int) {
        var child: Node? = element.firstChild
        while (child != null) {
            if (child is Element && (child.localName ?: child.tagName).lowercase() == "li") {
                val link = getElementsByTagNameLocal(child, "a").firstOrNull()
                val label = link?.textContent?.trim()
                if (!label.isNullOrEmpty()) {
                    toc.add(TocEntry(label, depth, -1))
                }
                val nestedList = getElementsByTagNameLocal(child, "ol").firstOrNull()
                if (nestedList != null) {
                    parseNavList(nestedList, toc, depth + 1)
                }
            }
            child = child.nextSibling
        }
    }

    /**
     * 递归解析章节 DOM 节点，提取标题、段落、引用、代码块、列表及图片。
     */
    private fun parseHtmlContent(
        node: Node,
        blocks: MutableList<Block>,
        toc: MutableList<TocEntry>,
        chapterDir: String,
        entries: Map<String, ByteArray>,
        entriesLower: Map<String, ByteArray>
    ) {
        var child: Node? = node.firstChild
        while (child != null) {
            if (child is Element) {
                val tag = (child.localName ?: child.tagName).lowercase()
                when (tag) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val level = tag.last().digitToInt()
                        val items = collectInlineItems(child)
                        emitInlineItems(
                            items = items,
                            blocks = blocks,
                            chapterDir = chapterDir,
                            entries = entries,
                            entriesLower = entriesLower,
                            blockBuilder = { runs -> Block.Heading(level, runs) }
                        )
                    }

                    "p", "figcaption" -> {
                        val items = collectInlineItems(child)
                        emitInlineItems(
                            items = items,
                            blocks = blocks,
                            chapterDir = chapterDir,
                            entries = entries,
                            entriesLower = entriesLower,
                            blockBuilder = { runs -> Block.Paragraph(runs) }
                        )
                    }

                    "blockquote" -> {
                        if (hasDirectBlockChildren(child)) {
                            parseHtmlContent(child, blocks, toc, chapterDir, entries, entriesLower)
                        } else {
                            val items = collectInlineItems(child)
                            emitInlineItems(
                                items = items,
                                blocks = blocks,
                                chapterDir = chapterDir,
                                entries = entries,
                                entriesLower = entriesLower,
                                blockBuilder = { runs -> Block.QuoteBlock(runs) }
                            )
                        }
                    }

                    "pre" -> {
                        val code = child.textContent
                        if (code.isNotBlank()) {
                            blocks.add(Block.CodeBlock(code.trim()))
                        }
                    }

                    "ul" -> {
                        var li: Node? = child.firstChild
                        while (li != null) {
                            if (li is Element && (li.localName ?: li.tagName).lowercase() == "li") {
                                val items = collectInlineItems(li)
                                emitInlineItems(
                                    items = items,
                                    blocks = blocks,
                                    chapterDir = chapterDir,
                                    entries = entries,
                                    entriesLower = entriesLower,
                                    blockBuilder = { runs -> Block.ListItem(0, ordered = false, runs = runs) }
                                )
                            }
                            li = li.nextSibling
                        }
                    }

                    "ol" -> {
                        var li: Node? = child.firstChild
                        var idx = 1
                        while (li != null) {
                            if (li is Element && (li.localName ?: li.tagName).lowercase() == "li") {
                                val currentIdx = idx
                                val items = collectInlineItems(li)
                                val beforeSize = blocks.size
                                emitInlineItems(
                                    items = items,
                                    blocks = blocks,
                                    chapterDir = chapterDir,
                                    entries = entries,
                                    entriesLower = entriesLower,
                                    blockBuilder = { runs ->
                                        Block.ListItem(0, ordered = true, orderIndex = currentIdx, runs = runs)
                                    }
                                )
                                if (blocks.size > beforeSize) idx++
                            }
                            li = li.nextSibling
                        }
                    }

                    "img", "image" -> {
                        extractImageBlock(child, chapterDir, entries, entriesLower)?.let { blocks.add(it) }
                    }

                    "svg" -> {
                        // EPUB 封面经常采用 <svg><image xlink:href="..."/></svg> 包装位图
                        for (imgEl in getElementsByTagNameLocal(child, "image")) {
                            extractImageBlock(imgEl, chapterDir, entries, entriesLower)?.let { blocks.add(it) }
                        }
                    }

                    "hr" -> {
                        blocks.add(Block.HorizontalRule)
                    }

                    "table" -> {
                        // 提取 HTML 表格为结构化 Block.TableBlock，同时保留单元格内的内嵌图片
                        val trList = getElementsByTagNameLocal(child, "tr")
                        val headers = mutableListOf<List<TextRun>>()
                        val rows = mutableListOf<List<List<TextRun>>>()
                        val tableImages = mutableListOf<Block.ImageBlock>()

                        for ((rowIdx, tr) in trList.withIndex()) {
                            val thCells = getDirectCells(tr, "th")
                            val tdCells = getDirectCells(tr, "td")
                            val isHeaderRow = thCells.isNotEmpty() && tdCells.isEmpty() && headers.isEmpty()
                            val cellElements = if (thCells.isNotEmpty()) thCells else tdCells
                            if (cellElements.isEmpty()) continue

                            val rowRuns = cellElements.map { cellEl ->
                                val items = collectInlineItems(cellEl, parentBold = isHeaderRow || rowIdx == 0 && thCells.isNotEmpty())
                                val textRuns = mutableListOf<TextRun>()
                                for (item in items) {
                                    when (item) {
                                        is InlineItem.Text -> textRuns.add(item.run)
                                        is InlineItem.ImageNode -> {
                                            extractImageBlock(item.element, chapterDir, entries, entriesLower)
                                                ?.let { tableImages.add(it) }
                                        }
                                    }
                                }
                                trimTextRuns(textRuns)
                            }

                            if (isHeaderRow) {
                                headers.addAll(rowRuns)
                            } else {
                                rows.add(rowRuns)
                            }
                        }

                        val maxCols = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
                        if (maxCols > 0 && (headers.isNotEmpty() || rows.isNotEmpty())) {
                            val normHeaders = if (headers.isNotEmpty()) {
                                List(maxCols) { headers.getOrNull(it) ?: emptyList() }
                            } else emptyList()
                            val normRows = rows.map { r -> List(maxCols) { r.getOrNull(it) ?: emptyList() } }
                            blocks.add(Block.TableBlock(normHeaders, normRows))
                        }
                        blocks.addAll(tableImages)
                    }

                    "div", "section", "article", "main", "header", "footer", "figure", "aside", "center" -> {
                        // 如果容器包含子块级元素，则递归遍历；若仅含内联文本/图片（如 Calibre 的 <div class="calibre1">），
                        // 则直接作为整段内联流处理，防止把一段话拆碎或漏掉 <span> 文字。
                        if (hasDirectBlockChildren(child)) {
                            parseHtmlContent(child, blocks, toc, chapterDir, entries, entriesLower)
                        } else {
                            val items = collectInlineItems(child)
                            emitInlineItems(
                                items = items,
                                blocks = blocks,
                                chapterDir = chapterDir,
                                entries = entries,
                                entriesLower = entriesLower,
                                blockBuilder = { runs -> Block.Paragraph(runs) }
                            )
                        }
                    }

                    else -> {
                        // 顶层直接出现的内联标签（如 <a> / <span> 包裹的 <img> 或文字）
                        val items = collectInlineItems(child)
                        emitInlineItems(
                            items = items,
                            blocks = blocks,
                            chapterDir = chapterDir,
                            entries = entries,
                            entriesLower = entriesLower,
                            blockBuilder = { runs -> Block.Paragraph(runs) }
                        )
                    }
                }
            } else if (child.nodeType == Node.TEXT_NODE) {
                val text = child.textContent.trim()
                if (text.isNotEmpty()) {
                    blocks.add(Block.Paragraph(listOf(TextRun(text))))
                }
            }
            child = child.nextSibling
        }
    }

    /** 检查容器节点是否包含子块级元素（或独立 svg 容器） */
    private fun hasDirectBlockChildren(element: Element): Boolean {
        var child: Node? = element.firstChild
        while (child != null) {
            if (child is Element) {
                val tag = (child.localName ?: child.tagName).lowercase()
                if (tag in blockTagNames || tag == "svg") return true
            }
            child = child.nextSibling
        }
        return false
    }

    /** 提取 `<tr>` 下的直接单元格子节点（`<th>` 或 `<td>`） */
    private fun getDirectCells(tr: Element, cellTag: String): List<Element> {
        val cells = mutableListOf<Element>()
        var child: Node? = tr.firstChild
        while (child != null) {
            if (child is Element && (child.localName ?: child.tagName).lowercase() == cellTag) {
                cells.add(child)
            }
            child = child.nextSibling
        }
        return cells
    }

    /** 表示内联流中的文字片段或内嵌图片元素 */
    private sealed class InlineItem {
        data class Text(val run: TextRun) : InlineItem()
        data class ImageNode(val element: Element) : InlineItem()
    }

    /**
     * 从 HTML 元素中按文档顺序收集内联文字（保留粗体/斜体样式）与内嵌图片（<img> / <image>）。
     */
    private fun collectInlineItems(
        element: Element,
        parentBold: Boolean = false,
        parentItalic: Boolean = false
    ): List<InlineItem> {
        val items = mutableListOf<InlineItem>()
        val buf = StringBuilder()

        fun flushText() {
            if (buf.isNotEmpty()) {
                val normalized = buf.toString()
                    .replace(Regex("[ \\t\\r\\n]+"), " ")
                if (normalized.isNotEmpty()) {
                    items.add(InlineItem.Text(TextRun(normalized, parentBold, parentItalic)))
                }
                buf.clear()
            }
        }

        var child: Node? = element.firstChild
        while (child != null) {
            when {
                child.nodeType == Node.TEXT_NODE -> {
                    buf.append(child.textContent)
                }
                child is Element -> {
                    val tag = (child.localName ?: child.tagName).lowercase()
                    when (tag) {
                        "b", "strong" -> {
                            flushText()
                            items.addAll(collectInlineItems(child, true, parentItalic))
                        }
                        "i", "em" -> {
                            flushText()
                            items.addAll(collectInlineItems(child, parentBold, true))
                        }
                        "br" -> {
                            flushText()
                            items.add(InlineItem.Text(TextRun("\n", parentBold, parentItalic)))
                        }
                        "img", "image" -> {
                            flushText()
                            items.add(InlineItem.ImageNode(child))
                        }
                        "svg" -> {
                            flushText()
                            for (imgEl in getElementsByTagNameLocal(child, "image")) {
                                items.add(InlineItem.ImageNode(imgEl))
                            }
                        }
                        "code" -> {
                            flushText()
                            val codeText = child.textContent
                            if (codeText.isNotEmpty()) {
                                items.add(InlineItem.Text(TextRun(codeText, parentBold, parentItalic)))
                            }
                        }
                        "rt", "rp" -> {
                            // 忽略日文/中文注音 <ruby> 中的注音小字 <rt>/<rp>，避免与正文汉字粘连重复
                        }
                        else -> {
                            flushText()
                            items.addAll(collectInlineItems(child, parentBold, parentItalic))
                        }
                    }
                }
            }
            child = child.nextSibling
        }
        flushText()
        return items
    }

    /**
     * 将收集到的 [InlineItem] 序列按顺序输出为文本块与 [Block.ImageBlock]。
     */
    private fun emitInlineItems(
        items: List<InlineItem>,
        blocks: MutableList<Block>,
        chapterDir: String,
        entries: Map<String, ByteArray>,
        entriesLower: Map<String, ByteArray>,
        blockBuilder: (List<TextRun>) -> Block
    ) {
        val currentRuns = mutableListOf<TextRun>()

        fun flushRuns() {
            if (currentRuns.isEmpty()) return
            // 清理首尾空白字符（保留段内换行与空格）
            val trimmed = trimTextRuns(currentRuns)
            currentRuns.clear()
            if (trimmed.isNotEmpty()) {
                blocks.add(blockBuilder(trimmed))
            }
        }

        for (item in items) {
            when (item) {
                is InlineItem.Text -> currentRuns.add(item.run)
                is InlineItem.ImageNode -> {
                    flushRuns()
                    extractImageBlock(item.element, chapterDir, entries, entriesLower)?.let {
                        blocks.add(it)
                    }
                }
            }
        }
        flushRuns()
    }

    /** 去除一组 TextRun 开头与结尾的多余空格 */
    private fun trimTextRuns(runs: List<TextRun>): List<TextRun> {
        if (runs.isEmpty()) return emptyList()
        val result = runs.toMutableList()
        while (result.isNotEmpty()) {
            val first = result.first()
            val trimmed = first.text.trimStart(' ', '\t', '\r', '\n', '\u3000')
            if (trimmed.isEmpty()) {
                result.removeAt(0)
            } else {
                result[0] = first.copy(text = trimmed)
                break
            }
        }
        while (result.isNotEmpty()) {
            val lastIdx = result.lastIndex
            val last = result[lastIdx]
            val trimmed = last.text.trimEnd(' ', '\t', '\r', '\n', '\u3000')
            if (trimmed.isEmpty()) {
                result.removeAt(lastIdx)
            } else {
                result[lastIdx] = last.copy(text = trimmed)
                break
            }
        }
        return result
    }

    /**
     * 从 `<img>` 或 SVG `<image>` 元素提取图片并构造 [Block.ImageBlock]。
     * 兼容 `src`、`xlink:href`、`href`、`data-src` 等属性写法。
     */
    private fun extractImageBlock(
        imgElement: Element,
        chapterDir: String,
        entries: Map<String, ByteArray>,
        entriesLower: Map<String, ByteArray>
    ): Block.ImageBlock? {
        val rawSrc = getImageSourceAttr(imgElement) ?: return null
        if (rawSrc.isBlank() || rawSrc.startsWith("data:", ignoreCase = true)) return null
        val resolvedPath = resolvePath(chapterDir, rawSrc)
        val imgBytes = findEntryBytes(entries, entriesLower, resolvedPath) ?: return null
        return ImageDownloader.decodeImageBlock(imgBytes, resolvedPath)
    }

    private fun getImageSourceAttr(el: Element): String? {
        val candidates = listOf("src", "xlink:href", "href", "data-src")
        for (attr in candidates) {
            val v = el.getAttribute(attr)
            if (!v.isNullOrBlank()) return v
        }
        // Namespace-aware 模式下 xlink:href 的 localName 为 href
        val attrs = el.attributes
        if (attrs != null) {
            for (i in 0 until attrs.length) {
                val item = attrs.item(i)
                val name = (item.localName ?: item.nodeName).lowercase()
                if (name == "src" || name == "href") {
                    val v = item.nodeValue
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        return null
    }

    // --- Utilities ---

    private fun unzipAll(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val normalizedKey = entry.name.replace('\\', '/').trimStart('/')
                entries[normalizedKey] = zip.readBytes()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return entries
    }

    /**
     * 规范化解析 EPUB 内部相对路径：
     * 1. 剥离 URL 锚点（`#...`）与查询参数（`?...`）；
     * 2. 进行 URL 百分号解码（如将 `%20` 还原为空格）；
     * 3. 处理 `.` 与 `..` 相对层级，生成与 ZIP 条目一致的标准路径。
     */
    private fun resolvePath(base: String, href: String): String {
        val cleanHref = href.substringBefore('#').substringBefore('?').trim()
        if (cleanHref.isEmpty()) return base
        val decodedHref = try {
            URLDecoder.decode(cleanHref, "UTF-8")
        } catch (_: Exception) {
            cleanHref
        }.replace('\\', '/')

        val combined = when {
            decodedHref.startsWith("/") -> decodedHref.trimStart('/')
            base.isEmpty() -> decodedHref
            else -> "$base/$decodedHref"
        }

        val stack = mutableListOf<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    /**
     * 在解压出的 ZIP 字典中查找目标文件：
     * 优先精确匹配，其次大小写不敏感匹配，最后按文件名后缀兜底匹配（应对部分非标准 EPUB 路径层级偏差）。
     */
    private fun findEntryBytes(
        entries: Map<String, ByteArray>,
        entriesLower: Map<String, ByteArray>,
        path: String
    ): ByteArray? {
        val normalized = path.replace('\\', '/').trimStart('/')
        entries[normalized]?.let { return it }
        val lower = normalized.lowercase()
        entriesLower[lower]?.let { return it }

        val fileName = lower.substringAfterLast('/')
        if (fileName.isNotEmpty()) {
            val suffixMatch = entriesLower.entries.firstOrNull { (k, _) ->
                k == fileName || k.endsWith("/$fileName")
            }
            if (suffixMatch != null) return suffixMatch.value
        }
        return null
    }

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // 禁用外部 DTD 加载，防止 XHTML 声明 <!DOCTYPE html ...> 触发网络请求或解析失败
        runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        return factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
    }

    private fun getElementsByTagNameLocal(doc: org.w3c.dom.Document, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = doc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = (el.localName ?: el.tagName).lowercase()
            if (name == localName.lowercase() || name.endsWith(":${localName.lowercase()}")) {
                result.add(el)
            }
        }
        return result
    }

    private fun getElementsByTagNameLocal(parent: Element, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = parent.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = (el.localName ?: el.tagName).lowercase()
            if (name == localName.lowercase() || name.endsWith(":${localName.lowercase()}")) {
                result.add(el)
            }
        }
        return result
    }

    private fun getMetaContent(doc: org.w3c.dom.Document, tagLocalName: String): String {
        for (el in getElementsByTagNameLocal(doc, tagLocalName)) {
            val text = el.textContent.trim()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun countAncestors(node: Node, ancestorLocalName: String): Int {
        var depth = 0
        var parent: Node? = node.parentNode
        while (parent != null) {
            if (parent is Element) {
                val name = (parent.localName ?: parent.tagName).lowercase()
                if (name == ancestorLocalName.lowercase()) {
                    depth++
                }
            }
            parent = parent.parentNode
        }
        return depth
    }
}

