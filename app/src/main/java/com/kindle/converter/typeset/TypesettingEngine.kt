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
        val font = if (bold && boldFont != null) boldFont else regularFont
        var width = 0f
        for (c in text) {
            width += measureChar(c, fontSize, bold)
        }
        return width
    }

    fun measureChar(c: Char, fontSize: Float, bold: Boolean = false): Float {
        val font = if (bold && boldFont != null) boldFont else regularFont
        return try {
            font.getStringWidth(c.toString()) / 1000f * fontSize
        } catch (e: Exception) {
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

    data class BlockLines(
        val lines: List<LineBreaker.Line>,
        val isHeading: Boolean = false,
        val headingLevel: Int = 0,
        val isListItem: Boolean = false,
        /** 非空时代表这是一个图片块，整个块只放这一个 ImageElement */
        val imageElement: LayoutElement.ImageElement? = null
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
            val lines = entry.lines
            if (lines.isEmpty() && entry.imageElement == null) continue

            // ----- 图片块：根据 imageWrapMode 分发 -----
            if (entry.imageElement != null) {
                val img = entry.imageElement

                if (params.imageWrapMode == ImageWrapMode.BEHIND_TEXT) {
                    // 衬于文字下方：图片填满整页，固定 y=0，添加在当前页最前（绘制顺序 = 在文字之前）
                    val placedImg = img.copy(x = 0f, y = 0f)
                    currentElements.add(0, placedImg)
                    continue
                }

                // 上下型（默认）：独占一行，按图片 height 占位。
                // 修复说明：
                // 1) 若当前页剩余高度放不下图片且当前页已有内容，先换新页；
                // 2) 换到新页后，即使图片高度接近 availableHeight（含 paragraphSpacing 可能略超），
                //    也必须二次钳制高度并正常绘制，绝不能因为 currentY + img.height > maxBottom 把图片静默丢弃！
                // 3) 图片宽度小于版心宽度时水平居中放置，视觉更平衡。
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

            // Heading: keep with at least 1 line of following content
            if (entry.isHeading && currentElements.isNotEmpty()) {
                val headingHeight = lines.size * lineHeight + lineHeight
                if (currentY + headingHeight > maxBottom) {
                    newPage()
                }
            }

            for ((lineIdx, line) in lines.withIndex()) {
                val isLastLineOfBlock = lineIdx == lines.size - 1
                val isFirstLineOfBlock = lineIdx == 0

                // 孤行控制（Orphan control）：段落首行不单独留在页面最底部
                if (!entry.isHeading && isFirstLineOfBlock &&
                    params.enableOrphanControl && lines.size > 1
                ) {
                    if (currentY + lineHeight * 2 > maxBottom && currentY > params.marginTop + 1f) {
                        newPage()
                    }
                }

                // 寡行控制（Widow control）：段落末行不单独落在下一页最顶部
                // 当排到倒数第 2 行时，若当前页只够放倒数第 2 行而放不下最后 1 行，
                // 提前换页，让最后 2 行一起落到下一页。
                if (!entry.isHeading && lineIdx == lines.size - 2 &&
                    params.enableWidowControl && lines.size >= 3
                ) {
                    if (currentY + lineHeight * 2 > maxBottom && currentY > params.marginTop + 1f) {
                        newPage()
                    }
                }

                // Page break if line doesn't fit
                if (currentY + lineHeight > maxBottom && currentElements.isNotEmpty()) {
                    newPage()
                }

                // ---- 行内水平对齐与标点悬挂（GB/T 15834 & W3C CLREQ） ----
                val lineIndent = line.indent
                val rawContentWidth = (line.width - lineIndent).coerceAtLeast(0f)
                val lineFontSize = line.segments.firstOrNull()?.fontSize ?: params.fontSize

                // 本行真正可用的横向宽度（已扣掉缩进）
                val avail = (params.availableWidth - lineIndent).coerceAtLeast(0f)

                // 核心修复：解决「行尾标点导致前置文字间距被压缩」的问题
                // 1) 当行末未溢出（rawContentWidth <= avail）时，标点本来就是该行正常字格的一部分，
                //    按 rawContentWidth 计算正向拉伸，使该行与上下行的汉字网格严格对齐（抄栅格）。
                // 2) 当行末因「避头」挤入了标点导致 rawContentWidth > avail 时，启动「标点悬挂」：
                //    扣除行尾标点的右半格空白与光学悬挂量（line.trailingOpticalHangWidth），
                //    让行内正文依然按照正常行宽微拉伸或保持原字距，绝不对正文施加负 charSpacing 压缩！
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

                val isEndLine = isLastLineOfBlock || line.isHardBreak
                val stretchActive = (params.textAlignment == TextAlignment.JUSTIFY || forceJustify) &&
                    !isEndLine && !entry.isHeading && diff > 0.5f

                // 拉伸上限 0.25em，压缩下限 0.15em
                val maxStretch = lineFontSize * 0.25f
                val maxCompress = lineFontSize * 0.15f

                val charSpacing = when {
                    gaps <= 0 -> 0f
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

                val shift = when (params.textAlignment) {
                    TextAlignment.LEFT, TextAlignment.JUSTIFY -> 0f
                    TextAlignment.RIGHT -> (avail - drawnWidth).coerceAtLeast(0f)
                    TextAlignment.CENTER -> ((avail - drawnWidth) / 2f).coerceAtLeast(0f)
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
                        lineHeight = lineHeight,
                        fontSize = segments.firstOrNull()?.fontSize ?: params.fontSize
                    )
                )

                currentY += lineHeight
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
        is LayoutElement.RuleLine -> el.y
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
                    // 修复：解析器产生的一级列表 level = 0，旧代码 level * 20f 算得 0 导致一级列表无缩进；
                    // 改为相对 fontSize 的动态缩进：(level + 1) * 1.25em。
                    val indent = (block.level + 1) * params.fontSize * 1.25f
                    val prefix = if (block.ordered) "${block.orderIndex}. " else "• "
                    val runsWithPrefix = listOf(TextRun(prefix)) + block.runs
                    val lines = lineBreaker.breakLines(
                        runsWithPrefix, availableWidth, params.fontSize,
                        firstLineIndent = 0f,
                        leftIndent = indent
                    )
                    blockLinesList.add(Paginator.BlockLines(lines, isListItem = true))
                }

                is Block.QuoteBlock -> {
                    // 引用块左缩进与字号联动（1.5em），避免大字号下缩进不明显、小字号下缩进过宽
                    val indent = params.fontSize * 1.5f
                    val lines = lineBreaker.breakLines(
                        block.runs, availableWidth, params.fontSize,
                        paragraphFirstLineIndent,
                        leftIndent = indent
                    )
                    blockLinesList.add(Paginator.BlockLines(lines))
                }

                is Block.CodeBlock -> {
                    val codeFontSize = params.fontSize * 0.85f
                    val indent = params.fontSize * 1.0f
                    val codeLines = block.text.split('\n').flatMap { codeLine ->
                        lineBreaker.breakLines(
                            listOf(TextRun(codeLine.ifEmpty { " " })),
                            availableWidth, codeFontSize,
                            firstLineIndent = 0f,
                            leftIndent = indent
                        )
                    }
                    blockLinesList.add(Paginator.BlockLines(codeLines))
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

    private fun headingScaleFor(level: Int, baseScale: Float): Float {
        return when (level) {
            1 -> baseScale
            2 -> baseScale * 0.85f
            3 -> baseScale * 0.72f
            4 -> baseScale * 0.62f
            5 -> baseScale * 0.55f
            else -> baseScale * 0.5f
        }
    }
}
