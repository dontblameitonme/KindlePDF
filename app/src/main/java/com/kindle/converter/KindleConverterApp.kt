package com.kindle.converter

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class KindleConverterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize PdfBox-Android resource loader
        // Must be called before any PDF operation
        PDFBoxResourceLoader.init(this)
    }
}
