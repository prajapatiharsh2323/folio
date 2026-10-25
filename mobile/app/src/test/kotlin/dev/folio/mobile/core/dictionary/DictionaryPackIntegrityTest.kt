package dev.folio.mobile.core.dictionary

import dev.folio.mobile.reader.DictionaryRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pack integrity (D1): the checked-in asset and the digest the runtime
 * enforces must never drift apart (DictionaryRuntime.PACK_SHA256 is the
 * installer's verification target). JVM tests read the asset straight off
 * the source tree — CI runs the same test in the app module working dir.
 */
class DictionaryPackIntegrityTest {

    private val asset = File("src/main/assets/dictionary/eng-hin.db")
    private val licence = File("src/main/assets/dictionary/eng-hin-COPYING.txt")

    @Test
    fun `checked-in pack matches the published sha`() {
        assertTrue(
            "pack asset missing — run mobile/tools/build_dict.py",
            asset.exists(),
        )
        val sha = DictionaryPackInstaller.sha256(asset.inputStream())
        assertEquals(DictionaryRuntime.PACK_SHA256, sha)
    }

    @Test
    fun `licence text ships with the pack (GPL-2.0 verbatim)`() {
        assertTrue(licence.exists())
        val text = licence.readText()
        assertTrue(text.contains("Version 2, June 1991"))
    }
}
