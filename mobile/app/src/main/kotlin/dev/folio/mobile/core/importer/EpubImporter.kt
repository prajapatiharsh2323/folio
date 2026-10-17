package dev.folio.mobile.core.importer

import dev.folio.mobile.core.model.Chapter
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.util.sha256Hex
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Thrown when an EPUB is DRM-protected (spec §6/§10: refuse, never circumvent). */
class DrmProtectedError(message: String = "This EPUB is DRM-protected and cannot be imported.") :
    Exception(message)

data class EpubImport(val result: ImportResult)

object EpubImporter {

    /** Reads all zip entries into a name→bytes map (validates zips implicitly). */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        map[entry.name] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid EPUB: not a readable zip archive", e)
        }
        return map
    }

    /** Parses container.xml to locate the OPF package file (spec §6). */
    private fun findOpfPath(zip: Map<String, ByteArray>): String {
        val containerBytes = zip["META-INF/container.xml"]
            ?: throw IllegalArgumentException("Invalid EPUB: META-INF/container.xml is missing")
        val text = containerBytes.toString(Charsets.UTF_8)
        val match = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(text)
            ?: throw IllegalArgumentException("Invalid EPUB: could not locate package full-path in container.xml")
        return match.groupValues[1]
    }

    /** Strips tags and decodes common + numeric entities to plain text. */
    internal fun extractPlainText(xml: String): String {
        val sb = StringBuilder(xml.length)
        var i = 0
        var inTag = false
        while (i < xml.length) {
            val c = xml[i]
            if (inTag) {
                if (c == '>') inTag = false
                i++
                continue
            }
            if (c == '<') {
                inTag = true
                sb.append(' ') // TS parity: each tag becomes one space
                i++
                continue
            }
            if (c == '&') {
                val semi = xml.indexOf(';', i)
                if (semi in (i + 2)..(i + 12)) {
                    val entity = xml.substring(i + 1, semi)
                    val decoded = decodeEntity(entity)
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
                sb.append('&')
                i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeEntity(entity: String): String? = when (entity.lowercase()) {
        "nbsp" -> " "
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> {
            if (entity.startsWith("#")) {
                val code = if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')) {
                    entity.substring(2).toIntOrNull(16)
                } else {
                    entity.substring(1).toIntOrNull(10)
                }
                code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
            } else null
        }
    }

    /** Resolves a relative path against an OPF directory handling ".." and ".". */
    internal fun resolvePath(baseDir: String, relativePath: String): String {
        val combined = if (baseDir.isNotEmpty()) baseDir + relativePath else relativePath
        val resolved = mutableListOf<String>()
        for (p in combined.split("/")) {
            when (p) {
                ".", "" -> {}
                ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                else -> resolved.add(p)
            }
        }
        return resolved.joinToString("/")
    }

    /** Image extensions accepted for covers (kept as the stored file extension). */
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp")

    /** Reads a cover candidate from the zip; null when missing/empty. */
    private fun loadCover(
        zip: Map<String, ByteArray>,
        opfDir: String,
        href: String
    ): ImportCover? {
        val fullHref = resolvePath(opfDir, href)
        val bytes = zip[fullHref]
            ?: zip["$opfDir$href"]
            ?: zip[href]
            ?: zip[runCatching { java.net.URLDecoder.decode(fullHref, "UTF-8") }.getOrDefault(fullHref)]
            ?: zip.entries.firstOrNull { it.key.lowercase() == fullHref.lowercase() }?.value
            ?: return null
        if (bytes.isEmpty()) return null
        val ext = fullHref.substringAfterLast('.', "").lowercase()
            .takeIf { it in IMAGE_EXTS } ?: "jpg"
        return ImportCover("cover.$ext", bytes)
    }

    /** Imports an EPUB archive conforming to spec §6. */
    fun import(bytes: ByteArray, originalName: String = "book.epub"): ImportResult {
        val zip = unzip(bytes)

        // DRM check (spec §6 & §10): rights.xml present, or encryption.xml with
        // anything other than standard font obfuscation → refuse.
        if (zip.containsKey("META-INF/rights.xml")) throw DrmProtectedError()
        zip["META-INF/encryption.xml"]?.let { enc ->
            val encText = enc.toString(Charsets.UTF_8)
            val allowedFontAlgos = setOf(
                "http://www.idpf.org/2008/embedding",
                "http://ns.adobe.com/pdf/enc#RC"
            )
            val algoMatches = Regex(
                "<(?:\\w+:)?EncryptionMethod\\s+[^>]*Algorithm=[\"']([^\"']+)[\"']",
                RegexOption.IGNORE_CASE
            ).findAll(encText)
            if (algoMatches.any()) {
                for (m in algoMatches) {
                    if (m.groupValues[1] !in allowedFontAlgos) throw DrmProtectedError()
                }
            } else if (encText.contains("CipherData")) {
                throw DrmProtectedError()
            }
        }

        // Find and read the OPF (exact, then decoded, then case-insensitive)
        val opfPath = findOpfPath(zip)
        val opfBytes = zip[opfPath]
            ?: zip[runCatching { java.net.URLDecoder.decode(opfPath, "UTF-8") }.getOrDefault(opfPath)]
            ?: zip.entries.firstOrNull { it.key.lowercase() == opfPath.lowercase() }?.value
            ?: throw IllegalArgumentException("Invalid EPUB: OPF package file not found at $opfPath")
        val opfText = opfBytes.toString(Charsets.UTF_8)

        val title = Regex(
            "<(?:\\w+:)?title\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?title>", RegexOption.IGNORE_CASE
        ).find(opfText)?.let { extractPlainText(it.groupValues[1]) }?.takeIf { it.isNotBlank() }
            ?: "Untitled Book"

        val author = Regex(
            "<(?:\\w+:)?creator\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?creator>", RegexOption.IGNORE_CASE
        ).find(opfText)?.let { extractPlainText(it.groupValues[1]) }?.takeIf { it.isNotBlank() }

        val language = Regex(
            "<(?:\\w+:)?language\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?language>", RegexOption.IGNORE_CASE
        ).find(opfText)?.let { extractPlainText(it.groupValues[1]) }?.takeIf { it.isNotBlank() }
            ?: "en"

        val opfDir = if (opfPath.contains("/")) opfPath.substring(0, opfPath.lastIndexOf("/") + 1) else ""

        // Manifest items: id → decoded href, plus cover candidates captured in
        // the same pass (properties="cover-image" wins over the heuristic).
        val manifestMap = LinkedHashMap<String, String>()
        var coverByProps: String? = null
        var coverByHeuristic: String? = null
        Regex("<item\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(opfText).forEach { m ->
            val attrs = m.groupValues[1]
            fun attr(name: String): String? =
                Regex("\\b$name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)
            val id = attr("id")
            val href = attr("href")
            if (id != null && href != null) {
                val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }
                    .getOrDefault(href)
                manifestMap[id] = decoded
                val mediaType = attr("media-type")?.lowercase()
                val isImage = mediaType?.startsWith("image/") == true ||
                    decoded.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
                if (isImage) {
                    val props = attr("properties")
                    if (props != null &&
                        props.split(Regex("\\s+")).any { it.equals("cover-image", true) }
                    ) {
                        if (coverByProps == null) coverByProps = decoded
                    } else if (coverByHeuristic == null &&
                        (id.contains("cover", true) || decoded.contains("cover", true))
                    ) {
                        coverByHeuristic = decoded
                    }
                }
            }
        }

        // EPUB2 fallback: <meta name="cover" content="id"/>
        val metaCoverHref = Regex(
            "<meta\\s+[^>]*\\bname\\s*=\\s*[\"']cover[\"'][^>]*>", RegexOption.IGNORE_CASE
        ).find(opfText)
            ?.let { tag ->
                Regex("\\bcontent\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag.value)?.groupValues?.get(1)
            }
            ?.let { manifestMap[it] }

        val cover = sequenceOf(coverByProps, metaCoverHref, coverByHeuristic)
            .filterNotNull()
            .distinct()
            .firstNotNullOfOrNull { loadCover(zip, opfDir, it) }

        // Spine reading order
        val spineItems = Regex(
            "<itemref\\s+[^>]*idref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/?>", RegexOption.IGNORE_CASE
        ).findAll(opfText).map { it.groupValues[1] }.toList()

        val chapters = mutableListOf<Chapter>()
        val searchTextList = mutableListOf<SearchChapterText>()
        var totalWordCount = 0

        for (i in spineItems.indices) {
            val itemId = spineItems[i]
            val href = manifestMap[itemId] ?: continue
            val fullHref = resolvePath(opfDir, href)
            val chapterBytes = zip[fullHref]
                ?: zip["$opfDir$href"]
                ?: zip[href]
                ?: zip[runCatching { java.net.URLDecoder.decode(fullHref, "UTF-8") }.getOrDefault(fullHref)]
                ?: zip.entries.firstOrNull { it.key.lowercase() == fullHref.lowercase() }?.value

            var chTitle = "Chapter ${i + 1}"
            var plainText = ""
            if (chapterBytes != null) {
                val chHtml = chapterBytes.toString(Charsets.UTF_8)
                val titleTag = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(chHtml)
                val hTag = Regex("<h[1-3][^>]*>([^<]+)</h[1-3]>", RegexOption.IGNORE_CASE).find(chHtml)
                chTitle = titleTag?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: hTag?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: chTitle
                plainText = extractPlainText(chHtml)
                totalWordCount += plainText.split(Regex("\\s+")).count { it.isNotBlank() }
            }

            val chapterId = "ch-${i + 1}"
            chapters += Chapter(chapterId, chTitle, order = i + 1, href = fullHref)
            searchTextList += SearchChapterText(chapterId, chTitle, plainText)
        }

        return ImportResult(
            meta = DocMeta(
                id = "pending",
                slug = "pending",
                title = title,
                author = author,
                kind = DocKind.Book,
                language = language,
                sourceFormat = SourceFormat.Epub,
                capturedAt = java.time.Instant.now().toString(),
                importedAt = "pending",
                contentHash = sha256Hex(bytes),
                wordCount = totalWordCount,
                searchable = true,
                tags = emptyList(),
                collectionIds = emptyList(),
                chapters = chapters
            ),
            original = ImportOriginal(originalName, bytes),
            reading = ImportReading("book.epub", bytes),
            cover = cover,
            searchText = searchTextList
        )
    }
}
