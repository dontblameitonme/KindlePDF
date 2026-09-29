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

            val fmt = guessFormat(bytes, url)
            // 关键修复：
            // BitmapFactory.Options.inJustDecodeBounds = true 时，decodeByteArray 按 Android API 规范
            // 必然返回 null，并将真实宽高写入 opts.outWidth / opts.outHeight。
            // 旧代码判断 `if (bmp != null)` 永远为 false，导致所有图片宽高被硬编码为 200x200 正方形造成严重变形。
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

