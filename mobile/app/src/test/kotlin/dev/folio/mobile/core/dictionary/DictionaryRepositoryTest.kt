package dev.folio.mobile.core.dictionary

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository seam tests (D1): the repository folds outcomes into UI state
 * with no line of network code — local-first by construction; these tests
 * also pin that a miss is returned honest (no fabricated entry, no fake
 * lemma fallback), and that corrupt-decode rows degrade to "no senses".
 */
class DictionaryRepositoryTest {

    private val meta = DictionaryMeta(
        schemaVersion = 1,
        dataset = "FreeDict eng-hin",
        datasetVersion = "1.6",
        sha256 = "abc",
        attribution = "FreeDict eng-hin; data: Anusaaraka",
        licence = "GPL-2.0",
    )

    /** Fake data source that records which keys were asked for. */
    private class FakeSource(
        private val byKey: Map<String, Pair<DictionaryRow?, Boolean>>,
    ) : OfflineDictionaryDataSource {
        val asked = mutableListOf<DictionaryQuery>()

        override suspend fun lookup(query: DictionaryQuery): DictionaryOutcome {
            asked.add(query)
            val hit = byKey[query.displayKey]
                ?: byKey[DictionaryNormalizer.relaxedKey(query)]
                ?: return DictionaryOutcome.NoMatch(query)
            val (row, relaxedOnly) = hit
            return if (row == null) {
                DictionaryOutcome.NoMatch(query)
            } else {
                DictionaryOutcome.Match(
                    query = query,
                    entries = listOf(row.toEntry()),
                    meta = meta,
                    isExactMatch = !relaxedOnly,
                )
            }
        }
    }

    private fun row(hw: String, pos: String?, sensesJson: String) =
        DictionaryRow(headword = hw, pos = pos, sensesJson = sensesJson)

    @Test
    fun `normalization is applied before lookup`() = runBlocking {
        val source = FakeSource(
            mapOf(
                "well" to (row("well", "N", """{"hindi":["कुएँ"]}""") to false),
            ),
        )
        val repo = DictionaryRepository(source)
        val state = repo.lookup("  (Well) ")
        assertTrue(state is DictionaryUiState.Ready)
        assertEquals("well", source.asked.single().displayKey)
    }

    @Test
    fun `exact hit lists senses with example only when the source has one`() = runBlocking {
        val source = FakeSource(
            mapOf(
                "run" to (
                    row(
                        "run",
                        "VI",
                        """{"hindi":["दौड़ना"],"example":"She cannot run fast."}""",
                    ) to false
                    ),
            ),
        )
        val repo = DictionaryRepository(source)
        val state = repo.lookup("run")
        val ready = state as DictionaryUiState.Ready
        assertEquals(1, ready.entries.size)
        assertEquals("run", ready.query.displayKey)
        assertTrue(ready.isExactMatch)
        assertEquals(1, ready.entries.single().senses.size)
        assertEquals("दौड़ना", ready.entries.single().senses.single().hindi.single())
        assertEquals("She cannot run fast.", ready.entries.single().senses.single().example)
    }

    @Test
    fun `multiword phrase goes to the data source as its own key`() = runBlocking {
        val source = FakeSource(emptyMap())
        val state = DictionaryRepository(source).lookup("give up")
        assertTrue(state is DictionaryUiState.NoMatch)
        assertEquals("give up", source.asked.single().displayKey)
    }

    @Test
    fun `missing words return an explicit no-match, never a fabricated entry`() = runBlocking {
        val repo = DictionaryRepository(FakeSource(emptyMap()))
        val state = repo.lookup("gezundheit")
        assertTrue(state is DictionaryUiState.NoMatch)
    }

    @Test
    fun `unrelated words are never mapped to a false lemma`() = runBlocking {
        // This build has no lemma machinery at all; running/ran/rannng are
        // treated as ordinary unknown keys and must miss (no "run" invented).
        val runs = listOf("running", "ran", "walkings")
        val repo = DictionaryRepository(FakeSource(emptyMap()))
        runs.forEach { raw ->
            val state = repo.lookup(raw)
            assertTrue("expected NoMatch for $raw", state is DictionaryUiState.NoMatch)
        }
    }

    @Test
    fun `unusable selection yields Invalid without touching the data source`() = runBlocking {
        val source = FakeSource(emptyMap())
        val repo = DictionaryRepository(source)
        assertTrue(repo.lookup("   ") is DictionaryUiState.Invalid)
        assertTrue(repo.lookup("!!!") is DictionaryUiState.Invalid)
        assertTrue(repo.lookup("") is DictionaryUiState.Invalid)
        assertEquals(0, source.asked.size)
    }

    @Test
    fun `unavailable source surfaces a retryable state`() = runBlocking {
        val source = object : OfflineDictionaryDataSource {
            override suspend fun lookup(query: DictionaryQuery): DictionaryOutcome =
                DictionaryOutcome.Unavailable(
                    "Dictionary data is not available on this install.",
                    retryable = true,
                )
        }
        val state = DictionaryRepository(source).lookup("run")
        val unavailable = state as DictionaryUiState.Unavailable
        assertTrue(unavailable.retryable)
    }

    @Test
    fun `corrupt senses payload degrades to no senses instead of crashing`() {
        val parsed = DictionaryRow(
            headword = "worn",
            pos = "Adj",
            sensesJson = "not-json-at-all",
        ).toEntry()
        assertTrue(parsed.senses.isEmpty())
    }

    @Test
    fun `senses keep the source order and multi-tuple hindi lists`() {
        val json = """[{"hindi":["दौड़","सैर"],"example":null},{"hindi":["माँग"]}]"""
        val entry = DictionaryRow("run", "N", json).toEntry()
        assertEquals(2, entry.senses.size)
        assertEquals(listOf("दौड़", "सैर"), entry.senses[0].hindi)
        assertEquals(listOf("माँग"), entry.senses[1].hindi)
    }
}
