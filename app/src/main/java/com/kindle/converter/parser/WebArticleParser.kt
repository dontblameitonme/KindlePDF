package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import com.kindle.converter.web.model.WebArticle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 将抓取的一篇或多篇 WebArticle 转换为 KindlePDF 的 Document 模型。
 * - 多篇文章之间自动插入 Block.PageBreak，保证每篇文章作为独立章节另起一页；
 * - 每篇文章标题作为 1 级大纲目录（TocEntry level = 1）；
 * - 正文内部的 <h1>~<h6> 转化为 2~6 级大纲目录；
 * - 支持 <table> 结构化表格、<pre><code> 代码块、<blockquote> 引用块、<ul>/<ol> 列表与加粗/斜体；
 * - 支持 <img> 图片通过 imageResolver 加载并转化为 Block.ImageBlock。
 */
class WebArticleParser {

    suspend fun parse(
        articles: List<WebArticle>,
        fallbackTitle: String = "未命名网页合集",
        imageResolver: suspend (String) -> DownloadedImage? = { null }
    ): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()
        val images = mutableMapOf<String, ByteArray>()

        for ((index, article) in articles.withIndex()) {
            if (index > 0) {
                // 章节间强分页
                blocks.add(Block.PageBreak)
            }

            // 文章主标题作为一级大纲
            val titleRuns = listOf(TextRun(article.title.trim(), bold = true))
            val titleHeading = Block.Heading(1, titleRuns)
            blocks.add(titleHeading)
            val articleHeadingIndex = blocks.lastIndex
            toc.add(TocEntry(article.title.trim(), level = 1, blockIndex = articleHeadingIndex))

            // 使用 Jsoup 解析文章主体 HTML
            val doc = Jsoup.parseBodyFragment(article.contentHtml, article.url)
            val body = doc.body()

            parseElementChildren(body, article.url, blocks, toc, images, imageResolver)
        }

