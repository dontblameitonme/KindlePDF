package com.kindle.converter.web.extractor

import com.kindle.converter.web.model.WebArticle
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup

class ExtractException(message: String) : Exception(message)

/**
 * 正文提取：Jsoup 预处理 + Readability4J 提取。
 * 同时支持原始 HTML（静态抓取）与 WebView 导出的渲染后 DOM。
 */
class ContentExtractor {

    fun extract(url: String, html: String): WebArticle {
        val document = Jsoup.parse(html, url)
        val docTextBefore = document.text()

        // 预处理：处理懒加载图片、清洗遮罩
        restoreLazyImages(document)
        OverlayCleaner.clean(document)

        val readability = Readability4J(url, document)
        val article = try {
            readability.parse()
        } catch (e: Exception) {
            throw ExtractException("正文解析失败：${e.message ?: "未知错误"}")
        }

        val content = article.content
        if (content.isNullOrBlank()) {
            throw ExtractException("未提取到正文内容（页面可能依赖 JS 渲染，可尝试 WebView 渲染）")
        }

        val contentDoc = Jsoup.parse(content)
        val contentTextLen = contentDoc.text().length
        if (contentTextLen < 50) {
            throw ExtractException("正文内容过短，提取可能失败")
        }

        val truncated = OverlayCleaner.isPossiblyTruncated(docTextBefore, contentTextLen)

        return WebArticle(
            url = url,
            title = article.title?.takeIf { it.isNotBlank() } ?: document.title(),
            contentHtml = content,
            possiblyTruncated = truncated,
        )
    }

    /** 将 data-src/data-original 等懒加载属性还原为 src */
    private fun restoreLazyImages(document: org.jsoup.nodes.Document) {
        for (img in document.select("img")) {
            if (img.attr("src").isBlank() || img.attr("src").startsWith("data:")) {
                for (attr in listOf("data-src", "data-original", "data-echo", "data-lazy-src")) {
                    val lazy = img.attr(attr)
                    if (lazy.isNotBlank() && !lazy.startsWith("data:")) {
                        img.attr("src", lazy)
                        break
                    }
                }
            }
        }
    }
}
