package com.palmerintech.firetube

import android.app.Application
import androidx.media3.cast.Cast
import com.google.android.gms.common.ConnectionResult
import com.google.firebase.FirebaseApp
import com.google.android.gms.common.GoogleApiAvailability
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.data.SettingsRepository
import com.palmerintech.firetube.data.db.FireTubeDatabase
import com.palmerintech.firetube.data.sync.AuthManager
import com.palmerintech.firetube.data.sync.Backup
import com.palmerintech.firetube.data.sync.CloudSync
import com.palmerintech.firetube.extractor.NewPipeStreamSource
import com.palmerintech.firetube.extractor.StreamSource
import com.palmerintech.firetube.extractor.AudioSourceRegistry
import com.palmerintech.firetube.lyrics.Lyrics
import com.palmerintech.firetube.player.ChapterStore
import com.palmerintech.firetube.player.Downloads
import com.palmerintech.firetube.player.MediaStack
import com.palmerintech.firetube.player.PlayerConnection
import com.palmerintech.firetube.player.QueueStore
import com.palmerintech.firetube.player.SleepTimer
import com.palmerintech.firetube.player.SpeedGuard
import com.palmerintech.firetube.player.SponsorBlock
import com.palmerintech.firetube.player.StreamResolver
import com.palmerintech.firetube.player.cast.CastProxyServer
import com.palmerintech.firetube.update.createUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency graph; one instance per process, owned by [FireTubeApp]. */
@UnstableApi
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val source: StreamSource by lazy {
        // Home's trending follows the phone's region ("United States"), as YouTube's charts name it.
        val country = java.util.Locale.getDefault().getDisplayCountry(java.util.Locale.ENGLISH).ifEmpty { null }
        NewPipeStreamSource(httpClient, chartCountry = country)
    }

    private val database = FireTubeDatabase.create(app)
    val settings = SettingsRepository(app)
    val library = LibraryRepository(database.library())

    // Same synchronous source as the song-cache key, so URL bitrate and cache key always agree.
    val sources by lazy { AudioSourceRegistry(listOf(source)) }
    val resolver by lazy {
        StreamResolver(sources, { library.knownTrack(it)?.identity }, chapterStore) { settings.audioQualityNow() }
    }
    private val chapterStore by lazy { ChapterStore(File(app.filesDir, "chapters")) }
    val mediaStack by lazy {
        MediaStack(app, httpClient, resolver, settings.cacheSizeMbNow()) { settings.audioQualityNow().name }
    }
    val castServer by lazy { CastProxyServer(app, resolver, httpClient) }

    /** Chromecast needs Google Play services (so not on Fire tablets). */
    val castAvailable: Boolean by lazy {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS &&
            runCatching { Cast.getSingletonInstance(app).initialize() }.isSuccess
    }
    val downloads by lazy { Downloads(app, mediaStack, resolver, chapterStore) }
    val sponsorBlock by lazy { SponsorBlock(httpClient) }
    val lyrics by lazy { Lyrics(httpClient, "FireTube/${BuildConfig.VERSION_NAME} (https://github.com/PalmerinTek/firetube)") }
    val sleepTimer = SleepTimer(appScope)
    val speedGuard = SpeedGuard()
    val queueStore = QueueStore(app)
    val player = PlayerConnection(app, MainScope())

    val auth = AuthManager(app)
    val sync = CloudSync(app, auth, library, appScope)
    val backup = Backup(library)
    val updater = createUpdater(app, httpClient)

    /** The song cache keys on quality, so only already-resolved URLs need dropping. */
    suspend fun setAudioQuality(quality: AudioQuality) {
        settings.setAudioQuality(quality)
        resolver.clear()
    }

    /** Whether this build includes Firebase (official builds do; forks without google-services.json don't). */
    val firebaseConfigured: Boolean = FirebaseApp.getApps(app).isNotEmpty()

}
