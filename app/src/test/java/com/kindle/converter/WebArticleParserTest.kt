package com.kindle.converter

import com.kindle.converter.data.Block
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.parser.WebArticleParser
import com.kindle.converter.typeset.LineBreaker
import com.kindle.converter.typeset.Paginator
import com.kindle.converter.typeset.TextMeasurer
import com.kindle.converter.typeset.TypesettingEngine
import com.kindle.converter.web.model.WebArticle
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebArticleParserTest {

    private val parser = WebArticleParser()

    @Test
    fun testSingleArticleParsing(): Unit = runBlocking {
        val article = WebArticle(
            url = "https://example.com/test",
            title = "第一篇文章：测试标题",
            contentHtml = """
                <p>这是第一段文字。</p>
                <h2>小节标题一</h2>
                <p>这是第二段文字，包含更详细的说明。</p>
                <pre><code>val x = 42</code></pre>
                <table>
                    <thead><tr><th>序号</th><th>名称</th></tr></thead>
                    <tbody><tr><td>1</td><td>测试</td></tr></tbody>
                </table>
            """.trimIndent()
        )

        val doc = parser.parse(listOf(article), fallbackTitle = "测试集")
        assertEquals("第一篇文章：测试标题", doc.title)
        
        // 校验目录结构
        assertEquals(2, doc.toc.size)
        assertEquals("第一篇文章：测试标题", doc.toc[0].title)
        assertEquals(1, doc.toc[0].level)
        assertEquals("小节标题一", doc.toc[1].title)
        assertEquals(3, doc.toc[1].level)

        // 校验包含的 Block 类型
        val blockTypes = doc.blocks.map { it::class }
        assertTrue(blockTypes.contains(Block.Heading::class))
        assertTrue(blockTypes.contains(Block.Paragraph::class))
        assertTrue(blockTypes.contains(Block.CodeBlock::class))
        assertTrue(blockTypes.contains(Block.TableBlock::class))
    }

    @Test
    fun testMultiArticlePageBreakAndToc(): Unit = runBlocking {
        val a1 = WebArticle(
            url = "https://example.com/a1",
            title = "文章 A",
            contentHtml = "<p>正文 A</p><h3>A 的小节</h3>"
        )
        val a2 = WebArticle(
            url = "https://example.com/a2",
            title = "文章 B",
            contentHtml = "<p>正文 B</p>"
        )

        val doc = parser.parse(listOf(a1, a2), fallbackTitle = "网页文章合集")
        assertEquals("网页文章合集", doc.title)

        // 验证两篇文章之间必须有 PageBreak
        val pageBreaks = doc.blocks.filterIsInstance<Block.PageBreak>()
        assertEquals(1, pageBreaks.size)

        // 验证大纲：A(1), A的小节(4), B(1)
        assertEquals(3, doc.toc.size)
        assertEquals("文章 A", doc.toc[0].title)
        assertEquals(1, doc.toc[0].level)
        assertEquals("A 的小节", doc.toc[1].title)
        assertEquals(4, doc.toc[1].level)
        assertEquals("文章 B", doc.toc[2].title)
        assertEquals(1, doc.toc[2].level)
    }

    @Test
    fun testNestedDivAndImageExtraction(): Unit = runBlocking {
        val article = WebArticle(
            url = "https://www.zhihu.com/question/123/answer/456",
            title = "知乎回答测试",
            contentHtml = """
                <div class="readability-page-1">
                    <p>这是第一段引言。</p>
                    <figure>
                        <img data-actualsrc="https://picx.zhimg.com/v2-test1.jpg" src="data:image/svg+xml;utf8,..." alt="图1"/>
                    </figure>
                    <p>这是第二段正文。</p>
                    <img data-src="https://mmbiz.qpic.cn/v2-test2.png" src="" alt="图2"/>
                </div>
            """.trimIndent()
        )

        val resolvedUrls = mutableListOf<String>()
        val doc = parser.parse(listOf(article)) { url ->
            resolvedUrls.add(url)
            com.kindle.converter.parser.DownloadedImage(
                bytes = ByteArray(10),
                format = "jpg",
                widthPx = 100,
                heightPx = 100
            )
        }

        assertEquals(2, resolvedUrls.size)
        assertEquals("https://picx.zhimg.com/v2-test1.jpg", resolvedUrls[0])
        assertEquals("https://mmbiz.qpic.cn/v2-test2.png", resolvedUrls[1])

        val imageBlocks = doc.blocks.filterIsInstance<Block.ImageBlock>()
        assertEquals(2, imageBlocks.size)

        val paragraphs = doc.blocks.filterIsInstance<Block.Paragraph>()
        assertEquals(2, paragraphs.size)
    }

    @Test
    fun testWebArticleTypesettingProducesNonEmptyPages(): Unit = runBlocking {
        val article = WebArticle(
            url = "https://example.com/article",
            title = "Sample Web Article",
            contentHtml = """
                <div id="readability-page-1">
                    <p>First paragraph of the clipped web article with enough content to render properly.</p>
                    <h2>Section One</h2>
                    <p>Second paragraph with <strong>bold</strong> and <em>italic</em> spans.</p>
                    <blockquote>A quoted sentence from the web page.</blockquote>
                </div>
            """.trimIndent()
        )
        val doc = parser.parse(listOf(article))
        PDDocument().use { pdDoc ->
            val font = PDType1Font.HELVETICA
            val boldFont = PDType1Font.HELVETICA_BOLD
            val measurer = TextMeasurer(font, boldFont)
            val engine = TypesettingEngine(measurer, LineBreaker(measurer), Paginator())
            val result = engine.typesetWithDetails(doc, TypesettingParams())
            assertTrue("Expected at least 1 typeset page", result.pages.isNotEmpty())
            assertTrue("Expected non-empty page elements", result.pages.first().elements.isNotEmpty())
            assertTrue("Expected TOC entries mapped", result.blockPageMap.isNotEmpty())
        }
    }
}
