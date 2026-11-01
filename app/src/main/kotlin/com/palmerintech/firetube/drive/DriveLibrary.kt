package com.palmerintech.firetube.drive

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Read-only Drive v3 catalog, lazily browsed folder by folder. Only metadata is retrieved during
 * browsing; bytes are fetched exclusively by downloadSelected after the user's explicit click.
 */
class DriveLibrary(
    private val context: Context,
    private val client: OkHttpClient,
    val access: DriveAccess,
) {
    companion object {
        const val ROOT = "root"
        const val SHARED_WITH_ME = "virtual:shared-with-me"
        const val SHARED_DRIVES = "virtual:shared-drives"
        private const val BASE = "https://www.googleapis.com/drive/v3/files"
        private const val FIELDS =
            "nextPageToken,incompleteSearch,files(id,name,mimeType,parents,size,modifiedTime,capabilities(canDownload),shortcutDetails(targetId,targetMimeType))"
    }

    private val json = Json { ignoreUnknownKeys = true }
    /** No OAuth-bearing request may follow redirects to an unverified host. */
    private val safeClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private fun accountDirectory(): File {
        val namespace = access.namespace ?: throw IOException("Connect Google Drive first")
        return File(context.noBackupFilesDir, "drive-library/$namespace").apply { mkdirs() }
    }

    private fun keyOf(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cachedFile(folderId: String): File = File(accountDirectory(), "folder-${keyOf(folderId)}.json")
    private fun audioFile(id: String): File = File(accountDirectory(), "audio-${keyOf(id)}.bin")

    /** Visible folders/audio entries from the last successful visit, including while offline. */
    suspend fun cached(folderId: String): List<DriveEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val file = cachedFile(folderId)
            if (!file.exists()) emptyList()
            else DriveAudioTypes.visible(json.decodeFromString<DriveSnapshot>(file.readText()).entries)
        }.getOrDefault(emptyList())
    }

    /**
     * Scan one real folder and all subfolders only after the user requests a preview.
     * Metadata listings are cached so the same tree can be navigated while offline.
     */
    suspend fun planFolderDownload(folderId: String, driveId: String? = null): DriveFolderPlan {
        if (!access.connected) throw IOException("Connect Google Drive before scanning a folder")
        val namespace = access.namespace ?: throw IOException("Google Drive account not verified")
        val plan = DriveFolderPlanner.scan(folderId, namespace) { id ->
            if (!access.connected || access.namespace != namespace)
                throw IOException("Google Drive account changed during folder scan")
            list(id, driveId)
        }
        if (access.namespace != namespace) throw IOException("Google Drive account changed during folder scan")
        return plan
    }

    /**
     * Build an editable playlist from the full folder tree without downloading any bytes.
     * Offline mode reads only folder snapshots previously visited or scanned. Missing snapshots
     * fail explicitly; silently creating a partial playlist would misrepresent the folder.
     */
    suspend fun planFolderPlaylist(folderId: String, driveId: String? = null): DriveFolderPlan {
        if (access.connected) return planFolderDownload(folderId, driveId)
        val namespace = access.namespace ?: throw IOException("Connect Google Drive to save a folder")
        return DriveFolderPlanner.scan(folderId, namespace) { id ->
            val saved = cachedFile(id)
            if (!saved.isFile) throw IOException("Folder is not available offline: $id")
            DriveAudioTypes.visible(json.decodeFromString<DriveSnapshot>(saved.readText()).entries)
        }
    }

    /**
     * An explicit second click starts sequential, cancellable downloads. Previously saved
     * files are reused; cancellation leaves all completed files playable offline.
     * Individual failures are counted, never silently described as a complete folder.
     */
    suspend fun downloadFolder(
        plan: DriveFolderPlan,
        onProgress: suspend (DriveFolderProgress) -> Unit = {},
    ): DriveFolderResult = withContext(Dispatchers.IO) {
        if (!access.connected || plan.accountNamespace != access.namespace)
            throw IOException("Connect the same Google Drive account before downloading")
        val remainingBytes = plan.audio.asSequence().filterNot { isOffline(it.effectiveId) }
            .mapNotNull { it.size?.toLongOrNull()?.takeIf { bytes -> bytes >= 0 } }
            .fold(0L) { sum, size ->
                if (Long.MAX_VALUE - sum < size) Long.MAX_VALUE else sum + size
            }
        if (remainingBytes > (context.noBackupFilesDir.usableSpace - 10_000_000L).coerceAtLeast(0L))
            throw IOException("Not enough free storage for the selected folder")

        var downloaded = 0
        var alreadySaved = 0
        val failures = mutableListOf<String>()
        onProgress(DriveFolderProgress(0, plan.audio.size, 0, 0, 0, null))
        for ((index, entry) in plan.audio.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!access.connected || plan.accountNamespace != access.namespace)
                throw IOException("Google Drive account changed; download stopped")
            if (isOffline(entry.effectiveId)) {
                alreadySaved++
            } else {
                try {
                    downloadSelected(entry)
                    downloaded++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures.add(entry.name)
                }
            }
            onProgress(DriveFolderProgress(index + 1, plan.audio.size, downloaded,
                alreadySaved, failures.size, entry.name))
        }
        DriveFolderResult(downloaded, alreadySaved, failures)
    }

    suspend fun list(folderId: String, driveId: String? = null): List<DriveEntry> = withContext(Dispatchers.IO) {
        if (folderId == SHARED_DRIVES) {
            val drives = listSharedDrives()
            saveListing(folderId, drives)
            return@withContext drives
        }
        val result = mutableListOf<DriveEntry>()
        var cursor: String? = null
        val visited = mutableSetOf<String>()
        do {
            currentCoroutineContext().ensureActive()
            val where = if (folderId == SHARED_WITH_ME) "sharedWithMe = true and trashed = false"
                else "'${folderId.replace("\\", "\\\\").replace("'", "\\'")}' in parents and trashed = false"
            val builder = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("q", where)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", FIELDS)
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
            if (driveId != null) {
                builder.addQueryParameter("corpora", "drive").addQueryParameter("driveId", driveId)
            } else {
                builder.addQueryParameter("corpora", "user")
            }
            if (cursor != null) builder.addQueryParameter("pageToken", cursor)
            val page = json.decodeFromString<DrivePage>(getText(builder.build().toString()))
            if (page.incompleteSearch) throw IOException("Drive returned an incomplete folder listing")
            result += page.files
            cursor = page.nextPageToken
            if (cursor != null && !visited.add(cursor)) throw IOException("Repeated Drive page token")
            if (visited.size >= 100) throw IOException("Drive folder too large to list safely")
        } while (cursor != null)
        val virtual = if (folderId == ROOT) listOf(
            DriveEntry(SHARED_WITH_ME, "Shared with me", DriveEntry.FOLDER_MIME),
            DriveEntry(SHARED_DRIVES, "Shared drives", DriveEntry.FOLDER_MIME),
        ) else emptyList()
        val visible = virtual + DriveAudioTypes.visible(result)
        saveListing(folderId, visible)
        visible
    }

    private fun saveListing(folderId: String, visible: List<DriveEntry>) {
        val file = AtomicFile(cachedFile(folderId))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(DriveSnapshot(visible)).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    /** Each shared drive is a browsable root; subsequent requests use corpora=drive. */
    private suspend fun listSharedDrives(): List<DriveEntry> {
        val found = mutableListOf<DriveEntry>()
        var pageToken: String? = null
        val seen = mutableSetOf<String>()
        do {
            currentCoroutineContext().ensureActive()
            val url = "https://www.googleapis.com/drive/v3/drives".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("fields", "nextPageToken,drives(id,name)")
            if (pageToken != null) url.addQueryParameter("pageToken", pageToken)
            val page = json.decodeFromString<DriveListPage>(getText(url.build().toString()))
            found.addAll(page.drives.map { DriveEntry(it.id, it.name, DriveEntry.FOLDER_MIME) })
            pageToken = page.nextPageToken
            if (pageToken != null && !seen.add(pageToken)) throw IOException("Repeated shared-drive page token")
            if (seen.size >= 100) throw IOException("Too many shared-drive pages")
        } while (pageToken != null)
        return DriveAudioTypes.visible(found)
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        fun request(token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        var response = safeClient.newCall(request(access.bearerToken())).execute()
        if (response.code == 401) {
            response.close()
            response = safeClient.newCall(request(access.refreshToken())).execute()
        }
        response.use {
            if (!it.isSuccessful) throw IOException("Google Drive listing failed (HTTP ${it.code})")
            it.body.string()
        }
    }

    fun isOffline(id: String): Boolean = runCatching {
        audioFile(id).let { it.isFile && it.length() > 0 }
    }.getOrDefault(false)

    fun offlineFile(id: String): File? = runCatching {
        audioFile(id).takeIf { it.isFile && it.length() > 0 }
    }.getOrNull()

    /**
     * User-triggered only; no automatic scan, sync of content, or background downloads.
     * Files are in app-private storage. A failed/cancelled fetch cannot mark a partial as ready.
     */
    suspend fun downloadSelected(entry: DriveEntry, onBytes: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        require(entry.isAudio && entry.canDownload) { "This Drive item is not downloadable audio" }
        val file = audioFile(entry.effectiveId)
        if (file.isFile && file.length() > 0) return@withContext file
        val expected = entry.size?.toLongOrNull()
        if (expected != null && expected > context.noBackupFilesDir.usableSpace - 10_000_000L)
            throw IOException("Not enough space on the device for this audio file")
        val partial = File(file.parentFile, file.name + ".partial")
        // A previous interrupted attempt must not be trusted or shown as offline.
        partial.delete()
        try {
            val url = BASE.toHttpUrl().newBuilder().addPathSegment(entry.effectiveId)
                .addQueryParameter("alt", "media")
                .addQueryParameter("supportsAllDrives", "true").build().toString()
            fun request(token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            var response = safeClient.newCall(request(access.bearerToken())).execute()
            if (response.code == 401) {
                response.close()
                response = safeClient.newCall(request(access.refreshToken())).execute()
            }
            response.use {
                if (!it.isSuccessful) throw IOException("Drive audio download failed (HTTP ${it.code})")
                var count = 0L
                it.body.byteStream().use { source ->
                    partial.outputStream().buffered().use { target ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            target.write(buffer, 0, read)
                            count += read
                            onBytes(count)
                        }
                    }
                }
                if (count == 0L || (expected != null && expected != count))
                    throw IOException("Incomplete audio download")
                if (!partial.renameTo(file)) throw IOException("Could not complete offline audio file")
            }
            file
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    /** Deletion is explicit too: clearing a selected copy never deletes the Drive original. */
    suspend fun removeOffline(id: String) = withContext(Dispatchers.IO) {
        audioFile(id).delete()
    }
}

@Serializable
private data class DriveSnapshot(val entries: List<DriveEntry>)

@Serializable
private data class DriveListPage(val drives: List<DriveInfo> = emptyList(), val nextPageToken: String? = null)

@Serializable
private data class DriveInfo(val id: String, val name: String)
