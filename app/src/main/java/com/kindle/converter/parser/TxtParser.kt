package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharsetDecoder

class TxtParser {

    private val chapterRegex = Regex(
        """^(?:第\s*[0-9一二三四五六七八九十百千万零]+\s*[章回卷节篇部集话]|Chapter\s+\d+|[序前]言|引子|尾声|后记|番外\S*)\s*.*$""",
        RegexOption.IGNORE_CASE
    )

    private val frontTocRegex = Regex(
        """^(?:全书)?(?:完整)?(?:导读)?(?:本文)?(?:文章)?(?:章节)?(?:目录|Contents|TABLE OF CONTENTS|Table of Contents|Navigation|导航)(?:[（(].*[）)])?[:：]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 解析纯文本（TXT）文件为结构化段落与章节大纲列表。
     *
     * 修复说明（便于后续维护）：
     * 1. 行尾换行符归一化顺序：
     *    旧版在 `split(Regex("\n[ \t]*\n"))` 之后才执行 `.replace("\r\n", "\n")`，
     *    导致 Windows 换行格式（`\r\n\r\n`）因中间夹有 `\r` 而无法匹配分段正则，整本书变成单一大段落。
     *    现先统一将 `\r\n` 与 `\r` 替换为 `\n`，再按换行切分段落。
     * 2. 兼容单换行与双换行分段，并清理段首全角空格（`\u3000`）：
     *    中文 TXT 小说/文稿普遍采用单换行分段且段首常自带两个全角空格 `　　`；
     *    剥离段首尾空白与全角空格后，统一交由排版引擎按用户设定的 `firstLineIndent`（默认 2em）控制首行缩进，
     *    避免出现“排版缩进 2em + 原文全角空格 2em = 4em”的双重缩进问题。
     * 3. 智能章节标题探测（chapterRegex）与目录大纲生成：
     *    自动识别「第 X 章/回/卷/节/篇」「Chapter N」「序言」「引子」「尾声」「后记」等常见章节名，
     *    生成 Block.Heading 并收集进入 Document.toc，让 TXT 电子书同样具备大号标题排版与 PDF 大纲书签跳转能力。
     */
    fun parse(bytes: ByteArray): Document {
        val normalized = detectEncoding(bytes)
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val rawLines = normalized
            .split(Regex("\n+"))
            .map { line -> line.trim { it <= ' ' || it == '\u3000' } }
            .filter { it.isNotEmpty() }

        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()

        for (cleaned in rawLines) {
            if (cleaned.length <= 40 && (chapterRegex.matches(cleaned) || frontTocRegex.matches(cleaned))) {
                blocks.add(Block.Heading(1, listOf(TextRun(cleaned, bold = true))))
                toc.add(TocEntry(cleaned, 1, blocks.lastIndex))
            } else {
                blocks.add(Block.Paragraph(listOf(TextRun(cleaned))))
            }
        }
        return Document(blocks, toc)
    }

    /**
     * Encoding detection: UTF-8 BOM → strict UTF-8 → GBK fallback.
     */
    private fun detectEncoding(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }

        // UTF-16 LE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }

        // UTF-16 BE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // Try strict UTF-8
        val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // Not valid UTF-8
        }

        // Fallback: GBK (common for Chinese txt files)
        return try {
            String(bytes, java.nio.charset.Charset.forName("GBK"))
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }
}
