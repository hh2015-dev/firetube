package com.palmerintech.firetube.player

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.extractor.AudioSourceRegistry
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LegacyAudioCacheTest {
    @Test fun existingDownloadIndexAndBothCacheKeysRemainReadableWithoutResolution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = StreamResolver(AudioSourceRegistry(emptyList())) { AudioQuality.HIGH }
        val stack = MediaStack(context, OkHttpClient(), resolver, 64) { "HIGH" }
        val id = "dQw4w9WgXcQ"
        val uri = MediaItems.uriOf(id)
        val payload = byteArrayOf(1, 2, 3, 4)
        val data = """{"id":"$id","title":"שלום العربية Ελληνικά","artist":"artist","duration":2,"thumb":null}""".toByteArray(Charsets.UTF_8)
        try {
            val index = DefaultDownloadIndex(stack.database)
            val request = DownloadRequest.Builder(id, uri).setData(data).build()
            index.putDownload(Download(request, Download.STATE_COMPLETED, 1L, 2L, 4L, 0, 0))
            val old = index.getDownload(id)!!
            assertEquals(uri, old.request.uri)
            assertEquals(id, Json.decodeFromString<Downloads.StoredTrack>(old.request.data.decodeToString()).toTrack().id)
            for ((cache, key) in listOf(stack.downloadCache to uri.toString(), stack.streamCache to "$uri@HIGH")) {
                val hole = cache.startReadWrite(key, 0, payload.size.toLong())
                try {
                    val file = cache.startFile(key, 0, payload.size.toLong())
                    file.writeBytes(payload)
                    cache.commitFile(file, payload.size.toLong())
                } finally { cache.releaseHoleSpan(hole) }
                val source = stack.playbackFactory.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri(uri).setLength(4).build())
                    val read = ByteArray(4)
                    assertEquals(4, source.read(read, 0, 4))
                    assertArrayEquals(payload, read)
                } finally { source.close() }
                cache.removeResource(key)
            }
        } finally {
            stack.streamCache.release()
            stack.downloadCache.release()
            stack.database.close()
            File(context.cacheDir, "media").deleteRecursively()
            File(context.filesDir, "downloads").deleteRecursively()
        }
    }
}
