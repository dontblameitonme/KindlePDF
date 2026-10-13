package com.kindle.converter.parser

import com.kindle.converter.data.Block
import com.kindle.converter.data.Document
import com.kindle.converter.data.TextRun
import com.kindle.converter.data.TocEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 纯本地轻量级 MOBI / AZW / AZW3 解析器：
 * 1. 支持标准 PalmDB (PDB) 容器解析与多记录切分；
 * 2. 支持无压缩（type 1）、PalmDOC LZ77（type 2）及高压缩 HUFF/CDIC（type 17480 / 'DH'）；
 * 3. 完整支持 extraRecordFlags 尾随变长字段剥离算法；
 * 4. 兼容 KF8 (AZW3) 独立及双格式（MOBI7/KF8 hybrid）边界自动感知与提取；
 * 5. 解析内嵌图片（JPEG/PNG/GIF/BMP/WebP），支持 recindex、kindle:embed 等引用映射；
 * 6. 支持 MOBI HTML 正文及 <mbp:pagebreak> 章节分页、标题目录自动提取。
 */
class MobiParser {

    suspend fun parse(bytes: ByteArray): Document {
        if (bytes.size < 78) {
            throw IllegalArgumentException("文件过小，非有效的 MOBI / PalmDB 文件")
        }

        // 1. 解析 PalmDB 头部及所有 Record 偏移
        val numRecords = readUInt16BE(bytes, 76)
        if (numRecords <= 0 || bytes.size < 78 + numRecords * 8) {
            throw IllegalArgumentException("PalmDB 记录表损坏或记录数为空")
        }

        val recordOffsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            recordOffsets[i] = readInt32BE(bytes, 78 + i * 8)
        }

        fun getRecord(index: Int): ByteArray {
            if (index !in 0 until numRecords) return ByteArray(0)
            val start = recordOffsets[index]
            val end = if (index + 1 < numRecords) recordOffsets[index + 1] else bytes.size
            if (start in 0 until bytes.size && end >= start && end <= bytes.size) {
                return bytes.copyOfRange(start, end)
            }
            return ByteArray(0)
        }

        // 2. 解析 Record 0
        val rec0 = getRecord(0)
        if (rec0.size < 16) {
            throw IllegalArgumentException("Record 0 头信息损坏")
        }

        val compression = readUInt16BE(rec0, 0)
        val textRecordCount = readUInt16BE(rec0, 8)
        val encryptionType = readUInt16BE(rec0, 12)
        if (encryptionType != 0) {
            throw IllegalStateException("此 MOBI/AZW 文件受 Kindle DRM 保护，无法直接解密排版。请使用无 DRM 格式。")
        }

        // 解析 MOBI 头部及 EXTH 元数据
        var title = ""
        var author = ""
        var textEncoding = 65001 // 默认 UTF-8
        var firstImageIndex = -1
        var huffOffset = -1
        var huffNumber = 0
        var extraRecordFlags = 0
        var kf8BoundaryRecord = -1

        val hasMobiHeader = rec0.size >= 32 &&
            rec0[16] == 'M'.code.toByte() &&
            rec0[17] == 'O'.code.toByte() &&
            rec0[18] == 'B'.code.toByte() &&
            rec0[19] == 'I'.code.toByte()