        val finalTitle = if (articles.size == 1) articles.first().title.ifBlank { fallbackTitle } else fallbackTitle
        return Document(blocks = blocks, toc = toc, title = finalTitle, author = "")
    }

    suspend fun parse(
        articles: List<WebArticle>,
        imageResolver: suspend (String) -> DownloadedImage?
    ): Document = parse(articles, "未命名网页合集", imageResolver)

    private suspend fun parseElementChildren(
        parent: Element,
        baseUrl: String,
        blocks: MutableList<Block>,
        toc: MutableList<TocEntry>,
        images: MutableMap<String, ByteArray>,
        imageResolver: suspend (String) -> DownloadedImage?
    ) {
        for (child in parent.children()) {
            parseBlockElement(child, baseUrl, blocks, toc, images, imageResolver)
        }
    }

    private suspend fun parseBlockElement(
        element: Element,
        baseUrl: String,
        blocks: MutableList<Block>,
        toc: MutableList<TocEntry>,
        images: MutableMap<String, ByteArray>,
        imageResolver: suspend (String) -> DownloadedImage?
    ) {
        val tagName = element.tagName().lowercase()
        when {
            tagName in listOf("h1", "h2", "h3", "h4", "h5", "h6") -> {
                val hLevel = tagName[1].digitToIntOrNull() ?: 2
                // 文章内标题级别从 2 级起步（文章大标题占 1 级）
                val targetLevel = (hLevel + 1).coerceIn(2, 6)
                val text = element.text().trim()
                if (text.isNotEmpty()) {
                    val runs = extractTextRuns(element)
                    blocks.add(Block.Heading(targetLevel, runs))
                    toc.add(TocEntry(text, level = targetLevel, blockIndex = blocks.lastIndex))
                }
            }

            tagName == "table" -> {
                val tableBlock = parseTable(element)
                if (tableBlock != null) {
                    blocks.add(tableBlock)
                }
            }

            tagName == "pre" || tagName == "code" -> {
                val codeText = element.text()
                if (codeText.isNotBlank()) {
                    blocks.add(Block.CodeBlock(codeText.trimEnd(), language = "text"))
                }
            }

            tagName == "blockquote" -> {
                val runs = extractTextRuns(element)
                if (runs.isNotEmpty()) {
                    blocks.add(Block.QuoteBlock(runs))
                }
            }

            tagName == "hr" -> {
                blocks.add(Block.HorizontalRule)
            }

            tagName in listOf("ul", "ol") -> {
                val isOrdered = tagName == "ol"
                for (li in element.children().filter { it.tagName().equals("li", ignoreCase = true) }) {
                    val runs = extractTextRuns(li)
                    if (runs.isNotEmpty()) {
                        blocks.add(Block.ListItem(level = 0, ordered = isOrdered, runs = runs))
                    }
                }
            }

            tagName in listOf("div", "section", "article", "main", "figure", "header", "footer") -> {
                val hasBlockChildren = element.children().any { child ->
                    child.tagName().lowercase() in listOf(
                        "p", "div", "section", "article", "figure", "h1", "h2", "h3", "h4", "h5", "h6",
                        "table", "pre", "blockquote", "ul", "ol", "img"
                    )
                }
                if (hasBlockChildren) {
                    parseElementChildren(element, baseUrl, blocks, toc, images, imageResolver)
                } else {
                    parseLeafBlock(element, baseUrl, blocks, images, imageResolver)
                }
            }

            tagName == "img" -> {
                val src = extractImageUrl(element)
                if (src.isNotBlank()) {
                    val img = imageResolver(src)
                    if (img != null) {
                        val imgName = "web_img_${images.size + 1}.${img.format}"
                        images[imgName] = img.bytes
                        blocks.add(Block.ImageBlock(img.bytes, img.widthPx, img.heightPx, img.format))
                    }
                }
            }

            else -> {
                // 普通块元素（p, span等）
                parseLeafBlock(element, baseUrl, blocks, images, imageResolver)
            }
        }
    }

    private suspend fun parseLeafBlock(
        element: Element,
        baseUrl: String,
        blocks: MutableList<Block>,
        images: MutableMap<String, ByteArray>,
        imageResolver: suspend (String) -> DownloadedImage?
    ) {
        val runs = extractTextRuns(element)
        if (runs.isNotEmpty()) {
            blocks.add(Block.Paragraph(runs))
        }
        val imgElements = element.select("img")
        for (imgEl in imgElements) {
            val src = extractImageUrl(imgEl)
            if (src.isNotBlank()) {
                val img = imageResolver(src)
                if (img != null) {
                    val imgName = "web_img_${images.size + 1}.${img.format}"
                    images[imgName] = img.bytes
                    blocks.add(Block.ImageBlock(img.bytes, img.widthPx, img.heightPx, img.format))
                }
            }
        }
    }

    private fun extractImageUrl(img: Element): String {
        val lazyAttrs = listOf(
            "data-actualsrc",    // 知乎
            "data-original",     // 知乎、CSDN、微博
            "data-src",          // 微信公众号、掘金
            "data-origin-src",   // 简书
            "data-lazy-src",     // 通用
            "data-echo",
            "data-url",
            "data-original-src",
            "src"
        )
        for (attr in lazyAttrs) {
            val raw = img.attr(attr).trim()
            if (raw.isNotBlank() && !raw.startsWith("data:image/svg", ignoreCase = true) && raw != "about:blank") {
                val abs = img.absUrl(attr).trim()
                val candidate = if (abs.isNotBlank()) abs else if (raw.startsWith("//")) "https:$raw" else raw
                if (candidate.startsWith("http://", ignoreCase = true) ||
                    candidate.startsWith("https://", ignoreCase = true) ||
                    candidate.startsWith("data:image/", ignoreCase = true)
                ) {
                    return candidate
                }
            }
        }
        val fallback = img.absUrl("src").ifBlank { img.attr("src") }.trim()
        return if (fallback.startsWith("//")) "https:$fallback" else fallback
    }

    private fun parseTable(tableEl: Element): Block.TableBlock? {
        val rows = mutableListOf<List<List<TextRun>>>()
        val headers = mutableListOf<List<TextRun>>()

        val theadRows = tableEl.select("thead tr")
        val allTrs = tableEl.select("tr")
        if (allTrs.isEmpty()) return null

        val headerTr = theadRows.firstOrNull() ?: allTrs.firstOrNull { tr -> tr.select("th").isNotEmpty() }
        val bodyTrs = if (headerTr != null) allTrs.filter { it !== headerTr } else allTrs

        if (headerTr != null) {
            val ths = headerTr.select("th, td")
            for (th in ths) {
                headers.add(extractTextRuns(th).ifEmpty { listOf(TextRun(th.text().trim(), bold = true)) })
            }
        }

        for (tr in bodyTrs) {
            val cells = mutableListOf<List<TextRun>>()
            for (td in tr.select("td, th")) {
                cells.add(extractTextRuns(td).ifEmpty { listOf(TextRun(td.text().trim())) })
            }
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }

        if (headers.isEmpty() && rows.isNotEmpty()) {
            val first = rows.removeAt(0)
            headers.addAll(first)
        }

        if (headers.isEmpty() && rows.isEmpty()) return null
        return Block.TableBlock(headers = headers, rows = rows)
    }

    private fun extractTextRuns(element: Element): List<TextRun> {
        val runs = mutableListOf<TextRun>()

        fun walk(node: Node, bold: Boolean, italic: Boolean) {
            when (node) {
                is TextNode -> {
                    val text = node.text()
                    if (text.isNotEmpty()) {
                        runs.add(TextRun(text, bold = bold, italic = italic))
                    }
                }
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag == "img" || tag == "script" || tag == "style") return
                    val isBold = bold || tag in listOf("b", "strong", "h1", "h2", "h3", "h4", "h5", "h6")
                    val isItalic = italic || tag in listOf("i", "em")
                    for (child in node.childNodes()) {
                        walk(child, isBold, isItalic)
                    }
                }
            }
        }

        for (child in element.childNodes()) {
            walk(child, bold = false, italic = false)
        }

        val merged = mutableListOf<TextRun>()
        for (run in runs) {
            val last = merged.lastOrNull()
            if (last != null && last.bold == run.bold && last.italic == run.italic) {
                merged[merged.lastIndex] = last.copy(text = last.text + run.text)
            } else {
                merged.add(run)
            }
        }
        return merged
    }
}
