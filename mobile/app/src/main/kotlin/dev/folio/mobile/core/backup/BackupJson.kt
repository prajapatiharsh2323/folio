package dev.folio.mobile.core.backup

import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.model.Manifest
import dev.folio.mobile.core.model.Progress
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Shared (lax) JSON codec for backup archives — unknown keys tolerated. */
internal val backupJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "    "
}

internal fun parseBackupMeta(text: String): BackupMeta =
    backupJson.decodeFromString(BackupMeta.serializer(), text)

internal fun parseManifest(text: String): Manifest =
    backupJson.decodeFromString(Manifest.serializer(), text)

internal fun parseAnnotations(text: String): List<Annotation> =
    backupJson.decodeFromString(ListSerializer(Annotation.serializer()), text)

internal fun parseProgressMap(text: String): Map<String, Progress> =
    backupJson.decodeFromString(MapSerializer(String.serializer(), Progress.serializer()), text)

internal fun parseCollections(text: String): List<Collection> =
    backupJson.decodeFromString(ListSerializer(Collection.serializer()), text)
