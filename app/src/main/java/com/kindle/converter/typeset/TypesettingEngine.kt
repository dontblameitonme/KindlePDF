package com.kindle.converter.typeset

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.ImageWrapMode
import com.kindle.converter.data.LayoutElement
import com.kindle.converter.data.PageLayout
import com.kindle.converter.data.PositionedSegment
import com.kindle.converter.data.TextAlignment
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.data.VerticalAlignment
import com.tom_roush.pdfbox.pdmodel.font.PDFont

// ============================================================
// TextMeasurer — Uses PdfBox font metrics for consistency
// between measurement and final PDF rendering.
// ============================================================

class TextMeasurer(
    private val regularFont: PDFont,
    private val boldFont: PDFont?
) {
    fun measureText(text: String, fontSize: Float, bold: Boolean = false): Float {
        var width = 0f
        for (c in text) {
            width += measureChar(c, fontSize, bold)
        }
        return width
    }

    /**
     * 解析当前字体可编码渲染的字符；若原字符在字体中缺失（如部分精简字库缺少 ►、◦、▪），
     * 则按 ChineseTypography.glyphFallbacks 依次尝试等价降级字符。
     */
    fun resolveSupportedChar(c: Char, bold: Boolean = false): Char {
        val font = if (bold && boldFont != null) boldFont else regularFont
        try {
            font.encode(c.toString())
            return c
        } catch (_: Exception) {
            val fallbacks = ChineseTypography.glyphFallbacks[c] ?: return c
            for (fb in fallbacks) {
                try {
                    font.encode(fb.toString())
                    return fb
                } catch (_: Exception) {
                    // try next fallback
                }
            }
            return fallbacks.firstOrNull() ?: c
        }
    }

    fun measureChar(c: Char, fontSize: Float, bold: Boolean = false): Float {
        val font = if (bold && boldFont != null) boldFont else regularFont
        return try {
            font.getStringWidth(c.toString()) / 1000f * fontSize
        } catch (e: Exception) {
            val fallbacks = ChineseTypography.glyphFallbacks[c]
            if (fallbacks != null) {
                for (fb in fallbacks) {
                    try {
                        return font.getStringWidth(fb.toString()) / 1000f * fontSize
                    } catch (_: Exception) {
                        // try next
                    }
                }
            }
            // Fallback: estimate based on whether CJK or Latin
            if (ChineseTypography.isCJK(c)) fontSize else fontSize * 0.5f
        }
    }

    /**
     * Measure a full line of text, summing all character widths.
     */
    fun measureRun(run: TextRun, fontSize: Float): Float {
        val effectiveSize = run.fontSize ?: fontSize
        return measureText(run.text, effectiveSize, run.bold)
    }
}

// ============================================================
// LineBreaker — Auto line breaking with Chinese typography rules
//
// Strategy (GB/T 15834-2011 & W3C CLREQ 出版级规范):
// - CJK characters: can break between characters (subject to 避头尾禁则)
// - Latin text: break at word boundaries (only rolls back within the active
//   Latin word, never across CJK characters)
// - 避头 (lineStartForbidden):
//   * Single trailing punctuation is allowed to hang at line end (标点悬挂,
//     up to 1.0 em box / ~0.25-0.5 em visible ink overhang).
//   * Consecutive forbidden punctuation that would exceed the hanging limit
//     triggers 「退字法」(Push-Out): rolls back the last normal character
//     onto the next line so no forbidden char ever lands at line start and
//     the current line is never over-compressed.
//   * Lookahead past inline spaces before breaking so "空格 + ）" never leaves
//     "）" at the start of the next line.
// - 避尾 (lineEndForbidden):
//   * Trailing opening brackets/quotes ('（', '“', '《', etc.) at line end
//     are rolled back to start the next line (推下造行), instead of pulling
//     subsequent characters to overflow the current line.
// - CJK-Latin mixed text: inserts 0.20 em visual gap between Han ideographs
//   and Latin letters/digits by splitting segments (keeping measured == drawn).
// ============================================================

class LineBreaker(private val measurer: TextMeasurer) {

    data class LineSegment(
        val text: String,
        val x: Float,
        val fontSize: Float,
        val bold: Boolean,
        val italic: Boolean
    )

    data class Line(
        val segments: List<LineSegment>,
        val width: Float,
        val indent: Float = 0f,
        /**
         * 行尾标点可悬挂/右半格留白的光学抵扣宽度（单位 pt）。
         * 当行末为全角点号或全角闭括号/闭引号时，其字形框右半格（0.5 em）为空白，
         * 且允许墨迹轻微悬挂于右页边（共抵扣约 0.75 em），避免 Paginator 压缩前置正文。
         */
        val trailingOpticalHangWidth: Float = 0f,
        /** 行尾是否为避头标点（用于 Paginator 禁止负字距压缩） */
        val endsWithHangingPunct: Boolean = false,
        /** 是否由段内硬换行 (\n) 结束（硬换行与段末行一样，不参与强制两端拉伸） */
        val isHardBreak: Boolean = false
    )

    private data class CharInfo(
        val char: Char,
        val bold: Boolean,
        val italic: Boolean,
        val fontSize: Float,
        val width: Float
    )

    /**
     * 已放置到当前行上的单个字符及其精确起始 x 坐标、对应在 chars 数组中的下标。
     * 采用字符级列表构建当前行，仅在行收尾时合并为 LineSegment，
     * 这样在执行「避尾推下」「避头退字」「英文单词回退」时可以跨样式边界精确回退，
     * 彻底消除旧版因 flushBuffer 清空 buf 导致的回退失效与行首禁则漏判。
     */
    private data class PlacedChar(
        val info: CharInfo,
        val x: Float,
        val charIndex: Int
    )

