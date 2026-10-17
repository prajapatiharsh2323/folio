package dev.folio.mobile

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * App-wide singletons bootstrap.
 * - PDFBox resources must be initialized once before device PDF import
 *   (asset glyphlists/fonts shipped inside the pdfbox AAR).
 */
class FolioApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { PDFBoxResourceLoader.init(applicationContext) }
            .onFailure { android.util.Log.e("FolioApp", "PDFBox init failed", it) }
    }
}
