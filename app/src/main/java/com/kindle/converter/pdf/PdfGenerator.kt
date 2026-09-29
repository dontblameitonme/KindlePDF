package com.kindle.converter.pdf

import android.content.Context
import com.kindle.converter.data.LayoutElement
import com.kindle.converter.data.PageLayout
import com.kindle.converter.data.TocEntry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.kindle.converter.typeset.ChineseTypography
import android.graphics.BitmapFactory
import java.io.File

/**
 * Generates PDF from PageLayout using PdfBox-Android.
 *
 * Key design decisions:
 * - Font is reloaded into the output document (PdfBox requirement)
 * - Y-axis is flipped: layout y from top → PDF y from bottom
 * - showText errors are caught per-segment (unsupported chars use fallback or are skipped)
 * - Synthetic bold (RenderingMode.FILL_STROKE) is applied when boldFont === regularFont
 * - Bookmarks are added from TOC entries
 */
class PdfGenerator(private val context: Context, private val fontManager: FontManager) {

    data class Bookmark(
        val title: String,
        val level: Int,
        val pageIndex: Int
    )

    /**
     * Generate PDF from typeset pages.
     *
     * @param pages List of PageLayout from the typesetting engine
     * @param bookmarks Optional bookmark entries (title, level, page index)
     * @param outputFile Destination file path
     * @return true on success
     */
    fun generate(
        pages: List<PageLayout>,
        bookmarks: List<Bookmark> = emptyList(),
        outputFile: File
    ): Boolean {
        if (pages.isEmpty()) return false

        val document = PDDocument()
        try {
            // Reload font into the output document
            val fontPair = fontManager.reloadFontForDocument(document) ?: return false
            val regularFont = fontPair.first
            val boldFont = fontPair.second ?: regularFont

            val pageRefs = mutableListOf<PDPage>()

            for (pageLayout in pages) {
                val page = PDPage(PDRectangle(pageLayout.width, pageLayout.height))
                document.addPage(page)
                pageRefs.add(page)

                val contentStream = PDPageContentStream(document, page)
                drawPage(contentStream, pageLayout, regularFont, boldFont, document)
                contentStream.close()
            }

            // Add bookmarks
            if (bookmarks.isNotEmpty()) {
                addBookmarks(document, bookmarks, pageRefs)
            }

            // Set document metadata
            document.documentInformation?.apply {
                setTitle("Kindle PDF")
                setCreator("Kindle PDF")
            }

            document.save(outputFile.absolutePath)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { document.close() } catch (e: Exception) {}
        }
    }

    private fun drawPage(
        contentStream: PDPageContentStream,
        pageLayout: PageLayout,
        regularFont: PDFont,
        boldFont: PDFont,
        document: PDDocument
    ) {
        val pageHeight = pageLayout.height

        for (element in pageLayout.elements) {
            when (element) {
                is LayoutElement.TextLine -> {
                    for (seg in element.segments) {
                        val font = if (seg.bold && boldFont !== regularFont) boldFont else regularFont
                        val needSyntheticBold = seg.bold && boldFont === regularFont
                        // Baseline: topY + ascent (approx fontSize * 0.8)
                        // PDF y is from bottom: pdfY = pageHeight - topY - ascent
                        val baselineY = pageHeight - element.topY - seg.fontSize * 0.8f

                        // Filter or fallback characters the font can't encode
                        val safeText = filterSupportedChars(seg.text, font)
                        if (safeText.isEmpty()) continue

                        // v9 修复（关键）：PDF 的 Tc 单位是 unscaled text space units，
                        // 在默认文本矩阵下就等于 pt，**不是** 1/1000 em。
                        val charSpacingTc = seg.charSpacing

                        try {
                            if (needSyntheticBold) {
                                // 伪粗体（Synthetic Bold）：当用户只导入了单字重 Regular 字体时，
                                // 使用 FILL_STROKE 描边填充模式加粗（描边宽度 0.032 * fontSize），
                                // 使标题、表头、行内 **加粗** 在 Kindle 墨水屏 PDF 上真正呈现黑体加粗效果。
                                contentStream.setLineWidth((seg.fontSize * 0.032f).coerceAtLeast(0.25f))
                            }
                            contentStream.beginText()
                            contentStream.setFont(font, seg.fontSize)
                            if (needSyntheticBold) {
                                contentStream.setRenderingMode(RenderingMode.FILL_STROKE)
                            }
                            if (charSpacingTc != 0f) {
                                contentStream.setCharacterSpacing(charSpacingTc)
                            }
                            contentStream.newLineAtOffset(seg.x, baselineY)
                            contentStream.showText(safeText)
                            if (needSyntheticBold) {
                                contentStream.setRenderingMode(RenderingMode.FILL)
                            }
                            contentStream.endText()
                            // 重置回 0，避免影响后续非 JUSTIFY segment
                            if (charSpacingTc != 0f) {
                                contentStream.setCharacterSpacing(0f)
                            }
                        } catch (e: Exception) {
                            try {
                                if (needSyntheticBold) {
                                    contentStream.setRenderingMode(RenderingMode.FILL)
                                }
                                contentStream.endText()
                            } catch (_: Exception) {}
                        }
                    }
                }

                is LayoutElement.RuleLine -> {
                    val y1 = pageHeight - element.y
                    val y2 = pageHeight - (element.y + element.height)
                    try {
                        contentStream.setLineWidth(element.strokeWidth)
                        contentStream.moveTo(element.x, y1)
                        contentStream.lineTo(element.x + element.width, y2)
                        contentStream.stroke()
                    } catch (e: Exception) {}
                }

                is LayoutElement.ImageElement -> {
                    drawImage(contentStream, element, pageHeight, document)
                }
            }
        }
    }

