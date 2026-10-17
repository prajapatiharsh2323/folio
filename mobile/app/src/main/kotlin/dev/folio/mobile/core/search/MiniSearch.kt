package dev.folio.mobile.core.search

import kotlin.math.ln

/**
 * Faithful Kotlin port of MiniSearch 7.2.0's engine internals, restricted to
 * the feature surface the PWA uses (packages/core/src/search/index.ts):
 * add/discard/re-add, field boosts, prefix search, Latin fuzzy (0.2 over terms
 * longer than 4 chars), BM25 scoring (k=1.2, b=0.7, d=0.5, weights
 * fuzzy=0.45/prefix=0.375), AND/OR term combination, quality multiplier
 * (matchedTerms * raw score), stable score-descending output.
 *
 * Divergences (documented, behavior-preserving):
 *  - The radix tree (SearchableMap) is replaced by flat HashMaps — prefix and
 *    fuzzy candidate lookup scan the term keys. Same results; the 500-doc
 *    Section 7 benchmark is milliseconds-scale.
 *  - Vacuum/autoVacuum batching is unnecessary in memory and is skipped;
 *    stale postings are pruned on encounter (mirroring MiniSearch's
 *    termResults cleanup for discarded docs).
 *  - Levenshtein is the same plain per-character algorithm (no transposition),
 *    computed per candidate term instead of via the incremental radix matrix.
 */
