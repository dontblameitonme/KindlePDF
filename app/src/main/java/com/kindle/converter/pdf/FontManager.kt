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
 * 没有设置或导入字体时，自动降级选用系统预装中文字体（如 NotoSansSC / DroidSansFallback），
 * 保证开箱即可直接转换排版。
 *
 * 度量统一使用 PdfBox 的 PDType0Font，保证预览与最终 PDF 一致。
 */
class FontManager(private val context: Context) {

    companion object {
        const val SYSTEM_FONT_ID = "system_default"
        const val SYSTEM_FONT_NAME = "系统默认字体"

        private val SYSTEM_CJK_CANDIDATE_PATHS = listOf(
            // Android 常见系统中文字体路径
            "/system/fonts/NotoSansSC-VF.ttf",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/NotoSansSC-Regular.ttf",
            "/system/fonts/NotoSerifSC-Regular.otf",
            "/system/fonts/NotoSerifSC-Regular.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/NotoSansHans-Regular.otf",
            "/system/fonts/NotoSansHans-Regular.ttf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/Roboto-Regular.ttf",
            // 本地开发/单元测试运行在 Windows 时的候选路径
            "C:\\Windows\\Fonts\\NotoSerifSC-VF.ttf",
            "C:\\Windows\\Fonts\\simsun.ttc",
            "C:\\Windows\\Fonts\\msyh.ttc"
        )
    }

    data class FontEntry(
        val id: String,
        val name: String,
        val fileName: String,   // 存放在 fonts 目录下的文件名（系统字体为绝对路径）
        val source: String      // "imported" 或 "system"
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
    private var cachedSystemFontFile: File? = null

    init {
        loadIndex()
        autoSelect()
    }

    /** 列出所有已导入管理的字体 */
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
    fun getDisplayName(): String? = currentFontInfo?.name ?: SYSTEM_FONT_NAME
    fun hasFont(): Boolean = currentFontInfo != null || getSystemFontFile() != null
    fun getCurrentFontInfo(): FontInfo? {
        if (currentFontInfo == null) {
            selectSystemDefaultFont()
        }
        return currentFontInfo
    }

    /** 当前所选字体的 Typeface，用于 Compose 预览渲染 */
    fun getCurrentTypeface(): Typeface? {
        val entry = currentEntry
        if (entry == null || entry.source == "system") {
            if (entry != null && entry.fileName.isNotBlank()) {
                val f = File(entry.fileName)
                if (f.exists()) {
                    try { return Typeface.createFromFile(f) } catch (_: Exception) {}
                }
            }
            return Typeface.DEFAULT
        }
        return try {
            Typeface.createFromFile(File(fontsDir, entry.fileName))
        } catch (_: Exception) { Typeface.DEFAULT }
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

    /** 寻找可用的系统预装字体文件 */
    fun getSystemFontFile(): File? {
        if (cachedSystemFontFile != null && cachedSystemFontFile!!.exists()) return cachedSystemFontFile
        // 1. 尝试系统候选路径
        for (path in SYSTEM_CJK_CANDIDATE_PATHS) {
            val f = File(path)
            if (f.exists() && f.canRead() && testLoadFont(f)) {
                cachedSystemFontFile = f
                return f
            }
        }
        // 2. 遍历 /system/fonts
        val systemFontsDir = File("/system/fonts")
        if (systemFontsDir.exists() && systemFontsDir.isDirectory) {
            val cjkFiles = systemFontsDir.listFiles { file ->
                val name = file.name.lowercase()
                (name.endsWith(".ttf") || name.endsWith(".otf")) &&
                    (name.contains("sc") || name.contains("hans") || name.contains("cjk") || name.contains("fallback") || name.contains("chinese"))
            } ?: emptyArray()
            for (f in cjkFiles) {
                if (f.canRead() && testLoadFont(f)) {
                    cachedSystemFontFile = f
                    return f
                }
            }
            // 3. 兜底任意系统 TTF
            val allTtfs = systemFontsDir.listFiles { f ->
                (f.name.endsWith(".ttf", ignoreCase = true) || f.name.endsWith(".otf", ignoreCase = true)) && f.canRead()
            } ?: emptyArray()
            for (f in allTtfs) {
                if (testLoadFont(f)) {
                    cachedSystemFontFile = f
                    return f
                }
            }
        }
        return null
    }

    private fun testLoadFont(file: File): Boolean {
        val testDoc = PDDocument()
        return try {
            PDType0Font.load(testDoc, file) != null
        } catch (_: Throwable) {
            false
        } finally {
            try { testDoc.close() } catch (_: Throwable) {}
        }
    }

    /** 选用系统默认字体 */
    fun selectSystemDefaultFont(): Boolean {
        val file = getSystemFontFile() ?: return false
        return try {
            scratchDoc?.close()
            val doc = PDDocument()
            scratchDoc = doc
            val font = PDType0Font.load(doc, file)
            currentEntry = FontEntry(SYSTEM_FONT_ID, SYSTEM_FONT_NAME, file.absolutePath, "system")
            currentFontInfo = FontInfo(SYSTEM_FONT_NAME, font, boldFont = font, source = "system")
            writeIndex(listFonts(), SYSTEM_FONT_ID)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun selectFont(id: String): Boolean {
        if (id == SYSTEM_FONT_ID) {
            return selectSystemDefaultFont()
        }
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
        if (id == SYSTEM_FONT_ID) return false
        val entry = listFonts().firstOrNull { it.id == id } ?: return false
        try {
            File(fontsDir, entry.fileName).delete()
        } catch (_: Exception) {}
        val remaining = listFonts().filter { it.id != id }
        if (currentEntry?.id == id) {
            currentFontInfo = null
            currentEntry = null
            try { scratchDoc?.close() } catch (_: Exception) {}
            scratchDoc = null
            if (remaining.isNotEmpty()) {
                selectFont(remaining.first().id)
            } else {
                selectSystemDefaultFont()
            }
        }
        writeIndex(remaining, currentEntry?.id)
        return true
    }

    /** 启动时自动选中字体（上次使用的，否则系统默认字体） */
    fun autoSelect(): Boolean {
        val fonts = listFonts()
        val curLastId = lastId
        if (curLastId == SYSTEM_FONT_ID || (fonts.isEmpty() && curLastId.isNullOrBlank())) {
            if (selectSystemDefaultFont()) return true
        }
        if (fonts.isNotEmpty()) {
            val target = fonts.firstOrNull { it.id == curLastId } ?: fonts.first()
            if (selectFont(target.id)) return true
        }
        return selectSystemDefaultFont()
    }

    /** 为输出 PDF 重新加载字体到目标文档 */
    fun reloadFontForDocument(outputDoc: PDDocument): Pair<PDFont, PDFont?>? {
        val entry = currentEntry ?: run {
            if (selectSystemDefaultFont()) currentEntry else null
        } ?: return null

        return try {
            val file = if (entry.source == "system") {
                File(entry.fileName)
            } else {
                File(fontsDir, entry.fileName)
            }
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
