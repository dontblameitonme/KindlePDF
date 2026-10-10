package com.kindle.converter

import com.kindle.converter.epub.BookMeta
import com.kindle.converter.epub.EpubBuilder
import com.kindle.converter.web.model.WebArticle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

class EpubBuilderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBuildEpubZipStructure(): Unit = runBlocking {
        val meta = BookMeta(
            title = "EPUB 测试书",
            author = "测试作者",
            language = "zh"
        )
        val articles = listOf(
            WebArticle(
                url = "https://example.com/c1",
                title = "第一章 序言",
                contentHtml = "<h1>第一章 序言</h1><p>内容段落测试</p>"
            ),
            WebArticle(
                url = "https://example.com/c2",
                title = "第二章 核心内容",
                contentHtml = "<h1>第二章 核心内容</h1><p>更多内容段落</p>"
            )
        )

        val outputFile = tempFolder.newFile("test_book.epub")
        val generatedFile = EpubBuilder.build(
            meta = meta,
            articles = articles,
            embedImages = false,
            userAgent = "test-agent",
            outputFile = outputFile
        )

        assertTrue("EPUB 文件应存在且大小大于 0", generatedFile.exists() && generatedFile.length() > 0)

        // 验证 Zip entries 包含 EPUB 3 规范必要文件
        val entries = mutableListOf<String>()
        ZipInputStream(generatedFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                entry = zip.nextEntry
            }
        }

        assertEquals("mimetype 必须是第一个 entry", "mimetype", entries.firstOrNull())
        assertTrue("必须包含 container.xml", entries.contains("META-INF/container.xml"))
        assertTrue("必须包含 content.opf", entries.contains("OEBPS/content.opf"))
        assertTrue("必须包含 toc.ncx", entries.contains("OEBPS/toc.ncx"))
        assertTrue("必须包含 nav.xhtml", entries.contains("OEBPS/nav.xhtml"))
        assertTrue("必须包含 chapter_001.xhtml", entries.contains("OEBPS/chapter_001.xhtml"))
        assertTrue("必须包含 chapter_002.xhtml", entries.contains("OEBPS/chapter_002.xhtml"))
    }
}
