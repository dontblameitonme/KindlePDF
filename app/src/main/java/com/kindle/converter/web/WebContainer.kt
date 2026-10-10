package com.kindle.converter.web

import android.content.Context
import com.kindle.converter.web.extractor.ContentExtractor
import com.kindle.converter.web.fetcher.WebFetcher
import com.kindle.converter.web.script.ScriptRepository
import com.kindle.converter.web.settings.WebSettingsRepository
import com.kindle.converter.web.webview.WebViewRenderer

class WebContainer(context: Context) {
    val settingsRepository = WebSettingsRepository(context.applicationContext)
    val scriptRepository = ScriptRepository(context.applicationContext)
    val webFetcher = WebFetcher()
    val contentExtractor = ContentExtractor()
    val webViewRenderer = WebViewRenderer(context.applicationContext)
    val appContext: Context = context.applicationContext
}
