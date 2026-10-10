package com.kindle.converter.epub

import com.kindle.converter.web.model.WebArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 书籍元数据 */
data class BookMeta(
    val title: String,
    val author: String = "",
    val language: String = "zh",
)

/**
 * EPUB 3 打包器：按规范写入 mimetype / container.xml / content.opf /
 * toc.ncx + nav.xhtml（双目录）/ 章节 XHTML / 图片。
 */
object EpubBuilder {

    /**
     * 生成 EPUB 文件。
     * @param articles 已提取的文章列表（每篇一章）
     * @param embedImages 是否嵌入图片
     * @return 生成的文件
     */
    suspend fun build(
        meta: BookMeta,
        articles: List<WebArticle>,
        embedImages: Boolean,
        userAgent: String,
        outputFile: File,
    ): File = withContext(Dispatchers.IO) {
        require(articles.isNotEmpty()) { "没有可写入的文章" }

        val bookId = "urn:uuid:" + UUID.randomUUID().toString()
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        // 1. 章节转换与图片收集
        val imageDownloader = if (embedImages) WebImageDownloader(userAgent) else null
        val chapters = mutableListOf<Pair<String, String>>() // title -> xhtml
        val allImageUrls = mutableListOf<String>()
        val chapterBodies = mutableListOf<Pair<String, String>>()

        for (article in articles) {
            if (imageDownloader != null) {
                val (body, imageUrls) = XhtmlConverter.toXhtmlBody(article.contentHtml, article.url)
                chapterBodies.add(article.title to body)
                allImageUrls.addAll(imageUrls)
            } else {
                chapterBodies.add(article.title to XhtmlConverter.stripImages(article.contentHtml, article.url))
            }
        }

        // 2. 下载图片（去重由下载器处理）
        val urlToLocal = imageDownloader?.downloadAll(allImageUrls.distinct()) ?: emptyMap()

        // 3. 生成最终章节
        for ((title, body) in chapterBodies) {
            val finalBody = if (imageDownloader != null) {
                XhtmlConverter.replaceImageUrls(body, urlToLocal)
            } else body
            chapters.add(title to XhtmlConverter.chapterDocument(title, finalBody))
        }

        // 4. 打包
        outputFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            writeMimeType(zip)
            writeEntry(zip, "META-INF/container.xml", containerXml())

            val imageFiles = imageDownloader?.downloadedFiles() ?: emptyMap()
            writeEntry(zip, "OEBPS/content.opf", contentOpf(meta, bookId, modified, chapters, imageFiles))
            writeEntry(zip, "OEBPS/toc.ncx", tocNcx(meta, bookId, chapters))
            writeEntry(zip, "OEBPS/nav.xhtml", navXhtml(chapters))

            chapters.forEachIndexed { index, (_, xhtml) ->
                writeEntry(zip, "OEBPS/chapter_%03d.xhtml".format(index + 1), xhtml)
            }

            for ((name, bytes) in imageFiles) {
                zip.putNextEntry(ZipEntry("OEBPS/images/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        outputFile
    }

    private fun writeMimeType(zip: ZipOutputStream) {
        val bytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun containerXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun contentOpf(
        meta: BookMeta,
        bookId: String,
        modified: String,
        chapters: List<Pair<String, String>>,
        imageFiles: Map<String, ByteArray>,
        mimeTypeResolver: (String) -> String = { name ->
            when (name.substringAfterLast('.')) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                else -> "image/jpeg"
            }
        },
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\">\n")
        append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("    <dc:identifier id=\"book-id\">").append(bookId).append("</dc:identifier>\n")
        append("    <dc:title>").append(XhtmlConverter.escapeXml(meta.title)).append("</dc:title>\n")
        if (meta.author.isNotBlank()) {
            append("    <dc:creator>").append(XhtmlConverter.escapeXml(meta.author)).append("</dc:creator>\n")
        }
        append("    <dc:language>").append(meta.language).append("</dc:language>\n")
        append("    <meta property=\"dcterms:modified\">").append(modified).append("</meta>\n")
        append("  </metadata>\n")
        append("  <manifest>\n")
        append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        chapters.forEachIndexed { index, _ ->
            append("    <item id=\"chapter_")
                .append(index + 1)
                .append("\" href=\"chapter_")
                .append("%03d".format(index + 1))
                .append(".xhtml\" media-type=\"application/xhtml+xml\"/>\n")
        }
        for (name in imageFiles.keys) {
            append("    <item id=\"img_").append(name.substringBefore('.'))
                .append("\" href=\"images/").append(name)
                .append("\" media-type=\"").append(mimeTypeResolver(name)).append("\"/>\n")
        }
        append("  </manifest>\n")
        append("  <spine toc=\"ncx\">\n")
        chapters.forEachIndexed { index, _ ->
            append("    <itemref idref=\"chapter_").append(index + 1).append("\"/>\n")
        }
        append("  </spine>\n")
        append("</package>\n")
    }

    private fun tocNcx(meta: BookMeta, bookId: String, chapters: List<Pair<String, String>>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
            append("  <head>\n")
            append("    <meta name=\"dtb:uid\" content=\"").append(bookId).append("\"/>\n")
            append("    <meta name=\"dtb:depth\" content=\"1\"/>\n")
            append("  </head>\n")
            append("  <docTitle><text>").append(XhtmlConverter.escapeXml(meta.title)).append("</text></docTitle>\n")
            append("  <navMap>\n")
            chapters.forEachIndexed { index, (title, _) ->
                append("    <navPoint id=\"navpoint-").append(index + 1)
                    .append("\" playOrder=\"").append(index + 1).append("\">\n")
                append("      <navLabel><text>").append(XhtmlConverter.escapeXml(title)).append("</text></navLabel>\n")
                append("      <content src=\"chapter_").append("%03d".format(index + 1)).append(".xhtml\"/>\n")
                append("    </navPoint>\n")
            }
            append("  </navMap>\n")
            append("</ncx>\n")
        }

    private fun navXhtml(chapters: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
        append("<head><title>目录</title></head>\n")
        append("<body>\n<nav epub:type=\"toc\" id=\"toc\">\n<h1>目录</h1>\n<ol>\n")
        chapters.forEachIndexed { index, (title, _) ->
            append("<li><a href=\"chapter_").append("%03d".format(index + 1))
                .append(".xhtml\">").append(XhtmlConverter.escapeXml(title)).append("</a></li>\n")
        }
        append("</ol>\n</nav>\n</body>\n</html>\n")
    }
}
