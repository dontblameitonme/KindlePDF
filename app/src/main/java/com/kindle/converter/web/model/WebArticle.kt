package com.kindle.converter.web.model

/** 提取出的网页文章正文 */
data class WebArticle(
    val url: String,
    val title: String,
    /** Readability 提取后的正文 HTML */
    val contentHtml: String,
    /** 疑似被截断/折叠 */
    val possiblyTruncated: Boolean = false,
)

/** 链接抓取状态 */
sealed class LinkStatus {
    data object Idle : LinkStatus()
    data object Fetching : LinkStatus()
    data class Success(val article: WebArticle) : LinkStatus()
    data class Error(val message: String) : LinkStatus()
}

/** 网页链接条目 */
data class LinkItem(
    val id: Long,
    val url: String,
    val status: LinkStatus = LinkStatus.Idle,
) {
    val domain: String
        get() = try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
}

/** 重抓方式 */
enum class FetchMode { DEFAULT, DESKTOP, WEBVIEW }
