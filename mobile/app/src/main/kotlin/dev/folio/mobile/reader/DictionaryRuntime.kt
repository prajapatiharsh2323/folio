package dev.folio.mobile.reader

import android.content.Context
import dev.folio.mobile.core.dictionary.DictionaryPackInstaller
import dev.folio.mobile.core.dictionary.DictionaryPackLocator
import dev.folio.mobile.core.dictionary.DictionaryPackSource
import dev.folio.mobile.core.dictionary.DictionaryRepository
import dev.folio.mobile.core.dictionary.SqliteOfflineDictionaryDataSource
import java.io.File
import java.io.InputStream

/**
 * Production wiring for the dictionary feature (D1). Android-only seams live
 * here; everything below is framework-free and unit-tested.
 *
 * Pack contract: the installer copies `assets/dictionary/eng-hin.db`
 * (FreeDict eng-hin 1.6, GPL-2.0) into filesDir/dictionary on first use,
 * verifying the published sha256 before promotion. The pack is app resource
 * data — it never joins the user's book library, backups, or sync protocol.
 */
object DictionaryRuntime {

    /**
     * sha256 of the shipped `app/src/main/assets/dictionary/eng-hin.db` bytes —
     * the value the installer verifies at first-use copy time. Recorded by
     * `tools/build_dict.py` (also written to `eng-hin.db.sha256` next to the
     * pack); a file cannot carry its own digest, so the pack's `meta`
     * instead stores `content_sha256` over its rows.
     */
    const val PACK_SHA256: String =
        "9c477e11945ed8e183cdd042d7d8391bff1619954270fd53967ab88effc250b1"

    private const val ASSET_PATH = "dictionary/eng-hin.db"

    fun repository(context: Context): DictionaryRepository =
        DictionaryRepository(
            SqliteOfflineDictionaryDataSource(
                installer = DictionaryPackInstaller(
                    source = assetPackSource(context),
                    locator = DictionaryPackLocator { File(context.filesDir, "dictionary") },
                ),
            ),
        )

    private fun assetPackSource(context: Context): DictionaryPackSource =
        object : DictionaryPackSource {
            override fun open(): InputStream? = try {
                context.assets.open(ASSET_PATH)
            } catch (t: Throwable) {
                null
            }

            override fun expectedSha256(): String = PACK_SHA256
        }
}
