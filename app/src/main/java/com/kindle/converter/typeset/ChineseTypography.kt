package com.kindle.converter.typeset

/**
 * Chinese typography rules — 避头尾 (line breaking), punctuation handling.
 *
 * Reference: GB/T 15834-2011 (标点符号用法) and W3C JLREQ.
 */
object ChineseTypography {

    // 避头: characters that CANNOT appear at the start of a line
    val lineStartForbidden = setOf(
        '，', '。', '！', '？', '》', '」', '』', '、', '；', '：',
        '）', '】', '·', '…', '—', '～', '〉', '〕', '〗', '〞', '〟',
        '％', '°', '′', '″', '℃', '‰',
        '.', ',', '!', '?', ';', ':', ')', ']', '}', '>', '\''
    )

    // 避尾: characters that CANNOT appear at the end of a line
    val lineEndForbidden = setOf(
        '《', '「', '『', '（', '【', '“', '‘', '〈', '〔', '〖', '〝',
        '(', '[', '{', '<', '"', '#', '$'
    )

    // Full-width punctuation that occupies a full em but can be compressed at line end
    val compressiblePunctuation = setOf(
        '，', '。', '！', '？', '、', '；', '：', '）', '》', '」', '』', '】',
        '〉', '〕', '〗', '·', '…'
    )

    fun isChinese(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
    }

    fun isCJK(c: Char): Boolean {
        val code = c.code
        return isChinese(c) ||
            code in 0x3000..0x303F ||   // CJK symbols and punctuation
            code in 0xFF00..0xFFEF ||   // Full-width forms
            code in 0x3040..0x30FF      // Hiragana, Katakana
    }

    fun isFullWidthPunctuation(c: Char): Boolean = c in compressiblePunctuation

    /**
     * Check if a space should be inserted between prev and current
     * for CJK-Latin mixed text (about 1/4 em gap).
     */
    fun needsCjkLatinGap(prev: Char, current: Char): Boolean {
        val prevIsCJK = isCJK(prev)
        val currIsCJK = isCJK(current)
        val prevIsLatin = prev in 'a'..'z' || prev in 'A'..'Z' || prev in '0'..'9'
        val currIsLatin = current in 'a'..'z' || current in 'A'..'Z' || current in '0'..'9'
        return (prevIsCJK && currIsLatin) || (prevIsLatin && currIsCJK)
    }

    /**
     * Check if a character is a word boundary for Latin text breaking.
     */
    fun isLatinWordBoundary(c: Char): Boolean {
        return c.isWhitespace() || c in lineStartForbidden || c in lineEndForbidden
    }
}