        if (hasMobiHeader) {
            val mobiHeaderLen = readInt32BE(rec0, 20)
            textEncoding = readInt32BE(rec0, 28)

            // 读取标题（fullNameOffset & fullNameLength）
            if (rec0.size >= 16 + 92) {
                val fullNameOffset = readInt32BE(rec0, 16 + 84)
                val fullNameLength = readInt32BE(rec0, 16 + 88)
                if (fullNameLength in 1..4096) {
                    if (fullNameOffset + fullNameLength <= rec0.size) {
                        title = String(rec0, fullNameOffset, fullNameLength, Charsets.UTF_8).trim()
                    } else if (16 + fullNameOffset + fullNameLength <= rec0.size) {
                        title = String(rec0, 16 + fullNameOffset, fullNameLength, Charsets.UTF_8).trim()
                    }
                }
            }

            // 读取首张图片索引与 HUFF/CDIC 参数
            if (rec0.size >= 16 + 120) {
                firstImageIndex = readInt32BE(rec0, 16 + 108)
                huffOffset = readInt32BE(rec0, 16 + 112)
                huffNumber = readInt32BE(rec0, 16 + 116)
            }

            // 读取 extraRecordFlags
            if (mobiHeaderLen >= 244 && rec0.size >= 16 + 244) {
                extraRecordFlags = readUInt16BE(rec0, 16 + 242)
            }

            // 读取 EXTH 扩展元数据
            val exthPos = 16 + mobiHeaderLen
            if (rec0.size >= exthPos + 12 &&
                rec0[exthPos] == 'E'.code.toByte() &&
                rec0[exthPos + 1] == 'X'.code.toByte() &&
                rec0[exthPos + 2] == 'T'.code.toByte() &&
                rec0[exthPos + 3] == 'H'.code.toByte()
            ) {
                val exthCount = readInt32BE(rec0, exthPos + 8)
                var pos = exthPos + 12
                for (i in 0 until exthCount) {
                    if (pos + 8 > rec0.size) break
                    val tag = readInt32BE(rec0, pos)
                    val tagLen = readInt32BE(rec0, pos + 4)
                    val dataLen = tagLen - 8
                    if (pos + 8 + dataLen <= rec0.size && dataLen >= 0) {
                        when (tag) {
                            100 -> author = String(rec0, pos + 8, dataLen, Charsets.UTF_8).trim()
                            503 -> {
                                val t = String(rec0, pos + 8, dataLen, Charsets.UTF_8).trim()
                                if (t.isNotEmpty()) title = t
                            }
                            121 -> {
                                if (dataLen == 4) {
                                    kf8BoundaryRecord = readInt32BE(rec0, pos + 8)
                                }
                            }
                        }
                    }
                    pos += tagLen
                }
            }
        }

        // 若 EXTH/MOBI 未提供书名，兜底使用 PalmDB 数据库名称
        if (title.isBlank()) {
            val dbName = String(bytes, 0, 32, Charsets.ISO_8859_1).trim().trim('\u0000')
            title = dbName.ifBlank { "MOBI 电子书" }
        }

        // 3. 判断是否采用 KF8 (AZW3) 章节流
        var activeStartRec = 1
        var activeRecordCount = textRecordCount
        var activeCompression = compression
        var activeExtraRecordFlags = extraRecordFlags
        var activeHuffOffset = huffOffset
        var activeHuffNumber = huffNumber
        var activeFirstImageIndex = firstImageIndex

        if (kf8BoundaryRecord in 1 until numRecords) {
            val kf8Rec = getRecord(kf8BoundaryRecord)
            if (kf8Rec.size >= 32) {
                val kf8Compression = readUInt16BE(kf8Rec, 0)
                val kf8Count = readUInt16BE(kf8Rec, 8)
                val kf8Enc = readUInt16BE(kf8Rec, 12)
                if (kf8Enc == 0 && kf8Count > 0 && kf8Compression in listOf(1, 2, 17480)) {
                    activeStartRec = kf8BoundaryRecord + 1
                    activeRecordCount = kf8Count
                    activeCompression = kf8Compression
                    if (kf8Rec.size >= 36 &&
                        kf8Rec[16] == 'M'.code.toByte() &&
                        kf8Rec[17] == 'O'.code.toByte() &&
                        kf8Rec[18] == 'B'.code.toByte() &&
                        kf8Rec[19] == 'I'.code.toByte()
                    ) {
                        val kf8HeaderLen = readInt32BE(kf8Rec, 20)
                        if (kf8Rec.size >= 16 + 120) {
                            activeFirstImageIndex = readInt32BE(kf8Rec, 16 + 108)
                            activeHuffOffset = readInt32BE(kf8Rec, 16 + 112)
                            activeHuffNumber = readInt32BE(kf8Rec, 16 + 116)
                        }
                        if (kf8HeaderLen >= 244 && kf8Rec.size >= 16 + 244) {
                            activeExtraRecordFlags = readUInt16BE(kf8Rec, 16 + 242)
                        }
                    }
                }
            }
        }

