package com.kindle.converter.parser

import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 远程图片下载器：用于 md / html 中 <img src="http(s)://..."> 的场景。
 *
 * 在 IO 线程执行网络请求，返回图片字节 + 探测到的格式与像素尺寸（供 PdfGenerator 等比缩放）。
 * 单张失败返回 null，调用方据此决定是否丢弃该图（不影响整篇排版）。
 */
data class DownloadedImage(
    val bytes: ByteArray,
    val format: String,
    val widthPx: Int,
    val heightPx: Int
)

object ImageDownloader {

    /** 下载一张远程图片；非 2xx、超时、解码失败均返回 null */
    suspend fun download(url: String): DownloadedImage? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Kindle PDF)"
            )
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.isEmpty()) return@withContext null

            val fmt = guessFormat(bytes)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val bmp = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()
            val w = if (bmp != null) opts.outWidth else 200
            val h = if (bmp != null) opts.outHeight else 200
            DownloadedImage(
                bytes = bytes,
                format = fmt,
                widthPx = w.coerceAtLeast(1),
                heightPx = h.coerceAtLeast(1)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 根据文件头猜测图片格式，默认 png */
    private fun guessFormat(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpg"
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "png"
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) return "gif"
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return "webp"
        return "png"
    }
}
