package dev.folio.mobile.reader

import android.content.Context
import dev.folio.mobile.core.dictionary.DictionaryPackInstaller
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

    /** sha256 of the checked-in `assets/dictionary/eng-hin.db` (build_dict.py output). */
    const val PACK_SHA256: String =
        "f94e84cebede613704c2e6e37a1b0a66292263461ead1be06069f4bc5ab113e8"

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
