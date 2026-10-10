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

    /** 将 data-actualsrc / data-src / data-original 等懒加载属性还原为有效 src */
    private fun restoreLazyImages(document: org.jsoup.nodes.Document) {
        // 1. 提取 noscript 中的真实图片标签（如知乎、WordPress 将高清原图放在 noscript 中）
        for (noscript in document.select("noscript")) {
            val innerImgs = noscript.children().filter { it.tagName().equals("img", ignoreCase = true) }
            for (innerImg in innerImgs) {
                noscript.before(innerImg)
            }
        }

        val lazyAttrs = listOf(
            "data-actualsrc",    // 知乎核心高清原图属性
            "data-original",     // 知乎、CSDN、微博
            "data-src",          // 微信公众号、掘金
            "data-origin-src",   // 简书
            "data-lazy-src",     // 淘宝、通用
            "data-echo",         // Echo.js
            "data-url",          // 某些新闻站
            "data-original-src",
            "data-ks-lazyload"
        )

        for (img in document.select("img")) {
            val currentSrc = img.attr("src").trim()
            val isCurrentPlaceholder = currentSrc.isBlank() ||
                currentSrc.startsWith("data:image/svg", ignoreCase = true) ||
                currentSrc.contains("placeholder", ignoreCase = true) ||
                currentSrc.contains("1x1", ignoreCase = true)

            for (attr in lazyAttrs) {
                var lazy = img.attr(attr).trim()
                if (lazy.isNotBlank() && !lazy.startsWith("data:image/svg", ignoreCase = true)) {
                    val abs = img.absUrl(attr).trim()
                    val candidate = if (abs.isNotBlank()) abs else if (lazy.startsWith("//")) "https:$lazy" else lazy
                    if (candidate.startsWith("http://") || candidate.startsWith("https://") || candidate.startsWith("data:image/")) {
                        img.attr("src", candidate)
                        break
                    }
                }
            }

            // 协议相对 URL 修正
            val fixedSrc = img.attr("src").trim()
            if (fixedSrc.startsWith("//")) {
                img.attr("src", "https:$fixedSrc")
            }
        }
    }
}
