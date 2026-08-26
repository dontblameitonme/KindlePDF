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
// Strategy:
// - CJK characters: can break anywhere (each char is a break unit)
// - Latin text: break at word boundaries (spaces)
// - 避头: forbidden chars squeezed onto current line
// - 避尾: opening brackets keep going to next char
// - Style changes split into separate segments
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
        val indent: Float = 0f
    )

    private data class CharInfo(
        val char: Char,
        val bold: Boolean,
        val italic: Boolean,
        val fontSize: Float,
        val width: Float
    )

    fun breakLines(
        runs: List<TextRun>,
        availableWidth: Float,
        fontSize: Float,
        firstLineIndent: Float = 0f,
        leftIndent: Float = 0f
    ): List<Line> {
        if (runs.isEmpty()) return emptyList()

        // 1) 把 runs 平展为字符数组
        val chars = ArrayList<CharInfo>(runs.sumOf { it.text.length })
        for (run in runs) {
            val effectiveSize = run.fontSize ?: fontSize
            for (c in run.text) {
                chars.add(CharInfo(c, run.bold, run.italic, effectiveSize,
                    measurer.measureChar(c, effectiveSize, run.bold)))
            }
        }
        if (chars.isEmpty()) return emptyList()

        val lines = mutableListOf<Line>()
        var pos = 0
        var isFirstLine = true

        while (pos < chars.size) {
            val indent = (if (isFirstLine) firstLineIndent else 0f) + leftIndent
            // 关键修复：可用内容宽度要扣除 indent，否则带首行缩进/列表缩进的行会向右溢出 marginRight
            val effectiveWidth = (availableWidth - indent).coerceAtLeast(0f)
            var x = indent
            val segs = mutableListOf<LineSegment>()
            var buf = StringBuilder()
            var bufBold = chars[pos].bold
            var bufItalic = chars[pos].italic
            var bufFontSize = chars[pos].fontSize
            var bufStartX = x

            // Skip leading whitespace
            while (pos < chars.size && chars[pos].char.isWhitespace()) {
                pos++
            }
            if (pos >= chars.size) break

            bufBold = chars[pos].bold
            bufItalic = chars[pos].italic
            bufFontSize = chars[pos].fontSize

            // 收尾函数：把缓冲区落成一个 segment。
            // v9：移除原来的"尾部全角标点压半角"——它只把 x 改小而不改变实际绘制文本，
            // 属于测量说谎（measured < drawn），会让 CENTER/RIGHT 位移偏半个字、
            // 并破坏 v8 建立的"测量宽度 == 绘制宽度"不变量。
            fun flushBuffer() {
                if (buf.isNotEmpty()) {
                    segs.add(LineSegment(buf.toString(), bufStartX, bufFontSize, bufBold, bufItalic))
                    buf = StringBuilder()
                }
            }

            var lineBroken = false
            while (pos < chars.size) {
                val ci = chars[pos]
                val c = ci.char
                val remainingWidth = effectiveWidth - (x - indent)

                // Overflow check
                if (ci.width > remainingWidth && buf.isNotEmpty()) {
                    // 避头: forbidden at line start → squeeze onto current line
                    if (c in ChineseTypography.lineStartForbidden) {
                        if (ci.bold != bufBold || ci.italic != bufItalic || ci.fontSize != bufFontSize) {
                            flushBuffer()
                            bufStartX = x
                            bufBold = ci.bold
                            bufItalic = ci.italic
                            bufFontSize = ci.fontSize
                        }
                        buf.append(c)
                        x += ci.width
                        pos++
                        continue
                    }

                    // 避尾: last char forbidden at line end → keep going
                    if (buf.isNotEmpty() && buf.last() in ChineseTypography.lineEndForbidden) {
                        if (ci.bold != bufBold || ci.italic != bufItalic || ci.fontSize != bufFontSize) {
                            flushBuffer()
                            bufStartX = x
                            bufBold = ci.bold
                            bufItalic = ci.italic
                            bufFontSize = ci.fontSize
                        }
                        buf.append(c)
                        x += ci.width
                        pos++
                        continue
                    }

                    // Latin text: try to break at last word boundary
                    if (!ChineseTypography.isCJK(c)) {
                        val lastSpaceIdx = buf.lastIndexOf(' ')
                        if (lastSpaceIdx > 0) {
                            // v9 修复三处 bug：
                            //  1) 原来在 flushBuffer() 之后才读 buf.length（此时 buf 已被清空为 0），
                            //     charsAfterSpace 变成负数 → pos -= 负数 = pos 前跳 → 直接吞掉正文字符。
                            //  2) 原来 lines.add(Line(segs, x)) 漏传 indent（默认 0），Paginator 计算
                            //     两端对齐时会少补一个缩进宽度，该行因此偏短 → 右边界参差。
                            //  3) 原来只把 segment 文本截断到空格前，却没有从 x 回退被丢弃字符的宽度，
                            //     line.width 偏大 → 两端对齐补的 extra 偏小 → 该行再次偏短。
                            val bufLen = buf.length
                            // buf 的内容与 chars[pos-bufLen until pos] 一一对应（每次 append 都伴随 pos++）
                            val rollbackStart = pos - bufLen + lastSpaceIdx
                            var rollbackWidth = 0f
                            for (k in rollbackStart until pos) rollbackWidth += chars[k].width
                            x -= rollbackWidth

                            buf.setLength(lastSpaceIdx)   // 截断到空格之前（空格本身丢弃）
                            flushBuffer()
                            pos = rollbackStart + 1       // 从空格之后的第一个字符重新排

                            lines.add(Line(segs.toList(), x, indent))
                            lineBroken = true
                            break
                        }
                    }

                    // Normal line break
                    flushBuffer()
                    lines.add(Line(segs.toList(), x, indent))
                    lineBroken = true
                    break
                }

                // Style change → flush and start new segment
                if (ci.bold != bufBold || ci.italic != bufItalic || ci.fontSize != bufFontSize) {
                    flushBuffer()
                    bufStartX = x
                    bufBold = ci.bold
                    bufItalic = ci.italic
                    bufFontSize = ci.fontSize
                }

                buf.append(c)
                x += ci.width
                pos++
            }

            // 自然收尾（pos >= chars.size）：本行即段落末行
            if (!lineBroken && pos >= chars.size) {
                flushBuffer()
                if (segs.isNotEmpty()) {
                    lines.add(Line(segs.toList(), x, indent))
                }
            }

            isFirstLine = false
        }

        return lines
    }
}

