package com.kindle.converter

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import com.kindle.converter.data.VolumeSplitMode
import com.kindle.converter.parser.DocumentSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSplitterTest {

    @Test
    fun testSplitIntoTwoVolumesWithToc() {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()

        // 构造 10 个章节，每章 1 个 Heading + 5 个 Paragraph = 6 blocks
        for (ch in 1..10) {
            val hIdx = blocks.size
            blocks.add(Block.Heading(1, listOf(TextRun("第${ch}章 标题", bold = true))))
            toc.add(TocEntry("第${ch}章 标题", 1, hIdx))
            for (p in 1..5) {
                blocks.add(Block.Paragraph(listOf(TextRun("正文第${ch}章 第${p}段内容。"))))
            }
        }
        assertEquals(60, blocks.size)

        val doc = Document(blocks = blocks, toc = toc, title = "测试修仙传", author = "忘语")
        val volumes = DocumentSplitter.splitIntoVolumes(doc, 2)

        assertEquals(2, volumes.size)
        assertEquals("上册", volumes[0].label)
        assertEquals("下册", volumes[1].label)
        assertEquals("测试修仙传（上册）", volumes[0].document.title)
        assertEquals("测试修仙传（下册）", volumes[1].document.title)

        // 上册与下册章节应当对齐
        assertEquals(5, volumes[0].document.toc.size)
        assertEquals(5, volumes[1].document.toc.size)
        assertEquals("第1章 标题", volumes[0].document.toc.first().title)
        assertEquals("第5章 标题", volumes[0].document.toc.last().title)
        assertEquals("第6章 标题", volumes[1].document.toc.first().title)
        assertEquals("第10章 标题", volumes[1].document.toc.last().title)

        // 下册第 1 章的 blockIndex 必须精确重映射归零（第 0 个 block）
        assertEquals(0, volumes[1].document.toc.first().blockIndex)
        // 且下册首个 block 必须是第 6 章的 Heading
        assertTrue(volumes[1].document.blocks.first() is Block.Heading)
        assertEquals("第6章 标题", (volumes[1].document.blocks.first() as Block.Heading).runs.first().text)
    }

    @Test
    fun testSplitIntoThreeVolumesWithToc() {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()

        for (ch in 1..12) {
            val hIdx = blocks.size
            blocks.add(Block.Heading(1, listOf(TextRun("第${ch}章", bold = true))))
            toc.add(TocEntry("第${ch}章", 1, hIdx))
            blocks.add(Block.Paragraph(listOf(TextRun("段落一"))))
            blocks.add(Block.Paragraph(listOf(TextRun("段落二"))))
        }

        val doc = Document(blocks = blocks, toc = toc, title = "三卷测试书")
        val volumes = DocumentSplitter.splitIntoVolumes(doc, 3)

        assertEquals(3, volumes.size)
        assertEquals("上册", volumes[0].label)
        assertEquals("中册", volumes[1].label)
        assertEquals("下册", volumes[2].label)
        assertEquals("三卷测试书（上册）", volumes[0].document.title)
        assertEquals("三卷测试书（中册）", volumes[1].document.title)
        assertEquals("三卷测试书（下册）", volumes[2].document.title)

        for (v in volumes) {
            assertEquals(0, v.document.toc.first().blockIndex)
        }
    }

    @Test
    fun testSplitIntoFourVolumes() {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()

        for (ch in 1..20) {
            val hIdx = blocks.size
            blocks.add(Block.Heading(1, listOf(TextRun("第${ch}回", bold = true))))
            toc.add(TocEntry("第${ch}回", 1, hIdx))
            blocks.add(Block.Paragraph(listOf(TextRun("内容正文"))))
        }

        val doc = Document(blocks = blocks, toc = toc, title = "四大名著")
        val volumes = DocumentSplitter.split(doc, VolumeSplitMode.FOUR_VOLUMES)

        assertEquals(4, volumes.size)
        assertEquals("第1卷", volumes[0].label)
        assertEquals("第2卷", volumes[1].label)
        assertEquals("第3卷", volumes[2].label)
        assertEquals("第4卷", volumes[3].label)
    }

    @Test
    fun testSplitByHeadingsWhenNoToc() {
        val blocks = mutableListOf<Block>()
        for (i in 1..8) {
            blocks.add(Block.Heading(1, listOf(TextRun("标题 $i"))))
            blocks.add(Block.Paragraph(listOf(TextRun("段落 $i"))))
        }

        val doc = Document(blocks = blocks, toc = emptyList(), title = "无目录测试")
        val volumes = DocumentSplitter.splitIntoVolumes(doc, 2)

        assertEquals(2, volumes.size)
        assertTrue(volumes[0].document.blocks.first() is Block.Heading)
        assertTrue(volumes[1].document.blocks.first() is Block.Heading)
    }

    @Test
    fun testSplitPlainParagraphs() {
        val blocks = (1..50).map { Block.Paragraph(listOf(TextRun("段落 $it"))) }
        val doc = Document(blocks = blocks, title = "纯段落文档")
        val volumes = DocumentSplitter.splitIntoVolumes(doc, 3)

        assertEquals(3, volumes.size)
        val totalSplitBlocks = volumes.sumOf { it.document.blocks.size }
        assertEquals(50, totalSplitBlocks)
    }

    @Test
    fun testAutoVolumesCalculation() {
        // 短文档（<600 页）应当不分卷
        val shortDoc = Document(
            blocks = (1..50).map { Block.Paragraph(listOf(TextRun("短文本段落，字数很少。"))) }
        )
        assertEquals(1, DocumentSplitter.calculateAutoVolumes(shortDoc))

        // 长文档（500 段，每段 500 字 = 250,000 字 ≈ 1000 页）应当建议分成 2 卷
        val longContent = "这是长篇小说的正文段落，文字非常充实。".repeat(25)
        val longDoc = Document(
            blocks = (1..500).map { Block.Paragraph(listOf(TextRun(longContent))) }
        )
        val autoVols = DocumentSplitter.calculateAutoVolumes(longDoc)
        assertTrue("autoVols should be >= 2, actual: $autoVols", autoVols >= 2)
    }

    @Test
    fun testSplitTextBytes() {
        val text = (1..100).joinToString("\n") { "这是第 $it 行文本正文数据。" }
        val bytes = text.toByteArray(Charsets.UTF_8)

        val chunks = DocumentSplitter.splitTextBytes(bytes, 2)
        assertEquals(2, chunks.size)

        val str1 = String(chunks[0], Charsets.UTF_8)
        val str2 = String(chunks[1], Charsets.UTF_8)

        assertTrue(str1.startsWith("这是第 1 行"))
        assertTrue(str2.endsWith("这是第 100 行文本正文数据。"))
        assertEquals(text, str1 + str2)
    }
}
