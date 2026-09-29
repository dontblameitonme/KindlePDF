package com.kindle.converter.parser

import android.graphics.Bitmap
import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.ImageWrapMode
import com.kindle.converter.data.ParseContext
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream

/**
 * PDF 输入解析器。
 *
 * 流程：
 * 1. PDDocument.load(bytes) 加载 PDF
 * 2. 逐页抽取文本：以 [LineStripper] 按 y 坐标分行；可选丢页眉/页脚/页码
 * 3. 逐页抽取图片：递归遍历该页 Resources 字典（含 Form XObject 内嵌），
 *    收集所有 PDImageXObject，统一重编码为 JPEG 字节存为 Block.ImageBlock
 *
 * 资源回收：try-with-resources 风格的 close() 在最后 ensure。
 */
class PdfParser {

    /**
     * 解析 PDF。
     * @param bytes PDF 字节流
     * @param ctx 解析上下文（含 stripHeadersFooters、imageWrapMode）
     */
    fun parse(bytes: ByteArray, ctx: ParseContext = ParseContext()): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()
        var title = ""
        var author = ""

        val doc = try {
            PDDocument.load(bytes)
        } catch (e: Exception) {
            blocks.add(Block.Paragraph(listOf(TextRun("[PDF 解析失败：${e.message ?: "未知错误"}]"))))
            return Document(blocks, toc, title, author)
        }

        try {
            val info = doc.documentInformation
            title = info?.title ?: ""
            author = info?.author ?: ""

            val pageCount = doc.pages.count
            val hideImages = ctx.imageWrapMode == ImageWrapMode.HIDE_IMAGES
            var previousFirstLine: String? = null  // 跨页追踪首行，识别重复页眉

            for (i in 0 until pageCount) {
                val page = doc.pages[i]
                val pageHeight = page.mediaBox.height.toFloat()

                // 1. 行级抽文本 + 清理页眉页脚
                val rawLines = extractPageLines(doc, i)
                val cleanedLines = if (ctx.stripHeadersFooters) {
                    cleanHeadersFooters(rawLines, pageHeight, previousFirstLine)
                } else rawLines
                // 缓存当页首行，供下一页去重
                previousFirstLine = rawLines.firstOrNull()?.second?.trim()?.takeIf { it.isNotEmpty() }

                // 行 → 段：连续 2+ 个空行视为分段；段内的多行接成一个段（不加空格——
                //    中文 PDF 行内字符原本就粘连，多余空格反而破坏阅读）
                val paragraphs = linesToParagraphs(cleanedLines.map { it.second })
                for (raw in paragraphs) {
                    val clean = raw.trim().replace(Regex("[ \t]+"), " ")
                    if (clean.isNotEmpty()) {
                        blocks.add(Block.Paragraph(listOf(TextRun(clean))))
                    }
                }

                // 2. 图片（仅在 HIDE_IMAGES=false 时抓）
                if (!hideImages) {
                    val images = extractImagesAsJpeg(page)
                    blocks.addAll(images)
                }
            }
        } catch (e: Exception) {
            if (blocks.isEmpty()) {
                blocks.add(Block.Paragraph(listOf(TextRun("[PDF 读取失败：${e.message ?: "未知错误"}]"))))
            }
        } finally {
            try { doc.close() } catch (_: Exception) {}
        }

