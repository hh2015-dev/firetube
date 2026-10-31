package com.palmerintech.firetube.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.palmerintech.firetube.extractor.AudioSource
import com.palmerintech.firetube.extractor.ExtractionException
import com.palmerintech.firetube.extractor.ResolvedStream
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only user-granted audio documents, including Google Drive when its provider is available. */
class DocumentAudio(private val context: Context) : AudioSource {
    override val sourceId = TrackIdentity.DOCUMENT

    /** Accept only picker-returned ACTION_OPEN_DOCUMENT URIs. Never request write access. */
    suspend fun importSelected(uris: List<Uri>): List<Track> = withContext(Dispatchers.IO) {
        uris.distinct().mapNotNull { uri ->
            try {
                if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(context, uri)) return@mapNotNull null
                val mimeType = context.contentResolver.getType(uri)
                val name = displayName(uri) ?: "Audio file"
                if (!supportsAudio(mimeType, name)) return@mapNotNull null
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!hasReadGrant(uri)) return@mapNotNull null
                val identity = TrackIdentity.forSource(sourceId, uri.toString())
                Track(identity.id, name, "", 0L, null, sourceId = sourceId, externalId = identity.externalId, fileName = name)
            } catch (_: SecurityException) { null
            } catch (_: IllegalArgumentException) { null
            } catch (_: java.io.IOException) { null }
        }
    }

    override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(trackId)
            if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(context, uri) || !hasReadGrant(uri))
                throw ExtractionException("Audio document permission missing or revoked")
            val mimeType = try { context.contentResolver.getType(uri) }
                catch (e: SecurityException) { throw ExtractionException("Audio document permission revoked", e) }
            ResolvedStream(TrackIdentity.forSource(sourceId, trackId).id, uri.toString(), mimeType, 0, Long.MAX_VALUE)
        }

    private fun hasReadGrant(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }

    private fun supportsAudio(mimeType: String?, name: String): Boolean {
        if (mimeType?.startsWith("audio/") == true) return true
        if (mimeType != null && mimeType != "application/octet-stream" &&
            mimeType !in setOf("application/ogg", "application/x-flac")) return false
        return name.substringAfterLast('.', "").lowercase() in setOf(
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "amr"
        )
    }
}
