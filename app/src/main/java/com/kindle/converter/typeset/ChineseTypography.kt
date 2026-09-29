package com.kindle.converter.typeset

/**
 * 中文排版核心规范与字符分类工具（避头尾禁则、行末标点半角/悬挂、中西文混排间隙）。
 *
 * 参考标准：
 * - GB/T 15834-2011《标点符号用法》
 * - W3C CLREQ《中文排版需求》（Requirements for Chinese Text Layout）
 * - 传统书版与 InDesign 光学边距对齐 / 标点挤压规范
 */
object ChineseTypography {

    /**
     * 避头标点集（行首禁则）：绝对禁止出现在行首的标点符号。
     *
     * 维护说明：
     * - 必须包含中文右双引号 '”' (\u201D) 与右单引号 '’' (\u2019)，
     *   旧版本漏掉了这两个最常见的中文闭引号，导致闭引号可能落入行首。
     * - 同时包含全角与半角的句读点号、闭括号、书名号右半、连接号、省略号及常用单位符号。
     */
    val lineStartForbidden = setOf(
        // 中文句读点号
        '，', '。', '！', '？', '、', '；', '：', '．',
        // 中文闭括号、右书名号、右引号
        '》', '〉', '」', '』', '）', '】', '〕', '〗', '］', '｝', '｠',
        '”', '’', '〞', '〟', '»', '›',
        // 中线符号（间隔号、省略号、破折号、波浪号）
        '·', '…', '—', '～', '–', '―',
        // 单位与比例符号
        '％', '%', '‰', '°', '′', '″', '℃',
        // 西文半角句读与闭括号
        '.', ',', '!', '?', ';', ':', ')', ']', '}', '>', '\''
    )

    /**
     * 避尾标点集（行末禁则）：绝对禁止出现在行末的标点符号。
     *
     * 维护说明：
     * - 包含中文左双引号 '“' (\u201C)、左单引号 '‘' (\u2018)、各类开括号、左书名号及货币/序号前缀。
     * - 当此类字符落在行末且后一字符无法放入当前行时，LineBreaker 应将其退至下一行行首（推下造行），
     *   而不是强行把后一字符挤入当前行造成行宽溢出与整行字距压缩。
     */
    val lineEndForbidden = setOf(
        // 中文开括号、左书名号、左引号
        '《', '〈', '「', '『', '（', '【', '〔', '〖', '［', '｛', '｟',
        '“', '‘', '〝', '«', '‹',
        // 西文半角开括号与前置符号
        '(', '[', '{', '<', '#', '$', '￥', '¥', '§'
    )

    /**
     * 墨迹居左、右半格留白的全角标点集合（用于行末「标点半角化 / 标点悬挂」计算）。
     *
     * 排版原理说明：
     * 在 CJK 字体度量中，全角点号与全角闭括号/闭引号的字形前进宽度（advance width）为 1.0 em，
     * 但实际可见墨迹位于左半格（约 0.5 em），右半格（约 0.5 em）为字形内置空白。
     * 若 Paginator 直接按完整 1.0 em 宽度对齐右页边：
     * 1) 当行末挤入标点时，为了把右半格看不见的空白塞进版心，会对整行前置文字施加负字距压缩；
     * 2) 标点可见墨迹反而停在距离右页边 0.5 em 处，造成行尾视觉凹陷（即显得“被压短了”）。
     * 因此在计算行视觉有效宽度时，若行尾字符属于本集合，应扣除其右侧约 0.5 em 的留白宽度。
     */
    val rightBlankFullWidthPunctuation = setOf(
        '，', '。', '！', '？', '、', '；', '：', '．',
        '）', '》', '〉', '」', '』', '】', '〕', '〗', '］', '｝',
        '”', '’', '〞', '〟'
    )

    // 保留向后兼容别名
    val compressiblePunctuation = rightBlankFullWidthPunctuation

    /**
     * 判定是否为 CJK 统一表意文字（纯汉字）。
     */
    fun isChinese(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF
    }

    /**
     * 判定是否为中文常用广义标点（Unicode 位于 General Punctuation 0x2000..0x206F 等区间）。
     *
     * 维护说明：
     * '“' (\u201C)、'”' (\u201D)、'‘' (\u2018)、'’' (\u2019)、'—' (\u2014)、'…' (\u2026)、'·' (\u00B7)
     * 虽然码位不在 0x3000..0x303F 或 0xFF00..0xFFEF，但在中文排版中属于全角/中文标点，
     * 必须纳入 isCJK 判定，绝不能被当作西文单词字符触发空格回退。
     */
    fun isChineseGeneralPunctuation(c: Char): Boolean {
        return when (c) {
            '“', '”', '‘', '’', '—', '…', '·', '～', '–', '―',
            '′', '″', '‰', '℃', '℉' -> true
            else -> false
        }
    }

    /**
     * 判定是否为 CJK 字符（含汉字、假名、全角符号及中文常用广义标点）。
     */
    fun isCJK(c: Char): Boolean {
        val code = c.code
        return isChinese(c) ||
            isChineseGeneralPunctuation(c) ||
            code in 0x3000..0x303F ||   // CJK symbols and punctuation
            code in 0xFF00..0xFFEF ||   // Full-width forms
            code in 0x3040..0x30FF      // Hiragana, Katakana
    }

