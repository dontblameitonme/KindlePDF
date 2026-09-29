package com.kindle.converter.data

// ============================================================
// IR: Unified Intermediate Representation
// All parsers output this format-agnostic document tree.
// ============================================================

data class Document(
    val blocks: List<Block>,
    val toc: List<TocEntry> = emptyList(),
    val title: String = "",
    val author: String = ""
)

sealed class Block {
    data class Paragraph(val runs: List<TextRun>) : Block()
    data class Heading(val level: Int, val runs: List<TextRun>) : Block()
    data class ListItem(
        val level: Int,
        val ordered: Boolean,
        val orderIndex: Int = 0,
        val runs: List<TextRun>
    ) : Block()
    data class QuoteBlock(val runs: List<TextRun>) : Block()
    data class CodeBlock(val text: String, val language: String? = null) : Block()
    data class ImageBlock(
        val bytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
        val format: String = "png"
    ) : Block()
    /**
     * 结构化数据表格块（由 Markdown 管道表格、HTML/EPUB `<table>` 或 DOCX `<w:tbl>` 解析生成）。
     * @param headers 表头行各列单元格的富文本列表（可为空列表表示无独立表头）
     * @param rows 数据行列表，每行包含各列单元格的富文本（List<TextRun>）
     */
    data class TableBlock(
        val headers: List<List<TextRun>>,
        val rows: List<List<List<TextRun>>>
    ) : Block()
    /**
     * 水平分隔线块（对应 Markdown `---` / `***` 或 HTML `<hr>`）。
     */
    object HorizontalRule : Block()
}

data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSize: Float? = null,
    val color: Int? = null
)

data class TocEntry(
    val title: String,
    val level: Int,
    val blockIndex: Int
)

/**
 * 解析上下文：把用户在 UI 上设置的排版偏好透传给 PDF 解析器，
 * 让 PDF 解析阶段就能根据用户选择决定是否丢弃页眉页脚、是否抓图片等。
 *
 * 设计原因：PDF 解析是 typeset 之前的预处理步骤；
 * 用户在 UI 上已经选定 stripHeadersFooters / imageWrapMode 后，
 * 解析器需要照办，避免 typeset 之后再回头改 IR（成本高且容易丢信息）。
 */
data class ParseContext(
    val stripHeadersFooters: Boolean = true,
    val imageWrapMode: ImageWrapMode = ImageWrapMode.TOP_AND_BOTTOM
)

// ============================================================
// PageLayout: Output of the typesetting engine.
// Consumed by both the PDF generator and the Compose preview.
// ============================================================

data class PageLayout(
    val width: Float,
    val height: Float,
    val elements: List<LayoutElement>
)

sealed class LayoutElement {
    data class TextLine(
        val segments: List<PositionedSegment>,
        val topY: Float,
        val lineHeight: Float,
        val fontSize: Float
    ) : LayoutElement()

    data class ImageElement(
        val bytes: ByteArray,
        val format: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) : LayoutElement()

    data class RuleLine(
        val y: Float,
        val x: Float,
        val width: Float,
        val strokeWidth: Float,
        /**
         * 线条垂直跨度（默认 0f 表示从 (x, y) 到 (x + width, y) 的水平线；
         * 若 height > 0f 且 width == 0f，则表示从 (x, y) 到 (x, y + height) 的垂直线，
         * 用于绘制表格列边框与引用块左侧装饰竖线）。
         */
        val height: Float = 0f
    ) : LayoutElement()
}

data class PositionedSegment(
    val text: String,
    val x: Float,
    val fontSize: Float,
    val bold: Boolean,
    val italic: Boolean,
    /**
     * JUSTIFY 时由 Paginator 按行级计算后下发到整行的每个 segment；
     * 渲染端（PDF 与预览）在每字符上累加该值，让字符均匀撑满行宽。
     * 单位：pt / 字符（绝对值）。非 JUSTIFY 时为 0f。
     * 物理意义：相比把 extra 直接加到 segment.x，这种实现不会让"立 那地力"那种
     * 中间空格变成大空隙——而是把字符间距均匀增大/缩小。
     */
    val charSpacing: Float = 0f
)

// ============================================================
// TypesettingParams: User-adjustable parameters
// ============================================================

