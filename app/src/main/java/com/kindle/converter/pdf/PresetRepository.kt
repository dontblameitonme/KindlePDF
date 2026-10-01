package com.kindle.converter.pdf

import android.content.Context
import com.kindle.converter.data.ImageWrapMode
import com.kindle.converter.data.TextAlignment
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.data.VerticalAlignment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 参数预设仓库：把整组排版设置（页面尺寸/边距/字号/行距等）以命名预设形式
 * 持久化到 App 私有目录，支持保存、加载、删除与切换。
 */
class PresetRepository(private val context: Context) {

    data class ParamPreset(val name: String, val params: TypesettingParams)

    private val file = File(context.filesDir, "presets.json")

    fun list(): List<ParamPreset> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { obj ->
                    ParamPreset(obj.optString("name"), paramsFromJson(obj.optJSONObject("params")))
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun save(name: String, params: TypesettingParams) {
        val all = list().filter { it.name != name }.toMutableList()
        all.add(ParamPreset(name, params))
        write(all)
    }

    fun delete(name: String) {
        write(list().filter { it.name != name })
    }

    fun get(name: String): TypesettingParams? = list().firstOrNull { it.name == name }?.params

    // ---- 最近一次使用的参数（默认参数永久化）----
    private val lastParamsFile = File(context.filesDir, "last_params.json")

    /** 持久化"上次上传/转换使用的参数"，下次启动自动作为默认 */
    fun saveLastParams(params: TypesettingParams) {
        try { lastParamsFile.writeText(paramsToJson(params).toString(2)) } catch (_: Exception) {}
    }

    /** 读取上次使用的参数；文件不存在或解析失败返回 null */
    fun loadLastParams(): TypesettingParams? {
        if (!lastParamsFile.exists()) return null
        return try { paramsFromJson(JSONObject(lastParamsFile.readText())) } catch (_: Exception) { null }
    }

    // ---- 上次选中的命名参数预设（参数预设区默认回选）----
    private val lastPresetNameFile = File(context.filesDir, "last_preset_name.json")

    /** 持久化“上次选中的参数预设名称”，下次启动自动在参数预设区回选 */
    fun saveLastPresetName(name: String?) {
        try {
            if (name == null) {
                if (lastPresetNameFile.exists()) lastPresetNameFile.delete()
            } else {
                lastPresetNameFile.writeText(JSONObject().put("name", name).toString(2))
            }
        } catch (_: Exception) {}
    }

    /** 读取上次选中的参数预设名称；文件不存在或解析失败返回 null */
    fun loadLastPresetName(): String? {
        if (!lastPresetNameFile.exists()) return null
        return try {
            val n = JSONObject(lastPresetNameFile.readText()).optString("name", "")
            if (n.isBlank()) null else n
        } catch (_: Exception) { null }
    }

    // ---- 各区域的折叠/展开状态（首页排版设置可点标题收起）----
    private val sectionExpandedFile = File(context.filesDir, "section_expanded.json")

    /** 读取所有区域的展开状态；缺失的 key 表示默认展开 */
    fun loadSectionExpanded(): Map<String, Boolean> {
        if (!sectionExpandedFile.exists()) return emptyMap()
        return try {
            val obj = JSONObject(sectionExpandedFile.readText())
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, obj.optBoolean(k, true))
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    /** 保存所有区域的展开状态 */
    fun saveSectionExpanded(states: Map<String, Boolean>) {
        try {
            val obj = JSONObject()
            states.forEach { (k, v) -> obj.put(k, v) }
            sectionExpandedFile.writeText(obj.toString(2))
        } catch (_: Exception) {}
    }

    private fun write(presets: List<ParamPreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("params", paramsToJson(p.params))
            })
        }
        try { file.writeText(arr.toString(2)) } catch (_: Exception) {}
    }

    private fun paramsToJson(p: TypesettingParams): JSONObject = JSONObject().apply {
        put("pageWidth", p.pageWidth)
        put("pageHeight", p.pageHeight)
        put("marginTop", p.marginTop)
        put("marginBottom", p.marginBottom)
        put("marginLeft", p.marginLeft)
        put("marginRight", p.marginRight)
        put("fontSize", p.fontSize)
        put("lineHeight", p.lineHeight)
        put("paragraphSpacing", p.paragraphSpacing)
        put("firstLineIndent", p.firstLineIndent)
        put("textAlignment", p.textAlignment.name)
        put("forceAlign", p.forceAlign)
        put("verticalAlignment", p.verticalAlignment.name)
        put("imageWrapMode", p.imageWrapMode.name)
        put("stripHeadersFooters", p.stripHeadersFooters)
        put("boldHeadings", p.boldHeadings)
        put("headingScale", p.headingScale)
        put("enableOrphanControl", p.enableOrphanControl)
        put("enableWidowControl", p.enableWidowControl)
    }

    private fun paramsFromJson(o: JSONObject?): TypesettingParams {
        if (o == null) return TypesettingParams()
        val taName = o.optString("textAlignment", TypesettingParams().textAlignment.name)
        val ta = try { TextAlignment.valueOf(taName) } catch (_: Exception) { TypesettingParams().textAlignment }
        val vaName = o.optString("verticalAlignment", TypesettingParams().verticalAlignment.name)
        val va = try { VerticalAlignment.valueOf(vaName) } catch (_: Exception) { TypesettingParams().verticalAlignment }
        val iwName = o.optString("imageWrapMode", TypesettingParams().imageWrapMode.name)
        val iw = try { ImageWrapMode.valueOf(iwName) } catch (_: Exception) { TypesettingParams().imageWrapMode }
        return TypesettingParams(
            pageWidth = o.optDouble("pageWidth", 258.0).toFloat(),
            pageHeight = o.optDouble("pageHeight", 346.0).toFloat(),
            marginTop = o.optDouble("marginTop", 18.0).toFloat(),
            marginBottom = o.optDouble("marginBottom", 18.0).toFloat(),
            marginLeft = o.optDouble("marginLeft", 18.0).toFloat(),
            marginRight = o.optDouble("marginRight", 18.0).toFloat(),
            fontSize = o.optDouble("fontSize", 12.0).toFloat(),
            lineHeight = o.optDouble("lineHeight", 1.5).toFloat(),
            paragraphSpacing = o.optDouble("paragraphSpacing", 3.6).toFloat(),
            firstLineIndent = o.optDouble("firstLineIndent", 16.0).toFloat(),
            textAlignment = ta,
            forceAlign = o.optBoolean("forceAlign", true),
            verticalAlignment = va,
            imageWrapMode = iw,
            stripHeadersFooters = o.optBoolean("stripHeadersFooters", true),
            boldHeadings = o.optBoolean("boldHeadings", true),
            headingScale = o.optDouble("headingScale", 1.4).toFloat(),
            enableOrphanControl = o.optBoolean("enableOrphanControl", true),
            enableWidowControl = o.optBoolean("enableWidowControl", true)
        )
    }
}
