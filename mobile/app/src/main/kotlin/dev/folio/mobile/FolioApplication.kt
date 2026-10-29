package dev.folio.mobile

import android.app.Application

/**
 * App-wide singletons bootstrap. Intentionally empty at v2: PDF import was
 * removed with the pdfbox dependency (S1 Phase 4, user decision 2), and no
 * other process-wide context is needed right now.
 */
class FolioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
