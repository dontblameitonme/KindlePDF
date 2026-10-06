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
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
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
                        is LayoutElement.ImageElement -> {
                            try {
                                val pdImage = JPEGFactory.createFromByteArray(doc, element.bytes)
                                val pdfY = pageHeight - element.y - element.height
                                cs.drawImage(pdImage, element.x, pdfY, element.width, element.height)
                            } catch (_: Exception) {}
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
                stack.addLast(-1 to rootOutline)
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

    @Test
    fun testTxtChapterDetection() {
        val sampleTxt = """
            序言
            这是一个开篇小故事。

            第一章 初入江湖
            天色微明，少年背着行囊出发了。

            第二章 风波又起
            茶馆里有人窃窃私语。

            尾声
            一切归于平静。
        """.trimIndent()

        val doc = com.kindle.converter.parser.TxtParser().parse(sampleTxt.toByteArray(Charsets.UTF_8))
        assertEquals(4, doc.toc.size)
        assertEquals(listOf("序言", "第一章 初入江湖", "第二章 风波又起", "尾声"), doc.toc.map { it.title })
        assertTrue(doc.blocks[0] is Block.Heading)
        assertTrue(doc.blocks[2] is Block.Heading)
    }

    @Test
    fun testEpubKpedBookmarks(): Unit = runBlocking {
        val kpedDir = File("""C:\Users\root\Downloads\kped""")
        assumeTrue("kped dir does not exist", kpedDir.exists())

        val fontPath = listOf(
            "C:\\Windows\\Fonts\\NotoSerifSC-VF.ttf",
            "C:\\Windows\\Fonts\\simsun.ttc",
            "C:\\Windows\\Fonts\\msyh.ttc"
        ).firstOrNull { File(it).exists() }
        assumeTrue("No CJK font found", fontPath != null)

        val fontFile = File(fontPath!!)
        val dummyDoc = PDDocument()
        val pdFont = PDType0Font.load(dummyDoc, fontFile)
        val measurer = TextMeasurer(pdFont, pdFont)
        val engine = TypesettingEngine(measurer, LineBreaker(measurer), Paginator())
        val params = TypesettingParams()
        val epubParser = com.kindle.converter.parser.EpubParser()

        // 1. 测试 知乎日报精选_20261004.epub
        val zhihuFile = File(kpedDir, "知乎日报精选_20261004.epub")
        if (zhihuFile.exists()) {
            val doc = epubParser.parse(zhihuFile.readBytes())
            assertTrue("Zhihu toc should not be empty", doc.toc.isNotEmpty())
            val typeset = engine.typesetWithDetails(doc, params)
            val pages = typeset.pages
            val blockPageMap = typeset.blockPageMap

            val chap1 = doc.toc.firstOrNull { it.title.contains("为什么巨唐没修长城") }
            assertTrue("Chapter 1 must be present in TOC", chap1 != null)
            val chap1Page = blockPageMap[chap1!!.blockIndex] ?: -1
            println("Zhihu Chapter 1 is at page: ${chap1Page + 1}")
            // 目录占用第 1~7 页，第一章正文必须在第 8 页或之后
            assertTrue("Zhihu Chapter 1 must be on page >= 8, but was ${chap1Page + 1}", chap1Page >= 7)

            val chap2 = doc.toc.firstOrNull { it.title.contains("蘑菇的毒性") }
            val bms = createBookmarksHelper(doc, pages, blockPageMap)
            val outFile = File(kpedDir, "知乎日报精选_20261004.pdf")
            renderPdfDirect(pages, bms, fontFile, outFile)
            println("Exported Zhihu to $outFile (${pages.size} pages, ${outFile.length() / 1024} KB)")
        }

        // 2. 测试 显微镜下的大明.epub
        val mingFile = kpedDir.listFiles { f -> f.name.contains("显微镜下的大明") && f.extension == "epub" }?.firstOrNull()
        if (mingFile != null) {
            val doc = epubParser.parse(mingFile.readBytes())
            assertTrue("Ming toc should not be empty", doc.toc.isNotEmpty())
            val typeset = engine.typesetWithDetails(doc, params)
            val pages = typeset.pages
            val blockPageMap = typeset.blockPageMap

            val preface = doc.toc.firstOrNull { it.title == "序言" }
            val vol1 = doc.toc.firstOrNull { it.title.contains("第一卷") }
            val vol2 = doc.toc.firstOrNull { it.title.contains("第二卷") }

            val prefacePage = blockPageMap[preface?.blockIndex ?: -1] ?: -1
            val vol1Page = blockPageMap[vol1?.blockIndex ?: -1] ?: -1
            val vol2Page = blockPageMap[vol2?.blockIndex ?: -1] ?: -1

            println("Ming Preface is at page: ${prefacePage + 1}")
            println("Ming Vol 1 is at page: ${vol1Page + 1}")
            println("Ming Vol 2 is at page: ${vol2Page + 1}")

            // 序言必须在第 2~5 页，绝不能被错判到全书最后第 1488 页！
            assertTrue("Preface must be on page 2~5, but was ${prefacePage + 1}", (prefacePage + 1) in 2..5)
            // 第一卷必须在正文篇章位置（page 10~40），绝不能在 1488 页！
            assertTrue("Vol 1 must be on page 10~40, but was ${vol1Page + 1}", (vol1Page + 1) in 10..40)
            assertTrue("Vol 2 must be after Vol 1, but was ${vol2Page + 1}", vol2Page > vol1Page)

            val bms = createBookmarksHelper(doc, pages, blockPageMap)
            val outFile = File(kpedDir, mingFile.nameWithoutExtension + ".pdf")
            renderPdfDirect(pages, bms, fontFile, outFile)
            println("Exported Ming to $outFile (${pages.size} pages, ${outFile.length() / 1024} KB)")
        }
        dummyDoc.close()
    }

    private fun createBookmarksHelper(
        doc: com.kindle.converter.data.Document,
        pages: List<PageLayout>,
        blockPageMap: Map<Int, Int>
    ): List<PdfGenerator.Bookmark> {
        val bookmarks = mutableListOf<PdfGenerator.Bookmark>()
        val seenTitleAndLevel = mutableSetOf<String>()
        var lastFoundPageIndex = 0
        for (entry in doc.toc) {
            val key = "${entry.level}:${entry.title.trim()}"
            if (key in seenTitleAndLevel) continue
            val pageIndex = blockPageMap[entry.blockIndex] ?: run {
                val normTarget = entry.title.filter { it.isLetterOrDigit() }.take(12)
                if (normTarget.isEmpty()) return@run -1
                (lastFoundPageIndex until pages.size).firstOrNull { pIdx ->
                    pages[pIdx].elements.any { el ->
                        el is LayoutElement.TextLine && el.segments.joinToString("") { it.text }.filter { it.isLetterOrDigit() }.contains(normTarget)
                    }
                } ?: -1
            }
            if (pageIndex in pages.indices) {
                seenTitleAndLevel.add(key)
                lastFoundPageIndex = maxOf(lastFoundPageIndex, pageIndex)
                bookmarks.add(PdfGenerator.Bookmark(entry.title, entry.level, pageIndex))
            }
        }
        return bookmarks
    }
}

