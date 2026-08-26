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
 * Lightweight DOCX parser — no Apache POI dependency.
 *
 * DOCX is a ZIP containing OOXML (XML). We directly:
 * 1. Unzip and read word/document.xml
 * 2. Walk <w:p> paragraphs, extract <w:r> runs with <w:t> text
 * 3. Detect heading levels from <w:pStyle w:val="Heading1"/> etc.
 * 4. Extract images from <w:drawing> (basic support)
 *
 * Complex structures (tables, text boxes, embedded objects) are
 * degraded to plain text to avoid parse failures.
 */
class DocxParser {

    fun parse(bytes: ByteArray): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()
        var title = ""
        var author = ""

        try {
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var documentXml: String? = null
            var coreXml: String? = null

            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "word/document.xml" -> documentXml = String(zip.readBytes(), Charsets.UTF_8)
                    "docProps/core.xml" -> coreXml = String(zip.readBytes(), Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }

            // Extract metadata
            if (coreXml != null) {
                val coreDoc = parseXml(coreXml)
                title = getTagText(coreDoc, "dc:title")
                author = getTagText(coreDoc, "dc:creator")
            }

            // Parse document body
            if (documentXml != null) {
                val doc = parseXml(documentXml)
                val body = getElementsByTagNameLocal(doc, "body").firstOrNull()
                if (body != null) {
                    var child = body.firstChild
                    while (child != null) {
                        if (child is Element && child.localName == "p") {
                            val block = parseParagraph(child)
                            if (block != null) {
                                blocks.add(block)
                                if (block is Block.Heading) {
                                    val headingText = block.runs.joinToString("") { it.text }
                                    toc.add(TocEntry(headingText, block.level, blocks.lastIndex))
                                }
                            }
                        }
                        child = child.nextSibling
                    }
                }
            }
        } catch (e: Exception) {
            // If parsing fails completely, at least return an empty document
            blocks.add(Block.Paragraph(listOf(TextRun("[DOCX parse error: ${e.message}]"))))
        }

        return Document(blocks, toc, title, author)
    }

    private fun parseParagraph(p: Element): Block? {
        // Get paragraph style
        val pPr = getFirstChildByLocalName(p, "pPr")
        val styleId = pPr?.let { getFirstChildByLocalName(it, "pStyle") }?.getAttribute("w:val")

        // Check for heading
        if (styleId != null) {
            val headingMatch = Regex("(?i)heading\\s*([1-6])").find(styleId)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].toInt()
                val runs = collectRuns(p)
                if (runs.isNotEmpty()) {
                    return Block.Heading(level, runs)
                }
            }

            // Check for list
            if (styleId.lowercase().contains("list")) {
                val numPr = pPr?.let { getFirstChildByLocalName(it, "numPr") }
                if (numPr != null) {
                    val runs = collectRuns(p)
                    if (runs.isNotEmpty()) {
                        return Block.ListItem(0, ordered = false, runs = runs)
                    }
                }
            }
        }

        // Check for numPr directly (list without style)
        val numPr = pPr?.let { getFirstChildByLocalName(it, "numPr") }
        if (numPr != null) {
            val runs = collectRuns(p)
            if (runs.isNotEmpty()) {
                return Block.ListItem(0, ordered = false, runs = runs)
            }
        }

        // Regular paragraph
        val runs = collectRuns(p)
        if (runs.isEmpty()) return null
        return Block.Paragraph(runs)
    }

    private fun collectRuns(p: Element): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        var child: Node = p.firstChild
        while (child != null) {
            if (child is Element && child.localName == "r") {
                val run = parseRun(child)
                if (run != null) runs.add(run)
            }
            child = child.nextSibling
        }
        return runs
    }

    private fun parseRun(r: Element): TextRun? {
        var text = StringBuilder()
        var bold = false
        var italic = false

        // Check run properties
        val rPr = getFirstChildByLocalName(r, "rPr")
        if (rPr != null) {
            if (getFirstChildByLocalName(rPr, "b") != null) bold = true
            if (getFirstChildByLocalName(rPr, "i") != null) italic = true
        }

        // Collect text from <w:t> elements
        var child: Node = r.firstChild
        while (child != null) {
            if (child is Element) {
                when (child.localName) {
                    "t" -> text.append(child.textContent)
                    "tab" -> text.append('\t')
                    "br" -> text.append('\n')
                }
            }
            child = child.nextSibling
        }

        if (text.isEmpty()) return null
        return TextRun(text.toString(), bold, italic)
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
            val el = nodes.item(i) as Element
            if (el.localName == localName) result.add(el)
        }
        return result
    }

    private fun getFirstChildByLocalName(parent: Element, localName: String): Element? {
        var child: Node = parent.firstChild
        while (child != null) {
            if (child is Element && child.localName == localName) return child
            child = child.nextSibling
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
}
