package com.kindle.converter.epub

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 网页图片下载器：下载正文图片、按 URL 去重、按文件头探测 MIME 与扩展名。
 * 单图失败不影响整体，超限图片跳过。
 */
class WebImageDownloader(private val userAgent: String) {

    companion object {
        /** 单图大小上限 3MB */
        private const val MAX_IMAGE_SIZE = 3L * 1024 * 1024
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 已下载图片：绝对 URL -> 本地文件名 */
    private val nameByUrl = LinkedHashMap<String, String>()

    /** 本地文件名 -> 图片字节 */
    private val fileBytes = LinkedHashMap<String, ByteArray>()
    private var counter = 0

    /**
     * 批量下载图片。
     * @return 绝对 URL -> 本地文件名（失败的不在其中）
     */
    fun downloadAll(urls: List<String>): Map<String, String> {
        for (url in urls) {
            if (nameByUrl.containsKey(url)) continue
            val bytes = downloadOne(url) ?: continue
            counter++
            val name = "img_%03d.%s".format(counter, extensionOf(bytes))
            nameByUrl[url] = name
            fileBytes[name] = bytes
        }
        return nameByUrl.toMap()
    }

    fun downloadedFiles(): Map<String, ByteArray> = fileBytes.toMap()

    fun downloadOne(url: String): ByteArray? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", url.substringBefore("://") + "://" +
                url.substringAfter("://").substringBefore("/"))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.let { body ->
                if ((body.contentLength().takeIf { it >= 0 } ?: 0) > MAX_IMAGE_SIZE) return null
                val data = body.bytes()
                if (data.size > MAX_IMAGE_SIZE) null else data
            }
        }
    } catch (_: Exception) {
        null
    }

    /** 按文件头（magic bytes）探测图片类型 */
    fun extensionOf(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
        bytes.size > 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "webp"
        bytes.size > 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
        else -> "jpg"
    }

    fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.')) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }
}
