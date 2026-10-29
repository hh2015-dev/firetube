// SPDX-License-Identifier: GPL-3.0-only
package com.palmerintech.firetube.drivepoc

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy

/** Test-source-set experiment only. Not registered in FireTube or packaged in any APK. */
@androidx.media3.common.util.UnstableApi
internal class DrivePocDataSource(
    private val endpoint: HttpUrl,
    private val broker: TokenBroker,
    private val loopbackFixture: Boolean = false,
) : DataSource, java.io.Closeable {
    interface TokenBroker {
        /** Bound to one account/session. A real implementation needs lifecycle-safe authorization. */
        fun accessToken(refresh: Boolean): String
    }

    private var active: DataSource? = null
    private var activeClient: OkHttpClient? = null
    private val listeners = mutableListOf<TransferListener>()
    private fun safeEndpoint(): Boolean = if (loopbackFixture) {
        endpoint.scheme == "http" && endpoint.host == "127.0.0.1"
    } else endpoint.scheme == "https" && endpoint.host == "www.googleapis.com" && endpoint.port == 443 &&
        endpoint.encodedPath.matches(Regex("/drive/v3/files/[A-Za-z0-9_-]+")) &&
        endpoint.query == "alt=media" && endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null

    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "Close the previous source first" }
        // No arbitrary URLs/headers, so auth cannot be redirected through another provider or URI.
        if (!safeEndpoint() || dataSpec.uri.toString() != endpoint.toString() || dataSpec.httpRequestHeaders.isNotEmpty() ||
            dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null) throw IOException("Invalid Drive request")
        for (attempt in 0..1) {
            val token = try { broker.accessToken(attempt == 1) } catch (_: Exception) { throw IOException("Authorization required") }
            if (token.isBlank() || token.any { it == '\r' || it == '\n' }) throw IOException("Authorization required")
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES)
                .apply { if (loopbackFixture) proxy(Proxy.NO_PROXY) } // Local fixture only; never a network-policy bypass.
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder().header("Authorization", "Bearer $token")
                        .header("Accept-Encoding", "identity").build()
                    val response = chain.proceed(request)
                    if (response.code == 200 && dataSpec.position != 0L) {
                        response.close(); throw IOException("Range ignored")
                    }
                    if (response.code == 206 && !validRange(response.header("Content-Range"), dataSpec.position, dataSpec.length)) {
                        response.close(); throw IOException("Invalid Content-Range")
                    }
                    response
                }.build()
            val source = OkHttpDataSource.Factory(client).createDataSource()
            listeners.forEach(source::addTransferListener)
            try {
                val length = source.open(dataSpec)
                active = source
                activeClient = client
                return length
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                source.close(); client.connectionPool.evictAll()
                if (error.responseCode == 401 && attempt == 0) continue
                // No server body, request object, OAuth token, or raw cause leaves this boundary.
                throw IOException("Drive HTTP ${error.responseCode}")
            } catch (_: IOException) {
                source.close(); client.connectionPool.evictAll()
                throw IOException("Drive range/transport unavailable")
            }
        }
        throw IOException("Authorization required")
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        active?.read(buffer, offset, length) ?: throw IOException("Source is closed")
    } catch (_: IOException) { throw IOException("Drive read unavailable") }
    override fun close() { try { active?.close() } finally { active = null; activeClient?.connectionPool?.evictAll(); activeClient = null } }
    override fun getUri(): Uri? = if (active == null) null else Uri.parse(endpoint.toString())
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
        .filterKeys { it.lowercase() in setOf("content-type", "content-range", "content-length", "accept-ranges") }
    override fun addTransferListener(listener: TransferListener) { listeners += listener; active?.addTransferListener(listener) }

    private fun validRange(header: String?, start: Long, length: Long): Boolean {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header ?: "") ?: return false
        val (first, last, total) = match.groupValues.drop(1).map { it.toLongOrNull() ?: return false }
        if (first != start || last < first || total <= last) return false
        return length == -1L || length > 0 && start <= Long.MAX_VALUE - length && last == minOf(start + length - 1, total - 1)
    }
}
