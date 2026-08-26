package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document as MdDocument
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

class MarkdownParser {

    private val parser: Parser = Parser.builder().build()

    /**
     * 解析 Markdown 为 Document。
     * @param imageResolver 远程/本地图片解析回调（返回 null 表示跳过该图）。
     *   用于把 md 中的 ![alt](url) 转成 ImageBlock 并渲染进 PDF。
     */
    suspend fun parse(
        markdown: String,
        imageResolver: suspend (String) -> DownloadedImage? = { null }
    ): Document {
        val mdDoc = parser.parse(markdown)
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()

        var child = mdDoc.firstChild
        while (child != null) {
            when (child) {
                is Heading -> {
                    val inlines = extractInlines(child)
                    val runs = collectTextRuns(inlines)
                    if (runs.isNotEmpty()) {
                        blocks.add(Block.Heading(child.level, runs))
                        val title = runs.joinToString("") { it.text }
                        toc.add(TocEntry(title, child.level, blocks.lastIndex))
                    }
                }

                is Paragraph -> {
                    emitInlineBlocks(extractInlines(child), blocks, imageResolver)
                }

                is BulletList -> {
                    var li = child.firstChild
                    while (li != null) {
                        if (li is ListItem) {
                            emitInlineBlocks(
                                extractInlines(li), blocks, imageResolver,
                                asListItem = true, ordered = false
                            )
                        }
                        li = li.next
                    }
                }

                is OrderedList -> {
                    var li = child.firstChild
                    var idx = child.startNumber
                    while (li != null) {
                        if (li is ListItem) {
                            emitInlineBlocks(
                                extractInlines(li), blocks, imageResolver,
                                asListItem = true, ordered = true, orderIndex = idx
                            )
                            idx++
                        }
                        li = li.next
                    }
                }

                is BlockQuote -> {
                    val runs = collectTextRuns(extractInlines(child))
                    if (runs.isNotEmpty()) blocks.add(Block.QuoteBlock(runs))
                }

                is FencedCodeBlock -> {
                    blocks.add(Block.CodeBlock(child.literal.trimEnd('\n'), child.info))
                }

                is IndentedCodeBlock -> {
                    blocks.add(Block.CodeBlock(child.literal.trimEnd('\n'), null))
                }

                is ThematicBreak -> {
                    blocks.add(Block.Paragraph(listOf(TextRun("---"))))
                }
            }
            child = child.next
        }

        return Document(blocks, toc)
    }

    // ---- 内联抽取 ----

    private sealed class InlineItem {
        data class Text(val runs: List<TextRun>) : InlineItem()
        data class ImageRef(val url: String) : InlineItem()
    }

    /**
     * 递归抽取段落/标题内的内联元素，保留文字与图片的相对顺序。
     * 文字合并为 TextRun；![alt](url) 抽成 ImageRef。
     */
    private fun extractInlines(
        node: org.commonmark.node.Node,
        parentBold: Boolean = false,
        parentItalic: Boolean = false
    ): List<InlineItem> {
        val items = mutableListOf<InlineItem>()
        val buf = StringBuilder()
        var bufBold = parentBold
        var bufItalic = parentItalic

        fun flush() {
            if (buf.isNotEmpty()) {
                items.add(InlineItem.Text(listOf(TextRun(buf.toString(), bufBold, bufItalic))))
                buf.clear()
            }
        }

        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> buf.append(child.literal)

                is Code -> {
                    flush()
                    items.add(InlineItem.Text(listOf(TextRun(child.literal, parentBold, parentItalic))))
                }

                is Emphasis -> {
                    flush()
                    items.addAll(extractInlines(child, parentBold, true))
                }

                is StrongEmphasis -> {
                    flush()
                    items.addAll(extractInlines(child, true, parentItalic))
                }

                is Image -> {
                    flush()
                    val dest = child.destination
                    if (!dest.isNullOrBlank()) items.add(InlineItem.ImageRef(dest))
                }

                is SoftLineBreak -> buf.append(' ')

                is HardLineBreak -> buf.append('\n')

                else -> {
                    // 链接等容器节点：展开其内联内容；图片保持为 ImageRef
                    val sub = extractInlines(child, parentBold, parentItalic)
                    for (it in sub) {
                        when (it) {
                            is InlineItem.Text -> buf.append(it.runs.joinToString("") { r -> r.text })
                            is InlineItem.ImageRef -> {
                                flush()
                                items.add(it)
                            }
                        }
                    }
                }
            }
            child = child.next
        }
        flush()
        return items
    }

    private fun collectTextRuns(inlines: List<InlineItem>): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        for (it in inlines) if (it is InlineItem.Text) runs.addAll(it.runs)
        return runs
    }

    private suspend fun emitInlineBlocks(
        inlines: List<InlineItem>,
        blocks: MutableList<Block>,
        resolver: suspend (String) -> DownloadedImage?,
        asListItem: Boolean = false,
        ordered: Boolean = false,
        orderIndex: Int = 0
    ) {
        val textRuns = mutableListOf<TextRun>()
        fun flushText() {
            if (textRuns.isNotEmpty()) {
                if (asListItem) {
                    blocks.add(Block.ListItem(0, ordered, orderIndex, textRuns.toList()))
                } else {
                    blocks.add(Block.Paragraph(textRuns.toList()))
                }
                textRuns.clear()
            }
        }
        for (it in inlines) {
            when (it) {
                is InlineItem.Text -> textRuns.addAll(it.runs)
                is InlineItem.ImageRef -> {
                    flushText()
                    // 远程/本地图片下载；失败则跳过该图
                    val img = resolver(it.url)
                    if (img != null) {
                        blocks.add(
                            Block.ImageBlock(img.bytes, img.widthPx, img.heightPx, img.format)
                        )
                    }
                }
            }
        }
        flushText()
    }
}
