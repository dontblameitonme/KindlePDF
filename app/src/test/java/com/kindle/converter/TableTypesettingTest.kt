package com.kindle.converter

import com.kindle.converter.data.Block
import com.kindle.converter.data.LayoutElement
import com.kindle.converter.data.TableDisplayMode
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.parser.MarkdownParser
import com.kindle.converter.pdf.PresetRepository
import com.kindle.converter.typeset.LineBreaker
import com.kindle.converter.typeset.Paginator
import com.kindle.converter.typeset.TextMeasurer
import com.kindle.converter.typeset.TypesettingEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TableTypesettingTest {

    private fun getEngine(): Pair<TypesettingEngine, TypesettingParams>? {
        val fontPath = listOf(
            "C:\\Windows\\Fonts\\NotoSerifSC-VF.ttf",
            "C:\\Windows\\Fonts\\simsun.ttc",
            "C:\\Windows\\Fonts\\msyh.ttc"
        ).firstOrNull { File(it).exists() } ?: return null

        val fontFile = File(fontPath)
        val dummyDoc = PDDocument()
        val pdFont = PDType0Font.load(dummyDoc, fontFile)
        val measurer = TextMeasurer(pdFont, pdFont)
        val engine = TypesettingEngine(measurer, LineBreaker(measurer), Paginator())
        val params = TypesettingParams()
        return Pair(engine, params)
    }

    private val markdownTableSample = """
        # 产业经济学分析报告

        | 产业赛道/资产类别 | 顺周期光环与底层驱动源 | 2026年核心出清机制 | 微观用工异化与从业者真实处境 |
        | :--- | :--- | :--- | :--- |
        | ③ 新五样与硬科技（光伏/锂电/车/芯片） | 接棒地产的“新质生产力”顶梁柱，地方政府产业基金疯狂注资，全球能源转型叙事与出海红利共振。全产业链狂飙扩产，资本开支高居各行业之首。 | “小写 i 型经济”：产值占 GDP 飙升至 18%，但工业利润率仅 744 亿元；技术降本演变为全产业链“自杀式内卷”；欧美碳关税与反补贴双反调查锁死海外高溢价市场。 | 工程师牛马化：核心算法架构岗与生产线两班倒技术员薪酬断崖，高学历人才在无尽的工艺微调中快速贬值，三十五岁面临严苛末位淘汰机制。 |
        | ④ 传统重工业与基建链 | 城镇化红利与基建托底稳增长，高杠杆重资产扩张模式。 | 地方债务严厉化债与有效需求不足共振，产能过剩步入长期减量出清阶段。 | 基层技术骨干转型艰难，项目奖金拖欠普遍，劳动强度居高不下。 |
    """.trimIndent()

    @Test
    fun testSmartCardModeTransformsWideTable() = runBlocking {
        val setup = getEngine()
        assumeTrue("CJK font not found on system", setup != null)
        val (engine, baseParams) = setup!!

        val parser = MarkdownParser()
        val doc = parser.parse(markdownTableSample)
        val tableBlock = doc.blocks.filterIsInstance<Block.TableBlock>().firstOrNull()
        assertNotNull("Markdown 应成功解析出 Block.TableBlock", tableBlock)
        assertEquals(4, tableBlock!!.headers.size)
        assertEquals(2, tableBlock.rows.size)

        // 使用智能卡片流模式排版
        val cardParams = baseParams.copy(tableDisplayMode = TableDisplayMode.SMART_CARD)
        val result = engine.typesetWithDetails(doc, cardParams)
        val pages = result.pages
        assertTrue("卡片流排版应产出有效页面", pages.isNotEmpty())

        val allTextSegments = pages.flatMap { it.elements }
            .filterIsInstance<LayoutElement.TextLine>()
            .flatMap { it.segments }
            .map { it.text }

        val combinedText = allTextSegments.joinToString("")

        // 验证卡片标题
        assertTrue("卡片标题应包含首列内容", combinedText.contains("③ 新五样与硬科技"))
        // 验证各字段标签全宽生成
        assertTrue("应包含【顺周期光环与底层驱动源】标签", combinedText.contains("【顺周期光环与底层驱动源】"))
        assertTrue("应包含【2026年核心出清机制】标签", combinedText.contains("【2026年核心出清机制】"))
        assertTrue("应包含【微观用工异化与从业者真实处境】标签", combinedText.contains("【微观用工异化与从业者真实处境】"))

        // 验证正文完整包含
        assertTrue("应包含'新质生产力'正文", combinedText.contains("新质生产力"))
        assertTrue("应包含'小写 i 型经济'正文", combinedText.contains("小写 i 型经济"))
        assertTrue("应包含'工程师牛马化'正文", combinedText.contains("工程师牛马化"))

        // 验证所有行的坐标绝不越过页底边界
        val maxAllowedY = cardParams.pageHeight - cardParams.marginBottom + 0.1f
        for (page in pages) {
            for (elem in page.elements) {
                if (elem is LayoutElement.TextLine) {
                    assertTrue(
                        "卡片流文字行 y=${elem.topY} + ${elem.lineHeight} 不得超出页底 $maxAllowedY",
                        elem.topY + elem.lineHeight <= maxAllowedY
                    )
                }
            }
        }
    }

    @Test
    fun testCompactGridModeIntraCellPaginationPreventsCutoff() = runBlocking {
        val setup = getEngine()
        assumeTrue("CJK font not found on system", setup != null)
        val (engine, baseParams) = setup!!

        val parser = MarkdownParser()
        val doc = parser.parse(markdownTableSample)

        // 使用传统紧凑网格模式排版
        val gridParams = baseParams.copy(tableDisplayMode = TableDisplayMode.COMPACT_GRID)
        val result = engine.typesetWithDetails(doc, gridParams)
        val pages = result.pages
        assertTrue("紧凑网格排版应产出有效页面", pages.isNotEmpty())

        val maxAllowedY = gridParams.pageHeight - gridParams.marginBottom + 0.5f

        // 验证所有文字行与网格边框严格约束在安全版心内，绝无腰斩
        for ((pIdx, page) in pages.withIndex()) {
            for (elem in page.elements) {
                when (elem) {
                    is LayoutElement.TextLine -> {
                        assertTrue(
                            "第 ${pIdx + 1} 页网格文字行 y=${elem.topY} + ${elem.lineHeight} 超出安全边界 $maxAllowedY！",
                            elem.topY + elem.lineHeight <= maxAllowedY
                        )
                    }
                    is LayoutElement.RuleLine -> {
                        assertTrue(
                            "第 ${pIdx + 1} 页网格边框 y=${elem.y} + ${elem.height} 超出安全边界 $maxAllowedY！",
                            elem.y + elem.height <= maxAllowedY
                        )
                    }
                    else -> {}
                }
            }
        }

        // 验证跨页时续页包含 "(续)" 标识或表头
        val allLines = pages.flatMap { it.elements }.filterIsInstance<LayoutElement.TextLine>()
        val hasContinuationHeader = allLines.any { line ->
            line.segments.any { it.text.contains("(续)") }
        }
        if (pages.size > 1) {
            assertTrue("跨页表格续页应包含 (续) 标识", hasContinuationHeader)
        }

        // 验证零文字丢失
        val combinedGridText = allLines.flatMap { it.segments }.joinToString("") { it.text }
        assertTrue("网格模式不得丢失文字'工程师牛马化'", combinedGridText.contains("工程师牛马化"))
        assertTrue("网格模式不得丢失文字'自杀式内卷'", combinedGridText.contains("自杀式内卷"))
        assertTrue("网格模式不得丢失文字'小写 i 型经济'", combinedGridText.contains("小写 i 型经济"))
    }

    @Test
    fun testTableDisplayModePersistence() {
        val repo = PresetRepository(android.content.ContextWrapper(null))
        val initial = TypesettingParams(tableDisplayMode = TableDisplayMode.COMPACT_GRID)

        val json = repo.paramsToJson(initial)
        assertEquals("COMPACT_GRID", json.getString("tableDisplayMode"))

        val restored = repo.paramsFromJson(json)
        assertEquals(TableDisplayMode.COMPACT_GRID, restored.tableDisplayMode)

        val smartCard = TypesettingParams(tableDisplayMode = TableDisplayMode.SMART_CARD)
        val json2 = repo.paramsToJson(smartCard)
        assertEquals("SMART_CARD", json2.getString("tableDisplayMode"))
        val restored2 = repo.paramsFromJson(json2)
        assertEquals(TableDisplayMode.SMART_CARD, restored2.tableDisplayMode)
    }
}
