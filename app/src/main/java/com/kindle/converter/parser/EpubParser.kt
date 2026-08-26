package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lightweight EPUB parser — self-written, no external dependencies.
 *
 * EPUB is a ZIP containing:
 * - META-INF/container.xml → points to content.opf
 * - content.opf → manifest (file list) + spine (reading order) + metadata
 * - chapter XHTML files → actual content
 * - toc.ncx (EPUB 2) or nav.xhtml (EPUB 3) → table of contents
 *
 * CSS is largely ignored — only text-align, text-indent, font-weight
 * are extracted from inline styles. This is a deliberate trade-off
 * to keep the parser robust and lightweight.
 */
class EpubParser {

    fun parse(bytes: ByteArray): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()
        var title = ""
        var author = ""

        try {
            val entries = unzipAll(bytes)

            // 1. Find content.opf via container.xml
            val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: return Document(blocks)
            val containerDoc = parseXml(containerXml)
            val rootfileEl = containerDoc.getElementsByTagName("rootfile").item(0) as? Element
            val opfPath = rootfileEl?.getAttribute("full-path") ?: return Document(blocks)
            val opfDir = opfPath.substringBeforeLast('/', "")

            // 2. Parse content.opf
            val opfXml = entries[opfPath]?.toString(Charsets.UTF_8) ?: return Document(blocks)
            val opfDoc = parseXml(opfXml)

            // Metadata
            title = getMetaContent(opfDoc, "title")
            author = getMetaContent(opfDoc, "creator")

            // Build manifest: id → href
            val manifest = mutableMapOf<String, String>()
            for (item in getElementsByTagNameLocal(opfDoc, "item")) {
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isNotEmpty() && href.isNotEmpty()) {
                    manifest[id] = resolvePath(opfDir, href)
                }
            }

            // Spine: reading order
            val chapterPaths = mutableListOf<String>()
            for (itemref in getElementsByTagNameLocal(opfDoc, "itemref")) {
                val idref = itemref.getAttribute("idref")
                manifest[idref]?.let { chapterPaths.add(it) }
            }

            // 3. Parse TOC
            parseToc(entries, opfDir, manifest, toc)