    fun breakLines(
        runs: List<TextRun>,
        availableWidth: Float,
        fontSize: Float,
        firstLineIndent: Float = 0f,
        leftIndent: Float = 0f
    ): List<Line> {
        if (runs.isEmpty()) return emptyList()

        // 1) 把 runs 平展为字符数组（保留 \n 供硬换行处理，过滤 \r）
        val chars = ArrayList<CharInfo>(runs.sumOf { it.text.length })
        for (run in runs) {
            val effectiveSize = run.fontSize ?: fontSize
            for (c in run.text) {
                if (c == '\r') continue
                val w = if (c == '\n') 0f else measurer.measureChar(c, effectiveSize, run.bold)
                chars.add(CharInfo(c, run.bold, run.italic, effectiveSize, w))
            }
        }
        if (chars.isEmpty()) return emptyList()

        val lines = mutableListOf<Line>()
        var pos = 0
        var isFirstLine = true

        while (pos < chars.size) {
            // 跳过行首空白（含普通空格与全角空格 \u3000，但若遇到空行 \n 则直接消费）
            while (pos < chars.size && chars[pos].char.isWhitespace()) {
                if (chars[pos].char == '\n') {
                    isFirstLine = true
                }
                pos++
            }
            if (pos >= chars.size) break

            val indent = (if (isFirstLine) firstLineIndent else 0f) + leftIndent
            // 可用内容宽度扣除 indent，防止带缩进的行越过右页边
            val effectiveWidth = (availableWidth - indent).coerceAtLeast(0f)
            var x = indent
            val placed = ArrayList<PlacedChar>(32)
            var hardBreak = false

            /**
             * 从当前行尾部回退至指定 placed 大小，并将全局游标 pos 与当前 x 同步回退。
             */
            fun rollbackTo(targetSize: Int) {
                if (targetSize < 0 || targetSize >= placed.size) return
                pos = placed[targetSize].charIndex
                while (placed.size > targetSize) {
                    placed.removeAt(placed.lastIndex)
                }
                // 同步移除回退后残留在行尾的空白符（空格本身不占右边界宽度）
                while (placed.isNotEmpty() && placed.last().info.char.isWhitespace()) {
                    placed.removeAt(placed.lastIndex)
                }
                x = if (placed.isEmpty()) indent else placed.last().x + placed.last().info.width
            }

            /**
             * 检查从 startIdx 开始的连续避头标点序列若全部挂在本行末尾，是否超出悬挂上限。
             * 出版标准：行末最多允许悬挂约 1.0 em 的字形框宽度（其中全角标点右半格 0.5 em 为空白，
             * 实际墨迹仅溢出 ≤ 0.5 em）。若连续出现多个避头标点（如 "”，"、"。）"）导致总溢出 > 1.05 em，
             * 则返回 false，由调用方触发「退字法」将标点前的正文字符一并推入下一行。
             */
            fun canHangForbiddenSequence(startIdx: Int, currentX: Float): Boolean {
                var simX = currentX
                var idx = startIdx
                var count = 0
                while (idx < chars.size) {
                    val ch = chars[idx]
                    if (ch.char == ' ' || ch.char == '\t') {
                        idx++
                        continue
                    }
                    if (ch.char !in ChineseTypography.lineStartForbidden) break
                    simX += ch.width
                    count++
                    // 最多允许连续悬挂不超 1.05 em（或单标点悬挂）
                    val maxHangEnd = indent + effectiveWidth + ch.fontSize * 1.05f
                    if (simX > maxHangEnd && count > 1) {
                        return false
                    }
                    if (simX > maxHangEnd && count == 1) {
                        // 即使单个标点，若之前已经有悬挂标点使 simX 远超上限，也不允许继续堆叠
                        val prevOver = currentX - (indent + effectiveWidth)
                        if (prevOver > ch.fontSize * 0.15f) return false
                    }
                    idx++
                }
                return true
            }

            /**
             * 「退字法（Kinsoku Push-Out）」：
             * 当行尾遇到连续避头标点无法全部悬挂时，向前回退到最后一个「可以合法作为下一行行首」
             * 且其前一个字符「可以合法作为本行行尾（非避尾标点）」的位置。
             * 返回 true 表示成功回退并可直接结束本行。
             */
            fun tryPushOutForKinsoku(): Boolean {
                // 最多向前回退 4 个字符，避免极端构造文本把整行退空
                val minKeep = (placed.size - 4).coerceAtLeast(1)
                for (cutIdx in (placed.size - 1) downTo minKeep) {
                    val candidateStart = placed[cutIdx].info.char
                    val prevEnd = placed[cutIdx - 1].info.char
                    if (!candidateStart.isWhitespace() &&
                        candidateStart !in ChineseTypography.lineStartForbidden &&
                        prevEnd !in ChineseTypography.lineEndForbidden
                    ) {
                        rollbackTo(cutIdx)
                        return true
                    }
                }
                return false
            }

            /**
             * 「避尾推下（Line-End Prohibition Rollback）」：
             * 若本行准备折行时，行末停着一个或多个避尾标点（如 '（'、'“'、'《'），
             * 将这些避尾标点从本行尾部剥离并退回下一行行首。
             */
            fun rollbackTrailingLineEndForbidden() {
                var cutIdx = placed.size
                while (cutIdx > 1 && placed[cutIdx - 1].info.char in ChineseTypography.lineEndForbidden) {
                    cutIdx--
                }
                if (cutIdx < placed.size && cutIdx >= 1) {
                    // 确保剥离后新的行尾字符不会违反其他规则
                    rollbackTo(cutIdx)
                }
            }

            while (pos < chars.size) {
                val ci = chars[pos]
                val c = ci.char

                // 1) 段内硬换行 (\n)：立即结束本行，下一行恢复首行缩进状态
                if (c == '\n') {
                    pos++
                    hardBreak = true
                    break
                }

                // 2) 计算中西文混排间隙（仅在纯汉字与西文字母/数字相邻时插入 0.20 em）
                val cjkLatinGap = if (placed.isNotEmpty() &&
                    ChineseTypography.needsCjkLatinGap(placed.last().info.char, c)
                ) {
                    ci.fontSize * 0.20f
                } else 0f

                val nextX = x + cjkLatinGap
                val nextEnd = nextX + ci.width
                val overflows = (nextEnd - indent) > effectiveWidth + 0.01f

                // 3) 溢出判定与禁则处理
                if (overflows && placed.isNotEmpty()) {
                    // 3a) 当前字符本身是空白符：跳过该空白符并预判后续字符是否为避头标点
                    if (c == ' ' || c == '\t') {
                        var lookahead = pos + 1
                        while (lookahead < chars.size && (chars[lookahead].char == ' ' || chars[lookahead].char == '\t')) {
                            lookahead++
                        }
                        if (lookahead < chars.size && chars[lookahead].char in ChineseTypography.lineStartForbidden) {
                            // 空格后面紧跟「）」等避头标点！丢弃中间空格，直接将 pos 移至该标点按避头规则处理
                            pos = lookahead
                            continue
                        } else {
                            // 正常在空格处断行（空格本身丢弃）
                            pos = lookahead
                            rollbackTrailingLineEndForbidden()
                            break
                        }
                    }

                    // 3b) 避头规则（c 禁止出现在下一行行首，如 ，。！？）”】 等）
                    if (c in ChineseTypography.lineStartForbidden) {
                        if (canHangForbiddenSequence(pos, x)) {
                            // 允许标点悬挂在本行末尾（不施加附加中西文间隙）
                            placed.add(PlacedChar(ci, x, pos))
                            x += ci.width
                            pos++
                            continue
                        } else {
                            // 连续标点超出悬挂上限（如行尾已有悬挂又来一个标点，或 "”，" 双标点溢出）：
                            // 优先采用「退字法」把前一个正文字符连同标点推到下一行
                            if (tryPushOutForKinsoku()) {
                                break
                            } else {
                                // 兜底：若整行无可退字符，仍收入本行以严守行首禁则
                                placed.add(PlacedChar(ci, x, pos))
                                x += ci.width
                                pos++
                                continue
                            }
                        }
                    }

                    // 3c) 避尾规则：若本行最后一个字符是左括号/左引号（如 '（'、'“'、'《'），
                    //     绝不能让它孤立留在行尾，更不能继续往本行硬塞后续文字，而是把该左标点退到下一行！
                    if (placed.last().info.char in ChineseTypography.lineEndForbidden) {
                        if (placed.size > 1) {
                            rollbackTrailingLineEndForbidden()
                            break
                        } else {
                            // 极端情况：本行只有这 1 个左括号，只能继续放入当前字符
                            placed.add(PlacedChar(ci, nextX, pos))
                            x = nextEnd
                            pos++
                            continue
                        }
                    }

                    // 3d) 西文单词断行回退：
                    //     仅当「当前溢出字符 c」与「行末已放置字符」同属连续西文单词（字母/数字/连字符）时，
                    //     才向前回退到该西文单词的起点。
                    //     核心修复：旧版使用 if (!isCJK(c)) + buf.lastIndexOf(' ')，会把中文引号“”误判为西文，
                    //     且会跨越整行汉字回退到行首附近的空格，导致半行中文被吞或把「）」推到下一行行首。
                    if (ChineseTypography.isLatinWordChar(c) &&
                        ChineseTypography.isLatinWordChar(placed.last().info.char)
                    ) {
                        var wordStart = placed.size - 1
                        while (wordStart > 0 && ChineseTypography.isLatinWordChar(placed[wordStart - 1].info.char)) {
                            wordStart--
                        }
                        // 如果单词紧贴在左括号/左引号（如 "(word"）之后，把左括号也一并退到下一行
                        while (wordStart > 0 && placed[wordStart - 1].info.char in ChineseTypography.lineEndForbidden) {
                            wordStart--
                        }
                        if (wordStart > 0) {
                            rollbackTo(wordStart)
                            break
                        }
                    }

                    // 3e) 普通断行前预判：检查紧随其后的（跳过空格后）是否会出现避头标点
                    var lookahead = pos
                    while (lookahead < chars.size && (chars[lookahead].char == ' ' || chars[lookahead].char == '\t')) {
                        lookahead++
                    }
                    if (lookahead < chars.size && chars[lookahead].char in ChineseTypography.lineStartForbidden) {
                        pos = lookahead
                        continue
                    }

                    // 正常折行（折行前确保行尾没有残留避尾左标点）
                    rollbackTrailingLineEndForbidden()
                    break
                }

                // 4) 未溢出：正常放入当前行
                placed.add(PlacedChar(ci, nextX, pos))
                x = nextEnd
                pos++
            }

            // 清理行尾可能残留的空白符
            while (placed.isNotEmpty() && placed.last().info.char.isWhitespace()) {
                placed.removeAt(placed.lastIndex)
            }

            if (placed.isNotEmpty()) {
                val segs = buildSegments(placed)
                val lastPlaced = placed.last()
                val lineWidth = lastPlaced.x + lastPlaced.info.width
                val lastChar = lastPlaced.info.char
                val endsWithHanging = lastChar in ChineseTypography.lineStartForbidden

                // 计算行尾标点的光学悬挂/右半格留白抵扣量：
                // 1) 若行尾是全角右留白标点（，。、；：！？）》」』】”’ 等）且实测字宽 >= 0.8em：
                //    其右半格 0.5em 为字形空白，且墨迹本身允许向页边悬挂 0.25em，合计可抵扣 0.75 * width；
                // 2) 若行尾是其他避头标点（如半角 ')'、',' 或 0.5em 引号）：
                //    允许悬挂 0.5 * width，确保不会触发前文负字距压缩。
                val opticalHang = when {
                    lastChar in ChineseTypography.rightBlankFullWidthPunctuation &&
                        lastPlaced.info.width >= lastPlaced.info.fontSize * 0.8f ->
                        lastPlaced.info.width * 0.75f
                    endsWithHanging ->
                        lastPlaced.info.width * 0.5f
                    else -> 0f
                }

                lines.add(
                    Line(
                        segments = segs,
                        width = lineWidth,
                        indent = indent,
                        trailingOpticalHangWidth = opticalHang,
                        endsWithHangingPunct = endsWithHanging,
                        isHardBreak = hardBreak
                    )
                )
            }

            isFirstLine = hardBreak
        }

        return lines
    }

