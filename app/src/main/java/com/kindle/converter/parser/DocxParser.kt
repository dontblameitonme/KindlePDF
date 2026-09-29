package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 轻量级 DOCX 解析器（零 Apache POI 依赖，直接解析 OOXML ZIP 结构）。
 *
 * DOCX 内部结构与解析流程：
 * 1. 解压读取：
 *    - `word/document.xml`：正文段落、标题、列表、表格及内嵌图片引用；
 *    - `word/_rels/document.xml.rels`：关系映射表（如 `rId7 -> media/image1.png`）；
 *    - `word/media/`：内嵌位图资源（PNG/JPEG/GIF/WebP 等）；
 *    - `docProps/core.xml`：文档标题与作者元数据。
 * 2. 遍历 `<w:body>` 下的 `<w:p>`（段落）与 `<w:tbl>`（表格），按阅读顺序提取文本与图片：
 *    - 识别 `<w:pStyle>` / `<w:outlineLvl>` 判定 1~6 级标题；
 *    - 识别 `<w:drawing>` 下的 `<a:blip r:embed="rId..."/>`（DrawingML 现代内嵌图）
 *      以及 `<w:pict>` 下的 `<v:imagedata r:id="rId..."/>`（VML 兼容模式内嵌图），
 *      通过关系表定位 `word/media/` 字节流并解码为 [Block.ImageBlock]。
 */
class DocxParser {

    fun parse(bytes: ByteArray): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()
        var title = ""
        var author = ""

        try {
            val entries = mutableMapOf<String, ByteArray>()
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                    entries[normalizedName] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }

            val documentXml = entries["word/document.xml"]?.toString(Charsets.UTF_8)
            val coreXml = entries["docProps/core.xml"]?.toString(Charsets.UTF_8)
            val relsXml = entries["word/_rels/document.xml.rels"]?.toString(Charsets.UTF_8)

            // 1. 提取文档元数据（标题、作者）
            if (coreXml != null) {
                runCatching {
                    val coreDoc = parseXml(coreXml)
                    title = getTagText(coreDoc, "dc:title").ifEmpty { getTagTextByLocal(coreDoc, "title") }
                    author = getTagText(coreDoc, "dc:creator").ifEmpty { getTagTextByLocal(coreDoc, "creator") }
                }
            }

            // 2. 解析 word/_rels/document.xml.rels，建立 rId -> ZIP 内部规范化路径映射
            val rels = if (relsXml != null) parseRelationships(relsXml) else emptyMap()

