package com.kindle.converter

import com.kindle.converter.data.Block
import com.kindle.converter.data.LayoutElement
import com.kindle.converter.data.PageLayout
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.parser.MarkdownParser
import com.kindle.converter.pdf.PdfGenerator
import com.kindle.converter.typeset.LineBreaker
import com.kindle.converter.typeset.Paginator
import com.kindle.converter.typeset.TextMeasurer
import com.kindle.converter.typeset.TypesettingEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BookmarkAndIndentTest {

    @Test
    fun testFrontMatterCatalogFiltering(): Unit = runBlocking {
        val md = """
            # 营养学指南
            
            ## 全书完整目录（全景导图）
            
            ### 第一篇：底层逻辑篇
            - 1.1 为什么吃
            - 1.2 能量代谢
            
            ### 第二篇：实操进阶篇
            - 2.1 减脂原则
            
            ---
            
            # 第一篇：底层逻辑篇
            
            正文内容...
            
            ## 第 1 章：为什么吃
            
            深入探讨...
            
            # 第二篇：实操进阶篇
            
            第二篇正文...
        """.trimIndent()

        val parser = MarkdownParser()
        val doc = parser.parse(md)
        val tocTitles = doc.toc.map { it.title }

        // The headings inside "## 全书完整目录" should be suppressed from TOC
        // Only actual chapters should appear in TOC
        assertEquals(
            listOf("营养学指南", "全书完整目录（全景导图）", "第一篇：底层逻辑篇", "第 1 章：为什么吃", "第二篇：实操进阶篇"),
            tocTitles
        )
    }

    @Test
    fun exportAndVerifyAllNutritionGuideMarkdowns(): Unit = runBlocking {
        val fontPath = listOf(
            "C:\\Windows\\Fonts\\NotoSerifSC-VF.ttf",
            "C:\\Windows\\Fonts\\simsun.ttc",
            "C:\\Windows\\Fonts\\msyh.ttc"
        ).firstOrNull { File(it).exists() }
        assumeTrue("No CJK font found on system", fontPath != null)

        val fontFile = File(fontPath!!)
        val guideDir = File("""C:\Users\root\.gemini\antigravity\scratch\nutrition-guide""")
        assumeTrue("Nutrition guide dir does not exist", guideDir.exists())

        val mdFiles = guideDir.listFiles { f -> f.extension == "md" }?.sortedBy { it.name } ?: emptyList()
        assumeTrue("No markdown files found", mdFiles.isNotEmpty())

        val dummyDoc = PDDocument()
        val pdFont = PDType0Font.load(dummyDoc, fontFile)
        val measurer = TextMeasurer(pdFont, pdFont)
        val engine = TypesettingEngine(measurer, LineBreaker(measurer), Paginator())
        val params = TypesettingParams()

        val parser = MarkdownParser()

        for (mdFile in mdFiles) {
            val content = mdFile.readText(Charsets.UTF_8)
            val doc = parser.parse(content)
            val typesetResult = engine.typesetWithDetails(doc, params)
            val pages = typesetResult.pages
            val blockPageMap = typesetResult.blockPageMap

            val bookmarks = mutableListOf<PdfGenerator.Bookmark>()
            val seenTitleAndLevel = mutableSetOf<String>()
            for (entry in doc.toc) {
                val key = "${entry.level}:${entry.title.trim()}"
                if (key in seenTitleAndLevel) continue
                val pageIndex = blockPageMap[entry.blockIndex] ?: -1
                if (pageIndex >= 0 && pageIndex < pages.size) {
                    seenTitleAndLevel.add(key)
                    bookmarks.add(PdfGenerator.Bookmark(entry.title, entry.level, pageIndex))
                }
            }

            if (mdFile.name.contains("完整全书")) {
                println("=== Full Book Bookmarks (${bookmarks.size} items) ===")
                for ((idx, bm) in bookmarks.take(15).withIndex()) {
                    println("  [$idx] Level ${bm.level} (Page ${bm.pageIndex + 1}): ${bm.title}")
                }
                val part1 = bookmarks.firstOrNull { it.title.startsWith("第一篇") }
                val chap1 = bookmarks.firstOrNull { it.title.startsWith("第 1 章") }
                val part2 = bookmarks.firstOrNull { it.title.startsWith("第二篇") }
                println("  Check: 第一篇 is at page ${part1?.let { it.pageIndex + 1 }}")
                println("  Check: 第 1 章 is at page ${chap1?.let { it.pageIndex + 1 }}")
                println("  Check: 第二篇 is at page ${part2?.let { it.pageIndex + 1 }}")
                assertTrue("第一篇 must be past page 3", (part1?.pageIndex ?: 0) >= 3)
                assertTrue("第二篇 must be past page 80", (part2?.pageIndex ?: 0) >= 80)
            }

            val outPdf = File(guideDir, mdFile.nameWithoutExtension + ".pdf")
            renderPdfDirect(pages, bookmarks, fontFile, outPdf)
            println("Exported: ${outPdf.name} (${pages.size} pages, ${outPdf.length() / 1024} KB)")
        }
        dummyDoc.close()
    }

    private fun renderPdfDirect(
        pages: List<PageLayout>,
        bookmarks: List<PdfGenerator.Bookmark>,
        fontFile: File,
        outFile: File
    ) {
        val doc = PDDocument()
        try {
            val regularFont = PDType0Font.load(doc, fontFile)
            val pageRefs = mutableListOf<PDPage>()
            for (pageLayout in pages) {
                val page = PDPage(PDRectangle(pageLayout.width, pageLayout.height))
                doc.addPage(page)
                pageRefs.add(page)
                val cs = PDPageContentStream(doc, page)
                val pageHeight = pageLayout.height
                for (element in pageLayout.elements) {
                    when (element) {
                        is LayoutElement.TextLine -> {
                            for (seg in element.segments) {
                                val baselineY = pageHeight - element.topY - seg.fontSize * 0.8f
                                val needBold = seg.bold
                                try {
                                    if (needBold) {
                                        val strokeWidth = (seg.fontSize * 0.020f).coerceIn(0.18f, 0.30f)
                                        cs.setLineWidth(strokeWidth)
                                    }
                                    cs.beginText()
                                    cs.setFont(regularFont, seg.fontSize)
                                    if (needBold) {
                                        cs.setRenderingMode(RenderingMode.FILL_STROKE)
                                    }
                                    if (seg.charSpacing != 0f) {
                                        cs.setCharacterSpacing(seg.charSpacing)
                                    }
                                    cs.newLineAtOffset(seg.x, baselineY)
                                    cs.showText(seg.text)
                                    if (needBold) {
                                        cs.setRenderingMode(RenderingMode.FILL)
                                    }
                                    cs.endText()
                                    if (seg.charSpacing != 0f) {
                                        cs.setCharacterSpacing(0f)
                                    }
                                } catch (_: Exception) {
                                    try {
                                        if (needBold) cs.setRenderingMode(RenderingMode.FILL)
                                        cs.endText()
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        is LayoutElement.RuleLine -> {
                            val pdfY = pageHeight - element.y
                            cs.setLineWidth(element.strokeWidth)
                            cs.setStrokingColor(0.2f, 0.2f, 0.2f)
                            if (element.width > 0f) {
                                cs.moveTo(element.x, pdfY)
                                cs.lineTo(element.x + element.width, pdfY)
                            } else {
                                cs.moveTo(element.x, pdfY)
                                cs.lineTo(element.x, pdfY - element.height)
                            }
                            cs.stroke()
                        }
                        else -> Unit
                    }
                }
                cs.close()
            }

            if (bookmarks.isNotEmpty()) {
                val rootOutline = PDDocumentOutline()
                doc.documentCatalog.documentOutline = rootOutline
                val stack = ArrayDeque<Pair<Int, PDOutlineNode>>()
                stack.addLast(0 to rootOutline)
                for (bm in bookmarks) {
                    while (stack.isNotEmpty() && stack.last().first >= bm.level && stack.last().second !== rootOutline) {
                        stack.removeLast()
                    }
                    val parent = stack.lastOrNull()?.second ?: rootOutline
                    val targetPage = pageRefs.getOrNull(bm.pageIndex)
                    val outlineItem = PDOutlineItem()
                    outlineItem.title = bm.title
                    if (targetPage != null) {
                        outlineItem.setDestination(targetPage)
                    }
                    parent.addLast(outlineItem)
                    stack.addLast(bm.level to outlineItem)
                }
            }

            doc.save(outFile.absolutePath)
        } finally {
            try { doc.close() } catch (_: Exception) {}
        }
    }
}