    /**
     * 将本行的 PlacedChar 序列合并为最小数量的 LineSegment。
     * 当样式（粗体/斜体/字号）变化，或字符间存在中西文混排间隙（x 不连续）时切分新 Segment。
     */
    private fun buildSegments(placed: List<PlacedChar>): List<LineSegment> {
        if (placed.isEmpty()) return emptyList()
        val segs = mutableListOf<LineSegment>()
        val sb = StringBuilder()
        var segStartX = placed[0].x
        var segBold = placed[0].info.bold
        var segItalic = placed[0].info.italic
        var segFontSize = placed[0].info.fontSize
        var expectedNextX = placed[0].x

        for (p in placed) {
            val info = p.info
            val hasGap = kotlin.math.abs(p.x - expectedNextX) > 0.05f
            val styleChanged = info.bold != segBold || info.italic != segItalic || info.fontSize != segFontSize

            if ((hasGap || styleChanged) && sb.isNotEmpty()) {
                segs.add(LineSegment(sb.toString(), segStartX, segFontSize, segBold, segItalic))
                sb.clear()
                segStartX = p.x
                segBold = info.bold
                segItalic = info.italic
                segFontSize = info.fontSize
            }
            sb.append(info.char)
            expectedNextX = p.x + info.width
        }
        if (sb.isNotEmpty()) {
            segs.add(LineSegment(sb.toString(), segStartX, segFontSize, segBold, segItalic))
        }
        return segs
    }
}

// ============================================================
// Paginator — Page breaking with orphan/widow control & optical margin alignment
// ============================================================

class Paginator {

    data class TableRowLayout(
        val cellLines: List<List<LineBreaker.Line>>,
        val height: Float,
        val isHeader: Boolean = false
    )

    data class TableLayoutData(
        val colWidths: List<Float>,
        val headerRow: TableRowLayout?,
        val bodyRows: List<TableRowLayout>,
        val cellPadX: Float,
        val cellPadY: Float,
        val cellLineHeight: Float,
        val tableFontSize: Float
    )

    data class BlockLines(
        val lines: List<LineBreaker.Line>,
        val isHeading: Boolean = false,
        val headingLevel: Int = 0,
        val isListItem: Boolean = false,
        val isQuoteBlock: Boolean = false,
        val isCodeBlock: Boolean = false,
        val isHorizontalRule: Boolean = false,
        val customLineHeight: Float? = null,
        /** 非空时代表这是一个图片块，整个块只放这一个 ImageElement */
        val imageElement: LayoutElement.ImageElement? = null,
        /** 非空时代表这是一个结构化表格块，支持跨页自动重复表头与完整网格绘制 */
        val tableLayout: TableLayoutData? = null
    )

