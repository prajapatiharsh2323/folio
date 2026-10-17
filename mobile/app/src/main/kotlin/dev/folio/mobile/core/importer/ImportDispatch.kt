package dev.folio.mobile.core.importer

/**
 * Single dispatch point for "import these bytes" (v1 library phase): detects
 * the format by CONTENT (never extension — FileDetector spec §6) and runs the
 * matching importer. Synchronous, primitive-arg — the UI layer is responsible
 * for reading the source stream and calling [LibraryManager.addDocument] on IO.
 */
object ImportDispatch {

    fun import(bytes: ByteArray, fileName: String): ImportResult = when (
        val format = FileDetector.detectFileFormat(bytes)
    ) {
        DetectedFormat.Epub -> EpubImporter.import(bytes, fileName)
        // PDF rendering is out of scope for v1 (EPUB-focused); treat as unsupported
        // so the file is not silently imported into an unreadable library entry.
        DetectedFormat.Pdf -> throw UnsupportedFormatException(fileName)
        DetectedFormat.Html -> HtmlImporter.import(bytes, fileName)
        DetectedFormat.Txt -> TxtImporter.import(bytes, fileName)
        DetectedFormat.Unknown -> throw UnsupportedFormatException(fileName)
    }
}

class UnsupportedFormatException(val fileName: String) :
    RuntimeException("Unsupported file format: $fileName")
