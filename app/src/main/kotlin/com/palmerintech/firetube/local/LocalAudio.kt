package com.palmerintech.firetube.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.content.pm.PackageManager
import com.palmerintech.firetube.extractor.AudioSource
import com.palmerintech.firetube.extractor.ExtractionException
import com.palmerintech.firetube.extractor.ResolvedStream
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/** The system media scanner decodes tags; never guess encodings or normalize its Unicode strings. */
class LocalAudio(private val context: Context) : AudioSource {
    override val sourceId = TrackIdentity.LOCAL
    // Device-bound references must not resolve against unrelated rows on another phone.
    // noBackupFilesDir is excluded from both Android backup and our explicit library backup.
    private val deviceId by lazy {
        synchronized(LocalAudio::class.java) {
            val file = androidx.core.util.AtomicFile(java.io.File(context.noBackupFilesDir, "local-audio-device-id"))
            if (file.baseFile.exists()) file.readFully().decodeToString() else {
                val id = java.util.UUID.randomUUID().toString()
                val out = file.startWrite()
                try { out.write(id.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
                catch (e: Exception) { file.failWrite(out); throw e }
                id
            }
        }
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw SecurityException("Audio permission required")
        val selection = buildList {
            if (Build.VERSION.SDK_INT >= 29) add("${MediaStore.Audio.Media.IS_PENDING}=0")
            if (Build.VERSION.SDK_INT >= 30) add("${MediaStore.Audio.Media.IS_TRASHED}=0")
        }.joinToString(" AND ").ifEmpty { null }
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection(), selection, null, "${MediaStore.Audio.Media.DISPLAY_NAME} ASC")?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(readTrack(cursor)) }
        } ?: throw java.io.IOException("MediaStore unavailable")
    }

    val changes = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        var registered = false
        try {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            registered = true
        } catch (_: SecurityException) { /* Screen still offers the permission request and refresh. */ }
        awaitClose { if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }

    override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream = withContext(Dispatchers.IO) {
        val ref = trackId.toUri()
        if (!isMediaReference(ref) || ref.getQueryParameter("device") != deviceId) throw ExtractionException("Local audio must be linked on this device")
        if (!hasPermission()) throw ExtractionException("Audio permission required")
        val uri = ref.buildUpon().clearQuery().build()
        try {
            context.contentResolver.query(uri, projection(), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw ExtractionException("Local file was removed or moved")
                val current = readTrack(cursor)
                // Never silently play a different file after a MediaStore row id was recycled.
                if (current.externalId != trackId) throw ExtractionException("Local file reference changed")
                ResolvedStream(current.id, uri.toString(), cursor.text(MediaStore.Audio.Media.MIME_TYPE), 0, Long.MAX_VALUE, track = current)
            } ?: throw ExtractionException("Local file unavailable")
        } catch (e: SecurityException) { throw ExtractionException("Audio permission revoked", e)
        } catch (e: IllegalArgumentException) { throw ExtractionException("Local file unavailable", e) }
    }

    internal fun readTrack(cursor: Cursor): Track {
        val volume = if (Build.VERSION.SDK_INT >= 29) cursor.text(MediaStore.Audio.Media.VOLUME_NAME) ?: "external" else "external"
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(volume) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(collection, cursor.number(MediaStore.Audio.Media._ID))
        val stamp = if (Build.VERSION.SDK_INT >= 30) "generation" to cursor.number(MediaStore.Audio.Media.GENERATION_ADDED) else "added" to cursor.number(MediaStore.Audio.Media.DATE_ADDED)
        val ref = uri.buildUpon().appendQueryParameter(stamp.first, stamp.second.toString()).appendQueryParameter("device", deviceId).build().toString()
        val identity = TrackIdentity.forSource(sourceId, ref)
        val fileName = cursor.text(MediaStore.Audio.Media.DISPLAY_NAME).orEmpty()
        return Track(identity.id, cursor.text(MediaStore.Audio.Media.TITLE)?.takeIf { it.isNotBlank() } ?: fileName,
            cursor.text(MediaStore.Audio.Media.ARTIST)?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(),
            cursor.number(MediaStore.Audio.Media.DURATION) / 1000, null, sourceId = sourceId, externalId = ref,
            album = cursor.text(MediaStore.Audio.Media.ALBUM)?.takeUnless { it == MediaStore.UNKNOWN_STRING }, fileName = fileName)
    }

    internal fun projection(): Array<String> = buildList {
        addAll(listOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATE_ADDED))
        if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Audio.Media.VOLUME_NAME)
        if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.GENERATION_ADDED)
    }.toTypedArray()

    private fun Cursor.text(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
    private fun Cursor.number(column: String): Long = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) 0L else getLong(it) } ?: 0L

    companion object {
        fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        internal fun isMediaReference(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == "media" &&
            uri.pathSegments.let { it.size == 4 && it[1] == "audio" && it[2] == "media" && it[3].toLongOrNull() != null } &&
            (uri.getQueryParameter("generation") ?: uri.getQueryParameter("added"))?.toLongOrNull() != null
    }
}