            // 3. 解析正文 word/document.xml
            if (documentXml != null) {
                val doc = parseXml(documentXml)
                val body = getElementsByTagNameLocal(doc, "body").firstOrNull()
                if (body != null) {
                    parseBodyElements(body, rels, entries, blocks, toc)
                }
            }
        } catch (e: Exception) {
            blocks.add(Block.Paragraph(listOf(TextRun("[DOCX parse error: ${e.message}]"))))
        }

        return Document(blocks, toc, title, author)
    }

    /**
     * 解析 `word/_rels/document.xml.rels` 中的 `<Relationship Id="rId..." Target="media/image1.png"/>`。
     */
    private fun parseRelationships(relsXml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val doc = runCatching { parseXml(relsXml) }.getOrNull() ?: return map
        for (rel in getElementsByTagNameLocal(doc, "Relationship")) {
            val id = rel.getAttribute("Id").takeIf { it.isNotEmpty() } ?: continue
            val target = rel.getAttribute("Target").takeIf { it.isNotEmpty() } ?: continue
            val targetMode = rel.getAttribute("TargetMode")
            if (targetMode.equals("External", ignoreCase = true)) continue
            map[id] = resolveWordRelativePath(target)
        }
        return map
    }

    /**
     * 将 `document.xml.rels` 中的相对路径（通常相对于 `word/` 目录）规范化为 ZIP 根路径。
     */
    private fun resolveWordRelativePath(target: String): String {
        val cleaned = target.replace('\\', '/').trim()
        val combined = if (cleaned.startsWith("/")) {
            cleaned.trimStart('/')
        } else if (cleaned.startsWith("word/")) {
            cleaned
        } else {
            "word/$cleaned"
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
     * 遍历 `<w:body>` 或容器节点下的段落（`<w:p>`）与表格（`<w:tbl>`）。
     */
    private fun parseBodyElements(
        container: Element,
        rels: Map<String, String>,
        entries: Map<String, ByteArray>,
        blocks: MutableList<Block>,
        toc: MutableList<TocEntry>
    ) {
        var child: Node? = container.firstChild
        while (child != null) {
            if (child is Element) {
                when (child.localName ?: child.tagName) {
                    "p", "w:p" -> {
                        val emitted = parseParagraphBlocks(child, rels, entries)
                        for (block in emitted) {
                            blocks.add(block)
                            if (block is Block.Heading) {
                                val headingText = block.runs.joinToString("") { it.text }.trim()
                                if (headingText.isNotEmpty()) {
                                    toc.add(TocEntry(headingText, block.level, blocks.lastIndex))
                                }
                            }
                        }
                    }
                    "tbl", "w:tbl" -> {
                        parseTableBlocks(child, rels, entries, blocks)
                    }
                    "sdt", "w:sdt" -> {
                        // 结构化文档标签（目录/内容控件）内部包含 sdtContent
                        val sdtContent = getFirstChildByLocalName(child, "sdtContent")
                        if (sdtContent != null) {
                            parseBodyElements(sdtContent, rels, entries, blocks, toc)
                        }
                    }
                }
            }
            child = child.nextSibling
        }
    }

    /**
     * 将 DOCX `<w:tbl>` 解析为结构化 [Block.TableBlock]，同时保留单元格内的内嵌图片。
     */
    private fun parseTableBlocks(
        tbl: Element,
        rels: Map<String, String>,
        entries: Map<String, ByteArray>,
        blocks: MutableList<Block>
    ) {
        val allRows = mutableListOf<List<List<TextRun>>>()
        val trailingImages = mutableListOf<Block.ImageBlock>()

        var trNode: Node? = tbl.firstChild
        while (trNode != null) {
            if (trNode is Element && (trNode.localName ?: trNode.tagName).endsWith("tr")) {
                val rowCells = mutableListOf<List<TextRun>>()
                var tcNode: Node? = trNode.firstChild
                while (tcNode != null) {
                    if (tcNode is Element && (tcNode.localName ?: tcNode.tagName).endsWith("tc")) {
                        val cellRuns = mutableListOf<TextRun>()
                        val paragraphs = getElementsByTagNameLocal(tcNode, "p")
                        for ((pIdx, p) in paragraphs.withIndex()) {
                            val items = collectParagraphItems(p)
                            for (item in items) {
                                when (item) {
                                    is ParagraphItem.Text -> cellRuns.add(item.run)
                                    is ParagraphItem.ImageRel -> {
                                        val targetPath = rels[item.rId] ?: continue
                                        val imgBytes = entries[targetPath]
                                            ?: entries.entries.firstOrNull { it.key.equals(targetPath, ignoreCase = true) }?.value
                                            ?: continue
                                        ImageDownloader.decodeImageBlock(imgBytes, targetPath)
                                            ?.let { trailingImages.add(it) }
                                    }
                                }
                            }
                            if (pIdx < paragraphs.lastIndex && cellRuns.isNotEmpty()) {
                                cellRuns.add(TextRun("\n"))
                            }
                        }
                        rowCells.add(cellRuns)
                    }
                    tcNode = tcNode.nextSibling
                }
                if (rowCells.isNotEmpty()) {
                    allRows.add(rowCells)
                }
            }
            trNode = trNode.nextSibling
        }

        val maxCols = allRows.maxOfOrNull { it.size } ?: 0
        if (maxCols > 0 && allRows.isNotEmpty()) {
            val headers = List(maxCols) { colIdx ->
                (allRows.first().getOrNull(colIdx) ?: emptyList()).map { it.copy(bold = true) }
            }
            val dataRows = allRows.drop(1).map { r ->
                List(maxCols) { colIdx -> r.getOrNull(colIdx) ?: emptyList() }
            }
            blocks.add(Block.TableBlock(headers = headers, rows = dataRows))
        }
        blocks.addAll(trailingImages)
    }

    /**
     * 表示段落内的内联项：文本 Run 或内嵌图片引用 ID。
     */
    private sealed class ParagraphItem {
        data class Text(val run: TextRun) : ParagraphItem()
        data class ImageRel(val rId: String) : ParagraphItem()
    }

    /**
     * 解析单个 `<w:p>` 段落，返回按出现顺序排列的 [Block] 列表（支持纯文本段、纯图片段、图文混排段）。
     */
    private fun parseParagraphBlocks(
        p: Element,
        rels: Map<String, String>,
        entries: Map<String, ByteArray>
    ): List<Block> {
        val pPr = getFirstChildByLocalName(p, "pPr")
        val styleEl = pPr?.let { getFirstChildByLocalName(it, "pStyle") }
        val styleId = styleEl?.let { getAttrByLocalName(it, "val") }
        val outlineEl = pPr?.let { getFirstChildByLocalName(it, "outlineLvl") }
        val outlineVal = outlineEl?.let { getAttrByLocalName(it, "val")?.toIntOrNull() }
        val numPr = pPr?.let { getFirstChildByLocalName(it, "numPr") }

        // 判定标题层级：支持英文 Heading1~6、中文 "标题 1"~6 以及大纲级别 outlineLvl (0..5)
        val headingLevel: Int? = when {
            styleId != null -> {
                val m = Regex("(?i)(?:heading|标题)\\s*([1-6])").find(styleId)
                m?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (outlineVal != null && outlineVal in 0..5) outlineVal + 1 else null
            }
            outlineVal != null && outlineVal in 0..5 -> outlineVal + 1
            else -> null
        }

        val isList = numPr != null || (styleId != null && styleId.lowercase().contains("list"))

        val items = collectParagraphItems(p)
        if (items.isEmpty()) return emptyList()

        val result = mutableListOf<Block>()
        val currentRuns = mutableListOf<TextRun>()

        fun flushTextRuns() {
            if (currentRuns.isEmpty()) return
            val combinedText = currentRuns.joinToString("") { it.text }
            if (combinedText.isNotBlank()) {
                val block = when {
                    headingLevel != null -> Block.Heading(headingLevel, currentRuns.toList())
                    isList -> Block.ListItem(0, ordered = false, runs = currentRuns.toList())
                    else -> Block.Paragraph(currentRuns.toList())
                }
                result.add(block)
            }
            currentRuns.clear()
        }

        for (item in items) {
            when (item) {
                is ParagraphItem.Text -> currentRuns.add(item.run)
                is ParagraphItem.ImageRel -> {
                    flushTextRuns()
                    val targetPath = rels[item.rId] ?: continue
                    val imgBytes = entries[targetPath]
                        ?: entries.entries.firstOrNull { it.key.equals(targetPath, ignoreCase = true) }?.value
                        ?: continue
                    ImageDownloader.decodeImageBlock(imgBytes, targetPath)?.let { result.add(it) }
                }
            }
        }
        flushTextRuns()

        return result
    }

    /**
     * 遍历段落子节点（含 `<w:r>`、`<w:hyperlink>`、`<w:ins>` 等），收集文本与图片关系 ID。
     */
    private fun collectParagraphItems(parent: Element): List<ParagraphItem> {
        val items = mutableListOf<ParagraphItem>()
        var child: Node? = parent.firstChild
        while (child != null) {
            if (child is Element) {
                val local = child.localName ?: child.tagName
                when (local) {
                    "r", "w:r" -> items.addAll(parseRunItems(child))
                    "hyperlink", "w:hyperlink", "ins", "w:ins", "smartTag", "w:smartTag" -> {
                        items.addAll(collectParagraphItems(child))
                    }
                    "drawing", "w:drawing", "pict", "w:pict", "object", "w:object" -> {
                        extractImageRelIds(child).forEach { items.add(ParagraphItem.ImageRel(it)) }
                    }
                }
            }
            child = child.nextSibling
        }
        return items
    }

    /**
     * 从单个 `<w:r>` Run 节点提取文本（保留粗体/斜体）及内嵌图片（`<w:drawing>` / `<w:pict>`）。
     */
    private fun parseRunItems(r: Element): List<ParagraphItem> {
        val out = mutableListOf<ParagraphItem>()
        val text = StringBuilder()
        var bold = false
        var italic = false

        val rPr = getFirstChildByLocalName(r, "rPr")
        if (rPr != null) {
            if (isTogglePropertyEnabled(rPr, "b") || isTogglePropertyEnabled(rPr, "bCs")) bold = true
            if (isTogglePropertyEnabled(rPr, "i") || isTogglePropertyEnabled(rPr, "iCs")) italic = true
        }

        fun flushText() {
            if (text.isNotEmpty()) {
                out.add(ParagraphItem.Text(TextRun(text.toString(), bold, italic)))
                text.clear()
            }
        }

        var child: Node? = r.firstChild
        while (child != null) {
            if (child is Element) {
                when (child.localName ?: child.tagName) {
                    "t", "w:t" -> text.append(child.textContent)
                    "tab", "w:tab" -> text.append('\t')
                    "br", "w:br", "cr", "w:cr" -> text.append('\n')
                    "drawing", "w:drawing", "pict", "w:pict", "object", "w:object" -> {
                        flushText()
                        for (rId in extractImageRelIds(child)) {
                            out.add(ParagraphItem.ImageRel(rId))
                        }
                    }
                }
            }
            child = child.nextSibling
        }
        flushText()
        return out
    }

    /**
     * 检查 `<w:b>` / `<w:i>` 等开关属性是否启用（排除 `<w:b w:val="0"/>` 或 `"false"`）。
     */
    private fun isTogglePropertyEnabled(rPr: Element, propLocalName: String): Boolean {
        val el = getFirstChildByLocalName(rPr, propLocalName) ?: return false
        val v = getAttrByLocalName(el, "val")?.lowercase()
        return v == null || (v != "0" && v != "false" && v != "off")
    }

    /**
     * 从 `<w:drawing>` 或 `<w:pict>` 节点中递归提取所有图片关系 ID：
     * - DrawingML：`<a:blip r:embed="rId..."/>`
     * - VML 兼容图形：`<v:imagedata r:id="rId..."/>`
     */
    private fun extractImageRelIds(drawingOrPict: Element): List<String> {
        val ids = mutableListOf<String>()
        for (blip in getElementsByTagNameLocal(drawingOrPict, "blip")) {
            val embedId = getAttrByLocalName(blip, "embed") ?: getAttrByLocalName(blip, "link")
            if (!embedId.isNullOrBlank()) ids.add(embedId)
        }
        for (imgData in getElementsByTagNameLocal(drawingOrPict, "imagedata")) {
            val relId = getAttrByLocalName(imgData, "id") ?: getAttrByLocalName(imgData, "href")
            if (!relId.isNullOrBlank()) ids.add(relId)
        }
        return ids.distinct()
    }

    // --- XML utilities ---

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    }

    private fun getElementsByTagNameLocal(doc: org.w3c.dom.Document, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = doc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.localName ?: el.tagName
            if (name == localName || name.endsWith(":$localName")) result.add(el)
        }
        return result
    }

    private fun getElementsByTagNameLocal(parent: Element, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = parent.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.localName ?: el.tagName
            if (name == localName || name.endsWith(":$localName")) result.add(el)
        }
        return result
    }

    private fun getFirstChildByLocalName(parent: Element, localName: String): Element? {
        var child: Node? = parent.firstChild
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.tagName
                if (name == localName || name.endsWith(":$localName")) return child
            }
            child = child.nextSibling
        }
        return null
    }

    private fun getAttrByLocalName(el: Element, attrLocalName: String): String? {
        val direct = el.getAttribute(attrLocalName)
        if (direct.isNotEmpty()) return direct
        val wPrefixed = el.getAttribute("w:$attrLocalName")
        if (wPrefixed.isNotEmpty()) return wPrefixed
        val rPrefixed = el.getAttribute("r:$attrLocalName")
        if (rPrefixed.isNotEmpty()) return rPrefixed
        val attrs = el.attributes ?: return null
        for (i in 0 until attrs.length) {
            val item = attrs.item(i)
            val name = item.localName ?: item.nodeName.substringAfter(':')
            if (name == attrLocalName && item.nodeValue.isNotEmpty()) {
                return item.nodeValue
            }
        }
        return null
    }

    private fun getTagText(doc: org.w3c.dom.Document, tagName: String): String {
        val nodes = doc.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            return nodes.item(0).textContent.trim()
        }
        return ""
    }

    private fun getTagTextByLocal(doc: org.w3c.dom.Document, localName: String): String {
        return getElementsByTagNameLocal(doc, localName).firstOrNull()?.textContent?.trim() ?: ""
    }
}

