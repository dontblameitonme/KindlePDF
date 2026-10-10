package com.kindle.converter.web.script

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kindle.converter.web.webview.UserscriptRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ScriptInfo(
    val name: String = "未安装",
    val version: String = "-",
    val author: String = "",
    val source: String = "",
    val updatedAt: Long = 0L,
) {
    val displayVersion: String get() = if (version == "-") "-" else "v$version"
}

/**
 * 用户脚本仓库：脚本正文存于应用内部文件，元数据存 DataStore。
 * 首装时从 assets 释放内置的《骚扰拦截》脚本。
 */
class ScriptRepository(private val context: Context) {

    companion object {
        const val ASSET_SCRIPT = "scripts/harass-block.user.js"
        const val DEFAULT_UPDATE_URL =
            "https://scriptcat.org/scripts/code/724/%E9%AA%9A%E6%89%B0%E6%8B%A6%E6%88%AA.user.js"
    }

    private val Context.scriptDataStore by preferencesDataStore(name = "web_script")

    private object Keys {
        val SOURCE = stringPreferencesKey("source")
        val UPDATED_AT = longPreferencesKey("updated_at")
    }

    private val scriptFile = File(context.filesDir, "userscript.js")

    private val updateClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val info: Flow<ScriptInfo> = context.scriptDataStore.data.map { p ->
        val text = readCurrentScript()
        val meta = UserscriptRunner.parseMeta(text)
        ScriptInfo(
            name = meta?.name ?: "未安装",
            version = meta?.version ?: "-",
            author = meta?.author ?: "",
            source = p[Keys.SOURCE] ?: "内置",
            updatedAt = p[Keys.UPDATED_AT] ?: 0L,
        )
    }

    /** 读取当前脚本全文（首次调用时从 assets 释放） */
    suspend fun readCurrentScript(): String = withContext(Dispatchers.IO) {
        if (!scriptFile.exists()) {
            scriptFile.parentFile?.mkdirs()
            context.assets.open(ASSET_SCRIPT).use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        scriptFile.readText(Charsets.UTF_8)
    }

    /** 导入新脚本（粘贴/文件），校验合法性后替换 */
    suspend fun updateFromText(text: String, source: String): Result<ScriptInfo> =
        withContext(Dispatchers.IO) {
            val meta = UserscriptRunner.parseMeta(text)
            if (meta == null || meta.matches.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("不是有效的用户脚本（缺少 ==UserScript== 元数据块或 @match 规则）")
                )
            }
            scriptFile.writeText(text, Charsets.UTF_8)
            saveMeta(source)
            Result.success(currentInfoSync())
        }

    /** 从源地址拉取最新版本并覆盖 */
    suspend fun updateFromUrl(url: String = DEFAULT_UPDATE_URL): Result<ScriptInfo> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val body = updateClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception("下载失败：HTTP ${resp.code}"))
                    }
                    resp.body?.string() ?: ""
                }
                updateFromText(body, "在线更新")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 恢复为内置版本 */
    suspend fun resetToAsset(): Result<ScriptInfo> = withContext(Dispatchers.IO) {
        try {
            val text = context.assets.open(ASSET_SCRIPT).bufferedReader(Charsets.UTF_8).use { it.readText() }
            scriptFile.writeText(text, Charsets.UTF_8)
            saveMeta("内置")
            Result.success(currentInfoSync())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun saveMeta(source: String) {
        context.scriptDataStore.edit { p ->
            p[Keys.SOURCE] = source
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    private fun currentInfoSync(): ScriptInfo {
        val text = scriptFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: ""
        val meta = UserscriptRunner.parseMeta(text)
        return ScriptInfo(
            name = meta?.name ?: "未安装",
            version = meta?.version ?: "-",
            author = meta?.author ?: "",
            source = "已更新",
            updatedAt = System.currentTimeMillis(),
        )
    }
}