    fun paginate(
        blockLines: List<BlockLines>,
        params: TypesettingParams,
        lineHeight: Float
    ): List<PageLayout> {
        val pages = mutableListOf<PageLayout>()
        var currentY = params.marginTop
        var currentElements = mutableListOf<LayoutElement>()
        val maxBottom = params.marginTop + params.availableHeight

        fun newPage() {
            if (currentElements.isNotEmpty()) {
                pages.add(PageLayout(params.pageWidth, params.pageHeight, currentElements.toList()))
            }
            currentElements = mutableListOf()
            currentY = params.marginTop
        }

        for (entry in blockLines) {
            // ----- 1) 水平分割线 (HorizontalRule) -----
            if (entry.isHorizontalRule) {
                val ruleBlockH = lineHeight * 0.7f
                if (currentY + ruleBlockH > maxBottom && currentElements.isNotEmpty()) {
                    newPage()
                }
                val ruleY = currentY + ruleBlockH * 0.5f
                currentElements.add(
                    LayoutElement.RuleLine(
                        x = params.marginLeft,
                        y = ruleY,
                        width = params.availableWidth,
                        height = 0f,
                        strokeWidth = 0.75f
                    )
                )
                currentY += ruleBlockH + params.paragraphSpacing * 0.5f
                continue
            }

            // ----- 2) 结构化表格块 (TableBlock)：完整网格线 + 行原子性 + 跨页自动重复表头 -----
            if (entry.tableLayout != null) {
                val tbl = entry.tableLayout
                val colOffsets = FloatArray(tbl.colWidths.size)
                var accX = 0f
                for (c in tbl.colWidths.indices) {
                    colOffsets[c] = accX
                    accX += tbl.colWidths[c]
                }
                val tableWidth = accX

                val firstRowH = tbl.bodyRows.firstOrNull()?.height ?: 0f
                val minStartH = (tbl.headerRow?.height ?: 0f) + firstRowH
                if (currentY + minStartH > maxBottom && currentElements.isNotEmpty()) {
                    newPage()
                }

                fun emitTableSlice(sliceRows: List<TableRowLayout>, startY: Float): Float {
                    if (sliceRows.isEmpty()) return startY
                    // 顶部外框横线
                    currentElements.add(
                        LayoutElement.RuleLine(
                            x = params.marginLeft,
                            y = startY,
                            width = tableWidth,
                            height = 0f,
                            strokeWidth = 0.9f
                        )
                    )
                    var rowY = startY
                    for ((rIdx, row) in sliceRows.withIndex()) {
                        for (c in tbl.colWidths.indices) {
                            val cellX = params.marginLeft + colOffsets[c] + tbl.cellPadX
                            val cellLines = row.cellLines.getOrNull(c) ?: emptyList()
                            for ((lIdx, cellLine) in cellLines.withIndex()) {
                                val lineTopY = rowY + tbl.cellPadY + lIdx * tbl.cellLineHeight
                                val segs = cellLine.segments.map { seg ->
                                    PositionedSegment(
                                        text = seg.text,
                                        x = cellX + seg.x,
                                        fontSize = seg.fontSize,
                                        bold = seg.bold,
                                        italic = seg.italic,
                                        charSpacing = 0f
                                    )
                                }
                                if (segs.isNotEmpty()) {
                                    currentElements.add(
                                        LayoutElement.TextLine(
                                            segments = segs,
                                            topY = lineTopY,
                                            lineHeight = tbl.cellLineHeight,
                                            fontSize = tbl.tableFontSize
                                        )
                                    )
                                }
                            }
                        }
                        rowY += row.height
                        val isLastInSlice = rIdx == sliceRows.lastIndex
                        val strokeW = when {
                            row.isHeader -> 0.85f
                            isLastInSlice -> 0.9f
                            else -> 0.4f
                        }
                        currentElements.add(
                            LayoutElement.RuleLine(
                                x = params.marginLeft,
                                y = rowY,
                                width = tableWidth,
                                height = 0f,
                                strokeWidth = strokeW
                            )
                        )
                    }
                    // 绘制垂直列分隔线与左右外框线
                    val sliceH = (rowY - startY).coerceAtLeast(1f)
                    currentElements.add(
                        LayoutElement.RuleLine(
                            x = params.marginLeft,
                            y = startY,
                            width = 0f,
                            height = sliceH,
                            strokeWidth = 0.75f
                        )
                    )
                    for (c in 1 until tbl.colWidths.size) {
                        currentElements.add(
                            LayoutElement.RuleLine(
                                x = params.marginLeft + colOffsets[c],
                                y = startY,
                                width = 0f,
                                height = sliceH,
                                strokeWidth = 0.4f
                            )
                        )
                    }
                    currentElements.add(
                        LayoutElement.RuleLine(
                            x = params.marginLeft + tableWidth,
                            y = startY,
                            width = 0f,
                            height = sliceH,
                            strokeWidth = 0.75f
                        )
                    )
                    return rowY
                }

                if (tbl.bodyRows.isEmpty() && tbl.headerRow != null) {
                    currentY = emitTableSlice(listOf(tbl.headerRow), currentY)
                } else {
                    var bodyIdx = 0
                    while (bodyIdx < tbl.bodyRows.size) {
                        val sliceRows = mutableListOf<TableRowLayout>()
                        var sliceH = 0f
                        if (tbl.headerRow != null) {
                            sliceRows.add(tbl.headerRow)
                            sliceH += tbl.headerRow.height
                        }
                        var bodyCountInSlice = 0
                        while (bodyIdx < tbl.bodyRows.size) {
                            val nextRow = tbl.bodyRows[bodyIdx]
                            val fits = currentY + sliceH + nextRow.height <= maxBottom + 0.1f
                            if (fits || (bodyCountInSlice == 0 && currentElements.isEmpty())) {
                                sliceRows.add(nextRow)
                                sliceH += nextRow.height
                                bodyIdx++
                                bodyCountInSlice++
                            } else {
                                break
                            }
                        }
                        if (bodyCountInSlice == 0) {
                            // 当前页剩余空间连表头+第1行都放不下，换新页重试
                            newPage()
                            continue
                        }
                        currentY = emitTableSlice(sliceRows, currentY)
                        if (bodyIdx < tbl.bodyRows.size) {
                            newPage()
                        }
                    }
                }
                currentY += params.paragraphSpacing
                continue
            }

            val lines = entry.lines
            if (lines.isEmpty() && entry.imageElement == null) continue

            // ----- 3) 图片块：根据 imageWrapMode 分发 -----
            if (entry.imageElement != null) {
                val img = entry.imageElement

                if (params.imageWrapMode == ImageWrapMode.BEHIND_TEXT) {
                    // 衬于文字下方：图片填满整页，固定 y=0，添加在当前页最前（绘制顺序 = 在文字之前）
                    val placedImg = img.copy(x = 0f, y = 0f)
                    currentElements.add(0, placedImg)
                    continue
                }

                // 上下型（默认）：独占一行，按图片 height 占位。
                if (currentY + img.height > maxBottom && currentElements.isNotEmpty()) {
                    newPage()
                }
                val maxFitH = (maxBottom - currentY).coerceAtLeast(1f)
                val finalW: Float
                val finalH: Float
                if (img.height > maxFitH) {
                    val scale = maxFitH / img.height
                    finalW = img.width * scale
                    finalH = maxFitH
                } else {
                    finalW = img.width
                    finalH = img.height
                }
                val centeredX = params.marginLeft + ((params.availableWidth - finalW) / 2f).coerceAtLeast(0f)
                currentElements.add(img.copy(x = centeredX, y = currentY, width = finalW, height = finalH))
                currentY += finalH + params.paragraphSpacing
                continue
            }

            // 本块的实际行高（支持标题放大防拥挤、代码块紧凑行高）
            val maxBlockFontSize = lines.firstOrNull()?.segments?.maxOfOrNull { it.fontSize } ?: params.fontSize
            val effectiveLineHeight = entry.customLineHeight
                ?: if (entry.isHeading) maxOf(lineHeight, maxBlockFontSize * 1.30f) else lineHeight

            // Heading: keep with at least 1 line of following content
            if (entry.isHeading && currentElements.isNotEmpty()) {
                val headingHeight = lines.size * effectiveLineHeight + lineHeight
                if (currentY + headingHeight > maxBottom) {
                    newPage()
                }
            }

            val codePadY = if (entry.isCodeBlock) 2.5f else 0f
            if (entry.isCodeBlock) {
                // 若代码块整体不超过半页高度且当前页剩余高度放不下整个代码块，则优先换页以保持流程图完整不跨页
                val totalCodeH = lines.size * effectiveLineHeight + codePadY * 2f
                if (totalCodeH <= params.availableHeight * 0.75f &&
                    currentY + totalCodeH > maxBottom &&
                    currentElements.isNotEmpty()
                ) {
                    newPage()
                } else if (currentY + effectiveLineHeight + codePadY * 2f > maxBottom && currentElements.isNotEmpty()) {
                    newPage()
                }
                currentY += codePadY
            }

            var sliceStartY = currentY
            var sliceHasLines = false

            // 判定代码块自身是否已经包含 ASCII 框线（若已包含 ┌─┐│└┘ 则不再重复加外框）
            val codeHasOwnBox = entry.isCodeBlock && lines.any { ln ->
                ln.segments.any { seg -> seg.text.any { ch -> ch in "┌─┐│└┘├┤┬┴┼" } }
            }

            fun flushBlockSliceDecorations(sliceEndY: Float) {
                if (!sliceHasLines) return
                if (entry.isQuoteBlock) {
                    // 引用块左侧竖线（支持跨页自动分段绘制）
                    val barX = params.marginLeft + params.fontSize * 0.5f
                    val barTop = sliceStartY + 1.5f
                    val barH = (sliceEndY - sliceStartY - 3f).coerceAtLeast(2f)
                    currentElements.add(
                        LayoutElement.RuleLine(
                            x = barX,
                            y = barTop,
                            width = 0f,
                            height = barH,
                            strokeWidth = 2.0f
                        )
                    )
                }
                if (entry.isCodeBlock && !codeHasOwnBox) {
                    // 普通源代码块（无自带 ASCII 框线）绘制上下边界细线
                    val boxTop = (sliceStartY - 2.0f).coerceAtLeast(params.marginTop)
                    val boxBottom = sliceEndY + 1.5f
                    val boxH = (boxBottom - boxTop).coerceAtLeast(2f)
                    val boxLeft = params.marginLeft
                    val boxW = params.availableWidth
                    currentElements.add(LayoutElement.RuleLine(x = boxLeft, y = boxTop, width = boxW, height = 0f, strokeWidth = 0.45f))
                    currentElements.add(LayoutElement.RuleLine(x = boxLeft, y = boxBottom, width = boxW, height = 0f, strokeWidth = 0.45f))
                    currentElements.add(LayoutElement.RuleLine(x = boxLeft, y = boxTop, width = 0f, height = boxH, strokeWidth = 0.45f))
                    currentElements.add(LayoutElement.RuleLine(x = boxLeft + boxW, y = boxTop, width = 0f, height = boxH, strokeWidth = 0.45f))
                }
            }

            for ((lineIdx, line) in lines.withIndex()) {
                val isLastLineOfBlock = lineIdx == lines.size - 1
                val isFirstLineOfBlock = lineIdx == 0

                // 孤行控制（Orphan control）：段落首行不单独留在页面最底部
                if (!entry.isHeading && !entry.isCodeBlock && isFirstLineOfBlock &&
                    params.enableOrphanControl && lines.size > 1
                ) {
                    if (currentY + effectiveLineHeight * 2 > maxBottom && currentY > params.marginTop + 1f) {
                        flushBlockSliceDecorations(currentY)
                        newPage()
                        sliceStartY = currentY
                        sliceHasLines = false
                    }
                }

                // 寡行控制（Widow control）：段落末行不单独落在下一页最顶部
                if (!entry.isHeading && !entry.isCodeBlock && lineIdx == lines.size - 2 &&
                    params.enableWidowControl && lines.size >= 3
                ) {
                    if (currentY + effectiveLineHeight * 2 > maxBottom && currentY > params.marginTop + 1f) {
                        flushBlockSliceDecorations(currentY)
                        newPage()
                        sliceStartY = currentY
                        sliceHasLines = false
                    }
                }

                // Page break if line doesn't fit
                if (currentY + effectiveLineHeight > maxBottom && currentElements.isNotEmpty()) {
                    flushBlockSliceDecorations(currentY)
                    newPage()
                    if (entry.isCodeBlock) {
                        currentY += codePadY
                    }
                    sliceStartY = currentY
                    sliceHasLines = false
                }

                // ---- 行内水平对齐与标点悬挂（GB/T 15834 & W3C CLREQ） ----
                val lineIndent = line.indent
                val rawContentWidth = (line.width - lineIndent).coerceAtLeast(0f)
                val lineFontSize = line.segments.firstOrNull()?.fontSize ?: params.fontSize

                // 本行真正可用的横向宽度（已扣掉缩进）
                val avail = (params.availableWidth - lineIndent).coerceAtLeast(0f)

                val effectiveContentWidth = if (rawContentWidth > avail && line.trailingOpticalHangWidth > 0f) {
                    (rawContentWidth - line.trailingOpticalHangWidth).coerceAtLeast(0f)
                } else {
                    rawContentWidth
                }
                val diff = avail - effectiveContentWidth

                // 可分摊字距的间隙数（按全部字形计数）
                val glyphCount = line.segments.sumOf { it.text.length }
                val gaps = glyphCount - 1

                val forceJustify = params.forceAlign &&
                    (params.textAlignment == TextAlignment.LEFT ||
                        params.textAlignment == TextAlignment.JUSTIFY)

                val isEndLine = isLastLineOfBlock || line.isHardBreak || entry.isCodeBlock
                // 注意：若因下一行开头是超长英文单词（如 monocytogenes）回退导致本行剩余空白 diff > 2.2em，
                // 不对本行强行拉伸，避免整行字距被拉得过于稀疏。
                val stretchActive = (params.textAlignment == TextAlignment.JUSTIFY || forceJustify) &&
                    !isEndLine && !entry.isHeading && !entry.isCodeBlock &&
                    diff > 0.5f && diff <= lineFontSize * 2.2f

                // 拉伸上限 0.16em，压缩下限 0.15em
                val maxStretch = lineFontSize * 0.16f
                val maxCompress = lineFontSize * 0.15f

                val charSpacing = when {
                    entry.isCodeBlock || gaps <= 0 -> 0f
                    // 标点悬挂保护：若行尾是避头标点，即使扣除光学悬挂后仍微超（<= 1em），
                    // 也保持 0 字距让标点自然悬挂于右页边，绝不压缩正文文字间距！
                    diff < -0.01f && line.endsWithHangingPunct && diff >= -lineFontSize -> 0f
                    // 非标点引起的极端溢出兜底回收
                    diff < -0.01f -> (diff / gaps).coerceAtLeast(-maxCompress)
                    stretchActive -> (diff / gaps).coerceAtMost(maxStretch)
                    else -> 0f
                }

                // 字距生效后本行的实际绘制宽度（CENTER / RIGHT 按它算位移）
                val drawnWidth = effectiveContentWidth + charSpacing * gaps

                val shift = if (entry.isCodeBlock) {
                    0f
                } else {
                    when (params.textAlignment) {
                        TextAlignment.LEFT, TextAlignment.JUSTIFY -> 0f
                        TextAlignment.RIGHT -> (avail - drawnWidth).coerceAtLeast(0f)
                        TextAlignment.CENTER -> ((avail - drawnWidth) / 2f).coerceAtLeast(0f)
                    }
                }

                // 同一行内若有多个 segment（粗体/字号变化或中西文混排间隙切分出的 segment），
                // 后续 segment 的 x 需按前面已绘制的字形数累加 charSpacing 补偿。
                var glyphsBefore = 0
                val segments = line.segments.map { seg ->
                    val positioned = PositionedSegment(
                        text = seg.text,
                        x = seg.x + params.marginLeft + shift + charSpacing * glyphsBefore,
                        fontSize = seg.fontSize,
                        bold = seg.bold,
                        italic = seg.italic,
                        charSpacing = charSpacing
                    )
                    glyphsBefore += seg.text.length
                    positioned
                }

                currentElements.add(
                    LayoutElement.TextLine(
                        segments = segments,
                        topY = currentY,
                        lineHeight = effectiveLineHeight,
                        fontSize = segments.firstOrNull()?.fontSize ?: params.fontSize
                    )
                )

                sliceHasLines = true
                currentY += effectiveLineHeight
            }

            flushBlockSliceDecorations(currentY)
            if (entry.isCodeBlock) {
                currentY += codePadY
            }

            // Paragraph spacing after non-heading blocks
            if (!entry.isHeading) {
                currentY += params.paragraphSpacing
            }
        }

        if (currentElements.isNotEmpty()) {
            pages.add(PageLayout(params.pageWidth, params.pageHeight, currentElements.toList()))
        }

        // ---- 整页垂直对齐 ----
        if (params.verticalAlignment != VerticalAlignment.TOP) {
            applyVerticalAlignment(pages, params, lineHeight)
        }
        return pages
    }

