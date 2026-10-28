package com.palmerintech.firetube.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.palmerintech.firetube.extractor.ExtractionException
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Content reads bypass both audio caches. Access and row generation are rechecked on every open. */
@androidx.media3.common.util.UnstableApi
internal class LocalPlaybackDataSource(context: Context, private val resolver: StreamResolver) : DataSource {
    private val content = ContentDataSource(context)
    override fun open(dataSpec: DataSpec): Long = try {
        val id = MediaItems.trackIdOf(dataSpec.uri) ?: throw IOException("Missing local identity")
        val resolved = runBlocking { resolver.resolve(id, forceRefresh = true) }
        val uri = resolved.url.toUri()
        if (uri.scheme != "content" || uri.authority != "media") throw IOException("Not a MediaStore resource")
        content.open(dataSpec.withUri(uri))
    } catch (e: ExtractionException) { throw StreamUnavailableException(true, e)
    } catch (e: SecurityException) { throw StreamUnavailableException(true, e)
    } catch (e: IOException) { throw StreamUnavailableException(true, e) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try { content.read(buffer, offset, length) }
        catch (e: SecurityException) { throw StreamUnavailableException(true, e) }
    override fun close() = content.close()
    override fun getUri(): Uri? = content.uri
    override fun addTransferListener(listener: TransferListener) = content.addTransferListener(listener)
}

/** Keeps the old YouTube/download/cache pipeline untouched for every non-local item. */
@androidx.media3.common.util.UnstableApi
internal class PlaybackDataSource(private val cached: DataSource, private val local: DataSource) : DataSource {
    private var active: DataSource = cached
    override fun open(dataSpec: DataSpec): Long {
        active = if (MediaItems.isLocalUri(dataSpec.uri)) local else cached
        return active.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = active.read(buffer, offset, length)
    override fun close() = active.close()
    override fun getUri(): Uri? = active.uri
    override fun getResponseHeaders() = active.responseHeaders
    override fun addTransferListener(listener: TransferListener) { cached.addTransferListener(listener); local.addTransferListener(listener) }
}
