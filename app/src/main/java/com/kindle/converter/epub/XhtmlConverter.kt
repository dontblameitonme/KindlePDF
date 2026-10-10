package com.kindle.converter.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * 正文 HTML -> XHTML 清洗转换：
 * 标签白名单过滤、移除内联事件脚本、图片引用替换为本地路径。
 */
object XhtmlConverter {

    private val SAFELIST: Safelist = Safelist.none()
        .addTags(
            "h1", "h2", "h3", "h4", "h5", "h6",
            "p", "div", "span", "section", "article",
            "br", "hr", "blockquote",
            "ul", "ol", "li",
            "b", "strong", "i", "em", "u", "s", "sub", "sup",
            "pre", "code",
            "table", "thead", "tbody", "tr", "td", "th",
            "figure", "figcaption", "img", "a",
        )
        .addAttributes("img", "src", "alt")
        .addAttributes("a", "href")
        .addProtocols("img", "src", "http", "https")
        .addProtocols("a", "href", "http", "https")

    /**
     * 将 Readability 提取的正文片段转换为 XHTML 主体。
     * @return Pair(xhtml 内容, 正文中引用的绝对图片 URL 列表)
     */
    fun toXhtmlBody(contentHtml: String, baseUrl: String): Pair<String, List<String>> {
        val document = Jsoup.parseBodyFragment(contentHtml, baseUrl)

        // 移除脚本与样式残留
        document.select("script, style, noscript, iframe").remove()

        // 规范化图片真实 URL（处理知乎 data-actualsrc、微信 data-src 等）
        for (img in document.select("img")) {
            val lazyAttrs = listOf("data-actualsrc", "data-original", "data-src", "data-origin-src", "data-lazy-src", "data-echo", "src")
            for (attr in lazyAttrs) {
                val raw = img.attr(attr).trim()
                if (raw.isNotBlank() && !raw.startsWith("data:image/svg", ignoreCase = true) && raw != "about:blank") {
                    val abs = img.absUrl(attr).trim()
                    val candidate = if (abs.isNotBlank()) abs else if (raw.startsWith("//")) "https:$raw" else raw
                    if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
                        img.attr("src", candidate)
                        break
                    }
                }
            }
        }

        // 白名单清洗（相对 URL 会按 baseUri 解析为绝对路径）
        val cleaned = Cleaner(SAFELIST).clean(document)
        cleaned.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)

        val imageUrls = cleaned.select("img").mapNotNull { img ->
            img.absUrl("src").takeIf { it.startsWith("http") }
        }.distinct()

        val body = cleaned.body().html()
        return body to imageUrls
    }

    /** 移除正文中的所有图片 */
    fun stripImages(contentHtml: String, baseUrl: String): String {
        val document = Jsoup.parseBodyFragment(contentHtml, baseUrl)
        document.select("img, figure").remove()
        val cleaned = Cleaner(SAFELIST).clean(document)
        cleaned.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
        return cleaned.body().html()
    }

    /** 将正文中的绝对图片 URL 替换为 EPUB 内部路径 */
    fun replaceImageUrls(xhtml: String, urlToLocal: Map<String, String>): String {
        var result = xhtml
        for ((url, local) in urlToLocal) {
            result = result.replace("\"$url\"", "\"../images/$local\"")
        }
        return result
    }

    fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 生成完整章节 XHTML 文档 */
    fun chapterDocument(title: String, bodyXhtml: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
        append("<head><title>").append(escapeXml(title)).append("</title></head>\n")
        append("<body>\n")
        append("<h1>").append(escapeXml(title)).append("</h1>\n")
        append(bodyXhtml)
        append("\n</body>\n</html>\n")
    }
}