data class TypesettingParams(
    val pageWidth: Float = 258f,
    val pageHeight: Float = 346f,
    val marginTop: Float = 18f,
    val marginBottom: Float = 18f,
    val marginLeft: Float = 18f,
    val marginRight: Float = 18f,
    val fontSize: Float = 12f,
    val lineHeight: Float = 1.5f,
    val paragraphSpacing: Float = 3.6f,
    val firstLineIndent: Float = 24f,
    val textAlignment: TextAlignment = TextAlignment.LEFT,
    /**
     * 强制对齐（默认开）。开启后在「左对齐 / 两端对齐」下，把每个段落除末行以外的所有行
     * 一律用字符间距撑满到右页边，消除"忽长忽短、凹凸不平"的参差边界。
     *
     * - 撑满上限 0.25em：留下的空隙过大时（多为超长英文单词换行）不再硬撑，避免夸张字距。
     * - 「居中 / 右对齐」不受此开关影响，那是用户明确选择的视觉效果。
     * - 与本开关无关、始终生效的是"溢出回收"：正文越过右页边时用负字距拉回页内。
     */
    val forceAlign: Boolean = true,
    /**
     * 页面内文本的垂直分布方式（对应 Word 「页面设置 → 布局 → 垂直对齐方式」）。
     * - TOP（默认）：从顶端开始排起，留白在底部
     * - CENTER：整块内容垂直居中，留白均分在顶/底
     * - JUSTIFY：剩余空白按行高均匀摊到每两行之间（最后一行后无空白）
     */
    val verticalAlignment: VerticalAlignment = VerticalAlignment.TOP,
    /**
     * PDF / docx / epub 中的图片与文字的环绕方式（对应 Word 「图片 → 环绕」）。
     * - TOP_AND_BOTTOM（默认）：上下型。图片独占一行宽度，前后段落与图片之间用 paragraphSpacing 隔开；最贴合 Kindle 阅读。
     * - BEHIND_TEXT：衬于文字下方。图片作为该页背景层；文字正常排版但绘制在图片之上（页面看起来像"水印/插图"）。
     * - HIDE_IMAGES：不显示图片。抽到的图直接丢弃，仅排文字。
     */
    val imageWrapMode: ImageWrapMode = ImageWrapMode.TOP_AND_BOTTOM,
    /**
     * 是否在 PDF 解析阶段自动丢弃页眉 / 页脚 / 页码。
     * 判定规则见 PdfParser.cleanPageMargins：首行 y < 8% 页高 + 文本模式 = 章节名/页眉丢弃；
     * 末行 y > 92% 页高 或纯数字 = 页码丢弃。仅对 PDF 输入有效；docx/epub 没有页眉页脚问题。
     */
    val stripHeadersFooters: Boolean = true,
    val boldHeadings: Boolean = true,
    val headingScale: Float = 1.4f,
    val enableOrphanControl: Boolean = true,
    val enableWidowControl: Boolean = true
) {
    val availableWidth: Float get() = pageWidth - marginLeft - marginRight
    val availableHeight: Float get() = pageHeight - marginTop - marginBottom
    val lineHeightInPoints: Float get() = fontSize * lineHeight

    companion object {
        // mm to points conversion (1 inch = 25.4mm = 72pt)
        fun mmToPoints(mm: Float): Float = mm * 72f / 25.4f

        // Kindle 页面尺寸预设（宽 × 高，单位 mm，竖版）
        val KINDLE_PRESETS = listOf(
            PageSizePreset("Kindle 6英寸（基础版/Paperwhite）", 91f, 122f),
            PageSizePreset("Kindle Oasis 7英寸", 103f, 140f),
            PageSizePreset("Kindle Voyage 6英寸", 91f, 122f),
            PageSizePreset("A4（仅测试用）", 210f, 297f)
        )
    }
}

data class PageSizePreset(
    val name: String,
    val widthMm: Float,
    val heightMm: Float
)

/**
 * 段落文字对齐方式。
 * - LEFT：左对齐（默认，含首行缩进）
 * - CENTER：居中（首行缩进无效）
 * - RIGHT：右对齐（首行缩进无效）
 * - JUSTIFY：两端对齐（最后一行退化为左对齐，首行缩进无效）
 */
enum class TextAlignment(val displayName: String) {
    LEFT("左对齐"),
    CENTER("居中"),
    RIGHT("右对齐"),
    JUSTIFY("两端对齐");

    companion object {
        val ALL: List<TextAlignment> = values().toList()
    }
}

/**
 * 页面内文本垂直对齐方式，对应 Word 「垂直对齐方式」。
 * - TOP（默认）：内容从顶部开始，留白集中在底部
 * - CENTER：内容垂直居中，顶/底留白相等
 * - JUSTIFY：剩余空白按行高均匀摊入行间（最后一行后无空白）
 */
enum class VerticalAlignment(val displayName: String) {
    TOP("顶端"),
    CENTER("居中"),
    JUSTIFY("两端对齐");

    companion object {
        val ALL: List<VerticalAlignment> = values().toList()
    }
}

/**
 * 图片与文字的环绕方式，对应 Word 「图片 → 环绕」。
 * - TOP_AND_BOTTOM：上下型（推荐）。图片独占整行，前后段落用 paragraphSpacing 与图片隔开。
 * - BEHIND_TEXT：衬于文字下方。图片当整页背景，文字正常排版并绘制在图上层。
 * - HIDE_IMAGES：不显示图片。抽到的图直接丢弃。
 */
enum class ImageWrapMode(val displayName: String) {
    TOP_AND_BOTTOM("上下型"),
    BEHIND_TEXT("衬于文字下方"),
    HIDE_IMAGES("不显示图片");

    companion object {
        val ALL: List<ImageWrapMode> = values().toList()
    }
}