// ============================================================
// Paginator — Page breaking with orphan/widow control
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
                    // 这样 PdfGenerator 渲染时图片先画、文字后画，视觉上文字压在图上层
                    val placedImg = img.copy(x = 0f, y = 0f)
                    currentElements.add(0, placedImg)
                    // 不推进 currentY —— 后续文字照样从原位排起，会"叠在"图片上
                    continue
                }

                // 上下型（默认）：独占一行，按图片 height 占位
                if (currentY + img.height + params.paragraphSpacing > maxBottom && currentElements.isNotEmpty()) {
                    newPage()
                }
                if (currentY + img.height <= maxBottom) {
                    // 关键修复：把图片 y 设为 currentY 后再 add，否则 y 留 0 会让图片画在顶端盖住文字
                    currentElements.add(img.copy(x = params.marginLeft, y = currentY))
                    currentY += img.height + params.paragraphSpacing
                }
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

                // Orphan control: first line of paragraph shouldn't be alone at page bottom
                if (entry.isHeading.not() && isFirstLineOfBlock &&
                    params.enableOrphanControl && lines.size > 1) {
                    if (currentY + lineHeight * 2 > maxBottom && currentY > params.marginTop + 1f) {
                        newPage()
                    }
                }

                // Page break if line doesn't fit
                if (currentY + lineHeight > maxBottom && currentElements.isNotEmpty()) {
                    newPage()
                }

                // ---- 行内水平对齐 ----
                // line.width = indent + content_width（indent = 首行缩进 + 列表/引用左缩进）
                val lineIndent = line.indent
                val lineContentWidth = (line.width - lineIndent).coerceAtLeast(0f)
                val lineFontSize = line.segments.firstOrNull()?.fontSize ?: params.fontSize

                // 本行真正可用的横向宽度（已扣掉缩进）
                val avail = (params.availableWidth - lineIndent).coerceAtLeast(0f)
                // diff > 0：还差这么多才顶到右页边；diff < 0：已经越过右页边（避头/避尾挤字造成）
                val diff = avail - lineContentWidth

                // 可分摊字距的间隙数。
                // 必须按「全部字形」计数（含空格）：PDF 的 Tc 对每个被绘制的字形都生效，
                // 只数非空白字符会低估总位移，加了字距后仍然溢出右页边。
                val glyphCount = line.segments.sumOf { it.text.length }
                val gaps = glyphCount - 1

                // 强制对齐：LEFT / JUSTIFY 下把非段末行一律撑满到右页边，消除凹凸不平。
                // CENTER / RIGHT 是用户明确要的视觉效果，不被强制对齐覆盖。
                val forceJustify = params.forceAlign &&
                    (params.textAlignment == TextAlignment.LEFT ||
                        params.textAlignment == TextAlignment.JUSTIFY)

                val stretchActive = (params.textAlignment == TextAlignment.JUSTIFY || forceJustify) &&
                    !isLastLineOfBlock && !entry.isHeading && diff > 0.5f

                // 拉伸上限 0.25em：避免一个超长英文单词换行后留下的大空隙被摊成夸张字距
                val maxStretch = lineFontSize * 0.25f
                // 压缩下限 0.15em：再紧就糊在一起了
                val maxCompress = lineFontSize * 0.15f

                val charSpacing = when {
                    gaps <= 0 -> 0f
                    // 溢出回收：与开关无关，始终生效，保证正文不越过右页边
                    diff < -0.01f -> (diff / gaps).coerceAtLeast(-maxCompress)
                    stretchActive -> (diff / gaps).coerceAtMost(maxStretch)
                    else -> 0f
                }

                // 字距生效后本行的实际绘制宽度（CENTER / RIGHT 要按它算位移）
                val drawnWidth = lineContentWidth + charSpacing * gaps

                val shift = when (params.textAlignment) {
                    TextAlignment.LEFT, TextAlignment.JUSTIFY -> 0f
                    TextAlignment.RIGHT -> (avail - drawnWidth).coerceAtLeast(0f)
                    TextAlignment.CENTER -> ((avail - drawnWidth) / 2f).coerceAtLeast(0f)
                }

                // 同一行内如果有多个 segment（粗体/字号变化切出来的），
                // 后续 segment 的 x 是按"无字距"算的，加了 charSpacing 后必须按
                // 它前面已绘制的字形数累计补偿，否则会与前一个 segment 重叠。
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
     *
     * 实现策略：取本页最顶元素 topY 与最底元素 bottomY，计算内容真实占用；
     * 把每页的每个 element 整体加一个 deltaY。
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
                    // 把可分配空白均匀摊入每个 TextLine 之间（最后一行后不补）
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

    /**
     * 把 freeSpace 均匀摊入 TextLine 之间。第 k 行向下平移 (k-1) * perGap，
     * 使每两行间距都增加 perGap，整页底部对齐 maxBottom。
     * 图片 / RuleLine 不参与分配，位置不变。
     */
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
                    val lines = lineBreaker.breakLines(
                        block.runs, availableWidth, headingFontSize,
                        firstLineIndent = 0f
                    )
                    blockLinesList.add(Paginator.BlockLines(lines, isHeading = true, headingLevel = block.level))
                }

                is Block.ListItem -> {
                    val indent = block.level * 20f
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
                    val indent = 20f
                    val lines = lineBreaker.breakLines(
                        block.runs, availableWidth, params.fontSize,
                        paragraphFirstLineIndent,
                        leftIndent = indent
                    )
                    blockLinesList.add(Paginator.BlockLines(lines))
                }

                is Block.CodeBlock -> {
                    val codeFontSize = params.fontSize * 0.85f
                    val indent = 16f
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
                    // 用户选择"不显示图片" → 直接跳过整个块
                    if (params.imageWrapMode == ImageWrapMode.HIDE_IMAGES) continue

                    val (drawW, drawH) = if (params.imageWrapMode == ImageWrapMode.BEHIND_TEXT) {
                        // 衬于文字下方：图片撑满整页（与页面同宽高），由 Paginator 放最底层
                        params.pageWidth to params.pageHeight
                    } else {
                        // 上下型：等比缩放至 availableWidth，按 96 DPI 把像素换算为 pt
                        computeImageFit(block, params.availableWidth, lineHeight)
                    }
                    val imgEl = LayoutElement.ImageElement(
                        bytes = block.bytes,
                        format = block.format,
                        x = params.marginLeft,
                        y = 0f,  // 由 Paginator 决定 y（BEHIND_TEXT 时 y=0；上下型时 y=currentY）
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
     * 等比缩放图片至最大宽度，并确保高度不超过一页高度的 1/3（避免单页一张大图）。
     * - 源尺寸是像素，目标尺寸是 pt（按 96 dpi：1 px ≈ 0.75 pt）。
     */
    private fun computeImageFit(
        block: Block.ImageBlock,
        maxWidthPt: Float,
        lineHeight: Float
    ): Pair<Float, Float> {
        val pxToPt = 0.75f
        val rawW = (block.widthPx * pxToPt).coerceAtLeast(1f)
        val rawH = (block.heightPx * pxToPt).coerceAtLeast(1f)
        val scale = if (rawW > maxWidthPt) maxWidthPt / rawW else 1f
        var w = rawW * scale
        var h = rawH * scale
        // 限制高度：最多占一页的 1/3 内容区，避免撑不下
        val maxHeight = lineHeight * 30f
        if (h > maxHeight) {
            val s = maxHeight / h
            w *= s
            h *= s
        }
        return w to h
    }

    private fun headingScaleFor(level: Int, baseScale: Float): Float {
        // H1 = fontSize * baseScale, H2 = * baseScale^0.85, etc.
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
