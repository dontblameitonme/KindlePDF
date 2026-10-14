package com.kindle.converter

import com.kindle.converter.data.Block
import com.kindle.converter.parser.LatexMathParser
import com.kindle.converter.parser.MarkdownParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAdvancedSyntaxTest {

    private val parser = MarkdownParser()

    @Test
    fun testLatexMathParserBasic() {
        val runs = LatexMathParser.parse("E = mc^2")
        val fullText = runs.joinToString("") { it.text }
        assertEquals("E = mc²", fullText)
        val eRun = runs.first { it.text == "E" }
        assertTrue("单字母变量 E 应为斜体", eRun.italic)
        val mcRun = runs.first { it.text.contains("m") }
        assertTrue("变量 mc 应为斜体", mcRun.italic)
    }

    @Test
    fun testLatexMathParserFractionsAndRadicals() {
        val runs = LatexMathParser.parse("""\frac{-b \pm \sqrt{b^2 - 4ac}}{2a}""")
        val fullText = runs.joinToString("") { it.text }
        assertTrue("应包含 ± 符号", fullText.contains("±"))
        assertTrue("应包含根号 √(b² - 4ac)", fullText.contains("√(b² - 4ac)"))
        assertTrue("应包含分号 / 2a", fullText.contains("/ 2a"))
    }

    @Test
    fun testLatexMathParserGreekAndCalculus() {
        val runs = LatexMathParser.parse("""\int_0^\infty e^{-x} dx = \alpha + \beta""")
        val fullText = runs.joinToString("") { it.text }
        assertTrue("应包含积分号与上下限", fullText.contains("∫(0..∞)"))
        assertTrue("应包含上标 e⁻ˣ", fullText.contains("e⁻ˣ"))
        assertTrue("应包含希腊字母 α 与 β", fullText.contains("α + β"))
    }

    @Test
    fun testBlockMathDisplayFormula() = runBlocking {
        val md = """
            # 物理学定律
            
            质能等价关系式如下：
            
            $$
            E = mc^2
            $$
            
            这是狭义相对论的核心结论。
        """.trimIndent()

        val doc = parser.parse(md)
        val mathBlock = doc.blocks.filterIsInstance<Block.MathBlock>().firstOrNull()
        assertNotNull("应解析出 Block.MathBlock", mathBlock)
        val formulaText = mathBlock!!.runs.joinToString("") { it.text }
        assertEquals("E = mc²", formulaText)
    }

    @Test
    fun testInlineMath() = runBlocking {
        val md = "根据勾股定理，直角三角形满足 ${'$'}a^2 + b^2 = c^2${'$'}，即斜边为 ${'$'}c = \\sqrt{a^2 + b^2}${'$'}。"
        val doc = parser.parse(md)
        val para = doc.blocks.filterIsInstance<Block.Paragraph>().firstOrNull()
        assertNotNull(para)
        val paraText = para!!.runs.joinToString("") { it.text }
        assertTrue("应正确展开行内公式 a² + b² = c²", paraText.contains("a² + b² = c²"))
        assertTrue("应正确展开根号 c = √(a² + b²)", paraText.contains("c = √(a² + b²)"))
    }

    @Test
    fun testCurrencyIsNotTreatedAsMath() = runBlocking {
        val md = "商品标价为 $10，运费为 $5，总计 $15。"
        val doc = parser.parse(md)
        val para = doc.blocks.filterIsInstance<Block.Paragraph>().firstOrNull()
        assertNotNull(para)
        val paraText = para!!.runs.joinToString("") { it.text }
        assertTrue("货币金额不应被当作公式解析", paraText.contains("$10") && paraText.contains("$15"))
    }

    @Test
    fun testFencedMathCodeBlock() = runBlocking {
        val md = """
            ```math
            \sum_{i=1}^n i = \frac{n(n+1)}{2}
            ```
        """.trimIndent()

        val doc = parser.parse(md)
        val mathBlock = doc.blocks.filterIsInstance<Block.MathBlock>().firstOrNull()
        assertNotNull("```math 代码块应识别为 Block.MathBlock", mathBlock)
        val formulaText = mathBlock!!.runs.joinToString("") { it.text }
        assertTrue("应包含求和符号 ∑", formulaText.contains("∑"))
    }

    @Test
    fun testTaskListCheckboxes() = runBlocking {
        val md = """
            - [ ] 待办任务项
            - [x] 已完成任务项
            - [X] 大写勾选任务项
        """.trimIndent()

        val doc = parser.parse(md)
        val items = doc.blocks.filterIsInstance<Block.ListItem>()
        assertEquals(3, items.size)
        assertTrue("第一项应为 ☐", items[0].runs.first().text.startsWith("☐ 待办任务项"))
        assertTrue("第二项应为 ☑", items[1].runs.first().text.startsWith("☑ 已完成任务项"))
        assertTrue("第三项应为 ☑", items[2].runs.first().text.startsWith("☑ 大写勾选任务项"))
    }

    @Test
    fun testStrikethroughAndHighlight() = runBlocking {
        val md = "这是 ~~已废弃内容~~ 以及 ==核心高亮重点==，支持 <del>HTML删除</del> 与 <mark>HTML标记</mark>。"
        val doc = parser.parse(md)
        val para = doc.blocks.filterIsInstance<Block.Paragraph>().firstOrNull()
        assertNotNull(para)
        val strikeRun = para!!.runs.firstOrNull { it.strikethrough && it.text.contains("已废弃内容") }
        assertNotNull("应生成 strikethrough = true 的 Run", strikeRun)
        val htmlStrikeRun = para.runs.firstOrNull { it.strikethrough && it.text.contains("HTML删除") }
        assertNotNull("HTML <del> 应生成 strikethrough = true 的 Run", htmlStrikeRun)
        val highlightRun = para.runs.firstOrNull { it.bold && it.text.contains("核心高亮重点") }
        assertNotNull("==高亮== 应生成 bold = true 的 Run", highlightRun)
    }

    @Test
    fun testSubscriptsSuperscriptsAndFootnotes() = runBlocking {
        val md = """
            水的化学式是 H~2~O，爱因斯坦方程是 E = mc^2^。
            根据最新科学文献[^1]，量子纠缠具有非定域性。
            
            [^1]: 见《物理评论快报》2024 年专刊。
        """.trimIndent()

        val doc = parser.parse(md)
        val allText = doc.blocks.flatMap { b ->
            when (b) {
                is Block.Paragraph -> b.runs.map { it.text }
                is Block.QuoteBlock -> b.runs.map { it.text }
                else -> emptyList()
            }
        }.joinToString(" ")

        assertTrue("应将 H~2~O 转换为 H₂O", allText.contains("H₂O"))
        assertTrue("应将 mc^2^ 转换为 mc²", allText.contains("mc²"))
        assertTrue("正文引用应转换为上标 [¹]", allText.contains("[¹]"))
        assertTrue("脚注定义行应转换为 [1]", allText.contains("[1] 见《物理评论快报》"))
    }

    @Test
    fun testGitHubAlertCallouts() = runBlocking {
        val md = """
            > [!TIP]
            > 建议阅读前准备好笔记本。
            
            > [!WARNING]
            > 未经授权请勿商用。
        """.trimIndent()

        val doc = parser.parse(md)
        val quotes = doc.blocks.filterIsInstance<Block.QuoteBlock>()
        assertEquals(2, quotes.size)
        val tipFirst = quotes[0].runs.first()
        assertEquals("💡 提示：", tipFirst.text)
        assertTrue(tipFirst.bold)
        val warnFirst = quotes[1].runs.first()
        assertEquals("⚠️ 警告：", warnFirst.text)
        assertTrue(warnFirst.bold)
    }
}