    /**
     * 对每一页根据 VerticalAlignment 调整 y。
     * - CENTER：内容总高居中
     * - JUSTIFY：把"可分配的剩余空白"均匀摊到每两行之间（最后一行后无空白）
     */
    private fun applyVerticalAlignment(
        pages: MutableList<PageLayout>,
        params: TypesettingParams,
        lineHeight: Float
    ) {
        val maxBottom = params.marginTop + params.availableHeight
        for (i in pages.indices) {
            val page = pages[i]
            if (page.elements.isEmpty()) continue
            val firstTop = page.elements.minOf { el -> elementTop(el) }
            val lastBottom = page.elements.maxOf { el -> elementBottom(el, lineHeight) }
            val contentHeight = (lastBottom - firstTop).coerceAtLeast(0f)
            val freeSpace = (maxBottom - firstTop) - contentHeight
            if (freeSpace <= 0.5f) continue

            val textLineCount = page.elements.count { it is LayoutElement.TextLine }
            when (params.verticalAlignment) {
                VerticalAlignment.TOP -> Unit
                VerticalAlignment.CENTER -> {
                    val delta = freeSpace / 2f
                    pages[i] = pageShiftyBy(page, delta)
                }
                VerticalAlignment.JUSTIFY -> {
                    val gaps = (textLineCount - 1).coerceAtLeast(0)
                    if (gaps <= 0) continue
                    val perGap = freeSpace / gaps
                    pages[i] = pageDistribute(page, perGap)
                }
            }
        }
    }

    private fun elementTop(el: LayoutElement): Float = when (el) {
        is LayoutElement.TextLine -> el.topY
        is LayoutElement.ImageElement -> el.y
        is LayoutElement.RuleLine -> el.y
    }

    private fun elementBottom(el: LayoutElement, lineHeight: Float): Float = when (el) {
        is LayoutElement.TextLine -> el.topY + el.lineHeight
        is LayoutElement.ImageElement -> el.y + el.height
        is LayoutElement.RuleLine -> el.y + el.height
    }

    private fun pageShiftyBy(page: PageLayout, delta: Float): PageLayout {
        val newEls = page.elements.map { el ->
            when (el) {
                is LayoutElement.TextLine -> el.copy(topY = el.topY + delta)
                is LayoutElement.ImageElement -> el.copy(y = el.y + delta)
                is LayoutElement.RuleLine -> el.copy(y = el.y + delta)
            }
        }
        return PageLayout(page.width, page.height, newEls)
    }

    private fun pageDistribute(page: PageLayout, perGap: Float): PageLayout {
        val lineSeenAt = page.elements.mapIndexedNotNull { idx, el ->
            if (el is LayoutElement.TextLine) idx else null
        }
        if (lineSeenAt.size <= 1) return page
        val newEls = page.elements.mapIndexed { idx, el ->
            if (el is LayoutElement.TextLine) {
                val k = lineSeenAt.indexOf(idx).coerceAtLeast(0)
                el.copy(topY = el.topY + perGap * k)
            } else el
        }
        return PageLayout(page.width, page.height, newEls)
    }
}

