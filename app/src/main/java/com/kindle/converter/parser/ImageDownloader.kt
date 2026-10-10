package com.kindle.converter.parser

import android.graphics.BitmapFactory
import com.kindle.converter.data.Block
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 远程图片下载与图片元数据探测工具：
 * 1) 用于 md / html 中 <img src="http(s)://..."> 的远程图片下载；
 * 2) 为 EPUB / DOCX 等本地归档解析器提供统一的图片格式识别与尺寸探测（decodeImageBlock）。
 */
data class DownloadedImage(
    val bytes: ByteArray,
    val format: String,
    val widthPx: Int,
    val heightPx: Int
)

object ImageDownloader {

    /** 下载一张远程图片；非 2xx、超时、解码失败均返回 null */
    suspend fun download(url: String, refererHint: String = ""): DownloadedImage? = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank()) return@withContext null

            // 1. 内联 Base64 图片 (data:image/...)
            if (cleanUrl.startsWith("data:image/", ignoreCase = true)) {
                val commaIdx = cleanUrl.indexOf(',')
                if (commaIdx > 0) {
                    val base64Part = cleanUrl.substring(commaIdx + 1)
                    val bytes = try {
                        android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
                    } catch (_: Exception) { null }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val fmt = guessFormat(bytes, "")
                        val (w, h) = decodeDimensions(bytes) ?: return@withContext null
                        return@withContext DownloadedImage(
                            bytes = bytes,
                            format = fmt,
                            widthPx = w.coerceAtLeast(1),
                            heightPx = h.coerceAtLeast(1)
                        )
                    }
                }
                return@withContext null
            }

            // 2. 协议相对 URL 补充 https:
            val targetUrl = if (cleanUrl.startsWith("//")) "https:$cleanUrl" else cleanUrl
            if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                return@withContext null
            }

            val conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty(
                "Accept",
                "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )

            // 防盗链：针对微信公众平台、知乎、CSDN、微博等图片 CDN 补充来源 Referer
            val host = runCatching { URL(targetUrl).host.lowercase() }.getOrDefault("")
            val referer = when {
                host.contains("zhimg.com") -> "https://www.zhihu.com/"
                host.contains("qpic.cn") || host.contains("weixin.qq.com") -> "https://mp.weixin.qq.com/"
                host.contains("csdnimg.cn") || host.contains("csdn.net") -> "https://blog.csdn.net/"
                host.contains("sinaimg.cn") || host.contains("weibo.com") -> "https://weibo.com/"
                refererHint.isNotBlank() -> refererHint
                else -> "https://$host/"
            }
            conn.setRequestProperty("Referer", referer)

            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.isEmpty()) return@withContext null

            val fmt = guessFormat(bytes, targetUrl)
            val (w, h) = decodeDimensions(bytes) ?: return@withContext null
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

    /**
     * 仅读取图片头部元数据获取像素宽高（不分配像素内存）。
     * 若字节流不是 Android BitmapFactory 支持的位图（如损坏数据或纯 SVG 文本），返回 null。
     */
    fun decodeDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outWidth to opts.outHeight
    }

    /**
     * 从原始图片字节流构造 Block.ImageBlock（供 EPUB / DOCX 解析器共用）。
     * 自动探测真实像素尺寸与格式；过滤无法解码或 <= 4px 的透明追踪像素图。
     */
    fun decodeImageBlock(bytes: ByteArray, pathHint: String = ""): Block.ImageBlock? {
        val (w, h) = decodeDimensions(bytes) ?: return null
        if (w <= 4 && h <= 4) return null
        val fmt = guessFormat(bytes, pathHint)
        return Block.ImageBlock(
            bytes = bytes,
            widthPx = w,
            heightPx = h,
            format = fmt
        )
    }

    /** 根据文件魔数（Magic Bytes）及路径后缀猜测图片格式，默认 png */
    fun guessFormat(bytes: ByteArray, pathHint: String = ""): String {
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
        val ext = pathHint.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
            return if (ext == "jpeg") "jpg" else ext
        }
        return "png"
    }
}

