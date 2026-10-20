package dev.folio.mobile.core.sync

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.File

/**
 * Google Drive implementation of the [SyncProvider] mirror protocol.
 *
 * The library is mirrored file by file into a normal Drive folder called
 * "Folio Library", so the books are visible in the Drive app and browser and
 * can be opened from anywhere — not hidden in an app-private corner.
 *
 * Scope: `drive.file` — per-file access to the files this app creates (or the
 * user explicitly opens for it). The app can never touch the user's other
 * Drive content. Access is scoped to this OAuth client; a different Folio
 * client (for example the PWA) cannot see these files without an explicit
 * user-mediated grant such as Google Picker.
 *
 * Uploads follow Drive's two paths: a plain multipart upload for small files
 * and a resumable, chunked upload for anything above 5 MB (a single EPUB
 * regularly exceeds that).
 */
class DriveSyncProvider(
    private val drive: Drive,
    private val folderName: String = DEFAULT_FOLDER_NAME,
    /** Scratch directory for staging large uploads on disk. */
    private val cacheDir: File? = null,
) : SyncProvider {

    companion object {
        /** The mirror folder, same name the PWA uses. */
        const val DEFAULT_FOLDER_NAME = "Folio Library"

        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val OCTET_STREAM = "application/octet-stream"

        /** Above this size Drive needs a resumable upload. */
        private const val RESUMABLE_THRESHOLD = 5L * 1024L * 1024L

        /** 5 MiB — a multiple of the 256 KiB minimum chunk size. */
        private const val CHUNK_SIZE = 5 * 1024 * 1024

        /** Builds a provider bound to a Google account credential. */
        fun create(
            credential: GoogleAccountCredential,
            cacheDir: File? = null,
            folderName: String = DEFAULT_FOLDER_NAME,
        ): DriveSyncProvider {
            val drive = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential,
            ).setApplicationName("Folio").build()
            return DriveSyncProvider(drive, folderName, cacheDir)
        }
    }

    private var rootFolderId: String? = null
    private val fileCache = HashMap<String, SyncFileEntry>()

    /** The id of the mirror folder, created on first use. */
    suspend fun rootFolderId(): String {
        rootFolderId?.let { return it }
        val existing = drive.files().list()
            .setQ("name = '$folderName' and mimeType = '$FOLDER_MIME' and trashed = false")
            .setFields("files(id,name)")
            .execute()
            .files
            ?.firstOrNull()
        val id = existing?.id ?: run {
            val created = drive.files().create(
                DriveFile().apply {
                    name = folderName
                    mimeType = FOLDER_MIME
                }
            ).execute()
            created.id
        }
        rootFolderId = id
        return id
    }

    override suspend fun list(): List<SyncFileEntry> {
        val root = rootFolderId()
        fileCache.clear()
        val entries = mutableListOf<SyncFileEntry>()
        var pageToken: String? = null
        do {
            val request = drive.files().list()
                .setQ("'$root' in parents and trashed = false and mimeType != '$FOLDER_MIME'")
                .setFields("nextPageToken,files(id,name,size,modifiedTime,appProperties)")
                .setPageSize(1000)
            pageToken?.let { request.setPageToken(it) }
            val response = request.execute()
            response.files?.forEach { file ->
                val path = file.getAppProperties()?.get("relPath")
                    ?: file.name
                    ?: return@forEach
                val entry = SyncFileEntry(
                    path = path,
                    size = file.getSize() ?: 0L,
                    sha256 = file.getAppProperties()?.get("sha256") ?: "",
                    modifiedAt = file.getModifiedTime()?.toString() ?: nowIso(),
                    id = file.id,
                )
                fileCache[path] = entry
                entries.add(entry)
            }
            pageToken = response.nextPageToken
        } while (!pageToken.isNullOrBlank())
        return entries
    }

    override suspend fun download(path: String): ByteArray {
        val entry = fileCache[path] ?: list().firstOrNull { it.path == path }
            ?: throw IllegalStateException("File not found in Drive: $path")
        val id = entry.id ?: throw IllegalStateException("File has no Drive id: $path")
        return drive.files().get(id).executeMediaAsInputStream().use { it.readBytes() }
    }

    override suspend fun upload(path: String, data: ByteArray, sha256: String): String =
        uploadFile(path, null, sha256, data)

    override suspend fun uploadFile(path: String, file: File, sha256: String): String =
        uploadFile(path, file, sha256, null)

    /**
     * Uploads one mirrored file. When a local [file] is available it is
     * streamed from disk (large EPUBs never sit in the heap); otherwise
     * [data] is sent as-is.
     */
    private suspend fun uploadFile(
        path: String,
        file: File?,
        sha256: String,
        data: ByteArray?,
    ): String {
        val root = rootFolderId()
        val existing = fileCache[path]
        val metadata = DriveFile().apply {
            name = path.substringAfterLast('/')
            if (existing == null) parents = listOf(root)
            appProperties = mapOf("relPath" to path, "sha256" to sha256)
        }

        val request = if (existing?.id != null) {
            drive.files().update(existing.id, metadata, contentFor(file, data))
        } else {
            drive.files().create(metadata, contentFor(file, data))
        }

        if ((file?.length() ?: (data?.size?.toLong() ?: 0L)) > RESUMABLE_THRESHOLD) {
            request.mediaHttpUploader.apply {
                setDirectUploadEnabled(false) // resumable
                setChunkSize(CHUNK_SIZE)
            }
        }

        val uploaded = request.execute()
        val id = uploaded.id ?: existing?.id ?: ""
        fileCache[path] = SyncFileEntry(
            path = path,
            size = file?.length() ?: (data?.size?.toLong() ?: 0L),
            sha256 = sha256,
            modifiedAt = uploaded.getModifiedTime()?.toString() ?: nowIso(),
            id = id,
        )
        return id
    }

    private fun contentFor(file: File?, data: ByteArray?): com.google.api.client.http.AbstractInputStreamContent =
        if (file != null) {
            FileContent(OCTET_STREAM, file)
        } else {
            ByteArrayContent(OCTET_STREAM, data ?: ByteArray(0))
        }

    private fun nowIso(): String = java.time.Instant.now().toString()
}
