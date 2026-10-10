package com.kindle.converter

import android.app.Application
import com.kindle.converter.web.WebContainer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class KindleConverterApp : Application() {
    lateinit var webContainer: WebContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Initialize PdfBox-Android resource loader
        // Must be called before any PDF operation
        PDFBoxResourceLoader.init(this)
        webContainer = WebContainer(this)
    }
}
