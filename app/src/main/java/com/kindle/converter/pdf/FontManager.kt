package com.kindle.converter.pdf

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 字体管理：支持导入字体并持久化到 App 私有目录，列表管理、选择、删除。
 * 不再依赖打包进 assets 的默认字体——首次启动若无已导入字体，需用户在界面导入。
 *
 * 度量统一使用 PdfBox 的 PDType0Font，保证预览与最终 PDF 一致。
 */
class FontManager(private val context: Context) {

    data class FontEntry(
        val id: String,
        val name: String,
        val fileName: String,   // 存放在 fonts 目录下的文件名
        val source: String      // "imported"
    )

    data class FontInfo(
        val name: String,
        val font: PDFont,
        val boldFont: PDFont?,
        val source: String
    )

    private val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "fonts_index.json")

    private var currentEntry: FontEntry? = null
    private var currentFontInfo: FontInfo? = null
    private var scratchDoc: PDDocument? = null
    private var lastId: String? = null

    init {
        loadIndex()
    }

    /** 列出所有已管理的字体 */
    fun listFonts(): List<FontEntry> {
        return readIndex().optJSONArray("fonts")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { obj ->
                    FontEntry(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        fileName = obj.optString("fileName"),
                        source = obj.optString("source", "imported")
                    )
                }
            }
        } ?: emptyList()
    }

    fun getCurrentEntry(): FontEntry? = currentEntry
    fun getDisplayName(): String? = currentFontInfo?.name
    fun hasFont(): Boolean = currentFontInfo != null
    fun getCurrentFontInfo(): FontInfo? = currentFontInfo

    /** 当前所选字体的 Typeface，用于 Compose 预览渲染 */
    fun getCurrentTypeface(): Typeface? {
        val entry = currentEntry ?: return null
        return try {
            Typeface.createFromFile(File(fontsDir, entry.fileName))
        } catch (e: Exception) { null }
    }

    /** 从 SAF Uri 导入字体：拷贝到 fonts 目录、登记、并选中它 */
    fun importFont(uri: Uri): FontEntry? {
        return try {
            val displayName = getDisplayNameFromUri(uri)
            val ext = displayName.substringAfterLast('.', "ttf").lowercase()
                .takeIf { it in setOf("ttf", "otf", "ttc") } ?: "ttf"
            val baseName = displayName.substringBeforeLast('.')
            val id = UUID.randomUUID().toString()
            val destFile = File(fontsDir, "$id.$ext")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { out -> input.copyTo(out) }
            }

            // 校验能否正常加载
            val testDoc = PDDocument()
            val loaded = try {
                PDType0Font.load(testDoc, destFile)
            } catch (e: Exception) {
                null
            } finally {
                try { testDoc.close() } catch (_: Exception) {}
            }
            if (loaded == null) {
                destFile.delete()
                return null
            }

            val entry = FontEntry(id, baseName.ifBlank { "字体" }, destFile.name, "imported")
            val fonts = listFonts().toMutableList().apply { add(entry) }
            writeIndex(fonts, id)
            selectFont(id)
            entry
        } catch (e: Exception) {
            null
        }
    }

    fun selectFont(id: String): Boolean {
        val entry = listFonts().firstOrNull { it.id == id } ?: return false
        return try {
            val file = File(fontsDir, entry.fileName)
            if (!file.exists()) return false
            scratchDoc?.close()
            val doc = PDDocument()
            scratchDoc = doc
            val font = PDType0Font.load(doc, file)
            currentEntry = entry
            currentFontInfo = FontInfo(entry.name, font, boldFont = font, source = entry.source)
            writeIndex(listFonts(), id)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFont(id: String): Boolean {
        val entry = listFonts().firstOrNull { it.id == id } ?: return false
        try {
            File(fontsDir, entry.fileName).delete()
        } catch (_: Exception) {}
        if (currentEntry?.id == id) {
            currentFontInfo = null
            currentEntry = null
            try { scratchDoc?.close() } catch (_: Exception) {}
            scratchDoc = null
        }
        val remaining = listFonts().filter { it.id != id }
        writeIndex(remaining, if (lastId == id) null else lastId)
        return true
    }

    /** 启动时自动选中字体（上次使用的，否则第一个） */
    fun autoSelect(): Boolean {
        val fonts = listFonts()
        if (fonts.isEmpty()) return false
        val target = fonts.firstOrNull { it.id == lastId } ?: fonts.first()
        return selectFont(target.id)
    }

    /** 为输出 PDF 重新加载字体到目标文档 */
    fun reloadFontForDocument(outputDoc: PDDocument): Pair<PDFont, PDFont?>? {
        val entry = currentEntry ?: return null
        return try {
            val file = File(fontsDir, entry.fileName)
            val font = PDType0Font.load(outputDoc, file)
            Pair(font, font)
        } catch (e: Exception) { null }
    }

    fun close() {
        try { scratchDoc?.close() } catch (_: Exception) {}
    }

    // --- 持久化 ---

    private fun readIndex(): JSONObject {
        if (!indexFile.exists()) return JSONObject()
        return try {
            JSONObject(indexFile.readText())
        } catch (e: Exception) { JSONObject() }
    }

    private fun loadIndex() {
        lastId = readIndex().optString("lastId", "").takeIf { it.isNotEmpty() }
    }

    private fun writeIndex(fonts: List<FontEntry>, lastId: String?) {
        val obj = JSONObject()
        obj.put("lastId", lastId ?: this.lastId ?: "")
        val arr = JSONArray()
        fonts.forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("fileName", f.fileName)
                put("source", f.source)
            })
        }
        obj.put("fonts", arr)
        try { indexFile.writeText(obj.toString(2)) } catch (_: Exception) {}
        this.lastId = lastId ?: this.lastId
    }

    private fun getDisplayNameFromUri(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return uri.lastPathSegment ?: "font"
    }
}
