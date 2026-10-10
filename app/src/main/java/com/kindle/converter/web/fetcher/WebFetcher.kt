package com.kindle.converter.web.fetcher

import com.kindle.converter.web.settings.UaMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class FetchException(message: String) : Exception(message)

/**
 * 网页下载器：处理编码探测、UA 切换、大小上限。
 * 默认使用桌面 UA（PC 版网页），可绕过多数移动端"打开 App"截断逻辑。
 */
class WebFetcher {

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** 下载上限 8MB，防止超大页面 */
        private const val MAX_BODY_SIZE = 8L * 1024 * 1024
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun userAgentFor(mode: UaMode, custom: String): String =
        custom.ifBlank { if (mode == UaMode.DESKTOP) DESKTOP_UA else MOBILE_UA }

    /** 下载网页并返回解码后的 HTML */
    suspend fun fetchHtml(url: String, userAgent: String = DESKTOP_UA): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw FetchException("网络请求失败：${e.message ?: e.javaClass.simpleName}")
        }

        response.use { resp ->
            if (!resp.isSuccessful) throw FetchException("HTTP ${resp.code}")

            val body = resp.body ?: throw FetchException("响应内容为空")
            val contentType = resp.header("Content-Type") ?: ""
            if (!contentType.contains("html", ignoreCase = true) &&
                !contentType.contains("xml", ignoreCase = true) &&
                contentType.isNotBlank()
            ) {
                throw FetchException("非网页内容（$contentType）")
            }

            val bytes = body.byteStream().use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read == -1) break
                    total += read
                    if (total > MAX_BODY_SIZE) throw FetchException("页面过大（超过 8MB）")
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }

            decode(bytes, contentType)
        }
    }

    /** 编码探测：响应头 charset > meta charset > UTF-8 */
    private fun decode(bytes: ByteArray, contentType: String): String {
        val headerCharset = Regex("charset\\s*=\\s*([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        if (headerCharset != null) {
            tryDecode(bytes, headerCharset)?.let { return it }
        }

        // 扫描前 2048 字节中的 meta charset
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val metaCharset = Regex(
            "<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE
        ).find(head)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+content\\s*=\\s*[\"'][^\"']*charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.get(1)
        if (metaCharset != null) {
            tryDecode(bytes, metaCharset)?.let { return it }
        }

        return tryDecode(bytes, "UTF-8")
            ?: tryDecode(bytes, "GB18030")
            ?: String(bytes, Charsets.UTF_8)
    }

    private fun tryDecode(bytes: ByteArray, charsetName: String): String? = try {
        val cs = Charset.forName(charsetName)
        val decoder = cs.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        decoder.decode(buffer).toString()
    } catch (_: Exception) {
        null
    }
}
