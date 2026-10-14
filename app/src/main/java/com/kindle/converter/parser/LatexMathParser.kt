package com.kindle.converter.parser

import com.kindle.converter.data.TextRun

/**
 * 纯本地轻量级 LaTeX 数学公式解析与排版转换器。
 *
 * 核心设计目标：
 * 1. 将 Markdown 中的独立块级公式 `$$...$$` 与行内公式 `$...$` 转换为适合 6 英寸 Kindle 等墨水屏阅读器
 *    高清晰度排版的 [TextRun] 序列。
 * 2. 遵循标准数学排版规范：
 *    - 拉丁单字母变量（x, y, z, a, b, c, t, n 等）呈现为斜体（italic = true）；
 *    - 数字、标点符号、数学运算符、希腊字母及标准函数名（sin, cos, ln, lim 等）呈现为正体（italic = false）；
 *    - 粗体向量/矩阵（\mathbf）呈现为粗体（bold = true）。
 * 3. 智能消解上下标（^{...}、_{...}）、分式（\frac）、根号（\sqrt）、希腊字母、微积分与求和符号，
 *    优先映射为紧凑无损的高清 Unicode 数学字符（如 x²、a₁、½、∫、∑、√、α、β、π 等）。
 */
object LatexMathParser {

    private val SUPERSCRIPTS: Map<Char, Char> = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ', 'a' to 'ᵃ',
        'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ',
        'g' to 'ᵍ', 'h' to 'ʰ', 'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ',
        'm' to 'ᵐ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ',
        't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ', 'z' to 'ᶻ',
        'T' to 'ᵀ', 'A' to 'ᴬ', 'B' to 'ᴮ', 'D' to 'ᴰ', 'E' to 'ᴱ',
        'G' to 'ᴳ', 'H' to 'ᴴ', 'I' to 'ᴵ', 'J' to 'ᴶ', 'K' to 'ᴷ',
        'L' to 'ᴸ', 'M' to 'ᴹ', 'N' to 'ᴺ', 'O' to 'ᴼ', 'P' to 'ᴾ',
        'R' to 'ᴿ', 'U' to 'ᵁ', 'V' to 'ⱽ', 'W' to 'ᵂ'
    )

    private val SUBSCRIPTS: Map<Char, Char> = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ'
    )

    private val VULGAR_FRACTIONS: Map<String, String> = mapOf(
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔",
        "1/4" to "¼", "3/4" to "¾", "1/5" to "⅕",
        "2/5" to "⅖", "3/5" to "⅗", "4/5" to "⅘",
        "1/6" to "⅙", "5/6" to "⅚", "1/8" to "⅛",
        "3/8" to "⅜", "5/8" to "⅝", "7/8" to "⅞"
    )

    private val GREEK_LOWER: Map<String, Char> = mapOf(
        "alpha" to 'α', "beta" to 'β', "gamma" to 'γ', "delta" to 'δ',
        "epsilon" to 'ε', "varepsilon" to 'ε', "zeta" to 'ζ', "eta" to 'η',
        "theta" to 'θ', "vartheta" to 'ϑ', "iota" to 'ι', "kappa" to 'κ',
        "lambda" to 'λ', "mu" to 'μ', "nu" to 'ν', "xi" to 'ξ',
        "pi" to 'π', "varpi" to 'ϖ', "rho" to 'ρ', "varrho" to 'ϱ',
        "sigma" to 'σ', "varsigma" to 'ς', "tau" to 'τ', "upsilon" to 'υ',
        "phi" to 'φ', "varphi" to 'ϕ', "chi" to 'χ', "psi" to 'ψ', "omega" to 'ω'
    )

    private val GREEK_UPPER: Map<String, Char> = mapOf(
        "Gamma" to 'Γ', "Delta" to 'Δ', "Theta" to 'Θ', "Lambda" to 'Λ',
        "Xi" to 'Ξ', "Pi" to 'Π', "Sigma" to 'Σ', "Upsilon" to 'Υ',
        "Phi" to 'Φ', "Psi" to 'Ψ', "Omega" to 'Ω'
    )

    private val MATH_OPERATORS: Map<String, String> = mapOf(
        "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "·",
        "circ" to "°", "bullet" to "•", "star" to "⋆", "ast" to "∗",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝",
        "ll" to "≪", "gg" to "≫",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "cap" to "∩", "cup" to "∪",
        "setminus" to "∖",
        "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "lor" to "∨", "land" to "∧", "neg" to "¬",
        "partial" to "∂", "nabla" to "∇", "infty" to "∞",
        "sum" to "∑", "prod" to "∏", "coprod" to "∐",
        "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
        "to" to "→", "rightarrow" to "→", "gets" to "←", "leftarrow" to "←",
        "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "Leftrightarrow" to "⇔", "iff" to "⇔",
        "uparrow" to "↑", "downarrow" to "↓", "mapsto" to "↦",
        "dots" to "…", "ldots" to "…", "cdots" to "…", "vdots" to "⋮", "ddots" to "⋱",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
        "hbar" to "ℏ", "ell" to "ℓ", "prime" to "′",
        "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", ":" to " ", "!" to ""
    )

    private val STANDARD_FUNCTIONS: Set<String> = setOf(
        "sin", "cos", "tan", "arcsin", "arccos", "arctan", "cot", "sec", "csc",
        "sinh", "cosh", "tanh", "exp", "log", "ln", "lg", "lim", "max", "min",
        "sup", "inf", "det", "gcd", "dim", "ker", "deg", "arg"
    )

    private val BLACKBOARD_BOLD: Map<Char, String> = mapOf(
        'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ"
    )

    /**
     * 将输入文本中支持的字符转换为 Unicode 上标格式。
     */
    fun toSuperscriptString(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val sup = SUPERSCRIPTS[ch]
            if (sup != null) {
                sb.append(sup)
            } else {
                return "^($text)"
            }
        }
        return sb.toString()
    }

    /**
     * 将输入文本中支持的字符转换为 Unicode 下标格式。
     */
    fun toSubscriptString(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val sub = SUBSCRIPTS[ch]
            if (sub != null) {
                sb.append(sub)
            } else {
                return "_($text)"
            }
        }
        return sb.toString()
    }

    /**
     * 解析 LaTeX 公式代码为富文本 [TextRun] 序列。
     */
    fun parse(rawLatex: String): List<TextRun> {
        var latex = rawLatex.trim()
        if (latex.startsWith("$$") && latex.endsWith("$$") && latex.length >= 4) {
            latex = latex.substring(2, latex.length - 2).trim()
        } else if (latex.startsWith("$") && latex.endsWith("$") && latex.length >= 2) {
            latex = latex.substring(1, latex.length - 1).trim()
        } else if (latex.startsWith("\\[") && latex.endsWith("\\]") && latex.length >= 4) {
            latex = latex.substring(2, latex.length - 2).trim()
        } else if (latex.startsWith("\\(") && latex.endsWith("\\)") && latex.length >= 4) {
            latex = latex.substring(2, latex.length - 2).trim()
        }

        if (latex.isBlank()) return emptyList()

        // 提取公式编号标签，如 \tag{1} 或 \tag{2.1}
        var tagNumber: String? = null
        val tagRegex = Regex("""\\tag\{([^}]+)\}""")
        val tagMatch = tagRegex.find(latex)
        if (tagMatch != null) {
            tagNumber = tagMatch.groupValues[1].trim()
            latex = latex.replace(tagMatch.value, "").trim()
        }

        // 去除常见的外部环境包裹
        latex = latex.replace(Regex("""\\begin\{(aligned|align\*?|matrix|pmatrix|bmatrix|cases|gather\*?)\}"""), "")
            .replace(Regex("""\\end\{(aligned|align\*?|matrix|pmatrix|bmatrix|cases|gather\*?)\}"""), "")
            .replace(Regex("""\\\\"""), "\n")
            .replace("&", " ")

        val runs = mutableListOf<TextRun>()
        val parser = LatexTokenizer(latex)
        parser.parseInto(runs)

        if (!tagNumber.isNullOrBlank()) {
            runs.add(TextRun("    ($tagNumber)", bold = false, italic = false))
        }

        return mergeAdjacentRuns(runs)
    }

    private class LatexTokenizer(private val text: String) {
        private var pos = 0
        private val len = text.length

        fun hasNext(): Boolean = pos < len
        private fun peek(): Char = if (pos < len) text[pos] else '\u0000'
        private fun advance(): Char = text[pos++]

        fun parseInto(
            out: MutableList<TextRun>,
            inBold: Boolean = false,
            inItalic: Boolean = false,
            inText: Boolean = false
        ) {
            while (hasNext()) {
                val c = peek()
                when {
                    c == '\\' -> {
                        advance()
                        handleCommand(out, inBold, inItalic, inText)
                    }
                    c == '^' -> {
                        advance()
                        handleSuperscript(out, inBold)
                    }
                    c == '_' -> {
                        advance()
                        handleSubscript(out, inBold)
                    }
                    c == '{' -> {
                        // 匿名组
                        advance()
                        parseInto(out, inBold, inItalic, inText)
                    }
                    c == '}' -> {
                        advance()
                        return
                    }
                    c == '\n' -> {
                        advance()
                        out.add(TextRun("\n", bold = inBold, italic = false))
                    }
                    c.isWhitespace() -> {
                        advance()
                        out.add(TextRun(" ", bold = inBold, italic = false))
                    }
                    c.isLatinLetter() -> {
                        advance()
                        val isSingleVar = !inText
                        out.add(TextRun(c.toString(), bold = inBold, italic = if (inText) inItalic else isSingleVar))
                    }
                    else -> {
                        advance()
                        out.add(TextRun(c.toString(), bold = inBold, italic = inItalic))
                    }
                }
            }
        }

        private fun handleCommand(
            out: MutableList<TextRun>,
            inBold: Boolean,
            inItalic: Boolean,
            inText: Boolean
        ) {
            if (!hasNext()) return
            val first = peek()
            val cmd = if (!first.isLetter()) {
                advance().toString()
            } else {
                val start = pos
                while (hasNext() && peek().isLetter()) advance()
                text.substring(start, pos)
            }

            when (cmd) {
                "frac", "dfrac", "tfrac" -> {
                    val num = readGroup()
                    val den = readGroup()
                    val numRuns = parseSubFormula(num, inBold)
                    val denRuns = parseSubFormula(den, inBold)
                    val numStr = numRuns.joinToString("") { it.text }.trim()
                    val denStr = denRuns.joinToString("") { it.text }.trim()

                    val vulgar = VULGAR_FRACTIONS["$numStr/$denStr"]
                    if (vulgar != null) {
                        out.add(TextRun(vulgar, bold = inBold, italic = false))
                    } else {
                        val numComplex = numStr.contains('+') || numStr.contains('-') || numStr.contains('=') || numStr.contains(' ')
                        val denComplex = denStr.contains('+') || denStr.contains('-') || denStr.contains('=') || denStr.contains(' ')
                        if (numComplex) out.add(TextRun("(", bold = inBold, italic = false))
                        out.addAll(numRuns)
                        if (numComplex) out.add(TextRun(")", bold = inBold, italic = false))
                        out.add(TextRun(" / ", bold = inBold, italic = false))
                        if (denComplex) out.add(TextRun("(", bold = inBold, italic = false))
                        out.addAll(denRuns)
                        if (denComplex) out.add(TextRun(")", bold = inBold, italic = false))
                    }
                }
                "sqrt" -> {
                    var degree: String? = null
                    skipWhitespace()
                    if (peek() == '[') {
                        advance()
                        val degStart = pos
                        while (hasNext() && peek() != ']') advance()
                        degree = text.substring(degStart, pos).trim()
                        if (peek() == ']') advance()
                    }
                    val body = readGroup()
                    val bodyRuns = parseSubFormula(body, inBold)
                    val degSym = when (degree) {
                        null, "2" -> "√"
                        "3" -> "∛"
                        "4" -> "∜"
                        else -> toSuperscriptString(degree) + "√"
                    }
                    out.add(TextRun(degSym + "(", bold = inBold, italic = false))
                    out.addAll(bodyRuns)
                    out.add(TextRun(")", bold = inBold, italic = false))
                }
                "text", "mathrm", "operatorname" -> {
                    val content = readGroup()
                    out.add(TextRun(content, bold = inBold, italic = false))
                }
                "mathbf" -> {
                    val content = readGroup()
                    val subRuns = parseSubFormula(content, inBold = true)
                    out.addAll(subRuns)
                }
                "mathit" -> {
                    val content = readGroup()
                    val subRuns = parseSubFormula(content, inBold = inBold, inItalic = true)
                    out.addAll(subRuns)
                }
                "mathbb" -> {
                    val content = readGroup().trim()
                    val ch = content.firstOrNull() ?: ' '
                    val bb = BLACKBOARD_BOLD[ch] ?: content
                    out.add(TextRun(bb, bold = inBold, italic = false))
                }
                "vec" -> {
                    val content = readGroup().trim()
                    out.add(TextRun("${content}⃗", bold = inBold, italic = true))
                }
                "hat" -> {
                    val content = readGroup().trim()
                    out.add(TextRun("${content}̂", bold = inBold, italic = true))
                }
                "bar" -> {
                    val content = readGroup().trim()
                    out.add(TextRun("${content}̄", bold = inBold, italic = true))
                }
                "dot" -> {
                    val content = readGroup().trim()
                    out.add(TextRun("${content}̇", bold = inBold, italic = true))
                }
                "ddot" -> {
                    val content = readGroup().trim()
                    out.add(TextRun("${content}̈", bold = inBold, italic = true))
                }
                "left", "right" -> {
                    skipWhitespace()
                    if (hasNext()) {
                        val delim = advance()
                        if (delim != '.') {
                            out.add(TextRun(delim.toString(), bold = inBold, italic = false))
                        }
                    }
                }
                "{" -> out.add(TextRun("{", bold = inBold, italic = false))
                "}" -> out.add(TextRun("}", bold = inBold, italic = false))
                else -> {
                    val greekL = GREEK_LOWER[cmd]
                    val greekU = GREEK_UPPER[cmd]
                    val op = MATH_OPERATORS[cmd]

                    when {
                        greekL != null -> out.add(TextRun(greekL.toString(), bold = inBold, italic = false))
                        greekU != null -> out.add(TextRun(greekU.toString(), bold = inBold, italic = false))
                        op != null -> out.add(TextRun(op, bold = inBold, italic = false))
                        STANDARD_FUNCTIONS.contains(cmd) -> out.add(TextRun(cmd, bold = inBold, italic = false))
                        else -> out.add(TextRun(cmd, bold = inBold, italic = inItalic))
                    }
                }
            }
        }

        private fun handleSuperscript(out: MutableList<TextRun>, inBold: Boolean) {
            val content = readTokenOrGroup()
            // 如果前一个符号是求和或积分的下界，将其合并为完整界限 (lower..upper)
            val last = out.lastOrNull()
            if (last != null) {
                if (last.text.endsWith("..")) {
                    out[out.lastIndex] = last.copy(text = last.text + content + ")")
                    return
                }
                if (last.text == "∑" || last.text == "∏" || last.text == "∫") {
                    out.add(TextRun("(..$content)", bold = inBold, italic = false))
                    return
                }
            }

            val sup = toSuperscriptString(content)
            out.add(TextRun(sup, bold = inBold, italic = false))
        }

        private fun handleSubscript(out: MutableList<TextRun>, inBold: Boolean) {
            val content = readTokenOrGroup()
            val last = out.lastOrNull()
            if (last != null) {
                if (last.text.startsWith("(..") && last.text.endsWith(")")) {
                    val upper = last.text.substring(3, last.text.length - 1)
                    out[out.lastIndex] = last.copy(text = "($content..$upper)")
                    return
                }
                if (last.text == "∑" || last.text == "∏" || last.text == "∫") {
                    out.add(TextRun("($content..", bold = inBold, italic = false))
                    return
                }
            }

            val sub = toSubscriptString(content)
            out.add(TextRun(sub, bold = inBold, italic = false))
        }

        private fun readTokenOrGroup(): String {
            skipWhitespace()
            if (!hasNext()) return ""
            return if (peek() == '{') {
                readGroup()
            } else if (peek() == '\\') {
                advance()
                val start = pos
                while (hasNext() && peek().isLetter()) advance()
                val cmd = text.substring(start, pos)
                GREEK_LOWER[cmd]?.toString() ?: GREEK_UPPER[cmd]?.toString() ?: MATH_OPERATORS[cmd] ?: cmd
            } else {
                advance().toString()
            }
        }

        private fun readGroup(): String {
            skipWhitespace()
            if (!hasNext()) return ""
            if (peek() != '{') return advance().toString()
            advance() // 消费 '{'
            val sb = StringBuilder()
            var depth = 1
            while (hasNext()) {
                val c = advance()
                if (c == '{') {
                    depth++
                    sb.append(c)
                } else if (c == '}') {
                    depth--
                    if (depth == 0) break
                    sb.append(c)
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun skipWhitespace() {
            while (hasNext() && peek().isWhitespace()) advance()
        }

        private fun parseSubFormula(subText: String, inBold: Boolean, inItalic: Boolean = false): List<TextRun> {
            val subRuns = mutableListOf<TextRun>()
            val subTokenizer = LatexTokenizer(subText)
            subTokenizer.parseInto(subRuns, inBold, inItalic)
            return subRuns
        }
    }

    private fun Char.isLatinLetter(): Boolean = (this in 'a'..'z') || (this in 'A'..'Z')

    /**
     * 合并连续属性完全一致的相邻 TextRun，降低 TextLine Segment 碎片率。
     */
    private fun mergeAdjacentRuns(runs: List<TextRun>): List<TextRun> {
        if (runs.isEmpty()) return emptyList()
        val merged = mutableListOf<TextRun>()
        var current = runs.first()

        for (i in 1 until runs.size) {
            val next = runs[i]
            if (current.bold == next.bold &&
                current.italic == next.italic &&
                current.fontSize == next.fontSize &&
                current.color == next.color &&
                current.strikethrough == next.strikethrough
            ) {
                current = current.copy(text = current.text + next.text)
            } else {
                if (current.text.isNotEmpty()) merged.add(current)
                current = next
            }
        }
        if (current.text.isNotEmpty()) merged.add(current)
        return merged
    }
}