        // 4. 解压并拼接所有文本 Records
        var huffReader: HuffReader? = null
        if (activeCompression == 17480 && activeHuffOffset >= 0 && activeHuffNumber > 0) {
            val huffList = mutableListOf<ByteArray>()
            for (h in 0 until activeHuffNumber) {
                val idx = activeHuffOffset + h
                if (idx in 0 until numRecords) {
                    huffList.add(getRecord(idx))
                }
            }
            if (huffList.isNotEmpty()) {
                huffReader = HuffReader(huffList)
            }
        }

        val textOut = ByteArrayOutputStream()
        val endRec = minOf(activeStartRec + activeRecordCount - 1, numRecords - 1)
        for (r in activeStartRec..endRec) {
            val raw = getRecord(r)
            if (raw.isEmpty()) continue
            val cleaned = stripTrailingEntries(raw, activeExtraRecordFlags)
            val decompressed = when (activeCompression) {
                1 -> cleaned
                2 -> PalmDocDecompressor.decompress(cleaned)
                17480 -> huffReader?.unpack(cleaned) ?: PalmDocDecompressor.decompress(cleaned)
                else -> cleaned
            }
            textOut.write(decompressed)
        }

        val allBytes = textOut.toByteArray()
        val charset = if (textEncoding == 1252) Charset.forName("windows-1252") else detectEncoding(allBytes)
        val textContent = String(allBytes, charset)

        // 5. 提取内嵌图片资源（从 activeFirstImageIndex 开始）
        val imageRecords = mutableListOf<ByteArray>()
        if (activeFirstImageIndex in 0 until numRecords) {
            for (i in activeFirstImageIndex until numRecords) {
                val imgRec = getRecord(i)
                if (looksLikeImage(imgRec)) {
                    imageRecords.add(imgRec)
                } else if (imageRecords.isNotEmpty()) {
                    // 已提取过图片且当前块不再是图片（如 FLIS / FCIS / BOUNDARY 等结构尾块），停止收集
                    break
                }
            }
        }

        // 6. 将正文解析为 Document（支持 HTML 与 纯文本）
        if (!textContent.contains('<') || !textContent.contains('>')) {
            // 纯文本旧式 PalmDOC
            val doc = TxtParser().parse(allBytes)
            return Document(doc.blocks, doc.toc, title, author)
        }

