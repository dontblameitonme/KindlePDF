package com.kindle.converter.web.webview

/** 解析后的用户脚本元数据 */
data class ScriptMeta(
    val name: String = "未命名脚本",
    val version: String = "unknown",
    val author: String = "",
    val matches: List<String> = emptyList(),
    val runAt: String = "document-idle",
) {
    val displayVersion: String get() = "v$version"
}

/**
 * 最小 userscript 运行器：解析元数据块（@name/@version/@match/@run-at），
 * 按 @match glob 规则匹配页面 URL，供 WebView 注入。
 * 不支持 GM_* API（内置脚本无此依赖）。
 */
object UserscriptRunner {

    private val META_BLOCK = Regex("//\\s*==UserScript==(.*?)//\\s*==/UserScript==", RegexOption.DOT_MATCHES_ALL)
    private val META_LINE = Regex("^\\s*//\\s*@(\\S+)\\s+(.*)$")

    /** 解析元数据；不是 userscript 时返回 null */
    fun parseMeta(script: String): ScriptMeta? {
        val block = META_BLOCK.find(script)?.groupValues?.get(1) ?: return null
        var name = "未命名脚本"
        var version = "unknown"
        var author = ""
        val matches = mutableListOf<String>()
        var runAt = "document-idle"

        for (line in block.lines()) {
            val m = META_LINE.find(line) ?: continue
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim()
            when (key) {
                "name" -> name = value
                "version" -> version = value
                "author" -> author = value
                "match" -> matches.add(value)
                "run-at" -> runAt = value
            }
        }
        return ScriptMeta(name, version, author, matches, runAt)
    }

    /** 脚本是否为合法 userscript（含元数据块且有 @match） */
    fun isValidUserscript(script: String): Boolean {
        val meta = parseMeta(script) ?: return false
        return meta.matches.isNotEmpty()
    }

    /** 页面 URL 是否命中任一 @match 规则 */
    fun matchesUrl(script: String, url: String): Boolean {
        val meta = parseMeta(script) ?: return false
        return meta.matches.any { matchPattern(it, url) }
    }

    /** 将 @match glob 模式转为正则并匹配 */
    fun matchPattern(pattern: String, url: String): Boolean = try {
        val regex = buildString {
            append("^")
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '*' -> append(".*")
                    '\\' -> append("\\\\")
                    '.' -> append("\\.")
                    '?' -> append("\\?")
                    '+' -> append("\\+")
                    '(' -> append("\\(")
                    ')' -> append("\\)")
                    '[' -> append("\\[")
                    ']' -> append("\\]")
                    '{' -> append("\\{")
                    '}' -> append("\\}")
                    '^' -> append("\\^")
                    '$' -> append("\\$")
                    '|' -> append("\\|")
                    else -> append(c)
                }
                i++
            }
            append("$")
        }
        Regex(regex).matches(url)
    } catch (_: Exception) {
        false
    }
}
