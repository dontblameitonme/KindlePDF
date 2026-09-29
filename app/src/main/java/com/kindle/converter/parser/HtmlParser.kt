package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry

/**
 * 轻量 HTML 解析器，面向"网页剪藏/文章"类内容：
 * - 按块级标签（p / h1-6 / div / li / blockquote / section / article / ul / ol）切分段落与标题；
 * - 抽取 <img src="..."> 并通过 imageResolver 下载后转成 ImageBlock（保持与文字的相对顺序）；
 * - 跳过 <script> / <style> 与所有行内标签，其可见文本并入正文；
 * - 解码常见 HTML 实体。
 *
 * 不追求严格 DOM 语义，目标是把带图的网页干净地排进 Kindle PDF。
 */
class HtmlParser {

    suspend fun parse(
        html: String,
        imageResolver: suspend (String) -> DownloadedImage? = { null }
    ): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()
        val buf = StringBuilder()

        val srcRe = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val blockCloseRe = Regex("""</(p|div|li|blockquote|section|article|ul|ol)\s*>""", RegexOption.IGNORE_CASE)

        fun flush(lvl: Int) {
            val text = decodeEntities(buf.toString()).trim()
            buf.setLength(0)
            if (text.isNotEmpty()) {
                val runs = listOf(TextRun(text))
                if (lvl > 0) {
                    blocks.add(Block.Heading(lvl, runs))
                    toc.add(TocEntry(text, lvl, blocks.lastIndex))
                } else {
                    blocks.add(Block.Paragraph(runs))
                }
            }
        }

        var i = 0
        val len = html.length
        while (i < len) {
            val c = html[i]
            if (c != '<') {
                buf.append(c)
                i++
                continue
            }

            val end = html.indexOf('>', i)
            val tagText = if (end >= 0) html.substring(i, end + 1) else html.substring(i)
            i = if (end >= 0) end + 1 else len
            val lower = tagText.lowercase()

            // 跳过脚本 / 样式 / 头部内容
            if (lower.startsWith("<script") || lower.startsWith("<style") || lower.startsWith("<head")) {
                val closeTag = when {
                    lower.startsWith("<script") -> "</script>"
                    lower.startsWith("<style") -> "</style>"
                    else -> "</head>"
                }
                val ci = html.indexOf(closeTag, i, ignoreCase = true)
                if (ci >= 0) i = ci + closeTag.length
                continue
            }

            when {
                lower.startsWith("<img") -> {
                    flush(0)
                    val m = srcRe.find(tagText)
                    val src = m?.groupValues?.getOrNull(1)
                    if (!src.isNullOrBlank()) {
                        val img = imageResolver(src)
                        if (img != null) {
                            blocks.add(
                                Block.ImageBlock(img.bytes, img.widthPx, img.heightPx, img.format)
                            )
                        }
                    }
                }

                lower.startsWith("</h") && lower.length > 3 && lower[3].isDigit() -> {
                    flush(lower[3].digitToIntOrNull() ?: 0)
                }

                lower.startsWith("<h") && lower.length > 2 && lower[2].isDigit() -> {
                    // 标题开始：先把前面残留的正文收尾，级别在 </hN> 时应用
                    flush(0)
                }

                blockCloseRe.matches(lower) -> {
                    flush(0)
                }

                lower == "<br>" || lower.startsWith("<br ") -> {
                    flush(0)
                }

                lower == "<hr>" || lower.startsWith("<hr ") -> {
                    flush(0)
                    blocks.add(Block.HorizontalRule)
                }

                else -> {
                    // 其余标签（含 <b> <a> <span> 等）跳过，其文本内容已/将进入 buf
                }
            }
        }
        flush(0)
        return Document(blocks, toc)
    }

    private fun decodeEntities(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
            }
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
    }
}