        return parseMobiHtml(textContent, imageRecords, title, author)
    }

    /** 将 MOBI 内嵌 HTML 与图片转换为 KindlePDF 统一 Block 模型 */
    private fun parseMobiHtml(
        html: String,
        imageRecords: List<ByteArray>,
        title: String,
        author: String
    ): Document {
        val blocks = mutableListOf<Block>()
        val toc = mutableListOf<TocEntry>()

        // 预处理 MOBI 专有标签：<mbp:pagebreak> 统一规范化为 <hr class="pagebreak"/>
        val normalizedHtml = html
            .replace(Regex("""<mbp:pagebreak\s*/?>""", RegexOption.IGNORE_CASE), """<hr class="pagebreak"/>""")
            .replace(Regex("""<mbp:section\s*/?>""", RegexOption.IGNORE_CASE), "<div>")

        val doc = Jsoup.parse(normalizedHtml)
        val body = doc.body() ?: doc

        fun findImageBlock(imgEl: Element): Block.ImageBlock? {
            val recindexStr = imgEl.attr("recindex").trim()
            val srcStr = imgEl.attr("src").trim()
            val fileposStr = imgEl.attr("filepos").trim()

            val imgIndex = when {
                recindexStr.isNotEmpty() -> recindexStr.toIntOrNull()?.minus(1)
                srcStr.startsWith("kindle:embed:", ignoreCase = true) -> {
                    srcStr.substringAfter("kindle:embed:").takeWhile { it.isDigit() }.toIntOrNull()?.minus(1)
                }
                srcStr.contains("image", ignoreCase = true) -> {
                    Regex("""\d+""").find(srcStr)?.value?.toIntOrNull()?.minus(1)
                }
                fileposStr.isNotEmpty() -> fileposStr.toIntOrNull()?.minus(1)
                else -> null
            }

            if (imgIndex != null && imgIndex in imageRecords.indices) {
                val bytes = imageRecords[imgIndex]
                return ImageDownloader.decodeImageBlock(bytes)
            }
            return null
        }

        fun extractInlineRuns(element: Element): List<TextRun> {
            val runs = mutableListOf<TextRun>()
            fun visit(node: Node, bold: Boolean, italic: Boolean) {
                when (node) {
                    is TextNode -> {
                        val text = node.text()
                        if (text.isNotEmpty()) {
                            runs.add(TextRun(text, bold = bold, italic = italic))
                        }
                    }
                    is Element -> {
                        val tag = node.tagName().lowercase()
                        val isBold = bold || tag in listOf("b", "strong", "h1", "h2", "h3", "h4", "h5", "h6")
                        val isItalic = italic || tag in listOf("i", "em")
                        for (child in node.childNodes()) {
                            visit(child, isBold, isItalic)
                        }
                    }
                }
            }
            for (child in element.childNodes()) {
                visit(child, bold = false, italic = false)
            }
            return runs
        }

        fun traverse(element: Element) {
            val tag = element.tagName().lowercase()
            when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = tag.last().digitToInt()
                    val runs = extractInlineRuns(element)
                    val hText = runs.joinToString("") { it.text }.trim()
                    if (hText.isNotEmpty()) {
                        blocks.add(Block.Heading(level, runs))
                        toc.add(TocEntry(hText, level, blocks.lastIndex))
                    }
                }

                "p", "figcaption" -> {
                    // 若 p 内部直接包含 img，将图片提取为独立块并保持顺序
                    val imgChildren = element.select("img")
                    if (imgChildren.isNotEmpty()) {
                        var textBuf = mutableListOf<TextRun>()
                        for (child in element.childNodes()) {
                            if (child is Element && child.tagName().equals("img", ignoreCase = true)) {
                                if (textBuf.isNotEmpty() && textBuf.any { it.text.isNotBlank() }) {
                                    blocks.add(Block.Paragraph(textBuf))
                                    textBuf = mutableListOf()
                                }
                                findImageBlock(child)?.let { blocks.add(it) }
                            } else if (child is TextNode) {
                                val t = child.text()
                                if (t.isNotEmpty()) textBuf.add(TextRun(t))
                            } else if (child is Element) {
                                textBuf.addAll(extractInlineRuns(child))
                            }
                        }
                        if (textBuf.isNotEmpty() && textBuf.any { it.text.isNotBlank() }) {
                            blocks.add(Block.Paragraph(textBuf))
                        }
                    } else {
                        val runs = extractInlineRuns(element)
                        if (runs.isNotEmpty() && runs.any { it.text.isNotBlank() }) {
                            blocks.add(Block.Paragraph(runs))
                        }
                    }
                }

                "img" -> {
                    findImageBlock(element)?.let { blocks.add(it) }
                }

                "blockquote" -> {
                    val runs = extractInlineRuns(element)
                    if (runs.isNotEmpty() && runs.any { it.text.isNotBlank() }) {
                        blocks.add(Block.QuoteBlock(runs))
                    }
                }

                "pre" -> {
                    val text = element.wholeText()
                    if (text.isNotBlank()) {
                        blocks.add(Block.CodeBlock(text.trim()))
                    }
                }

                "ul", "ol" -> {
                    val isOrdered = tag == "ol"
                    for (li in element.children().filter { it.tagName().equals("li", ignoreCase = true) }) {
                        val runs = extractInlineRuns(li)
                        if (runs.isNotEmpty() && runs.any { it.text.isNotBlank() }) {
                            blocks.add(Block.ListItem(level = 0, ordered = isOrdered, runs = runs))
                        }
                    }
                }

                "table" -> {
                    val trs = element.select("tr")
                    val headers = mutableListOf<List<TextRun>>()
                    val rows = mutableListOf<List<List<TextRun>>>()
                    for (tr in trs) {
                        val thCells = tr.children().filter { it.tagName().equals("th", ignoreCase = true) }
                        val tdCells = tr.children().filter { it.tagName().equals("td", ignoreCase = true) }
                        val isHeaderRow = thCells.isNotEmpty() && tdCells.isEmpty() && headers.isEmpty()
                        val cellElements = if (thCells.isNotEmpty()) thCells else tdCells
                        val cellRunsList = cellElements.map { cell ->
                            extractInlineRuns(cell).ifEmpty { listOf(TextRun(cell.text().trim())) }
                        }
                        if (isHeaderRow) {
                            headers.addAll(cellRunsList)
                        } else if (cellRunsList.isNotEmpty()) {
                            rows.add(cellRunsList)
                        }
                    }
                    if (headers.isNotEmpty() || rows.isNotEmpty()) {
                        val maxCols = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
                        val normHeaders = if (headers.isNotEmpty()) {
                            List(maxCols) { headers.getOrNull(it) ?: emptyList() }
                        } else emptyList()
                        val normRows = rows.map { r -> List(maxCols) { r.getOrNull(it) ?: emptyList() } }
                        blocks.add(Block.TableBlock(normHeaders, normRows))
                    }
                }

                "hr" -> {
                    if (element.hasClass("pagebreak")) {
                        blocks.add(Block.PageBreak)
                    } else {
                        blocks.add(Block.HorizontalRule)
                    }
                }

                else -> {
                    // 容器节点（如 div、article、section、body 等）递归遍历其子元素
                    val isBlockContainer = tag in listOf("div", "article", "section", "main", "body")
                    if (isBlockContainer) {
                        val directText = element.ownText().trim()
                        if (directText.isNotEmpty() && element.children().isEmpty()) {
                            blocks.add(Block.Paragraph(listOf(TextRun(directText))))
                        } else {
                            for (child in element.children()) {
                                traverse(child)
                            }
                        }
                    }
                }
            }
        }

        traverse(body)

        // 目录兜底：若未解析到 TOC，且存在 Heading，提取作为目录
        if (toc.isEmpty()) {
            for ((idx, block) in blocks.withIndex()) {
                if (block is Block.Heading) {
                    val hText = block.runs.joinToString("") { it.text }.trim()
                    if (hText.isNotEmpty()) {
                        toc.add(TocEntry(hText, block.level, idx))
                    }
                }
            }
        }

        return Document(blocks, toc, title, author)
    }

    /** 检查是否为常见格式的图片文件头魔数 */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // JPEG (FF D8 FF)
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        // PNG (89 50 4E 47)
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        // GIF (GIF87a / GIF89a)
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) return true
        // BMP (BM)
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return true
        // WebP (RIFF....WEBP)
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return true
        return false
    }

    /** 剥离 MOBI Text Record 末尾的变长 trailing entries */
    private fun stripTrailingEntries(data: ByteArray, extraFlags: Int): ByteArray {
        if (extraFlags == 0 || data.isEmpty()) return data
        var num = 0
        val size = data.size
        var flags = extraFlags shr 1
        while (flags != 0) {
            if ((flags and 1) != 0) {
                val psize = size - num
                if (psize <= 0) break
                var bitpos = 0
                var result = 0
                var curr = psize
                while (curr > 0) {
                    val v = data[curr - 1].toInt() and 0xFF
                    result = result or ((v and 0x7F) shl bitpos)
                    bitpos += 7
                    curr--
                    if ((v and 0x80) != 0 || bitpos >= 28 || curr == 0) {
                        break
                    }
                }
                num += result
            }
            flags = flags shr 1
        }
        if ((extraFlags and 1) != 0) {
            val off = size - num - 1
            if (off in data.indices) {
                num += (data[off].toInt() and 0x03) + 1
            }
        }
        val cleanLen = (size - num).coerceIn(0, size)
        return data.copyOfRange(0, cleanLen)
    }

    private fun detectEncoding(bytes: ByteArray): Charset {
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        // Strict UTF-8 check
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            return Charsets.UTF_8
        } catch (_: Exception) { }
        return try {
            Charset.forName("GBK")
        } catch (_: Exception) {
            Charsets.ISO_8859_1
        }
    }

    private fun readUInt16BE(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readInt32BE(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readUInt32BE(data: ByteArray, offset: Int): Long {
        return readInt32BE(data, offset).toLong() and 0xFFFFFFFFL
    }
}

/**
 * 专为 PalmDOC LZ77 算法打造的高性能零内存拷贝缓冲区，完美支持任意 distance 重叠运行（Run-Length）回溯复制
 */
internal class FastByteArrayBuffer(initialCapacity: Int = 8192) {
    var buf = ByteArray(initialCapacity)
    var size = 0

    fun append(b: Byte) {
        if (size == buf.size) {
            buf = buf.copyOf(buf.size * 2)
        }
        buf[size++] = b
    }

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensureCapacity(size + length)
        System.arraycopy(bytes, offset, buf, size, length)
        size += length
    }

    fun copyBack(distance: Int, length: Int) {
        if (distance <= 0 || distance > size || length <= 0) return
        ensureCapacity(size + length)
        val srcStart = size - distance
        for (j in 0 until length) {
            buf[size] = buf[srcStart + j]
            size++
        }
    }

    private fun ensureCapacity(minCap: Int) {
        if (minCap > buf.size) {
            var newCap = buf.size * 2
            if (newCap < minCap) newCap = minCap
            buf = buf.copyOf(newCap)
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** PalmDOC (LZ77 变体) 纯 Kotlin 高性能解压器 */
internal object PalmDocDecompressor {
    fun decompress(input: ByteArray): ByteArray {
        val out = FastByteArrayBuffer(input.size * 2)
        var i = 0
        val len = input.size
        while (i < len) {
            val token = input[i].toInt() and 0xFF
            i++
            when {
                token == 0 -> {
                    out.append(0)
                }
                token in 1..8 -> {
                    val count = minOf(token, len - i)
                    out.append(input, i, count)
                    i += count
                }
                token in 9..0x7F -> {
                    out.append(token.toByte())
                }
                token in 0x80..0xBF -> {
                    if (i >= len) break
                    val nextByte = input[i].toInt() and 0xFF
                    i++
                    val pair = (token shl 8) or nextByte
                    val distance = (pair shr 3) and 0x07FF
                    val length = (pair and 0x07) + 3
                    out.copyBack(distance, length)
                }
                token in 0xC0..0xFF -> {
                    out.append(' '.code.toByte())
                    out.append((token xor 0x80).toByte())
                }
            }
        }
        return out.toByteArray()
    }
}

/** MOBI / Kindle 专有 HUFF/CDIC 高压缩解密器 */
internal class HuffReader(records: List<ByteArray>) {
    private data class Dict1Entry(val codelen: Int, val term: Boolean, val maxcode: Long)

    private val dict1 = arrayOfNulls<Dict1Entry>(256)
    private val mincode = LongArray(33)
    private val maxcode = LongArray(33)
    private val dictionary = mutableListOf<Pair<ByteArray, Boolean>?>()

    init {
        if (records.isNotEmpty()) {
            loadHuff(records[0])
            for (i in 1 until records.size) {
                loadCdic(records[i])
            }
        }
    }

    private fun loadHuff(huff: ByteArray) {
        if (huff.size < 24) return
        val magic = String(huff, 0, 4, Charsets.ISO_8859_1)
        if (magic != "HUFF") return

        val off1 = readInt32(huff, 8)
        val off2 = readInt32(huff, 12)

        if (off1 in 0..huff.size - 256 * 4) {
            for (i in 0 until 256) {
                val v = readUInt32(huff, off1 + i * 4)
                val codelen = (v and 0x1FL).toInt()
                val term = (v and 0x80L) != 0L
                val maxcodeRaw = v ushr 8
                val maxc = (((maxcodeRaw + 1) shl (32 - codelen)) - 1) and 0xFFFFFFFFL
                dict1[i] = Dict1Entry(codelen, term, maxc)
            }
        }

        if (off2 in 0..huff.size - 64 * 4) {
            mincode[0] = 0L
            maxcode[0] = 0L
            for (codelen in 1..32) {
                val minVal = readUInt32(huff, off2 + (codelen - 1) * 2 * 4)
                val maxVal = readUInt32(huff, off2 + ((codelen - 1) * 2 + 1) * 4)
                mincode[codelen] = (minVal shl (32 - codelen)) and 0xFFFFFFFFL
                maxcode[codelen] = (((maxVal + 1) shl (32 - codelen)) - 1) and 0xFFFFFFFFL
            }
        }
    }

    private fun loadCdic(cdic: ByteArray) {
        if (cdic.size < 16) return
        val magic = String(cdic, 0, 4, Charsets.ISO_8859_1)
        if (magic != "CDIC") return

        val phrases = readInt32(cdic, 8)
        val bits = readInt32(cdic, 12)
        val n = minOf(1 shl bits, phrases - dictionary.size)

        for (i in 0 until n) {
            val off = readUInt16(cdic, 16 + i * 2)
            if (16 + off + 2 <= cdic.size) {
                val blen = readUInt16(cdic, 16 + off)
                val sliceLen = blen and 0x7FFF
                val sliceStart = 18 + off
                val sliceEnd = minOf(sliceStart + sliceLen, cdic.size)
                val slice = if (sliceEnd >= sliceStart) cdic.copyOfRange(sliceStart, sliceEnd) else ByteArray(0)
                val flag = (blen and 0x8000) != 0
                dictionary.add(Pair(slice, flag))
            }
        }
    }

    fun unpack(data: ByteArray): ByteArray {
        val out = FastByteArrayBuffer(data.size * 3)
        var bitsleft = data.size * 8
        val padded = data + ByteArray(8)
        var pos = 0
        var x = readUInt64(padded, pos)
        var n = 32

        while (true) {
            if (n <= 0) {
                pos += 4
                if (pos + 8 <= padded.size) {
                    x = readUInt64(padded, pos)
                }
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL
            val entryIdx = (code ushr 24).toInt()
            val entry = dict1[entryIdx] ?: break
            var codelen = entry.codelen
            var maxc = entry.maxcode

            if (!entry.term) {
                while (codelen <= 32 && code < mincode[codelen]) {
                    codelen++
                }
                if (codelen > 32) break
                maxc = this.maxcode[codelen]
            }

            n -= codelen
            bitsleft -= codelen
            if (bitsleft < 0) break

            val r = ((maxc - code) ushr (32 - codelen)).toInt()
            if (r in dictionary.indices) {
                val item = dictionary[r]
                if (item != null) {
                    var (slice, flag) = item
                    if (!flag) {
                        dictionary[r] = null
                        slice = unpack(slice)
                        dictionary[r] = Pair(slice, true)
                    }
                    out.append(slice, 0, slice.size)
                }
            }
        }
        return out.toByteArray()
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return readInt32(data, offset).toLong() and 0xFFFFFFFFL
    }

    private fun readUInt64(data: ByteArray, offset: Int): Long {
        if (offset + 7 >= data.size) return 0L
        var res = 0L
        for (i in 0 until 8) {
            res = (res shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return res
    }
}