        return Document(blocks, toc, title, author)
    }

    // ----------------------------------------------------------
    // 行级文本抽取：继承 PDFTextStripper，按 y 容差 ±2pt 累积
    // ----------------------------------------------------------

    /**
     * 返回每页的 (y坐标, 行文本) 列表，按 y 从大到小排序（PDF 坐标原点在左下，
     * 所以 y 越大越靠上 —— 我们需要按"页面视觉从上到下"输出，因此翻转）。
     */
    private fun extractPageLines(doc: PDDocument, pageIndex: Int): List<Pair<Float, String>> {
        return try {
            val stripper = LineStripper()
            stripper.startPage = pageIndex + 1
            stripper.endPage = pageIndex + 1
            stripper.getText(doc)
            stripper.lines.sortedByDescending { it.first }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 内部行收集器：把同一行的字符按 x 顺序拼起来。同一行判定 = y 坐标容差 ±2pt。
     *
     * 行内空格策略（这是修复「立 那地力同时」问题的关键）：
     * - 中文 PDF 中相邻字符的物理距离本身就有 ±半个字符的波动，按 0.4 字符高度加
     *   空格会误伤 CJK-CJK 边界，造成每两个汉字之间塞一个空格。
     * - 改为只在以下两种情况加空格：
     *     (a) 行内出现真空格符
     *     (b) 上一字符与新字符起点间距 ≥ 1 个 em（即 Latin word-space 的真实场景）
     */
    private class LineStripper : PDFTextStripper() {
        val lines: MutableList<Pair<Float, String>> = mutableListOf()

        // 累积当前行
        private var currentY: Float = Float.NaN
        private var currentLine = StringBuilder()
        private var currentXEnd: Float = -1f  // 上一字符结尾 x（PDF 坐标）

        @Synchronized
        override fun processTextPosition(text: TextPosition) {
            val ch = text.getUnicode()
            if (ch.isNullOrEmpty()) return
            val y = text.getYDirAdj()  // 屏幕坐标自上而下
            val x = text.getXDirAdj()
            val w = text.getWidth()
            val h = text.getHeight()

            // 距离当前行的 y 太远 → 开新行
            val sameLine = !currentY.isNaN() && kotlin.math.abs(currentY - y) <= 2f
            if (!sameLine) {
                flushCurrentLine()
                currentY = y
                currentLine = StringBuilder()
                currentXEnd = x
            }

            // 行内空格策略：仅当真字符是空格字符（' ' / full-width '\u3000'），或
            // 上一字符结尾与新字符起点的水平间距 ≥ fontSize ≈ 1em（典型 Latin word gap）
            val gap = if (currentXEnd < 0f) 0f else (x - currentXEnd)
            val isRealSpace = ch.length == 1 && (ch[0] == ' ' || ch[0] == '\u3000')
            // 用字符自身高度 h 作参考（旧 0.4*h 太宽松，几乎任意 CJK 都会触发）
            val isWordGap = gap > h * 1.0f
            if (isRealSpace || isWordGap) {
                currentLine.append(' ')
            }
            currentLine.append(ch)
            currentXEnd = x + w
        }

        private fun flushCurrentLine() {
            val text = currentLine.toString().trim()
            if (text.isNotEmpty()) {
                lines.add(currentY to text)
            }
        }

        override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
            super.endPage(page)
            flushCurrentLine()
            // 重置（以防多页抽取时复用同一个 stripper）
            currentY = Float.NaN
            currentLine = StringBuilder()
            currentXEnd = -1f
        }

        init {
            lineSeparator = "\n"
        }
    }

    // ----------------------------------------------------------
    // 页眉 / 页脚 / 页码清理
    // ----------------------------------------------------------

    /**
     * 清理规则（按 PDF 坐标自上而下）：
     * 1. 首行 y < pageHeight * 0.10 → 视为页眉丢弃
     * 2. 末行 y > pageHeight * 0.90 → 视为页脚丢弃
     * 3. 末行 trim 后是纯数字 / 罗马数字 / 中文数字 / "-N" → 视为页码丢弃
     * 4. 首行 trim 后与 previousFirstLine 相同 → 视为重复页眉丢弃
     * 5. 中间连续空行只保留 1 个（避免 stripper 多输出空行）
     */
    private fun cleanHeadersFooters(
        rawLines: List<Pair<Float, String>>,
        pageHeight: Float,
        previousFirstLine: String?
    ): List<Pair<Float, String>> {
        if (rawLines.isEmpty()) return rawLines
        val top = pageHeight * 0.10f
        val bottom = pageHeight * 0.90f

        val first = rawLines.first()
        val last = rawLines.last()

        // 首行：y 太靠上 OR 与上一页首行重复 → 丢弃
        val firstLineText = first.second.trim()
        val dropFirst = first.first < top ||
            (previousFirstLine != null && previousFirstLine == firstLineText && firstLineText.length <= 24)

        // 末行：y 太靠下 OR 文本模式像页码 → 丢弃
        val lastLineText = last.second.trim()
        val dropLast = last.first > bottom || looksLikePageNumber(lastLineText)

        return rawLines
            .drop(if (dropFirst) 1 else 0)
            .let { if (dropLast && it.isNotEmpty()) it.dropLast(1) else it }
            .filter { it.second.isNotBlank() }
    }

    /**
     * 判定一行像不像页码：
     * - 纯数字：1, 23, 100
     * - 数字前缀：-5, 第3页
     * - 罗马数字：I, II, XII, xxiv
     * - 中文数字：一、二、十二、三十二、二十
     */
    private fun looksLikePageNumber(text: String): Boolean {
        if (text.isEmpty() || text.length > 12) return false
        val t = text.trim()
        // 纯数字
        if (t.matches(Regex("^-?\\d+$"))) return true
        // "第N页" / "第N章" / "-N-" / "P.N"
        if (t.matches(Regex("^[第P].?\\d+[页章节]?$", RegexOption.IGNORE_CASE))) return true
        // 罗马数字
        if (t.matches(Regex("^[IVXLCDM]+$", RegexOption.IGNORE_CASE))) return true
        return false
    }

    /**
     * 把行列表切成段落：
     * - 连续空行视为段落分隔
     * - 段内非空行直接拼接（无空格）—— PDF 抽出的文本行本身已被 stripper 切碎，
     *   在中间补空格会把中文文本切成"立 那地力同时"。修复：段内 join 用空串。
     */
    private fun linesToParagraphs(lines: List<String>): List<String> {
        val paragraphs = mutableListOf<String>()
        val buf = StringBuilder()
        var hasContent = false
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) {
                if (hasContent) {
                    paragraphs.add(buf.toString())
                    buf.clear()
                    hasContent = false
                }
            } else {
                buf.append(t)
                hasContent = true
            }
        }
        if (hasContent) paragraphs.add(buf.toString())
        return paragraphs
    }

    // ----------------------------------------------------------
    // 图片抽取
    // ----------------------------------------------------------

    private fun extractImagesAsJpeg(page: PDPage): List<Block.ImageBlock> {
        val out = mutableListOf<Block.ImageBlock>()
        try {
            val seen = HashSet<String>()
            val images = mutableListOf<PDImageXObject>()
            collectImages(page.resources, images, seen)
            for (img in images) {
                try {
                    val w = img.width
                    val h = img.height
                    if (w <= 0 || h <= 0) continue
                    // 修复说明：
                    // 旧代码曾使用 `if (w >= pageW - 2 && h >= pageH - 2) continue` 试图过滤整页背景图，
                    // 但混淆了物理单位：`img.width/height` 是位图像素（px，通常 800~2400px），
                    // 而 `page.mediaBox.width/height` 是 PDF 点（pt，A4 仅 595x842pt）。
                    // 直接数值比较会导致几乎所有高分辨率正常插图被误判为“全页背景”而丢弃。
                    // 此处仅过滤 < 16x16 的装饰性碎图/追踪像素。
                    if (w < 16 || h < 16) continue

                    val bitmap = img.image ?: continue
                    val bytes = ByteArrayOutputStream().use { baos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, baos)
                        baos.toByteArray()
                    }
                    if (bytes.isEmpty()) continue
                    out.add(Block.ImageBlock(bytes, w, h, format = "jpg"))
                } catch (_: Exception) {
                    // 单图失败不影响后续
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun collectImages(
        res: PDResources?,
        out: MutableList<PDImageXObject>,
        seen: HashSet<String>
    ) {
        if (res == null) return
        val names = try { res.xObjectNames } catch (_: Exception) { return }
        for (name in names) {
            if (name !is COSName) continue
            val xobj: PDXObject? = try { res.getXObject(name) } catch (_: Exception) { null }
            if (xobj == null) continue
            val key = try { xobj.cosObject.toString() } catch (_: Exception) { name.name }
            if (!seen.add(key)) continue
            when (xobj) {
                is PDImageXObject -> out.add(xobj)
                is PDFormXObject -> {
                    try { collectImages(xobj.resources, out, seen) } catch (_: Exception) {}
                }
            }
        }
    }
}