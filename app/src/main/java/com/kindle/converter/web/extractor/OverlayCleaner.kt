package com.kindle.converter.web.extractor

import org.jsoup.nodes.Document

/**
 * 静态遮罩清洗：移除"打开 App"弹窗、下载浮层、登录提示等常见骚扰元素，
 * 并解除正文容器上的 CSS 隐藏，作为用户脚本未覆盖站点的兜底。
 */
object OverlayCleaner {

    /** class/id 中命中这些关键词的元素会被移除 */
    private val KEYWORDS = listOf(
        "open-app", "openapp", "open_app", "app-open", "appjump", "app-jump",
        "read-more", "readmore", "read_more", "unfold", "expand-full", "expand_more",
        "mask", "modal", "dialog", "popup", "pop-up", "overlay", "download-bar",
        "download-tip", "app-guide", "app-guide-mask", "open-in-app", "launch-app",
        "pullup", "pull-up", "float-btn", "float-button", "login-tip", "login-guide",
    )

    /** 明显的无关结构 */
    private val STRUCTURE_SELECTORS = listOf(
        "script", "style", "noscript", "iframe", "svg", "canvas", "form",
        "nav", "header", "footer", "aside",
    )

    /** 常见广告/推荐容器 */
    private val NOISE_SELECTORS = listOf(
        "[class*=advert]", "[class*=recommend]", "[class*=related]",
        "[id*=advert]", "[class*=share-bar]", "[class*=social-share]",
    )

    private val TRUNCATION_MARKERS = listOf(
        "展开全文", "显示全部", "查看更多", "加载全文", "继续阅读",
        "打开App阅读", "打开APP阅读", "App内查看", "APP内查看",
        "打开百度APP", "下载APP", "下载客户端阅读",
    )

    /** 在 Jsoup Document 上执行清洗（原地修改） */
    fun clean(document: Document) {
        for (selector in STRUCTURE_SELECTORS) {
            document.select(selector).remove()
        }
        for (selector in NOISE_SELECTORS) {
            runCatching { document.select(selector).remove() }
        }

        for (element in document.allElements) {
            val classAttr = element.className().lowercase()
            val idAttr = (element.id() ?: "").lowercase()
            if (KEYWORDS.any { it in classAttr || it in idAttr }) {
                element.remove()
                continue
            }
            // 解除 CSS 隐藏，让被折叠的全文参与提取
            val style = element.attr("style")
            if (style.contains("display:none", ignoreCase = true) ||
                style.contains("display: none", ignoreCase = true) ||
                element.hasAttr("hidden")
            ) {
                // 只放开包含较多文本的容器，避免放出隐藏广告
                if (element.text().length > 200) {
                    element.removeAttr("hidden")
                    element.attr(
                        "style",
                        style.replace(Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE), "display:block")
                    )
                }
            }
        }
    }

    /** 判断正文疑似被截断/折叠 */
    fun isPossiblyTruncated(documentText: String, contentTextLength: Int): Boolean {
        if (contentTextLength < 300 && TRUNCATION_MARKERS.any { it in documentText }) return true
        return false
    }
}
