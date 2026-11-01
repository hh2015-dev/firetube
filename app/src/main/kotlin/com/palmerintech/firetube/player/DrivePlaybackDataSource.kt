package com.palmerintech.firetube.player

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.palmerintech.firetube.drive.DriveLibrary
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * For saved files, playback is fully offline. For online files, Authorization is an HTTP header
 * created at read time, never part of the media URI/cache key or public player metadata.
 */
@androidx.media3.common.util.UnstableApi
internal class DrivePlaybackDataSource(
    private val library: DriveLibrary,
    private val client: OkHttpClient,
    private val resolver: StreamResolver,
) : DataSource {
    private var active: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun open(dataSpec: DataSpec): Long {
        val id = MediaItems.trackIdOf(dataSpec.uri) ?: throw IOException("Missing Drive identity")
        val remote = runBlocking { resolver.resolve(id, forceRefresh = true) }
        val path = remote.url.toUri()
        if (path.scheme != "https" || path.host != "www.googleapis.com" ||
            path.pathSegments.size != 4 || path.pathSegments[0] != "drive" ||
            path.pathSegments[1] != "v3" || path.pathSegments[2] != "files") {
            throw IOException("Unexpected Google Drive URL")
        }
        val driveId = path.pathSegments[3]
        val offline = library.offlineFile(driveId)
        if (offline != null) {
            val file = FileDataSource().also { ds -> listeners.forEach(ds::addTransferListener) }
            active = file
            return file.open(dataSpec.withUri(Uri.fromFile(offline)))
        }

        fun http(token: String): HttpDataSource = OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            .createDataSource().also { ds -> listeners.forEach(ds::addTransferListener) }

        val first = http(runBlocking { library.access.bearerToken() })
        active = first
        return try {
            first.open(dataSpec.withUri(path))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 401) throw IOException("Google Drive playback failed (HTTP ${e.responseCode})", e)
            first.close()
            val retry = http(runBlocking { library.access.refreshToken() })
            active = retry
            retry.open(dataSpec.withUri(path))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("Drive source is not open")).read(buffer, offset, length)
    override fun close() { active?.close(); active = null }
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        active?.addTransferListener(transferListener)
    }
}
