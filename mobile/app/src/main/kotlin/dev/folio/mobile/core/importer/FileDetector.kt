package dev.folio.mobile.core.importer

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Detects the file format from raw bytes, mirroring packages/core/src/import/detect.ts. */
enum class DetectedFormat(val value: String) {
    Epub("epub"), Pdf("pdf"), Html("html"), Txt("txt"), Unknown("unknown")
}

object FileDetector {

    /**
     * Spec §6: detect by content, never by extension.
     * - ZIP whose mimetype entry is application/epub+zip → EPUB
     * - Bytes starting with %PDF- → PDF
     * - Valid UTF-8 text with HTML markers → HTML
     * - Other valid UTF-8 → TXT; anything else → unknown
     */
    fun detectFileFormat(bytes: ByteArray): DetectedFormat {
        if (bytes.isEmpty()) return DetectedFormat.Unknown

        // PDF signature: %PDF-
        if (bytes.size >= 5 &&
            bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[4] == 0x2d.toByte()
        ) {
            return DetectedFormat.Pdf
        }

        // ZIP header PK\x03\x04 → check mimetype entry
        if (bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        ) {
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var entry = zip.nextEntry
                    var scanned = 0
                    while (entry != null && scanned < 50) {
                        if (entry.name.equals("mimetype", ignoreCase = true)) {
                            val mime = zip.readBytes().toString(Charsets.UTF_8).trim()
                            if (mime == "application/epub+zip") return DetectedFormat.Epub
                        }
                        scanned++
                        entry = zip.nextEntry
                    }
                }
            } catch (_: Exception) {
                // Not a valid zip
            }
        }

        // Text / HTML — strict UTF-8 decode (fatal equivalent)
        val fullText = try {
            strictUtf8Decode(bytes)
        } catch (_: Exception) {
            return DetectedFormat.Unknown
        }

        val cleanHead = fullText.removePrefix("\uFEFF").trimStart()
        val sample = cleanHead.take(65536).lowercase()

        val markers = listOf("<html", "<!doctype", "<body", "<head", "<title", "<article", "<?xml")
        if (markers.any { sample.contains(it) } ||
            Regex(
                "<(!doctype|html|head|body|title|article|main|p|h[1-6]|div)[\\s>]",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(cleanHead.take(8192))
        ) {
            return DetectedFormat.Html
        }

        return DetectedFormat.Txt
    }

    /** Strict UTF-8 decode: throws on invalid sequences (TextDecoder fatal parity). */
    private fun strictUtf8Decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