            // 4. Parse each chapter in spine order
            for (chapterPath in chapterPaths) {
                val chapterXml = entries[chapterPath]?.toString(Charsets.UTF_8) ?: continue
                val chapterDoc = parseXml(chapterXml)
                val body = getElementsByTagNameLocal(chapterDoc, "body").firstOrNull()
                if (body != null) {
                    parseHtmlContent(body, blocks, toc)
                }
            }
        } catch (e: Exception) {
            blocks.add(Block.Paragraph(listOf(TextRun("[EPUB parse error: ${e.message}]"))))
        }

        return Document(blocks, toc, title, author)
    }

    private fun parseToc(
        entries: Map<String, ByteArray>,
        opfDir: String,
        manifest: Map<String, String>,
        toc: MutableList<TocEntry>
    ) {
        // Try NCX (EPUB 2)
        val ncxPath = manifest.values.find { it.endsWith(".ncx") }
        if (ncxPath != null) {
            val ncxXml = entries[ncxPath]?.toString(Charsets.UTF_8) ?: return
            val ncxDoc = parseXml(ncxXml)
            for (navPoint in getElementsByTagNameLocal(ncxDoc, "navPoint")) {
                val label = getElementsByTagNameLocal(navPoint, "text").firstOrNull()?.textContent?.trim()
                val depth = countAncestors(navPoint, "navPoint")
                if (label != null && label.isNotEmpty()) {
                    toc.add(TocEntry(label, depth, -1))
                }
            }
            return
        }

        // Try nav.xhtml (EPUB 3)
        val navPath = manifest.values.find { it.endsWith("nav.xhtml") || it.endsWith("nav.html") }
        if (navPath != null) {
            val navXml = entries[navPath]?.toString(Charsets.UTF_8) ?: return
            val navDoc = parseXml(navXml)
            val navEl = getElementsByTagNameLocal(navDoc, "nav").firstOrNull()
            if (navEl != null) {
                parseNavList(navEl, toc, 0)
            }
        }
    }

    private fun parseNavList(element: Element, toc: MutableList<TocEntry>, depth: Int) {
        var child: Node = element.firstChild
        while (child != null) {
            if (child is Element && child.localName?.lowercase() == "li") {
                val link = getElementsByTagNameLocal(child, "a").firstOrNull()
                val label = link?.textContent?.trim()
                if (label != null && label.isNotEmpty()) {
                    toc.add(TocEntry(label, depth, -1))
                }
                // Check for nested lists
                val nestedList = getElementsByTagNameLocal(child, "ol").firstOrNull()
                if (nestedList != null) {
                    parseNavList(nestedList, toc, depth + 1)
                }
            }
            child = child.nextSibling
        }
    }

    private fun parseHtmlContent(node: Node, blocks: MutableList<Block>, toc: MutableList<TocEntry>) {
        var child = node.firstChild
        while (child != null) {
            if (child is Element) {
                when (child.localName?.lowercase()) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val level = child.localName!!.last().digitToInt()
                        val runs = collectInlineText(child)
                        if (runs.isNotEmpty()) {
                            blocks.add(Block.Heading(level, runs))
                        }
                    }

                    "p" -> {
                        val runs = collectInlineText(child)
                        if (runs.isNotEmpty()) {
                            blocks.add(Block.Paragraph(runs))
                        }
                    }

                    "blockquote" -> {
                        val runs = collectInlineText(child)
                        if (runs.isNotEmpty()) {
                            blocks.add(Block.QuoteBlock(runs))
                        }
                    }

                    "pre" -> {
                        val code = child.textContent
                        if (code.isNotBlank()) {
                            blocks.add(Block.CodeBlock(code.trim()))
                        }
                    }

                    "ul" -> {
                        var li = child.firstChild
                        while (li != null) {
                            if (li is Element && li.localName?.lowercase() == "li") {
                                val runs = collectInlineText(li)
                                if (runs.isNotEmpty()) {
                                    blocks.add(Block.ListItem(0, ordered = false, runs = runs))
                                }
                            }
                            li = li.nextSibling
                        }
                    }

                    "ol" -> {
                        var li = child.firstChild
                        var idx = 1
                        while (li != null) {
                            if (li is Element && li.localName?.lowercase() == "li") {
                                val runs = collectInlineText(li)
                                if (runs.isNotEmpty()) {
                                    blocks.add(Block.ListItem(0, ordered = true, orderIndex = idx, runs = runs))
                                    idx++
                                }
                            }
                            li = li.nextSibling
                        }
                    }

                    "div", "section", "article", "main", "header", "footer" -> {
                        parseHtmlContent(child, blocks, toc)
                    }

                    "img" -> {
                        // Image extraction would go here
                        // Skipped in this version for simplicity
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

    /**
     * Collect inline text from an HTML element, preserving bold/italic.
     */
    private fun collectInlineText(
        element: Element,
        parentBold: Boolean = false,
        parentItalic: Boolean = false
    ): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        val buf = StringBuilder()
        var bufBold = parentBold
        var bufItalic = parentItalic

        fun flush() {
            if (buf.isNotEmpty()) {
                runs.add(TextRun(buf.toString(), bufBold, bufItalic))
                buf.clear()
            }
        }

        var child = element.firstChild
        while (child != null) {
            when {
                child.nodeType == Node.TEXT_NODE -> {
                    buf.append(child.textContent)
                }
                child is Element -> {
                    when (child.localName?.lowercase()) {
                        "b", "strong" -> {
                            flush()
                            runs.addAll(collectInlineText(child, true, bufItalic))
                        }
                        "i", "em" -> {
                            flush()
                            runs.addAll(collectInlineText(child, bufBold, true))
                        }
                        "br" -> buf.append('\n')
                        "span", "a", "font", "sub", "sup", "small", "mark" -> {
                            collectInlineText(child, bufBold, bufItalic)
                                .forEach { buf.append(it.text) }
                        }
                        "code" -> {
                            flush()
                            runs.add(TextRun(child.textContent, parentBold, parentItalic))
                        }
                        else -> {
                            collectInlineText(child, bufBold, bufItalic)
                                .forEach { buf.append(it.text) }
                        }
                    }
                }
            }
            child = child.nextSibling
        }
        flush()

        return runs
    }

    // --- Utilities ---

    private fun unzipAll(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                entries[entry.name] = zip.readBytes()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return entries
    }

    private fun resolvePath(base: String, href: String): String {
        if (base.isEmpty()) return href
        return "$base/$href"
    }

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
    }

    private fun getElementsByTagNameLocal(doc: org.w3c.dom.Document, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = doc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.localName?.lowercase() == localName.lowercase()) result.add(el)
        }
        return result
    }

    private fun getElementsByTagNameLocal(parent: Element, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = parent.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.localName?.lowercase() == localName.lowercase()) result.add(el)
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
        var parent = node.parentNode
        while (parent != null) {
            if (parent is Element && parent.localName?.lowercase() == ancestorLocalName.lowercase()) {
                depth++
            }
            parent = parent.parentNode
        }
        return depth
    }
}
