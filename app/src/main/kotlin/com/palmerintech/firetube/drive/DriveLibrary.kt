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
        private const val BASE = "https://www.googleapis.com/drive/v3/files"
        private const val FIELDS =
            "nextPageToken,incompleteSearch,files(id,name,mimeType,parents,size,modifiedTime,capabilities(canDownload),shortcutDetails(targetId,targetMimeType))"
    }

    private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun list(folderId: String): List<DriveEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DriveEntry>()
        var cursor: String? = null
        val visited = mutableSetOf<String>()
        do {
            currentCoroutineContext().ensureActive()
            val builder = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("q", "'${folderId.replace("\\", "\\\\").replace("'", "\\'")}' in parents and trashed = false")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", FIELDS)
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
            if (cursor != null) builder.addQueryParameter("pageToken", cursor)
            val page = json.decodeFromString<DrivePage>(getText(builder.build().toString()))
            if (page.incompleteSearch) throw IOException("Drive returned an incomplete folder listing")
            result += page.files
            cursor = page.nextPageToken
            if (cursor != null && !visited.add(cursor)) throw IOException("Repeated Drive page token")
            if (visited.size >= 100) throw IOException("Drive folder too large to list safely")
        } while (cursor != null)
        val visible = DriveAudioTypes.visible(result)
        val file = AtomicFile(cachedFile(folderId))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(DriveSnapshot(visible)).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        visible
    }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        fun request(token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        var response = client.newCall(request(access.bearerToken())).execute()
        if (response.code == 401) {
            response.close()
            response = client.newCall(request(access.refreshToken())).execute()
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
        val file = audioFile(entry.id)
        if (file.isFile && file.length() > 0) return@withContext file
        val expected = entry.size?.toLongOrNull()
        if (expected != null && expected > context.noBackupFilesDir.usableSpace - 10_000_000L)
            throw IOException("Not enough space on the device for this audio file")
        val partial = File(file.parentFile, file.name + ".partial")
        // A previous interrupted attempt must not be trusted or shown as offline.
        partial.delete()
        try {
            val url = "$BASE/${entry.id}?alt=media&supportsAllDrives=true"
            fun request(token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            var response = client.newCall(request(access.bearerToken())).execute()
            if (response.code == 401) {
                response.close()
                response = client.newCall(request(access.refreshToken())).execute()
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
