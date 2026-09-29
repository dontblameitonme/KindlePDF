package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Markdown 解析器（基于 CommonMark AST + GFM 管道表格预解析扩展）。
 *
 * 核心特性与针对复杂结构文稿（如科普/教材/指南）的优化说明：
 * 1. GFM 管道表格支持（splitSegmentsWithTables & parseMarkdownTable）：
 *    在不增加外部第三方重依赖的前提下，在围栏代码块（``` / ~~~）之外自动识别标准 GFM 表格
 *    （表头行 `| A | B |` + 对齐分隔行 `| :--- | :--- |` + 数据行），
 *    并对每个单元格内部递归调用 CommonMark 行内解析以保留 `**粗体**`、`*斜体*`、`` `代码` ``，
 *    最终输出结构化的 [Block.TableBlock]。
 * 2. 多级嵌套列表支持（parseListNode）：
 *    递归遍历 `ListItem` 内部嵌套的子级 `BulletList` / `OrderedList`，正确传递 `level = 0, 1, 2...`，
 *    防止旧版 `extractInlines` 将父列表项与所有子列表项拍平粘连到同一段。
 * 3. 多段落/带列表的富文本引用块（parseBlockQuote）：
 *    递归解析 `>` 引用块内部的多个子段落与有序/无序列表，使导读框、公式框、贴士框层次分明。
 * 4. 水平分隔线（ThematicBreak）：
 *    将 `---` / `***` 映射为 [Block.HorizontalRule]，由排版引擎绘制真正的水平细线。
 */
class MarkdownParser {

    private val parser: Parser = Parser.builder().build()

    /** GFM 表格对齐分隔行正则，例如 `| :--- | :---: | ---: |` 或 `--- | ---` */
    private val tableDelimiterRegex = Regex(
        """^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)+\|?\s*$"""
    )

    /**
     * 解析 Markdown 为 Document。
     * @param imageResolver 远程/本地图片解析回调（返回 null 表示跳过该图）。
     */
    suspend fun parse(
        markdown: String,
        imageResolver: suspend (String) -> DownloadedImage? = { null }
    ): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()

        val normalized = markdown.replace("\r\n", "\n").replace("\r", "\n")
        val segments = splitSegmentsWithTables(normalized)

        for (seg in segments) {
            when (seg) {
                is MdSegment.MarkdownText -> {
                    val mdDoc = parser.parse(seg.text)
                    parseBlockChildren(mdDoc, blocks, toc, imageResolver)
                }
                is MdSegment.PipeTable -> {
                    val tableBlock = buildTableBlock(seg.headerLine, seg.bodyLines)
                    if (tableBlock != null) {
                        blocks.add(tableBlock)
                    }
                }
            }
        }

        return Document(blocks, toc)
    }

    // ---- GFM 管道表格预切分与解析 ----

    private sealed class MdSegment {
        data class MarkdownText(val text: String) : MdSegment()
        data class PipeTable(val headerLine: String, val bodyLines: List<String>) : MdSegment()
    }

    /**
     * 在围栏代码块（``` 或 ~~~）之外扫描并切分出 GFM 管道表格，其余部分保持原始 Markdown 文本交给 CommonMark。
     */
    private fun splitSegmentsWithTables(markdown: String): List<MdSegment> {
        val lines = markdown.split('\n')
        val result = mutableListOf<MdSegment>()
        val textBuf = StringBuilder()

        fun flushText() {
            if (textBuf.isNotEmpty()) {
                result.add(MdSegment.MarkdownText(textBuf.toString()))
                textBuf.setLength(0)
            }
        }

        var inFence = false
        var fenceMarker = ""
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            // 检测围栏代码块边界（``` 或 ~~~）
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                if (!inFence) {
                    inFence = true
                    fenceMarker = marker
                } else if (trimmed.startsWith(fenceMarker)) {
                    inFence = false
                    fenceMarker = ""
                }
                textBuf.append(line).append('\n')
                i++
                continue
            }

            // 在非代码块区域检测 GFM 表格（当前行含 '|'，且下一行为表格对齐分隔行）
            if (!inFence && i + 1 < lines.size && isCandidateTableRow(line) && tableDelimiterRegex.matches(lines[i + 1])) {
                flushText()
                val headerLine = line
                val bodyLines = mutableListOf<String>()
                i += 2 // 跳过表头行与分隔行
                while (i < lines.size) {
                    val rowLine = lines[i]
                    val rowTrim = rowLine.trim()
                    if (rowTrim.isEmpty() ||
                        rowTrim.startsWith("```") ||
                        rowTrim.startsWith("~~~") ||
                        rowTrim.startsWith("#") ||
                        rowTrim.startsWith(">") ||
                        !rowTrim.contains('|')
                    ) {
                        break
                    }
                    bodyLines.add(rowLine)
                    i++
                }
                result.add(MdSegment.PipeTable(headerLine, bodyLines))
                continue
            }

            textBuf.append(line).append('\n')
            i++
        }
        flushText()
        return result
    }

    private fun isCandidateTableRow(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith(">") || t.startsWith("#")) return false
        return t.contains('|') && !tableDelimiterRegex.matches(t)
    }

    /**
     * 将表头行与数据行解析为 [Block.TableBlock]，单元格内部支持完整行内富文本（粗体/斜体/行内代码）。
     */
    private fun buildTableBlock(headerLine: String, bodyLines: List<String>): Block.TableBlock? {
        val rawHeaders = splitPipeRow(headerLine)
        if (rawHeaders.isEmpty()) return null
        val colCount = rawHeaders.size

        val headers: List<List<TextRun>> = rawHeaders.map { cellText ->
            parseInlineCellRuns(cellText, defaultBold = true)
        }

        val rows: List<List<List<TextRun>>> = bodyLines.mapNotNull { rowLine ->
            val rawCells = splitPipeRow(rowLine)
            if (rawCells.isEmpty()) return@mapNotNull null
            List(colCount) { colIdx ->
                val cellText = rawCells.getOrNull(colIdx) ?: ""
                parseInlineCellRuns(cellText, defaultBold = false)
            }
        }

        return Block.TableBlock(headers = headers, rows = rows)
    }

    /**
     * 按未转义的 `|` 切分表格行，自动剥离行首与行尾的外框 `|`。
     */
    private fun splitPipeRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)

        val cells = mutableListOf<String>()
        val buf = StringBuilder()
        var k = 0
        while (k < s.length) {
            if (s[k] == '\\' && k + 1 < s.length && s[k + 1] == '|') {
                buf.append('|')
                k += 2
            } else if (s[k] == '|') {
                cells.add(buf.toString().trim())
                buf.setLength(0)
                k++
            } else {
                buf.append(s[k])
                k++
            }
        }
        cells.add(buf.toString().trim())
        return cells
    }

    /**
     * 解析表格单元格内的 Markdown 行内语法（`**bold**`、`*italic*`、`` `code` ``）。
     */
    private fun parseInlineCellRuns(cellMarkdown: String, defaultBold: Boolean): List<TextRun> {
        if (cellMarkdown.isBlank()) return emptyList()
        val doc = parser.parse(cellMarkdown.trim())
        val runs = collectTextRuns(extractInlines(doc, parentBold = defaultBold, parentItalic = false))
        return if (runs.isEmpty()) listOf(TextRun(cellMarkdown.trim(), bold = defaultBold)) else runs
    }

    // ---- 块级 AST 递归遍历 ----

    private suspend fun parseBlockChildren(
        parent: Node,
        blocks: MutableList<Block>,
        toc: MutableList<TocEntry>,
        imageResolver: suspend (String) -> DownloadedImage?
    ) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is Heading -> {
                    val inlines = extractInlines(child)
                    val runs = collectTextRuns(inlines)
                    if (runs.isNotEmpty()) {
                        blocks.add(Block.Heading(child.level, runs))
                        val title = runs.joinToString("") { it.text }
                        toc.add(TocEntry(title, child.level, blocks.lastIndex))
                    }
                }

                is Paragraph -> {
                    emitInlineBlocks(extractInlines(child), blocks, imageResolver)
                }

                is BulletList -> {
                    parseListNode(
                        listNode = child,
                        ordered = false,
                        startNumber = 1,
                        level = 0,
                        blocks = blocks,
                        imageResolver = imageResolver
                    )
                }

                is OrderedList -> {
                    @Suppress("DEPRECATION")
                    val startNum = child.startNumber
                    parseListNode(
                        listNode = child,
                        ordered = true,
                        startNumber = startNum,
                        level = 0,
                        blocks = blocks,
                        imageResolver = imageResolver
                    )
                }

                is BlockQuote -> {
                    parseBlockQuote(child, blocks, imageResolver)
                }

                is FencedCodeBlock -> {
                    blocks.add(Block.CodeBlock(child.literal.trimEnd('\n', '\r'), child.info?.trim()))
                }

                is IndentedCodeBlock -> {
                    blocks.add(Block.CodeBlock(child.literal.trimEnd('\n', '\r'), null))
                }

                is ThematicBreak -> {
                    blocks.add(Block.HorizontalRule)
                }
            }
            child = child.next
        }
    }

    /**
     * 递归解析无序/有序列表，支持多级嵌套子列表（`level = 0, 1, 2...`）。
     */
    private suspend fun parseListNode(
        listNode: Node,
        ordered: Boolean,
        startNumber: Int,
        level: Int,
        blocks: MutableList<Block>,
        imageResolver: suspend (String) -> DownloadedImage?
    ) {
        var li = listNode.firstChild
        var idx = startNumber
        while (li != null) {
            if (li is ListItem) {
                var sub = li.firstChild
                var emittedCurrentItem = false
                while (sub != null) {
                    when (sub) {
                        is BulletList -> {
                            parseListNode(
                                listNode = sub,
                                ordered = false,
                                startNumber = 1,
                                level = level + 1,
                                blocks = blocks,
                                imageResolver = imageResolver
                            )
                        }
                        is OrderedList -> {
                            @Suppress("DEPRECATION")
                            val subStart = sub.startNumber
                            parseListNode(
                                listNode = sub,
                                ordered = true,
                                startNumber = subStart,
                                level = level + 1,
                                blocks = blocks,
                                imageResolver = imageResolver
                            )
                        }
                        is FencedCodeBlock -> {
                            blocks.add(Block.CodeBlock(sub.literal.trimEnd('\n', '\r'), sub.info?.trim()))
                        }
                        else -> {
                            val inlines = extractInlines(sub)
                            if (inlines.isNotEmpty()) {
                                emitInlineBlocks(
                                    inlines = inlines,
                                    blocks = blocks,
                                    resolver = imageResolver,
                                    asListItem = true,
                                    listLevel = level,
                                    ordered = ordered && !emittedCurrentItem,
                                    orderIndex = idx
                                )
                                emittedCurrentItem = true
                            }
                        }
                    }
                    sub = sub.next
                }
                idx++
            }
            li = li.next
        }
    }

    /**
     * 递归解析 `BlockQuote`（`> ...`）：
     * 保留引用块内部的多段落、标题、列表项边界，使每段话或每条列表都输出为独立的 [Block.QuoteBlock]。
     */
    private suspend fun parseBlockQuote(
        quoteNode: Node,
        blocks: MutableList<Block>,
        imageResolver: suspend (String) -> DownloadedImage?,
        indentPrefix: String = ""
    ) {
        var child = quoteNode.firstChild
        while (child != null) {
            when (child) {
                is Paragraph, is Heading -> {
                    val runs = collectTextRuns(extractInlines(child)).toMutableList()
                    if (runs.isNotEmpty()) {
                        if (indentPrefix.isNotEmpty()) {
                            runs.add(0, TextRun(indentPrefix, bold = runs.first().bold))
                        }
                        blocks.add(Block.QuoteBlock(runs))
                    }
                }
                is BulletList -> {
                    var li = child.firstChild
                    while (li != null) {
                        if (li is ListItem) {
                            emitQuoteListItem(li, "• ", indentPrefix, blocks, imageResolver)
                        }
                        li = li.next
                    }
                }
                is OrderedList -> {
                    var li = child.firstChild
                    @Suppress("DEPRECATION")
                    var idx = child.startNumber
                    while (li != null) {
                        if (li is ListItem) {
                            emitQuoteListItem(li, "$idx. ", indentPrefix, blocks, imageResolver)
                            idx++
                        }
                        li = li.next
                    }
                }
                is BlockQuote -> {
                    parseBlockQuote(child, blocks, imageResolver, indentPrefix)
                }
                is FencedCodeBlock -> {
                    blocks.add(Block.CodeBlock(child.literal.trimEnd('\n', '\r'), child.info?.trim()))
                }
            }
            child = child.next
        }
    }

    private suspend fun emitQuoteListItem(
        li: ListItem,
        marker: String,
        parentPrefix: String,
        blocks: MutableList<Block>,
        imageResolver: suspend (String) -> DownloadedImage?
    ) {
        var sub = li.firstChild
        var firstPara = true
        while (sub != null) {
            when (sub) {
                is BulletList -> {
                    var subLi = sub.firstChild
                    while (subLi != null) {
                        if (subLi is ListItem) {
                            emitQuoteListItem(subLi, "◦ ", "$parentPrefix  ", blocks, imageResolver)
                        }
                        subLi = subLi.next
                    }
                }
                is OrderedList -> {
                    var subLi = sub.firstChild
                    @Suppress("DEPRECATION")
                    var subIdx = sub.startNumber
                    while (subLi != null) {
                        if (subLi is ListItem) {
                            emitQuoteListItem(subLi, "$subIdx. ", "$parentPrefix  ", blocks, imageResolver)
                            subIdx++
                        }
                        subLi = subLi.next
                    }
                }
                else -> {
                    val runs = collectTextRuns(extractInlines(sub)).toMutableList()
                    if (runs.isNotEmpty()) {
                        val prefix = if (firstPara) "$parentPrefix$marker" else "$parentPrefix  "
                        runs.add(0, TextRun(prefix, bold = marker.first().isDigit()))
                        blocks.add(Block.QuoteBlock(runs))
                        firstPara = false
                    }
                }
            }
            sub = sub.next
        }
    }

    // ---- 内联抽取 ----

    private sealed class InlineItem {
        data class Text(val runs: List<TextRun>) : InlineItem()
        data class ImageRef(val url: String) : InlineItem()
    }

    /**
     * 递归抽取段落/标题内的内联元素，保留文字与图片的相对顺序。
     * 注意：遇到子级列表（BulletList / OrderedList）等块级容器时不向内展开，交由外层块级递归处理。
     */
    private fun extractInlines(
        node: Node,
        parentBold: Boolean = false,
        parentItalic: Boolean = false
    ): List<InlineItem> {
        val items = mutableListOf<InlineItem>()
        val buf = StringBuilder()
        val bufBold = parentBold
        val bufItalic = parentItalic

        fun flush() {
            if (buf.isNotEmpty()) {
                items.add(InlineItem.Text(listOf(TextRun(buf.toString(), bufBold, bufItalic))))
                buf.clear()
            }
        }

        var child = node.firstChild
        while (child != null) {
            when (child) {
                is BulletList, is OrderedList, is FencedCodeBlock, is IndentedCodeBlock, is ThematicBreak -> {
                    // 块级子节点由外层单独处理，不混入当前段落内联流
                }

                is Text -> buf.append(child.literal)

                is Code -> {
                    flush()
                    items.add(InlineItem.Text(listOf(TextRun(child.literal, parentBold, parentItalic))))
                }

                is Emphasis -> {
                    flush()
                    items.addAll(extractInlines(child, parentBold, true))
                }

                is StrongEmphasis -> {
                    flush()
                    items.addAll(extractInlines(child, true, parentItalic))
                }

                is Image -> {
                    flush()
                    val dest = child.destination
                    if (!dest.isNullOrBlank()) items.add(InlineItem.ImageRef(dest))
                }

                is SoftLineBreak -> {
                    // 中文段落内软换行不强插多余英文空格，仅当两侧均为 ASCII 可见字符时补空格
                    val prevChar = if (buf.isNotEmpty()) buf.last() else null
                    val nextNodeText = (child.next as? Text)?.literal
                    val nextChar = nextNodeText?.firstOrNull()
                    if (prevChar != null && nextChar != null && prevChar.code < 128 && nextChar.code < 128) {
                        buf.append(' ')
                    }
                }

                is HardLineBreak -> buf.append('\n')

                else -> {
                    // 链接或内联容器节点：保留子节点的粗斜体属性
                    flush()
                    items.addAll(extractInlines(child, parentBold, parentItalic))
                }
            }
            child = child.next
        }
        flush()
        return items
    }

    private fun collectTextRuns(inlines: List<InlineItem>): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        for (it in inlines) if (it is InlineItem.Text) runs.addAll(it.runs)
        return runs
    }

    private suspend fun emitInlineBlocks(
        inlines: List<InlineItem>,
        blocks: MutableList<Block>,
        resolver: suspend (String) -> DownloadedImage?,
        asListItem: Boolean = false,
        listLevel: Int = 0,
        ordered: Boolean = false,
        orderIndex: Int = 0
    ) {
        val textRuns = mutableListOf<TextRun>()
        fun flushText() {
            if (textRuns.isNotEmpty()) {
                if (asListItem) {
                    blocks.add(Block.ListItem(listLevel, ordered, orderIndex, textRuns.toList()))
                } else {
                    blocks.add(Block.Paragraph(textRuns.toList()))
                }
                textRuns.clear()
            }
        }
        for (it in inlines) {
            when (it) {
                is InlineItem.Text -> textRuns.addAll(it.runs)
                is InlineItem.ImageRef -> {
                    flushText()
                    val img = resolver(it.url)
                    if (img != null) {
                        blocks.add(
                            Block.ImageBlock(img.bytes, img.widthPx, img.heightPx, img.format)
                        )
                    }
                }
            }
        }
        flushText()
    }
}

