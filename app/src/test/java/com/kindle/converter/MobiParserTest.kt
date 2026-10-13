package com.kindle.converter

import com.kindle.converter.data.Block
import com.kindle.converter.parser.DocumentParser
import com.kindle.converter.parser.MobiParser
import com.kindle.converter.parser.PalmDocDecompressor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MobiParserTest {

    private val parser = MobiParser()

    @Test
    fun testPalmDocDecompressorLiteralsAndSpaces() {
        // 0x48 ('H'), 0x65 ('e'), 0x6C ('l'), 0x6C ('l'), 0x6F ('o'), 0xC1 (' ' + 'A')
        val input = byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F, 0xC1.toByte())
        val output = PalmDocDecompressor.decompress(input)
        val text = String(output, Charsets.US_ASCII)
        assertEquals("Hello A", text)
    }

    @Test
    fun testPalmDocDecompressorRepeatAndOverlap() {
        // "ABC" followed by back-reference: distance 3, length 6
        // token: 0x80..0xBF
        // distance = 3 -> pair = (3 shl 3) | (6 - 3) = 24 | 3 = 27 = 0x001B
        // pair with 0x8000: 0x801B -> byte1 = 0x80, byte2 = 0x1B
        val input = byteArrayOf('A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 0x80.toByte(), 0x1B.toByte())
        val output = PalmDocDecompressor.decompress(input)
        val text = String(output, Charsets.US_ASCII)
        // "ABC" + copy 6 from distance 3 ("ABCABC") = "ABCABCABC"
        assertEquals("ABCABCABC", text)
    }

    @Test
    fun testParseSyntheticMobiDocument(): Unit = runBlocking {
        val htmlContent = """
            <html>
            <head><title>测试书籍</title></head>
            <body>
            <h1>第一章 起航</h1>
            <p>这是一段测试文本，包含<b>加粗内容</b>与普通正文。</p>
            <mbp:pagebreak/>
            <h2>第二章 探险</h2>
            <p>第二章的正文内容，排版自适应。</p>
            </body>
            </html>
        """.trimIndent()

        val mobiBytes = createSyntheticMobi(
            bookTitle = "Kindle 探险记",
            author = "测试作者",
            html = htmlContent
        )

        // 校验 DocumentParser 能够自动嗅探扩展名和魔数
        val doc = DocumentParser.parse("test_book.mobi", mobiBytes)
        assertEquals("Kindle 探险记", doc.title)
        assertEquals("测试作者", doc.author)

        // 校验标题及正文解析
        assertTrue("应包含第一章标题", doc.blocks.any { it is Block.Heading && it.level == 1 && it.runs.any { r -> r.text.contains("第一章") } })
        assertTrue("应包含第二章标题", doc.blocks.any { it is Block.Heading && it.level == 2 && it.runs.any { r -> r.text.contains("第二章") } })
        assertTrue("应包含加粗文本 Run", doc.blocks.any { it is Block.Paragraph && it.runs.any { r -> r.bold && r.text.contains("加粗内容") } })
        assertTrue("应识别 mbp:pagebreak 为 PageBreak", doc.blocks.any { it is Block.PageBreak })

        // 校验目录
        assertEquals(2, doc.toc.size)
        assertEquals("第一章 起航", doc.toc[0].title)
        assertEquals(1, doc.toc[0].level)
        assertEquals("第二章 探险", doc.toc[1].title)
        assertEquals(2, doc.toc[1].level)
    }

    @Test
    fun testParseAzw3Extension(): Unit = runBlocking {
        val htmlContent = "<h1>AZW3 测试</h1><p>支持 AZW3 格式转换为 Kindle PDF。</p>"
        val mobiBytes = createSyntheticMobi("AZW3专著", "开发者", htmlContent)

        val doc = DocumentParser.parse("sample.azw3", mobiBytes)
        assertEquals("AZW3专著", doc.title)
        assertEquals(1, doc.toc.size)
        assertEquals("AZW3 测试", doc.toc[0].title)
    }

    @Test(expected = IllegalStateException::class)
    fun testDrmRejection(): Unit = runBlocking {
        val mobiBytes = createSyntheticMobi(
            bookTitle = "DRM 保护书",
            author = "作者",
            html = "<p>正文</p>",
            encryption = 2 // DRM encrypted
        )
        DocumentParser.parse("protected.mobi", mobiBytes)
    }

    @Test
    fun testPlainTextMobi(): Unit = runBlocking {
        val plainText = "第一章 序章\n这是没有任何 HTML 标签的纯文本 MOBI。\n第二章 发展\n内容正常解析。"
        val mobiBytes = createSyntheticMobi("纯文本书", "作者", plainText)

        val doc = DocumentParser.parse("plain.mobi", mobiBytes)
        assertEquals("纯文本书", doc.title)
        assertTrue(doc.blocks.isNotEmpty())
    }

    @Test
    fun testPalmDocCompressedDocument(): Unit = runBlocking {
        // 使用 PalmDOC 压缩后的记录测试
        val htmlContent = "<h1>PalmDoc 测试</h1><p>这是通过 PalmDoc LZ77 算法压缩的数据。</p>"
        // 构造简单的 PalmDoc 压缩流：将所有字符使用 space+char 编码或 literal 编码
        val raw = htmlContent.toByteArray(Charsets.UTF_8)
        val compressedStream = ByteArrayOutputStream()
        for (b in raw) {
            val v = b.toInt() and 0xFF
            if (v in 9..0x7F) {
                compressedStream.write(v)
            } else {
                compressedStream.write(1) // 1 literal
                compressedStream.write(v)
            }
        }
        val compBytes = compressedStream.toByteArray()

        val mobiBytes = createSyntheticMobi(
            bookTitle = "压缩书",
            author = "作者",
            html = htmlContent,
            compression = 2,
            compressedRecordBytes = compBytes
        )
        val doc = DocumentParser.parse("comp.mobi", mobiBytes)
        assertEquals("压缩书", doc.title)
        assertEquals(1, doc.toc.size)
        assertEquals("PalmDoc 测试", doc.toc[0].title)
    }

    /** 构造一个结构合规的微型标准 PalmDB / MOBI 二进制流用于测试 */
    private fun createSyntheticMobi(
        bookTitle: String,
        author: String,
        html: String,
        compression: Int = 1,
        encryption: Int = 0,
        compressedRecordBytes: ByteArray? = null
    ): ByteArray {
        val textBytes = html.toByteArray(Charsets.UTF_8)
        val rec1Bytes = compressedRecordBytes ?: textBytes

        // Record 0: PalmDOC header (16 bytes) + MOBI header + EXTH header
        val rec0Stream = ByteArrayOutputStream()

        // 1. PalmDOC Header (16 bytes)
        rec0Stream.write(byteArrayOf(0, compression.toByte())) // compression
        rec0Stream.write(byteArrayOf(0, 0)) // unused
        rec0Stream.write(intToBytes4(textBytes.size)) // textLength
        rec0Stream.write(byteArrayOf(0, 1)) // recordCount = 1
        rec0Stream.write(byteArrayOf(0x10, 0x00)) // recordSize = 4096
        rec0Stream.write(byteArrayOf(0, encryption.toByte())) // encryption
        rec0Stream.write(byteArrayOf(0, 0)) // unused

        // 2. MOBI Header (232 bytes)
        val mobiHeaderStream = ByteArrayOutputStream()
        mobiHeaderStream.write("MOBI".toByteArray(Charsets.ISO_8859_1)) // identifier
        mobiHeaderStream.write(intToBytes4(232)) // headerLength
        mobiHeaderStream.write(intToBytes4(2)) // mobiType (Book)
        mobiHeaderStream.write(intToBytes4(65001)) // textEncoding (UTF-8)
        mobiHeaderStream.write(intToBytes4(12345)) // uniqueID
        mobiHeaderStream.write(intToBytes4(6)) // fileVersion

        // 填充保留字段至 offset 84 (fullNameOffset)
        val padTo84 = ByteArray(84 - 24)
        mobiHeaderStream.write(padTo84)

        val titleBytes = bookTitle.toByteArray(Charsets.UTF_8)
        // fullNameOffset: 稍后放在 EXTH 之后
        val fullNameOffsetEstimated = 16 + 232 + 48 // 临时偏移
        mobiHeaderStream.write(intToBytes4(fullNameOffsetEstimated))
        mobiHeaderStream.write(intToBytes4(titleBytes.size))

        // 填充至 108 (firstImageIndex)
        val padTo108 = ByteArray(108 - (84 + 8))
        mobiHeaderStream.write(padTo108)
        mobiHeaderStream.write(intToBytes4(2)) // firstImageIndex = 2 (Record 2)
        mobiHeaderStream.write(intToBytes4(-1)) // huffOffset
        mobiHeaderStream.write(intToBytes4(0)) // huffNumber

        // 填充至 128 (exthFlags)
        val padTo128 = ByteArray(128 - (116 + 4))
        mobiHeaderStream.write(padTo128)
        mobiHeaderStream.write(intToBytes4(0x40)) // exthFlags (bit 6 = EXTH present)

        // 填充至 232
        val padTo232 = ByteArray(232 - (128 + 4))
        mobiHeaderStream.write(padTo232)

        rec0Stream.write(mobiHeaderStream.toByteArray())

        // 3. EXTH Header
        val exthStream = ByteArrayOutputStream()
        val authorBytes = author.toByteArray(Charsets.UTF_8)
        exthStream.write("EXTH".toByteArray(Charsets.ISO_8859_1))
        val exthLen = 12 + (8 + authorBytes.size) + (8 + titleBytes.size)
        exthStream.write(intToBytes4(exthLen))
        exthStream.write(intToBytes4(2)) // 2 records

        // Tag 100: Author
        exthStream.write(intToBytes4(100))
        exthStream.write(intToBytes4(8 + authorBytes.size))
        exthStream.write(authorBytes)

        // Tag 503: Title
        exthStream.write(intToBytes4(503))
        exthStream.write(intToBytes4(8 + titleBytes.size))
        exthStream.write(titleBytes)

        rec0Stream.write(exthStream.toByteArray())
        rec0Stream.write(titleBytes)

        val rec0Bytes = rec0Stream.toByteArray()

        // 3 Records: Record 0, Record 1 (text), Record 2 (empty dummy image)
        val numRecords = 3
        val rec2Bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) // Dummy JPEG header

        val headerSize = 78
        val recordTableSize = numRecords * 8 + 2 // 2 bytes padding
        val offset0 = headerSize + recordTableSize
        val offset1 = offset0 + rec0Bytes.size
        val offset2 = offset1 + rec1Bytes.size

        val out = ByteArrayOutputStream()
        // PalmDB Header (78 bytes)
        val dbNameBytes = ByteArray(32)
        System.arraycopy("TEST_MOBI_DB".toByteArray(Charsets.ISO_8859_1), 0, dbNameBytes, 0, 12)
        out.write(dbNameBytes) // 0..31
        out.write(byteArrayOf(0, 0)) // 32..33
        out.write(byteArrayOf(0, 0)) // 34..35
        out.write(ByteArray(24)) // 36..59
        out.write("BOOK".toByteArray(Charsets.ISO_8859_1)) // 60..63 type
        out.write("MOBI".toByteArray(Charsets.ISO_8859_1)) // 64..67 creator
        out.write(ByteArray(8)) // 68..75
        out.write(byteArrayOf(0, numRecords.toByte())) // 76..77 numRecords = 3

        // Record entries (8 bytes each)
        writeRecordEntry(out, offset0, 0)
        writeRecordEntry(out, offset1, 1)
        writeRecordEntry(out, offset2, 2)
        out.write(byteArrayOf(0, 0)) // 2 bytes gap

        // Record data
        out.write(rec0Bytes)
        out.write(rec1Bytes)
        out.write(rec2Bytes)

        return out.toByteArray()
    }

    private fun writeRecordEntry(out: ByteArrayOutputStream, offset: Int, uid: Int) {
        out.write(intToBytes4(offset))
        out.write(0) // attributes
        out.write(byteArrayOf(0, 0, uid.toByte())) // unique ID (3 bytes)
    }

    private fun intToBytes4(v: Int): ByteArray {
        return byteArrayOf(
            ((v ushr 24) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        )
    }
}