// ============================================================
// TypesettingEngine — Orchestrates the full pipeline
// IR → Line breaking → Pagination → PageLayout
// ============================================================

class TypesettingEngine(
    private val measurer: TextMeasurer,
    private val lineBreaker: LineBreaker,
    private val paginator: Paginator
) {
    fun typeset(document: Document, params: TypesettingParams): List<PageLayout> {
        val availableWidth = params.availableWidth
        val lineHeight = params.lineHeightInPoints
        val blockLinesList = mutableListOf<Paginator.BlockLines>()

        // 首行缩进规则（按中文排版习惯）：
        //   LEFT   ：保留（中文段落的视觉特征）
        //   JUSTIFY：保留（书版正文常用首行缩进 + 两端对齐）
        //   CENTER / RIGHT：清零（这两种对齐与首行缩进视觉冲突）
        val paragraphFirstLineIndent = when (params.textAlignment) {
            TextAlignment.LEFT, TextAlignment.JUSTIFY -> params.firstLineIndent
            TextAlignment.CENTER, TextAlignment.RIGHT -> 0f
        }

        for (block in document.blocks) {
            when (block) {
                is Block.Paragraph -> {
                    val lines = lineBreaker.breakLines(
                        block.runs, availableWidth, params.fontSize,
                        paragraphFirstLineIndent
                    )
                    blockLinesList.add(Paginator.BlockLines(lines))
                }

                is Block.Heading -> {
                    val headingFontSize = params.fontSize * headingScaleFor(block.level, params.headingScale)
                    val headingRuns = if (params.boldHeadings) {
                        block.runs.map { it.copy(bold = true, fontSize = headingFontSize) }
                    } else {
                        block.runs.map { it.copy(fontSize = headingFontSize) }
                    }
                    val lines = lineBreaker.breakLines(
                        headingRuns, availableWidth, headingFontSize,
                        firstLineIndent = 0f
                    )
                    blockLinesList.add(Paginator.BlockLines(lines, isHeading = true, headingLevel = block.level))
                }

                is Block.ListItem -> {
                    // 多级列表与悬挂缩进（Hanging Indent）：
                    // 1) 不同层级使用区分明显的列表符号（0级 •，1级 ◦，2级及以上 ▪），并经字体字形可用性校验；
                    // 2) 正文左边界统一对齐在 bodyLeftIndent，首行列表符号悬挂在 bodyLeftIndent 左侧，
                    //    折行后的第 2、3 行与首行正文文字严格左对齐，不再退到符号下方。
                    val bulletChar = when (block.level) {
                        0 -> measurer.resolveSupportedChar('•')
                        1 -> measurer.resolveSupportedChar('◦')
                        else -> measurer.resolveSupportedChar('▪')
                    }
                    val prefix = if (block.ordered) "${block.orderIndex}. " else "$bulletChar "
                    val prefixWidth = measurer.measureText(prefix, params.fontSize, bold = block.ordered)
                    val baseIndent = (block.level + 1) * params.fontSize * 1.15f
                    val bodyLeftIndent = (baseIndent + prefixWidth).coerceAtMost(availableWidth * 0.45f)
                    val prefixX = (bodyLeftIndent - prefixWidth).coerceAtLeast(0f)

                    val bodyLines = lineBreaker.breakLines(
                        block.runs, availableWidth, params.fontSize,
                        firstLineIndent = 0f,
                        leftIndent = bodyLeftIndent
                    )
                    val lines = if (bodyLines.isNotEmpty()) {
                        val first = bodyLines.first()
                        val prefixSeg = LineBreaker.LineSegment(
                            text = prefix,
                            x = prefixX,
                            fontSize = params.fontSize,
                            bold = block.ordered,
                            italic = false
                        )
                        listOf(first.copy(segments = listOf(prefixSeg) + first.segments)) + bodyLines.drop(1)
                    } else {
                        val prefixSeg = LineBreaker.LineSegment(
                            text = prefix,
                            x = prefixX,
                            fontSize = params.fontSize,
                            bold = block.ordered,
                            italic = false
                        )
                        listOf(
                            LineBreaker.Line(
                                segments = listOf(prefixSeg),
                                width = bodyLeftIndent,
                                indent = prefixX,
                                isHardBreak = true
                            )
                        )
                    }
                    blockLinesList.add(Paginator.BlockLines(lines, isListItem = true))
                }

                is Block.QuoteBlock -> {
                    // 引用块：左侧留出 1.35em 缩进供 Paginator 绘制左侧竖线，取消首行额外 2em 缩进以适配多段/列表型 Callout
                    val indent = params.fontSize * 1.35f
                    val lines = lineBreaker.breakLines(
                        block.runs, availableWidth, params.fontSize,
                        firstLineIndent = 0f,
                        leftIndent = indent
                    )
                    blockLinesList.add(Paginator.BlockLines(lines, isQuoteBlock = true))
                }

                is Block.CodeBlock -> {
                    val codeBlockLines = layoutCodeBlock(block, availableWidth, params.fontSize)
                    blockLinesList.add(codeBlockLines)
                }

                is Block.TableBlock -> {
                    val tableLayout = layoutTableBlock(block, availableWidth, params)
                    if (tableLayout != null) {
                        blockLinesList.add(Paginator.BlockLines(emptyList(), tableLayout = tableLayout))
                    }
                }

                is Block.HorizontalRule -> {
                    blockLinesList.add(Paginator.BlockLines(emptyList(), isHorizontalRule = true))
                }

                is Block.ImageBlock -> {
                    if (params.imageWrapMode == ImageWrapMode.HIDE_IMAGES) continue

                    val (drawW, drawH) = if (params.imageWrapMode == ImageWrapMode.BEHIND_TEXT) {
                        params.pageWidth to params.pageHeight
                    } else {
                        computeImageFit(
                            block = block,
                            maxWidthPt = params.availableWidth,
                            maxHeightPt = (params.availableHeight - params.paragraphSpacing).coerceAtLeast(lineHeight * 2f)
                        )
                    }
                    val imgEl = LayoutElement.ImageElement(
                        bytes = block.bytes,
                        format = block.format,
                        x = params.marginLeft,
                        y = 0f,
                        width = drawW,
                        height = drawH
                    )
                    blockLinesList.add(Paginator.BlockLines(emptyList(), imageElement = imgEl))
                }
            }
        }

        return paginator.paginate(blockLinesList, params, lineHeight)
    }

    /**
     * 代码块与 ASCII / Unicode 框线图（如决策树、餐盘比例图、流程图）自适应排版引擎：
     *
     * 核心设计：
     * 1. **ASCII 框线图右边界自动对齐修正（`normalizeAsciiBoxes`）**：
     *    手写 Markdown 框线图在中英混排时极易出现作者少算或多算空格（如顶框 `┌───┐` 宽 50 列，
     *    而内部文字行 `│ ... │` 宽 58 列，导致右侧竖线 `│` 参差错位）。
     *    引擎自动识别连续的单栏框线块，统计块内最宽行的半角列数，自动补齐顶/中/底横框线 `─` 并对齐每行末尾的 `│`。
     * 2. **东亚半角/全角严格网格定位（0.5em / 1.0em Monospace Grid）**：
     *    按半角列宽（`halfEm = codeFontSize * 0.5f`）逐字符计算精确网格坐标，空白符只推进列游标不绘制字形，
     *    彻底消除比例字体空格宽度偏差。
     * 3. **基于最长行宽度的字号自适应缩放（Auto-Fit Font Scaling）**：
     *    统计整个代码块的最大半角列数 `maxCols`，超出版心宽度时自动等比缩小字号（下限 `5.0pt`），确保宽幅流程图单行完整呈现。
     */
    private fun layoutCodeBlock(
        block: Block.CodeBlock,
        availableWidth: Float,
        baseFontSize: Float
    ): Paginator.BlockLines {
        val initialLines = block.text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.replace("\t", "    ") }

        val rawLines = normalizeAsciiBoxes(initialLines)

        val maxCols = rawLines.maxOfOrNull { line ->
            line.sumOf { c -> ChineseTypography.eastAsianColWidth(c) }
        }?.coerceAtLeast(1) ?: 1

        val padX = (baseFontSize * 0.45f).coerceAtLeast(4f)
        val innerWidth = (availableWidth - padX * 2f).coerceAtLeast(40f)
        val defaultCodeSize = baseFontSize * 0.82f

        // 预留 +1 半角列（0.5em），容纳行尾 1.0em 宽度的右框线字符（如 ┐、│、┘）
        val neededCols = maxCols + 1
        val idealFontSize = innerWidth / (neededCols * 0.5f)
        val codeFontSize = minOf(defaultCodeSize, idealFontSize).coerceAtLeast(5.0f)
        val halfEm = codeFontSize * 0.5f
        val maxColsPerLine = (innerWidth / halfEm).toInt().coerceAtLeast(8)

        val resultLines = mutableListOf<LineBreaker.Line>()

        for (rawLine in rawLines) {
            if (rawLine.isBlank()) {
                // 保留空行高度
                resultLines.add(
                    LineBreaker.Line(
                        segments = listOf(
                            LineBreaker.LineSegment(
                                text = " ",
                                x = padX,
                                fontSize = codeFontSize,
                                bold = false,
                                italic = false
                            )
                        ),
                        width = padX,
                        indent = padX,
                        isHardBreak = true
                    )
                )
                continue
            }

            var col = 0
            var currentSegs = mutableListOf<LineBreaker.LineSegment>()
            var maxLineX = padX

            for (ch in rawLine) {
                val wCols = ChineseTypography.eastAsianColWidth(ch)
                // 仅当单行极端超长（即便缩到 5.0pt 仍超出页面宽度）时才安全折行
                if (col + wCols > maxColsPerLine && currentSegs.isNotEmpty()) {
                    resultLines.add(
                        LineBreaker.Line(
                            segments = currentSegs,
                            width = maxLineX,
                            indent = padX,
                            isHardBreak = true
                        )
                    )
                    currentSegs = mutableListOf()
                    col = 0
                    maxLineX = padX
                }

                if (ch != ' ') {
                    val safeChar = measurer.resolveSupportedChar(ch, bold = false)
                    val charX = padX + col * halfEm
                    currentSegs.add(
                        LineBreaker.LineSegment(
                            text = safeChar.toString(),
                            x = charX,
                            fontSize = codeFontSize,
                            bold = false,
                            italic = false
                        )
                    )
                    maxLineX = charX + wCols * halfEm
                }
                col += wCols
            }

            if (currentSegs.isNotEmpty()) {
                resultLines.add(
                    LineBreaker.Line(
                        segments = currentSegs,
                        width = maxLineX,
                        indent = padX,
                        isHardBreak = true
                    )
                )
            }
        }

        return Paginator.BlockLines(
            lines = resultLines,
            isCodeBlock = true,
            customLineHeight = codeFontSize * 1.35f
        )
    }

    /**
     * 自动对齐 ASCII 框线图的右边框：
     * 针对以 `┌...┐` 开始、`└...┘` 结束的闭合框线块，若其内部为单栏框（不含 `┬┴┼` 分栏），
     * 自动按该框内最宽行的半角列数统一补齐横线 `─` 与每行末尾 `│` 前的空格，消除手写 Markdown 空格计数误差。
     */
    private fun normalizeAsciiBoxes(lines: List<String>): List<String> {
        val out = lines.toMutableList()
        var i = 0
        while (i < out.size) {
            val trimmedStart = out[i].trim()
            if (trimmedStart.startsWith('┌') && trimmedStart.endsWith('┐')) {
                // 向下寻找匹配的 └...┘
                var endIdx = -1
                var allBoxRows = true
                var hasMultiCol = false
                for (j in i until out.size) {
                    val t = out[j].trim()
                    if (t.isEmpty()) {
                        allBoxRows = false
                        break
                    }
                    val firstC = t.first()
                    val lastC = t.last()
                    if (firstC !in "┌├└│" || lastC !in "┐┤┘│") {
                        allBoxRows = false
                        break
                    }
                    if (t.any { it in "┬┴┼" }) {
                        hasMultiCol = true
                    }
                    if (firstC == '└' && lastC == '┘') {
                        endIdx = j
                        break
                    }
                }

                if (allBoxRows && endIdx > i && !hasMultiCol) {
                    val leadingSpaces = out[i].takeWhile { it == ' ' }
                    // 计算每一行去掉行尾边框符与尾随空格后的最小所需半角列数
                    var targetCols = 0
                    for (j in i..endIdx) {
                        val t = out[j].trim()
                        val firstC = t.first()
                        val lastC = t.last()
                        val isHorizRule = firstC in "┌├└" && lastC in "┐┤┘" &&
                            t.substring(1, t.length - 1).all { it == '─' || it == '-' }
                        val rowCols = if (isHorizRule) {
                            t.sumOf { ChineseTypography.eastAsianColWidth(it) }
                        } else {
                            val inner = t.substring(1, t.length - 1).trimEnd(' ')
                            1 + inner.sumOf { ChineseTypography.eastAsianColWidth(it) } + 1 + 1
                        }
                        if (rowCols > targetCols) targetCols = rowCols
                    }

                    for (j in i..endIdx) {
                        val t = out[j].trim()
                        val firstC = t.first()
                        val lastC = t.last()
                        val isHorizRule = firstC in "┌├└" && lastC in "┐┤┘" &&
                            t.substring(1, t.length - 1).all { it == '─' || it == '-' }
                        out[j] = if (isHorizRule) {
                            val dashCount = (targetCols - 2).coerceAtLeast(1)
                            leadingSpaces + firstC + "─".repeat(dashCount) + lastC
                        } else {
                            val inner = t.substring(1, t.length - 1).trimEnd(' ')
                            val innerCols = inner.sumOf { ChineseTypography.eastAsianColWidth(it) }
                            val padSpaces = (targetCols - 2 - innerCols).coerceAtLeast(1)
                            leadingSpaces + firstC + inner + " ".repeat(padSpaces) + lastC
                        }
                    }
                    i = endIdx + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * 结构化表格（Block.TableBlock）智能列宽分配与单元格折行排版引擎：
     *
     * 核心设计：
     * 1. **按列数自适应字号**：2 列采用 0.90x 正文字号，3 列 0.85x，4 列 0.80x，5 列及以上 0.74x（不低于 6.5pt）。
     * 2. **智能两段式列宽分配（防窄列挤压折行）**：
     *    - 先测量每列的最大自然单行宽度 `maxNaturalW[c]`；
     *    - 若所有列自然宽度之和 `<= availableWidth`，则按比例舒展铺满版心；
     *    - 若超出 `availableWidth`，对于自然宽度不超过平均列宽 `0.95x` 的紧凑列（如「营养素」「RNI」「状态」），
     *      直接分配其完整自然宽度使其**零折行**；剩余宽度按 `width^0.75` 权重分配给长文本描述列，
     *      并保证每列不低于最小可读宽度 `minColW`。
     * 3. **单元格内多样式折行**：支持单元格内粗体、斜体，且将单元格内每行标记为 `isHardBreak = true`，
     *    避免窄列文字被强制两端对齐拉出巨大字距空洞。
     */
    private fun layoutTableBlock(
        block: Block.TableBlock,
        availableWidth: Float,
        params: TypesettingParams
    ): Paginator.TableLayoutData? {
        val colCount = maxOf(
            block.headers.size,
            block.rows.maxOfOrNull { it.size } ?: 0
        )
        if (colCount <= 0) return null

        val scale = when {
            colCount <= 2 -> 0.90f
            colCount == 3 -> 0.85f
            colCount == 4 -> 0.80f
            else -> 0.74f
        }
        val tableFontSize = (params.fontSize * scale).coerceIn(6.5f, params.fontSize)
        val cellLineHeight = tableFontSize * 1.32f
        val cellPadX = (tableFontSize * 0.35f).coerceAtLeast(3f)
        val cellPadY = (tableFontSize * 0.30f).coerceAtLeast(2.5f)

        // 1) 测量每一列的最大自然宽度
        val minColW = (tableFontSize * 2.2f + cellPadX * 2f).coerceAtMost(availableWidth / colCount)
        val maxNaturalW = FloatArray(colCount) { minColW }

        fun measureCellNaturalWidth(runs: List<TextRun>, isHeader: Boolean): Float {
            if (runs.isEmpty()) return minColW
            var maxLineW = 0f
            var currentW = 0f
            for (run in runs) {
                val parts = run.text.split('\n')
                for ((idx, part) in parts.withIndex()) {
                    if (idx > 0) {
                        maxLineW = maxOf(maxLineW, currentW)
                        currentW = 0f
                    }
                    currentW += measurer.measureText(part, tableFontSize, bold = isHeader || run.bold)
                }
            }
            maxLineW = maxOf(maxLineW, currentW)
            // 表头略加 1.08 权重，避免表头文字差一两个像素被折行
            val factor = if (isHeader) 1.08f else 1.0f
            return (maxLineW * factor + cellPadX * 2f).coerceAtLeast(minColW)
        }

        for (c in 0 until colCount) {
            val hCell = block.headers.getOrNull(c)
            if (hCell != null) {
                maxNaturalW[c] = maxOf(maxNaturalW[c], measureCellNaturalWidth(hCell, isHeader = true))
            }
            for (row in block.rows) {
                val bCell = row.getOrNull(c)
                if (bCell != null) {
                    maxNaturalW[c] = maxOf(maxNaturalW[c], measureCellNaturalWidth(bCell, isHeader = false))
                }
            }
        }

        // 2) 计算每列最终宽度 colWidths
        val totalNatural = maxNaturalW.sum()
        val colWidths = FloatArray(colCount)
        if (totalNatural <= availableWidth) {
            val ratio = availableWidth / totalNatural.coerceAtLeast(1f)
            for (c in 0 until colCount) {
                colWidths[c] = maxNaturalW[c] * ratio
            }
        } else {
            val avgColW = availableWidth / colCount
            val compactThreshold = avgColW * 0.95f
            var lockedWidth = 0f
            val isCompact = BooleanArray(colCount)
            var flexCount = 0
            for (c in 0 until colCount) {
                if (maxNaturalW[c] <= compactThreshold) {
                    isCompact[c] = true
                    colWidths[c] = maxNaturalW[c]
                    lockedWidth += colWidths[c]
                } else {
                    flexCount++
                }
            }

            val remainingW = (availableWidth - lockedWidth).coerceAtLeast(flexCount * minColW)
            if (flexCount > 0 && lockedWidth < availableWidth * 0.75f) {
                val weights = FloatArray(colCount) { c ->
                    if (isCompact[c]) 0f else Math.pow(maxNaturalW[c].toDouble(), 0.72).toFloat()
                }
                val weightSum = weights.sum().coerceAtLeast(0.001f)
                for (c in 0 until colCount) {
                    if (!isCompact[c]) {
                        colWidths[c] = (remainingW * (weights[c] / weightSum)).coerceAtLeast(minColW)
                    }
                }
            } else {
                // 所有列都较宽：全部按阻尼权重分配
                val weights = FloatArray(colCount) { c ->
                    Math.pow(maxNaturalW[c].toDouble(), 0.72).toFloat()
                }
                val weightSum = weights.sum().coerceAtLeast(0.001f)
                for (c in 0 until colCount) {
                    colWidths[c] = (availableWidth * (weights[c] / weightSum)).coerceAtLeast(minColW)
                }
            }

            // 归一化使总宽度严格等于 availableWidth
            val finalSum = colWidths.sum().coerceAtLeast(1f)
            val normRatio = availableWidth / finalSum
            for (c in 0 until colCount) {
                colWidths[c] *= normRatio
            }
        }

        // 3) 对表头行与数据行执行单元格内折行
        fun buildRowLayout(cells: List<List<TextRun>>, isHeader: Boolean): Paginator.TableRowLayout {
            val cellLinesList = ArrayList<List<LineBreaker.Line>>(colCount)
            var maxLinesInRow = 1
            for (c in 0 until colCount) {
                val rawRuns = cells.getOrNull(c) ?: emptyList()
                val styledRuns = rawRuns.map { run ->
                    // 同时对其中的特殊字符执行字体可用性降级替换
                    val safeStr = buildString(run.text.length) {
                        for (ch in run.text) {
                            append(measurer.resolveSupportedChar(ch, bold = isHeader || run.bold))
                        }
                    }
                    run.copy(
                        text = safeStr,
                        bold = isHeader || run.bold,
                        fontSize = tableFontSize
                    )
                }
                val contentW = (colWidths[c] - cellPadX * 2f).coerceAtLeast(tableFontSize)
                val broken = lineBreaker.breakLines(
                    runs = styledRuns,
                    availableWidth = contentW,
                    fontSize = tableFontSize,
                    firstLineIndent = 0f,
                    leftIndent = 0f
                ).map { it.copy(isHardBreak = true) }
                cellLinesList.add(broken)
                if (broken.size > maxLinesInRow) {
                    maxLinesInRow = broken.size
                }
            }
            val rowHeight = maxLinesInRow * cellLineHeight + cellPadY * 2f
            return Paginator.TableRowLayout(
                cellLines = cellLinesList,
                height = rowHeight,
                isHeader = isHeader
            )
        }

        val headerRowLayout = if (block.headers.isNotEmpty()) {
            buildRowLayout(block.headers, isHeader = true)
        } else null

        val bodyRowLayouts = block.rows.map { rowCells ->
            buildRowLayout(rowCells, isHeader = false)
        }

        return Paginator.TableLayoutData(
            colWidths = colWidths.toList(),
            headerRow = headerRowLayout,
            bodyRows = bodyRowLayouts,
            cellPadX = cellPadX,
            cellPadY = cellPadY,
            cellLineHeight = cellLineHeight,
            tableFontSize = tableFontSize
        )
    }

    /**
     * 等比缩放图片至页面可用宽高范围内。
     *
     * 关键修复：
     * 旧版写死 maxHeight = lineHeight * 30f（默认 18pt * 30 = 540pt），
     * 而 6 英寸 Kindle 页面 availableHeight 仅为 310pt（346 - 36）。
     * 这导致所有竖版插图/封面缩放后高度在 320~400pt 之间，超出 maxBottom (328pt)，
     * 进而被 Paginator 的 `if (currentY + img.height <= maxBottom)` 判定为 false 静默丢弃！
     * 现改为严格以页面真实可用高度 maxHeightPt 为上限等比缩放，确保任何比例的图片均能完整显示。
     */
    private fun computeImageFit(
        block: Block.ImageBlock,
        maxWidthPt: Float,
        maxHeightPt: Float
    ): Pair<Float, Float> {
        val pxToPt = 0.75f
        val rawW = (block.widthPx * pxToPt).coerceAtLeast(1f)
        val rawH = (block.heightPx * pxToPt).coerceAtLeast(1f)
        val scaleW = if (rawW > maxWidthPt) maxWidthPt / rawW else 1f
        var w = rawW * scaleW
        var h = rawH * scaleW
        if (h > maxHeightPt) {
            val scaleH = maxHeightPt / h
            w *= scaleH
            h *= scaleH
        }
        return w.coerceAtLeast(1f) to h.coerceAtLeast(1f)
    }

    /**
     * 标题字号层级缩放系数（H1 ~ H6）：
     * 修复旧版在 baseScale = 1.5 时 H4 (1.5 * 0.62 = 0.93x) 比正文还小的问题，
     * 改为基于 (baseScale - 1.0) 的递减插值，确保 H1~H6 均大于等于正文字号且层次分明。
     */
    private fun headingScaleFor(level: Int, baseScale: Float): Float {
        val extra = (baseScale - 1.0f).coerceAtLeast(0.2f)
        return when (level) {
            1 -> 1.0f + extra * 1.00f   // 默认 1.50x
            2 -> 1.0f + extra * 0.72f   // 默认 1.36x
            3 -> 1.0f + extra * 0.48f   // 默认 1.24x
            4 -> 1.0f + extra * 0.28f   // 默认 1.14x
            5 -> 1.0f + extra * 0.14f   // 默认 1.07x
            else -> 1.0f + extra * 0.06f // 默认 1.03x
        }
    }
}

