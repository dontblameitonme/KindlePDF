package com.kindle.converter.web.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class UaMode { DESKTOP, MOBILE }

data class WebSettings(
    val embedImages: Boolean = true,
    val defaultUa: UaMode = UaMode.DESKTOP,
    val concurrency: Int = 3,
    val customUserAgent: String = "",
)

private val Context.webSettingsDataStore by preferencesDataStore(name = "web_settings")

class WebSettingsRepository(private val context: Context) {

    private object Keys {
        val EMBED_IMAGES = booleanPreferencesKey("embed_images")
        val DEFAULT_UA = stringPreferencesKey("default_ua")
        val CONCURRENCY = intPreferencesKey("concurrency")
        val CUSTOM_UA = stringPreferencesKey("custom_ua")
    }

    val settings: Flow<WebSettings> = context.webSettingsDataStore.data.map { p ->
        WebSettings(
            embedImages = p[Keys.EMBED_IMAGES] ?: true,
            defaultUa = runCatching { UaMode.valueOf(p[Keys.DEFAULT_UA] ?: "") }
                .getOrDefault(UaMode.DESKTOP),
            concurrency = (p[Keys.CONCURRENCY] ?: 3).coerceIn(1, 6),
            customUserAgent = p[Keys.CUSTOM_UA] ?: "",
        )
    }

    suspend fun setEmbedImages(value: Boolean) = edit(Keys.EMBED_IMAGES, value)
    suspend fun setDefaultUa(value: UaMode) = edit(Keys.DEFAULT_UA, value.name)
    suspend fun setConcurrency(value: Int) = edit(Keys.CONCURRENCY, value.coerceIn(1, 6))
    suspend fun setCustomUserAgent(value: String) = edit(Keys.CUSTOM_UA, value)

    private suspend fun <T> edit(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.webSettingsDataStore.edit { it[key] = value }
    }
}
