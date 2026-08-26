package com.kindle.converter.parser

import com.kindle.converter.data.Document
import com.kindle.converter.data.ParseContext

/**
 * Dispatcher: routes to the correct parser based on file extension.
 */
object DocumentParser {

    suspend fun parse(
        fileName: String,
        bytes: ByteArray,
        ctx: ParseContext = ParseContext(),
        imageResolver: suspend (String) -> DownloadedImage? = { null }
    ): Document {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> TxtParser().parse(bytes)
            "md", "markdown", "mdown" -> {
                val text = detectEncoding(bytes)
                MarkdownParser().parse(text, imageResolver)
            }
            "html", "htm" -> {
                val text = detectEncoding(bytes)
                HtmlParser().parse(text, imageResolver)
            }
            "docx" -> DocxParser().parse(bytes)
            "epub" -> EpubParser().parse(bytes)
            "pdf" -> PdfParser().parse(bytes, ctx)
            else -> {
                // 扩展名兜底之前先嗅探魔数，避免 PDF 被误当 txt
                if (looksLikePdf(bytes)) PdfParser().parse(bytes, ctx)
                else if (looksLikeZip(bytes)) {
                    // 可能是 docx/epub 没拿到后缀
                    if (looksLikeDocx(bytes)) DocxParser().parse(bytes)
                    else EpubParser().parse(bytes)
                } else TxtParser().parse(bytes)
            }
        }
    }

    private fun looksLikePdf(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        return bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[4] == '-'.code.toByte()
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
            (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())
    }

    private fun looksLikeDocx(bytes: ByteArray): Boolean {
        // docx 文件头通常含 [Content_Types].xml
        val head = bytes.copyOfRange(0, minOf(bytes.size, 4096))
        val str = String(head, Charsets.ISO_8859_1)
        return str.contains("[Content_Types].xml") ||
            str.contains("word/document.xml")
    }

    private fun detectEncoding(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // Try UTF-8 strict
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // pass
        }
        return try {
            String(bytes, java.nio.charset.Charset.forName("GBK"))
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    val supportedExtensions = listOf("txt", "md", "markdown", "html", "htm", "docx", "epub", "pdf")

    val mimeTypeMap = mapOf(
        "txt" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "html" to "text/html",
        "htm" to "text/html",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "epub" to "application/epub+zip",
        "pdf" to "application/pdf"
    )
}
