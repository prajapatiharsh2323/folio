package dev.folio.mobile.core.store

import java.io.File

/**
 * Audit EPUB-003: Settings previously reported the whole app *partition*
 * (StatFs on filesDir) as "Used" — functionally static device info. What
 * "Storage" should show is folio's own payload, i.e. the bytes of every
 * regular file under the store root (schema-1 library + user files + doc
 * settings). Cheap at schema-1 sizes (a few hundred files, few hundred MB max).
 */
fun directoryBytes(dir: File): Long =
    dir.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
