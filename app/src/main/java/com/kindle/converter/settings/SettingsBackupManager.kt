package com.kindle.converter.settings

import android.content.Context
import com.kindle.converter.data.TypesettingParams
import com.kindle.converter.pdf.PresetRepository
import com.kindle.converter.web.WebContainer
import com.kindle.converter.web.settings.UaMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * 全局设置与预设备份管理器：
 * 负责导出与导入排版参数预设、界面状态与网页剪藏规则配置。
 */
class SettingsBackupManager(
    private val context: Context,
    private val presetRepo: PresetRepository,
    private val webContainer: WebContainer
) {
    /** 导出所有设置与预设为 JSON */
    suspend fun exportSettings(out: OutputStream) = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("appName", "KindlePDF")
        root.put("exportedAt", System.currentTimeMillis())

        // 1. 排版设置与命名预设
        val typesetObj = JSONObject()
        val presets = presetRepo.list()
        val presetsArr = JSONArray()
        for (p in presets) {
            val pObj = JSONObject()
            pObj.put("name", p.name)
            pObj.put("params", presetRepo.paramsToJson(p.params))
            presetsArr.put(pObj)
        }
        typesetObj.put("presets", presetsArr)

        val lastParams = presetRepo.loadLastParams()
        if (lastParams != null) {
            typesetObj.put("lastParams", presetRepo.paramsToJson(lastParams))
        }
        val lastPresetName = presetRepo.loadLastPresetName()
        if (lastPresetName != null) {
            typesetObj.put("lastPresetName", lastPresetName)
        }
        val sectionExpanded = presetRepo.loadSectionExpanded()
        if (sectionExpanded.isNotEmpty()) {
            val secObj = JSONObject()
            sectionExpanded.forEach { (k, v) -> secObj.put(k, v) }
            typesetObj.put("sectionExpanded", secObj)
        }
        root.put("typesetting", typesetObj)

        // 2. 网页抓取设置
        val webSettings = webContainer.settingsRepository.settings.first()
        val webObj = JSONObject()
        webObj.put("embedImages", webSettings.embedImages)
        webObj.put("defaultUa", webSettings.defaultUa.name)
        webObj.put("concurrency", webSettings.concurrency)
        webObj.put("customUserAgent", webSettings.customUserAgent)
        webObj.put("alwaysUseWebView", webSettings.alwaysUseWebView)
        webObj.put("directWebViewDomains", webSettings.directWebViewDomains)
        webObj.put("webViewDarkMode", webSettings.webViewDarkMode)
        root.put("web", webObj)

        // 3. 用户脚本
        val scriptText = runCatching { webContainer.scriptRepository.readCurrentScript() }.getOrNull()
        if (!scriptText.isNullOrBlank()) {
            val scriptObj = JSONObject()
            scriptObj.put("content", scriptText)
            root.put("script", scriptObj)
        }

        out.write(root.toString(2).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 导入 JSON 设置并恢复 */
    suspend fun importSettings(input: InputStream): Result<String> = withContext(Dispatchers.IO) {
        try {
            val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)

            var restoredPresets = 0
            var restoredWeb = false
            var restoredScript = false

            // 1. 恢复排版设置与预设
            val typesetObj = root.optJSONObject("typesetting")
            if (typesetObj != null) {
                val presetsArr = typesetObj.optJSONArray("presets")
                if (presetsArr != null) {
                    for (i in 0 until presetsArr.length()) {
                        val pObj = presetsArr.optJSONObject(i) ?: continue
                        val name = pObj.optString("name")
                        val paramsObj = pObj.optJSONObject("params")
                        if (name.isNotBlank() && paramsObj != null) {
                            val params = presetRepo.paramsFromJson(paramsObj)
                            presetRepo.save(name, params)
                            restoredPresets++
                        }
                    }
                }
                val lastParamsObj = typesetObj.optJSONObject("lastParams")
                if (lastParamsObj != null) {
                    val p = presetRepo.paramsFromJson(lastParamsObj)
                    presetRepo.saveLastParams(p)
                }
                val lastPresetName = typesetObj.optString("lastPresetName", "")
                if (lastPresetName.isNotBlank()) {
                    presetRepo.saveLastPresetName(lastPresetName)
                }
                val secObj = typesetObj.optJSONObject("sectionExpanded")
                if (secObj != null) {
                    val map = mutableMapOf<String, Boolean>()
                    val keys = secObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = secObj.optBoolean(k, true)
                    }
                    presetRepo.saveSectionExpanded(map)
                }
            }

            // 2. 恢复网页抓取设置
            val webObj = root.optJSONObject("web")
            if (webObj != null) {
                if (webObj.has("embedImages")) {
                    webContainer.settingsRepository.setEmbedImages(webObj.optBoolean("embedImages", true))
                }
                if (webObj.has("defaultUa")) {
                    val uaStr = webObj.optString("defaultUa", "DESKTOP")
                    val ua = runCatching { UaMode.valueOf(uaStr) }.getOrDefault(UaMode.DESKTOP)
                    webContainer.settingsRepository.setDefaultUa(ua)
                }
                if (webObj.has("concurrency")) {
                    webContainer.settingsRepository.setConcurrency(webObj.optInt("concurrency", 3))
                }
                if (webObj.has("customUserAgent")) {
                    webContainer.settingsRepository.setCustomUserAgent(webObj.optString("customUserAgent", ""))
                }
                if (webObj.has("alwaysUseWebView")) {
                    webContainer.settingsRepository.setAlwaysUseWebView(webObj.optBoolean("alwaysUseWebView", false))
                }
                if (webObj.has("directWebViewDomains")) {
                    webContainer.settingsRepository.setDirectWebViewDomains(webObj.optString("directWebViewDomains", "zhihu.com, zhuanlan.zhihu.com, juejin.cn"))
                }
                if (webObj.has("webViewDarkMode")) {
                    webContainer.settingsRepository.setWebViewDarkMode(webObj.optBoolean("webViewDarkMode", false))
                }
                restoredWeb = true
            }

            // 3. 恢复用户脚本
            val scriptObj = root.optJSONObject("script")
            val scriptContent = scriptObj?.optString("content")
            if (!scriptContent.isNullOrBlank()) {
                webContainer.scriptRepository.updateFromText(scriptContent, "导入备份")
                restoredScript = true
            }

            val summary = buildList {
                if (restoredPresets > 0) add("排版预设 $restoredPresets 组")
                if (restoredWeb) add("网页抓取设置")
                if (restoredScript) add("用户脚本")
            }.joinToString("、")

            Result.success(if (summary.isNotBlank()) "成功恢复：$summary" else "设置导入成功")
        } catch (e: Exception) {
            Result.failure(Exception("解析设置文件失败：${e.message}"))
        }
    }
}
