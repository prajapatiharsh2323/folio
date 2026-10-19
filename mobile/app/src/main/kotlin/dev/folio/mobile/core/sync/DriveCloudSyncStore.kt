package dev.folio.mobile.core.sync

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import java.io.InputStream

/**
 * Google Drive implementation of [CloudSyncStore].
 *
 * The snapshot lives in the app's *hidden* Drive folder (`appDataFolder`):
 *  - it never shows up in the user's "My Drive" UI, so it can't be clobbered
 *    by accident and the user is never tempted to reorganise it;
 *  - it is billed against the user's own 15 GB Drive quota, so the app costs
 *    the maintainer nothing to host it;
 *  - the only OAuth scope requested is `drive.appdata`, so the app can never
 *    see the user's files, only its own folder.
 *
 * Uploads use the resumable protocol (`directUploadEnabled = false`) because
 * a library of EPUBs regularly exceeds Drive's 5 MB single-request limit.
 */
class DriveCloudSyncStore(
    private val drive: Drive,
) : CloudSyncStore {

    companion object {
        /** Fixed name: the cloud holds exactly one snapshot per account. */
        const val REMOTE_FILE_NAME = "folio-latest.zip"

        /** The hidden per-app Drive space. */
        const val APP_DATA_SPACE = "appDataFolder"

        private const val MIME_TYPE = "application/zip"

        /** 1 MiB — a multiple of the required 256 KiB resumable chunk size. */
        private const val CHUNK_SIZE = 1024 * 1024

        /** Builds a store bound to a Google account credential. */
        fun create(credential: GoogleAccountCredential): DriveCloudSyncStore {
            val drive = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential,
            ).setApplicationName("Folio").build()
            return DriveCloudSyncStore(drive)
        }
    }

    override suspend fun upload(
        archive: File,
        onProgress: ((uploaded: Long, total: Long) -> Unit)?,
    ): RemoteBackupInfo {
        val total = archive.length()
        val existing = findRemote()

        val request = if (existing != null) {
            // Update in place: the file id (and therefore the snapshot slot)
            // stays stable across syncs.
            val metadata = DriveFile().apply { name = REMOTE_FILE_NAME }
            drive.files().update(existing.id, metadata, FileContent(MIME_TYPE, archive))
        } else {
            val metadata = DriveFile().apply {
                name = REMOTE_FILE_NAME
                parents = listOf(APP_DATA_SPACE)
            }
            drive.files().create(metadata, FileContent(MIME_TYPE, archive))
        }

        request.mediaHttpUploader.apply {
            setDirectUploadEnabled(false) // resumable upload
            setChunkSize(CHUNK_SIZE)
            if (onProgress != null) {
                setProgressListener(
                    MediaHttpUploaderProgressListener { uploader ->
                        onProgress(uploader.numBytesUploaded, total)
                    }
                )
            }
        }

        val uploaded = request.execute()
        return RemoteBackupInfo(
            fileId = uploaded.id,
            modifiedTime = uploaded.modifiedTime?.toString(),
            sizeBytes = uploaded.getSize() ?: total,
        )
    }

    override suspend fun openDownload(): InputStream? {
        val remote = findRemote() ?: return null
        return drive.files().get(remote.id).executeMediaAsInputStream()
    }

    override suspend fun remoteInfo(): RemoteBackupInfo? {
        val remote = findRemote() ?: return null
        return RemoteBackupInfo(
            fileId = remote.id,
            modifiedTime = remote.modifiedTime?.toString(),
            sizeBytes = remote.getSize() ?: 0L,
        )
    }

    /** The single snapshot file in the app folder, or null when there is none. */
    private fun findRemote(): DriveFile? =
        drive.files().list()
            .setSpaces(APP_DATA_SPACE)
            .setQ("name = '$REMOTE_FILE_NAME' and trashed = false")
            .setFields("files(id,name,modifiedTime,size)")
            .execute()
            .files
            ?.firstOrNull()
}
