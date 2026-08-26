package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharsetDecoder

class TxtParser {

    fun parse(bytes: ByteArray): Document {
        val text = detectEncoding(bytes)
        val blocks = text
            .split(Regex("\n[ \t]*\n"))
            .filter { it.isNotBlank() }
            .map { para ->
                val cleaned = para.trim().replace("\r\n", "\n").replace("\r", "\n")
                Block.Paragraph(listOf(TextRun(cleaned)))
            }
        return Document(blocks)
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