    /**
     * 在 ContentStream 上绘制一张图片。
     * Y 轴翻转：layout y 顶距 → PDF y（距页底）。
     * 单图失败不影响整页。
     */
    private fun drawImage(
        contentStream: PDPageContentStream,
        element: LayoutElement.ImageElement,
        pageHeight: Float,
        document: PDDocument
    ) {
        try {
            val pdImage = createImage(document, element.bytes, element.format) ?: return
            val pdfY = pageHeight - element.y - element.height
            contentStream.drawImage(pdImage, element.x, pdfY, element.width, element.height)
        } catch (e: Exception) {
            // 图片解码失败等异常，单张图片失败不影响整页
        }
    }

    /**
     * 根据格式构造 PDImageXObject。空 / 失败返回 null。
     * - JPEG/JPG：走 JPEGFactory.createFromByteArray
     * - 其他（PNG/未知）：先用 BitmapFactory 解码为 Android Bitmap，
     *   再走 LosslessFactory.createFromImage。LosslessFactory 不支持
     *   字节直构，所以必须经 Bitmap。
     */
    private fun createImage(document: PDDocument, bytes: ByteArray, format: String): PDImageXObject? {
        if (bytes.isEmpty()) return null
        val fmt = format.lowercase()
        return try {
            when (fmt) {
                "jpg", "jpeg" ->
                    JPEGFactory.createFromByteArray(document, bytes)
                else -> {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                    try {
                        LosslessFactory.createFromImage(document, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                try {
                    LosslessFactory.createFromImage(document, bitmap)
                } finally { bitmap.recycle() }
            } catch (_: Exception) { null }
        }
    }

    /**
     * Filter out characters that the font cannot encode, attempting ChineseTypography.glyphFallbacks
     * (e.g., ► -> →, ◦ -> ·, ▪ -> •) before dropping a character.
     */
    private fun filterSupportedChars(text: String, font: PDFont): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            try {
                font.encode(c.toString())
                sb.append(c)
            } catch (e: Exception) {
                val fallbacks = ChineseTypography.glyphFallbacks[c]
                if (fallbacks != null) {
                    for (fb in fallbacks) {
                        try {
                            font.encode(fb.toString())
                            sb.append(fb)
                            break
                        } catch (_: Exception) {
                            // try next fallback
                        }
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Build a hierarchical bookmark outline from flat bookmark entries.
     */
    private fun addBookmarks(
        document: PDDocument,
        bookmarks: List<Bookmark>,
        pages: List<PDPage>
    ) {
        try {
            val rootOutline: PDDocumentOutline = document.documentCatalog.documentOutline
                ?: PDDocumentOutline().also { document.documentCatalog.documentOutline = it }

            val stack = ArrayDeque<Pair<Int, PDOutlineNode>>()
            stack.addLast(0 to rootOutline)

            for (bookmark in bookmarks) {
                // Pop stack until we find a parent with lower level
                while (stack.isNotEmpty() && stack.last().first >= bookmark.level && stack.last().second !== rootOutline) {
                    stack.removeLast()
                }

                val parent: PDOutlineNode = stack.lastOrNull()?.second ?: rootOutline
                val targetPage = pages.getOrNull(bookmark.pageIndex)

                val outlineItem = PDOutlineItem()
                outlineItem.setTitle(bookmark.title)
                if (targetPage != null) {
                    outlineItem.setDestination(targetPage)
                }
                parent.addLast(outlineItem)

                // Push as potential parent for nested bookmarks
                stack.addLast(bookmark.level to outlineItem)
            }
        } catch (e: Exception) {
            // Bookmarks are nice-to-have, don't fail the PDF generation
        }
    }
}
