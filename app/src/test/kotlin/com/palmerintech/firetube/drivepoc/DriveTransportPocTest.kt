// SPDX-License-Identifier: GPL-3.0-only
package com.palmerintech.firetube.drivepoc

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real loopback HTTP and the existing Media3 OkHttpDataSource. No Google requests/accounts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.media3.common.util.UnstableApi
class DriveTransportPocTest {
    private lateinit var server: HttpServer
    private lateinit var endpoint: String
    private val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
    private val refreshes = mutableListOf<Boolean>()
    private var handle: (HttpExchange) -> Unit = {}
    private val bytes = javaClass.getResourceAsStream("/local-audio/tone.mp3")!!.use { it.readBytes() }
    private val broker = object : DrivePocDataSource.TokenBroker {
        override fun accessToken(refresh: Boolean): String { refreshes += refresh; return if (refresh) "fixture-new" else "fixture-old" }
    }
    @Before fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestHeaders.getFirst("Authorization") to exchange.requestHeaders.getFirst("Range")
            try { handle(exchange) } finally { exchange.close() }
        }
        server.start()
        endpoint = "http://127.0.0.1:${server.address.port}/drive/v3/files/private-fixture?alt=media"
        handle = { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == null) reply(exchange, 200, bytes)
            else {
                val parts = range.removePrefix("bytes=").split('-')
                val start = parts[0].toInt()
                val end = parts[1].toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                reply(exchange, 206, bytes.copyOfRange(start, end + 1))
            }
        }
    }
    @After fun cleanup() { server.stop(0) }
    private fun reply(exchange: HttpExchange, code: Int, body: ByteArray = "private error fixture-old".toByteArray()) {
        exchange.responseHeaders.add("Content-Type", "audio/mpeg")
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    private fun source() = DrivePocDataSource(endpoint.toHttpUrl(), broker, loopbackFixture = true)
    private fun spec(position: Long = 0, length: Long = -1) = DataSpec.Builder().setUri(endpoint).setPosition(position).setLength(length).build()
    private fun readAll(source: DrivePocDataSource): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(97)
        while (true) { val read = source.read(buffer, 0, buffer.size); if (read == -1) break; output.write(buffer, 0, read) }
        return output.toByteArray()
    }
    private fun failure(block: () -> Unit): IOException {
        try { block(); fail("Expected a safe playback error") } catch (error: IOException) { return error }
        error("unreachable")
    }
    @Test fun partial206ReadsExactBytesAndKeepsTokenOutOfUriAndDataSpec() {
        val spec = spec(37, 101)
        source().use { source ->
            source.open(spec)
            assertArrayEquals(bytes.copyOfRange(37, 138), readAll(source))
            assertEquals(endpoint, source.uri.toString())
            assertFalse(source.uri.toString().contains("fixture-old"))
            assertTrue(spec.httpRequestHeaders.isEmpty())
        }
        assertEquals(listOf("Bearer fixture-old" to "bytes=37-137"), requests)
    }
    @Test fun complete200ReachesMedia3Mp3Extractor() {
        source().use { source ->
            val length = source.open(spec())
            val extractor = Mp3Extractor()
            try { assertTrue(extractor.sniff(DefaultExtractorInput(source, 0, length))) } finally { extractor.release() }
        }
        assertEquals(1, requests.size)
    }
    @Test fun progressiveReadReturnsBeforeTheServerSendsTheRestOfTheFile() {
        val firstChunk = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        handle = { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output ->
                output.write(bytes, 0, 64); output.flush(); firstChunk.countDown()
                if (resume.await(5, TimeUnit.SECONDS)) {
                    output.write(bytes, 64, bytes.size - 64); finished.set(true)
                }
            }
        }
        try {
            source().use { source ->
                source.open(spec())
                assertTrue(firstChunk.await(2, TimeUnit.SECONDS))
                val first = ByteArray(32)
                assertEquals(32, source.read(first, 0, first.size))
                assertArrayEquals(bytes.copyOfRange(0, 32), first)
                assertFalse("Media3 can read while the remaining bytes are unavailable", finished.get())
                resume.countDown()
                assertArrayEquals(bytes.copyOfRange(32, bytes.size), readAll(source))
            }
        } finally { resume.countDown() }
    }
    @Test fun ignoredRange200IsRejectedRatherThanSilentlyReadingTheWrongOffset() {
        handle = { reply(it, 200, bytes) }
        source().use { source -> failure { source.open(spec(100, 20)) } }
        assertEquals(1, requests.size)
    }
    @Test fun bounded200FromZeroIsReadable() {
        handle = { reply(it, 200, bytes) }
        source().use { source -> source.open(spec(0, 20)); assertArrayEquals(bytes.take(20).toByteArray(), readAll(source)) }
    }
    @Test fun wrong206StartIsRejected() {
        handle = { it.responseHeaders.add("Content-Range", "bytes 0-19/${bytes.size}"); reply(it, 206, bytes.take(20).toByteArray()) }
        source().use { source -> failure { source.open(spec(100, 20)) } }
    }
    @Test fun missing206ContentRangeIsRejected() {
        handle = { reply(it, 206, bytes.take(20).toByteArray()) }
        source().use { source -> failure { source.open(spec(100, 20)) } }
    }
    @Test fun unauthorized401RefreshesOnceAndPreservesTheExactRange() {
        val normal = handle
        handle = { if (requests.size == 1) reply(it, 401) else normal(it) }
        source().use { source -> source.open(spec(123, 45)); assertArrayEquals(bytes.copyOfRange(123, 168), readAll(source)) }
        assertEquals(listOf(false, true), refreshes)
        assertEquals(listOf("Bearer fixture-old" to "bytes=123-167", "Bearer fixture-new" to "bytes=123-167"), requests)
    }
    @Test fun repeated401StopsAfterOneRefreshWithoutLeakingServerBodyOrToken() {
        handle = { reply(it, 401) }
        source().use { source ->
            val error = failure { source.open(spec()) }
            assertEquals("Drive HTTP 401", error.message)
            assertNull(error.cause)
            assertFalse(error.stackTraceToString().contains("fixture-old"))
        }
        assertEquals(2, requests.size)
    }
    @Test fun forbidden403DoesNotRefreshOrTreatPermissionAsExpiredToken() {
        handle = { reply(it, 403) }
        source().use { source -> assertEquals("Drive HTTP 403", failure { source.open(spec()) }.message) }
        assertEquals(listOf(false), refreshes); assertEquals(1, requests.size)
    }
    @Test fun deletion404RemainsExplicitAndDoesNotLoop() {
        handle = { reply(it, 404) }
        source().use { source -> assertEquals("Drive HTTP 404", failure { source.open(spec()) }.message) }
        assertEquals(1, requests.size)
    }
    @Test fun redirectIsNotFollowedAndCannotSendBearerToAnotherPath() {
        handle = { it.responseHeaders.add("Location", "$endpoint&redirect=1"); reply(it, 302) }
        source().use { source -> assertEquals("Drive HTTP 302", failure { source.open(spec()) }.message) }
        assertEquals(1, requests.size)
    }
    @Test fun unrelatedUrlAndCallerHeadersAreRejectedBeforeReadingCredentials() {
        source().use { source ->
            failure { source.open(DataSpec(Uri.parse("https://example.invalid/"))) }
            failure { source.open(spec().buildUpon().setHttpRequestHeaders(mapOf("Authorization" to "bad")).build()) }
        }
        assertTrue(refreshes.isEmpty()); assertTrue(requests.isEmpty())
    }
    @Test fun productionBoundaryRejectsPlainHttpEvenForTheFixture() {
        DrivePocDataSource(endpoint.toHttpUrl(), broker).use { source -> failure { source.open(spec()) } }
        assertTrue(refreshes.isEmpty()); assertTrue(requests.isEmpty())
    }
    @Test fun reopeningAfterSeekRequestsFreshAuthAndTheNewOffset() {
        source().use { source ->
            source.open(spec(0, 10)); readAll(source); source.close()
            source.open(spec(500, 12)); assertArrayEquals(bytes.copyOfRange(500, 512), readAll(source))
        }
        assertEquals(listOf(false, false), refreshes)
        assertEquals("bytes=500-511", requests.last().second)
    }
}