    /**
     * 判定是否为墨迹居左、右半格留白的全角标点。
     */
    fun isFullWidthPunctuation(c: Char): Boolean = c in rightBlankFullWidthPunctuation

    /**
     * 判定是否为拉丁字母或阿拉伯数字（用于中西文混排间隙与英文单词断行）。
     */
    fun isLatinAlphanumeric(c: Char): Boolean {
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c in '\u00C0'..'\u024F' // 带重音符号的拉丁字母
    }

    /**
     * 判定是否为可构成连续西文单词内部的字符（字母、数字、连字符、单词内撇号等）。
     * LineBreaker 仅在这些字符连续出现并溢出时，才向前回退到该西文单词的起点。
     */
    fun isLatinWordChar(c: Char): Boolean {
        return isLatinAlphanumeric(c) || c == '-' || c == '_'
    }

    /**
     * 判定相邻两字符之间是否需要插入中西文混排间隙（约 0.2 em ~ 1/4 em，遵循 W3C CLREQ §3.2.2）。
     *
     * 注意：仅在「纯汉字（isChinese）」与「西文字母/数字（isLatinAlphanumeric）」直接相邻时插入间隙；
     * 全角标点（如 '（'、'）'、'“'、'”'、'，'）自身已带半格留白，不与西文之间额外加间隙。
     */
    fun needsCjkLatinGap(prev: Char, current: Char): Boolean {
        val prevIsHan = isChinese(prev)
        val currIsHan = isChinese(current)
        val prevIsLatin = isLatinAlphanumeric(prev)
        val currIsLatin = isLatinAlphanumeric(current)
        return (prevIsHan && currIsLatin) || (prevIsLatin && currIsHan)
    }

    /**
     * Check if a character is a word boundary for Latin text breaking.
     */
    fun isLatinWordBoundary(c: Char): Boolean {
        return c.isWhitespace() || c in lineStartForbidden || c in lineEndForbidden
    }

    /**
     * 判定是否为制表符/框线字符或几何箭头符号（Unicode Box Drawing 0x2500..0x257F、
     * Block Elements & Geometric Shapes 0x2580..0x25FF、Arrows 0x2190..0x21FF）。
     */
    fun isBoxDrawingOrGeometric(c: Char): Boolean {
        val code = c.code
        return code in 0x2500..0x25FF || code in 0x2190..0x21FF
    }

    /**
     * 计算字符在代码块 / ASCII 流程图中的半角网格列宽（1 列 = 0.5 em，2 列 = 1.0 em）。
     *
     * 规范依据：
     * - 现代编辑器（VS Code / JetBrains / Markdown 流程图）中，汉字、假名、全角标点占 2 个半角列（1.0 em）；
     * - ASCII 字符、空格、Unicode 制表框线（┌─┐│└┘├┤┬┴┼）与方向箭头（▲▼◄►→←↑↓）占 1 个半角列（0.5 em）。
     * 按此网格坐标定位代码块中的每个字符，可确保包含中英文混排的 ASCII 树状图、流程图、餐盘比例图在任何字体下严格垂直对齐。
     */
    fun eastAsianColWidth(c: Char): Int {
        val code = c.code
        return if (
            isChinese(c) ||
            code in 0x3000..0x30FF ||
            code in 0xFF01..0xFF60 ||
            c in setOf('“', '”', '‘', '’', '—', '…', '￥')
        ) {
            2
        } else {
            1
        }
    }

    /**
     * 特殊符号字形降级映射表（Glyph Fallback Table）：
     * 当用户选择的字体（如部分精简版中文字体）缺失几何箭头（►◄▲▼）、空心圆点（◦）、方点（▪）或框线字形时，
     * 自动降级为几乎所有 CJK 字体必含的等价符号（→←↑↓、·、•、+、-、|），避免被 PdfGenerator 静默丢弃。
     */
    val glyphFallbacks: Map<Char, List<Char>> = mapOf(
        '►' to listOf('→', '>'),
        '▶' to listOf('→', '>'),
        '◄' to listOf('←', '<'),
        '◀' to listOf('←', '<'),
        '▲' to listOf('↑', '^'),
        '△' to listOf('↑', '^'),
        '▼' to listOf('↓', 'v'),
        '▽' to listOf('↓', 'v'),
        '◦' to listOf('·', '•', '-'),
        '○' to listOf('·', 'o'),
        '▪' to listOf('•', '·', '*'),
        '▫' to listOf('·', '-'),
        '■' to listOf('•', '#'),
        '✓' to listOf('√', 'v'),
        '✔' to listOf('√', 'v'),
        '✗' to listOf('×', 'x'),
        '✘' to listOf('×', 'x'),
        '─' to listOf('—', '-'),
        '━' to listOf('—', '-'),
        '│' to listOf('|'),
        '┃' to listOf('|'),
        '┌' to listOf('+'),
        '┐' to listOf('+'),
        '└' to listOf('+'),
        '┘' to listOf('+'),
        '├' to listOf('+'),
        '┤' to listOf('+'),
        '┬' to listOf('+'),
        '┴' to listOf('+'),
        '┼' to listOf('+')
    )
}


