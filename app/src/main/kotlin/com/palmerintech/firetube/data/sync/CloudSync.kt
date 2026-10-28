package com.palmerintech.firetube.data.sync

import com.google.firebase.Firebase
import com.google.firebase.database.database
import android.content.Context
import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.data.db.PlaylistEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Mirrors playlists (incl. Favorites) to Firebase Realtime Database at
 * `v2/users/{uid}/playlists/{playlistId}`. The device's database stays the source of truth;
 * conflicts resolve last-writer-wins per playlist. Fits easily in the free Spark plan.
 *
 * (Realtime Database rather than Firestore: Firestore bundles its own protobuf classes, which
 * clash with the protobuf runtime NewPipeExtractor needs.)
 */
class CloudSync(
    context: Context,
    private val auth: AuthManager,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Idle : Status
        data object Syncing : Status
        data class Done(val at: Long) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()
    private val db by lazy { Firebase.database }
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** Playlists edited locally and not yet pushed. */
    private val dirty = mutableSetOf<String>()

    @OptIn(FlowPreview::class)
    fun start() {
        // Full sync whenever someone signs in (and at startup if already signed in).
        scope.launch {
            auth.user.map { it?.uid }.distinctUntilChanged().collect { uid -> if (uid != null) syncNow() }
        }
        // Push local edits shortly after they happen — every playlist touched, not just the last.
        scope.launch {
            library.localChanges
                .onEach { id -> synchronized(dirty) { dirty += id } }
                .debounce(1500)
                .collect { pushDirty() }
        }
    }

    suspend fun syncNow() {
        val uid = auth.user.value?.uid ?: return
        mutex.withLock {
            _status.value = Status.Syncing
            try {
                val remote = playlists(uid).get().await().children.mapNotNull { child ->
                    val id = child.key ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    runCatching { fromMap(id, child.value as Map<String, Any?>) }.getOrNull()
                }
                // First sync of this account on this device: merge Favorites rather than overwrite.
                val firstSync = prefs.getString(KEY_LAST_UID, null) != uid
                val unionIds = if (firstSync) setOf(PlaylistEntity.FAVORITES_ID) else emptySet()
                library.mergeSnapshots(remote, unionIds).forEach { push(uid, it) }
                prefs.edit().putString(KEY_LAST_UID, uid).apply()
                _status.value = Status.Done(System.currentTimeMillis())
            } catch (e: Exception) {
                Timber.w(e, "Sync failed")
                _status.value = Status.Failed(e.message.orEmpty())
            }
        }
    }

    private suspend fun pushDirty() {
        val uid = auth.user.value?.uid ?: return
        val ids = synchronized(dirty) { dirty.toList().also { dirty.clear() } }
        for (id in ids) {
            runCatching { library.snapshot(id)?.let { push(uid, it) } }
                .onFailure {
                    Timber.w(it, "Sync push failed for %s", id)
                    synchronized(dirty) { dirty += id } // retried with the next edit or sync
                }
        }
    }

    private fun playlists(uid: String) = db.reference.child("v2").child("users").child(uid).child("playlists")

    private suspend fun push(uid: String, p: PlaylistSnapshot) {
        playlists(uid).child(p.id).setValue(toMap(p)).await()
    }

    private fun toMap(p: PlaylistSnapshot): Map<String, Any?> = mapOf(
        "name" to p.name,
        "updatedAt" to p.updatedAt,
        "deleted" to p.deleted,
        "sourceUrl" to p.sourceUrl,
        "tracks" to p.tracks.map { it.toCloudMap() },
    )

    @Suppress("UNCHECKED_CAST")
    private fun fromMap(id: String, d: Map<String, Any?>): PlaylistSnapshot = PlaylistSnapshot(
        id = id,
        name = d["name"] as String,
        updatedAt = (d["updatedAt"] as Number).toLong(),
        deleted = d["deleted"] as? Boolean ?: false,
        sourceUrl = d["sourceUrl"] as? String,
        // Realtime Database drops empty lists, so a missing "tracks" just means empty.
        tracks = (d["tracks"] as? List<Map<String, Any?>?>).orEmpty().filterNotNull().map { t ->
            TrackSnapshot.fromCloudMap(t)
        },
    )

    private companion object {
        const val KEY_LAST_UID = "last_synced_uid"
    }
}