class MiniSearchEngine(
    val fields: List<String>,
    /** boost per field, key order = [fields] order; missing → 1.0 */
    val boost: Map<String, Double> = emptyMap(),
    val processTerm: (String) -> String,
    val tokenize: (String) -> List<String>,
    /** ratio (like the PWA's fuzzy: () => 0.2) or null to disable for a term */
    val fuzzyRatio: (String) -> Double? = { null },
    val prefixEnabled: Boolean = true,
    val combineWith: String = COMBINE_AND,
    /** doc-level boost fn: (docId, derivedTerm) -> Double, 1.0 default */
    val boostDocument: ((docId: String, term: String) -> Double)? = null,
    private val k: Double = 1.2,
    private val b: Double = 0.7,
    private val d: Double = 0.5
) {

    companion object {
        const val COMBINE_OR = "or"
        const val COMBINE_AND = "and"
        const val COMBINE_AND_NOT = "and_not"
        private val FUZZY_WEIGHT = 0.45
        private val PREFIX_WEIGHT = 0.375
        private val MAX_FUZZY = 6
    }

    /** term -> fieldId -> (shortId -> termFreq) */
    private val index = HashMap<String, HashMap<Int, HashMap<Int, Int>>>()
    private var documentCount = 0
    private val documentIds = HashMap<Int, String>()
    private val idToShortId = HashMap<String, Int>()
    private val fieldIds = HashMap<String, Int>()
    private val fieldLength = HashMap<Int, DoubleArray>()
    private val avgFieldLength = DoubleArray(fields.size)
    private val storedFields = HashMap<Int, Map<String, Any?>>()
    private var nextId = 0

    init {
        fields.forEachIndexed { i, f -> fieldIds[f] = i }
    }

    fun has(id: String): Boolean = idToShortId.containsKey(id)

    fun getStoredFields(id: String): Map<String, Any?>? =
        idToShortId[id]?.let { storedFields[it] }

    /** @throws IllegalArgumentException on duplicate ID (parity with MiniSearch). */
    fun add(doc: Map<String, Any?>) {
        val id = doc["id"] ?: throw IllegalArgumentException("MiniSearch: document does not have ID field \"id\"")
        val idStr = id.toString()
        require(!idToShortId.containsKey(idStr)) { "MiniSearch: duplicate ID $idStr" }

        val shortId = addDocumentId(idStr)
        saveStoredFields(shortId, doc)
        for (field in fields) {
            val fieldValue = doc[field] ?: continue
            val tokens = tokenize(stringify(fieldValue))
            val fieldId = fieldIds[field]!!
            val uniqueTerms = tokens.toSet().size
            addFieldLength(shortId, fieldId, documentCount - 1, uniqueTerms)
            for (token in tokens) {
                val processed = processTerm(token)
                if (processed.isNotEmpty()) {
                    addTerm(fieldId, shortId, processed)
                }
            }
        }
    }

    fun discard(id: String) {
        val shortId = idToShortId.remove(id) ?: return
        documentIds.remove(shortId)
        storedFields.remove(shortId)
        fieldLength.remove(shortId)?.let { lengths ->
            for (fieldId in lengths.indices) {
                removeFieldLength(shortId, fieldId, documentCount, lengths[fieldId])
            }
        }
        documentCount -= 1
    }

    fun removeAll() {
        index.clear()
        documentCount = 0
        documentIds.clear()
        idToShortId.clear()
        fieldLength.clear()
        java.util.Arrays.fill(avgFieldLength, 0.0)
        storedFields.clear()
        nextId = 0
    }

    data class RawResult(
        val docId: String,
        val score: Double,
        /** unique source (query) terms that matched — drives the quality multiplier */
        val terms: List<String>,
        /** derivedTerm -> matching field names */
        val match: Map<String, List<String>>
    )

    fun search(query: String): List<RawResult> {
        val processedTerms = tokenize(query)
            .flatMap { t -> listOf(processTerm(t)) }
            .filter { it.isNotEmpty() }

        if (processedTerms.isEmpty()) return emptyList()

        val raw = combineResults(
            processedTerms.map { term -> executeQuerySpec(term) },
            combineWith
        )

        val results = raw.map { (shortId, value) ->
            val quality = value.terms.size.takeIf { it > 0 } ?: 1
            RawResult(
                docId = documentIds.getValue(shortId),
                score = value.score * quality,
                terms = value.terms,
                match = value.match
            )
        }
        return results.sortedByDescending { it.score } // stable, like Array#sort
    }

    // --- internals (direct ports) ---

    private fun executeQuerySpec(termString: String): HashMap<Int, Score> {
        val boosts = LinkedHashMap<String, Double>(fields.size)
        for (field in fields) boosts[field] = boost[field] ?: 1.0

        val results = HashMap<Int, Score>()
        termResults(termString, termString, 1.0, 1.0, index[termString], boosts, results)

        // prefix matches: every term starting with the query term (incl. exact, skipped)
        if (prefixEnabled) {
            for ((docTerm, data) in index) {
                if (!docTerm.startsWith(termString)) continue
                val distance = docTerm.length - termString.length
                if (distance == 0) continue // exact match already scored above
                val weight = PREFIX_WEIGHT * docTerm.length / (docTerm.length + 0.3 * distance)
                val entry = HashMap<Int, Score>()
                termResults(termString, docTerm, weight, 1.0, data, boosts, entry)
                combineOrInto(results, entry)
            }
        }

        // fuzzy matches (MiniSearch fuzzy ratio -> bounded edit distance)
        fuzzyRatio(termString)?.let { ratio ->
            val maxDistance = if (ratio < 1) {
                minOf(MAX_FUZZY, Math.round(termString.length * ratio).toInt())
            } else {
                ratio.toInt()
            }
            if (maxDistance > 0) {
                for ((docTerm, data) in index) {
                    val distance = levenshtein(termString, docTerm, maxDistance)
                    if (distance == null || distance == 0) continue // exact match handled above
                    val weight = FUZZY_WEIGHT * docTerm.length / (docTerm.length + distance)
                    val entry = HashMap<Int, Score>()
                    termResults(termString, docTerm, weight, 1.0, data, boosts, entry)
                    combineOrInto(results, entry)
                }
            }
        }
        return results
    }

    private fun termResults(
        sourceTerm: String,
        derivedTerm: String,
        termWeight: Double,
        termBoost: Double,
        fieldTermData: HashMap<Int, HashMap<Int, Int>>?,
        boosts: Map<String, Double>,
        results: MutableMap<Int, Score>
    ) {
        if (fieldTermData == null) return
        for ((fieldName, fieldBoost) in boosts) {
            val fieldId = fieldIds[fieldName]!!
            val fieldTermFreqs = fieldTermData[fieldId] ?: continue
            var matchingFields = fieldTermFreqs.size
            val avgLen = avgFieldLength[fieldId]
            // Java maps can't delete during iteration (JS Maps can) — collect
            // stale postings, prune them right after the scoring loop.
            val stale = ArrayList<Pair<Int, Int>>()
            for ((docShortId, termFreq) in fieldTermFreqs) {
                val docId = documentIds[docShortId]
                if (docId == null) {
                    // stale posting for a discarded doc — skip + prune
                    stale.add(fieldId to docShortId)
                    matchingFields -= 1
                    continue
                }
                val docBoost = boostDocument?.invoke(docId, derivedTerm) ?: 1.0
                if (docBoost == 0.0) continue

                val lengths = fieldLength.getValue(docShortId)
                val rawScore = calcBm25Score(
                    termFreq, matchingFields, documentCount, lengths[fieldId], avgLen
                )
                val weighted = termWeight * termBoost * fieldBoost * docBoost * rawScore
                val existing = results[docShortId]
                if (existing != null) {
                    existing.score += weighted
                    if (sourceTerm !in existing.terms) existing.terms = existing.terms + sourceTerm
                    existing.match.computeIfAbsent(derivedTerm) { ArrayList() }.add(fieldName)
                } else {
                    results[docShortId] = Score(
                        weighted,
                        ArrayList(listOf(sourceTerm)),
                        hashMapOf(derivedTerm to arrayListOf(fieldName))
                    )
                }
            }
            for ((staleFieldId, staleDocShortId) in stale) {
                removeTermStale(staleFieldId, staleDocShortId, derivedTerm, fieldTermData)
            }
        }
    }

    /** Same cleanup MiniSearch performs when encountering discarded postings. */
    private fun removeTermStale(
        fieldId: Int,
        docShortId: Int,
        term: String,
        fieldTermData: HashMap<Int, HashMap<Int, Int>>
    ) {
        val indexData = index[term] ?: return
        val fieldIndex = indexData[fieldId] ?: return
        val tf = fieldIndex[docShortId] ?: return
        if (tf <= 1) {
            if (fieldIndex.size <= 1) {
                indexData.remove(fieldId)
            } else {
                fieldIndex.remove(docShortId)
            }
        } else {
            fieldIndex[docShortId] = tf - 1
        }
        if (indexData.isEmpty()) index.remove(term)
    }

    private class Score(
        var score: Double,
        var terms: List<String>,
        val match: HashMap<String, MutableList<String>>
    )

    private fun combineOrInto(a: MutableMap<Int, Score>, b: Map<Int, Score>) {
        for ((docId, valueB) in b) {
            val existing = a[docId]
            if (existing == null) {
                a[docId] = valueB
            } else {
                existing.score += valueB.score
                for ((term, fieldsMatched) in valueB.match) {
                    existing.match.computeIfAbsent(term) { ArrayList() }.addAll(fieldsMatched)
                }
                existing.terms = existing.terms + valueB.terms.filter { it !in existing.terms }
            }
        }
    }

    private fun combineResults(
        results: List<HashMap<Int, Score>>,
        operator: String
    ): Map<Int, Score> {
        if (results.isEmpty()) return emptyMap()
        return when (operator.lowercase()) {
            COMBINE_OR -> {
                val acc = HashMap<Int, Score>()
                results.forEach { combineOrInto(acc, it) }
                acc
            }
            COMBINE_AND -> {
                var acc = results.first()
                for (next in results.drop(1)) {
                    val combined = LinkedHashMap<Int, Score>()
                    for ((docId, valueB) in next) {
                        val existing = acc[docId] ?: continue
                        for ((term, fieldsMatched) in valueB.match) {
                            existing.match.computeIfAbsent(term) { ArrayList() }.addAll(fieldsMatched)
                        }
                        existing.terms = existing.terms + valueB.terms.filter { it !in existing.terms }
                        combined[docId] = Score(
                            existing.score + valueB.score,
                            existing.terms,
                            existing.match
                        )
                    }
                    acc = combined as HashMap<Int, Score>
                }
                acc
            }
            COMBINE_AND_NOT -> {
                val acc = results.first().toMutableMap()
                for (next in results.drop(1)) next.keys.forEach { acc.remove(it) }
                acc
            }
            else -> throw IllegalArgumentException("Invalid combination operator: $operator")
        }
    }

    private fun calcBm25Score(
        termFreq: Int,
        matchingCount: Int,
        totalCount: Int,
        fieldLength: Double,
        avgFieldLength: Double
    ): Double {
        val invDocFreq = ln(1.0 + (totalCount - matchingCount + 0.5) / (matchingCount + 0.5))
        return invDocFreq * (
            d + termFreq * (k + 1) /
                (termFreq + k * (1 - b + b * fieldLength / avgFieldLength))
            )
    }

    private fun addTerm(fieldId: Int, docShortId: Int, term: String) {
        val indexData = index.getOrPut(term) { HashMap() }
        val fieldIndex = indexData[fieldId]
        if (fieldIndex == null) {
            val fresh = HashMap<Int, Int>()
            fresh[docShortId] = 1
            indexData[fieldId] = fresh
        } else {
            fieldIndex[docShortId] = (fieldIndex[docShortId] ?: 0) + 1
        }
    }

    private fun addDocumentId(documentId: String): Int {
        val shortId = nextId
        idToShortId[documentId] = shortId
        documentIds[shortId] = documentId
        documentCount += 1
        nextId += 1
        return shortId
    }

    private fun addFieldLength(docShortId: Int, fieldId: Int, count: Int, length: Int) {
        val lengths = fieldLength.getOrPut(docShortId) {
            DoubleArray(fields.size) { 0.0 }
        }
        lengths[fieldId] = length.toDouble()
        val average = avgFieldLength[fieldId]
        val totalFieldLength = (average * count) + length
        avgFieldLength[fieldId] = totalFieldLength / (count + 1)
    }

    private fun removeFieldLength(docShortId: Int, fieldId: Int, count: Int, length: Double) {
        // count here is documentCount BEFORE decrement (calls ordered like MiniSearch)
        if (count == 1) {
            avgFieldLength[fieldId] = 0.0
            fieldLength.remove(docShortId)?.let { it[fieldId] = 0.0 }
            return
        }
        val totalFieldLength = (avgFieldLength[fieldId] * count) - length
        avgFieldLength[fieldId] = totalFieldLength / (count - 1)
    }

    private fun saveStoredFields(docShortId: Int, doc: Map<String, Any?>) {
        val stored = HashMap<String, Any?>()
        // normalization: everything referenced by SearchIndex grouping comes from
        // storedFields entries (docId, chapterId, title, chapterTitle, author)
        for (key in doc.keys) {
            val value = doc[key]
            if (value != null) stored[key] = value
        }
        storedFields[docShortId] = stored
    }

    private fun stringify(value: Any?): String = when (value) {
        is List<*> -> value.joinToString(",")
        else -> value.toString()
    }

    /** Bounded Levenshtein; null when the distance provably exceeds maxDistance. */
    private fun levenshtein(a: String, b: String, maxDistance: Int): Int? {
        if (a == b) return 0
        val n = a.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..b.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..n) {
                val cost = if (b[i - 1] == a[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxDistance) return null
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n].takeIf { it <= maxDistance }
    }
}
