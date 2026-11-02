package com.palmerintech.firetube.ui.library

import android.app.Activity
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.data.DriveFolderPlaylistIdentity
import com.palmerintech.firetube.R
import com.palmerintech.firetube.drive.DriveEntry
import com.palmerintech.firetube.drive.DriveLibrary
import com.palmerintech.firetube.drive.DriveFolderPlan
import com.palmerintech.firetube.drive.DriveFolderProgress
import com.palmerintech.firetube.drive.DriveFolderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Drive is a *catalog*, not an auto-playing playlist. The user alone chooses Play, Download,
 * and Remove offline. Folder listing never downloads audio bytes or starts the player.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveLibraryScreen(container: AppContainer, onBack: () -> Unit, contentPadding: PaddingValues) {
    val access = container.driveAccess
    val library = container.driveLibrary
    val offlineRevision by library.offlineRevision.collectAsState()
    val pinnedFolderIds by library.favoriteFolderIds.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val folders = remember { mutableStateListOf(DriveEntry(DriveLibrary.ROOT, "My Drive", DriveEntry.FOLDER_MIME)) }
    val folder = folders.last()
    var connected by remember { mutableStateOf(access.connected) }
    var items by remember { mutableStateOf<List<DriveEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val working = remember { mutableStateMapOf<String, Boolean>() }
    val changed = remember { mutableStateMapOf<String, Boolean>() }
    var toRemove by remember { mutableStateOf<DriveEntry?>(null) }
    var toRemoveFolder by remember { mutableStateOf<Pair<DriveEntry, DriveFolderPlan>?>(null) }
    var offlineMessage by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var scanningFolder by remember { mutableStateOf<String?>(null) }
    var plannedFolder by remember { mutableStateOf<Pair<DriveEntry, DriveFolderPlan>?>(null) }
    var plannedPlaylist by remember { mutableStateOf<Pair<DriveEntry, DriveFolderPlan>?>(null) }
    var playlistMessage by remember { mutableStateOf<String?>(null) }
    var runningFolderJob by remember { mutableStateOf<Job?>(null) }
    var folderProgress by remember { mutableStateOf<DriveFolderProgress?>(null) }
    var folderResult by remember { mutableStateOf<DriveFolderResult?>(null) }
    var folderBlocked by remember { mutableStateOf(0) }

    suspend fun reload(folderId: String, online: Boolean, driveId: String?) {
        loading = true
        error = null
        items = library.cached(folderId)
        if (online) {
            try {
                items = library.list(folderId, driveId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Could not refresh Google Drive. Showing saved folder information, if available."
            }
        }
        loading = false
    }

    // A shared drive has its own search corpus; remember the selected drive through subfolders.
    val sharedDriveId = if (folders.size >= 3 &&
        folders[1].effectiveId == DriveLibrary.SHARED_DRIVES) folders[2].effectiveId else null
    LaunchedEffect(folder.effectiveId, connected, refresh, sharedDriveId) {
        reload(folder.effectiveId, connected, sharedDriveId)
    }

    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            scope.launch {
                try {
                    access.acceptIntent(requireNotNull(result.data))
                    access.confirmAccount()
                    connected = true
                    refresh++
                } catch (_: Exception) {
                    error = "Google Drive permission or account verification failed."
                }
            }
        } else {
            error = "Google Drive authorization was cancelled."
        }
    }

    fun connect() {
        scope.launch {
            error = null
            try {
                val response = access.beginAuthorization()
                if (response.hasResolution()) {
                    val pending = response.pendingIntent
                        ?: throw IllegalStateException("Google returned no consent screen")
                    grant.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    access.accept(response)
                    access.confirmAccount()
                    connected = true
                    refresh++
                }
            } catch (_: Exception) {
                error = "Google Drive connection failed. Check OAuth configuration and try again."
            }
        }
    }

    // Resolve localized messages within composition; callbacks must not read resources from
    // LocalContext, which would miss configuration changes.
    val scanFailedMessage = stringResource(R.string.drive_folder_scan_failed)
    val cancelledMessage = stringResource(R.string.drive_folder_cancelled)
    val downloadFailedMessage = stringResource(R.string.drive_folder_download_failed)
    val playlistSavedMessage = stringResource(R.string.drive_playlist_saved)
    val playlistExistsMessage = stringResource(R.string.drive_playlist_already_saved)
    val playlistFailedMessage = stringResource(R.string.drive_playlist_failed)
    val removalFailedMessage = stringResource(R.string.drive_offline_remove_failed)
    val folderRemovalDone = stringResource(R.string.drive_folder_remove_done)
    val addedFavoriteFolderMessage = stringResource(R.string.drive_folder_favorite_added)
    val favoriteFolderFailedMessage = stringResource(R.string.drive_folder_favorite_failed)

    // Scanning only requests Drive metadata. Downloading starts after a second, explicit confirmation.
    fun prepareFolder(entry: DriveEntry) {
        if (!connected || scanningFolder != null || runningFolderJob != null) return
        scanningFolder = entry.effectiveId
        error = null
        scope.launch {
            try {
                val driveId = if (folder.effectiveId == DriveLibrary.SHARED_DRIVES)
                    entry.effectiveId else sharedDriveId
                val plan = library.planFolderDownload(entry.effectiveId, driveId)
                plannedFolder = entry to plan
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = scanFailedMessage
            } finally {
                scanningFolder = null
            }
        }
    }

    fun prepareFolderRemoval(entry: DriveEntry) {
        if (scanningFolder != null || runningFolderJob != null) return
        scanningFolder = entry.effectiveId
        error = null
        scope.launch {
            try {
                val plan = library.planFolderOfflineRemoval(entry.effectiveId)
                toRemoveFolder = entry to plan
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = removalFailedMessage
            } finally {
                scanningFolder = null
            }
        }
    }

    // A saved playlist is metadata only. It never starts a download or playback.
    fun preparePlaylist(entry: DriveEntry) {
        if (scanningFolder != null || runningFolderJob != null) return
        scanningFolder = entry.effectiveId
        error = null
        playlistMessage = null
        scope.launch {
            try {
                val driveId = if (folder.effectiveId == DriveLibrary.SHARED_DRIVES)
                    entry.effectiveId else sharedDriveId
                val plan = library.planFolderPlaylist(entry.effectiveId, driveId)
                plannedPlaylist = entry to plan
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = playlistFailedMessage
            } finally {
                scanningFolder = null
            }
        }
    }

    fun toggleFavoriteFolder(entry: DriveEntry) {
        if (scanningFolder != null || runningFolderJob != null) return
        val account = access.namespace ?: run {
            error = favoriteFolderFailedMessage
            return
        }
        val playlistId = DriveFolderPlaylistIdentity.forFolder(account, entry.effectiveId)
        if (playlistId in pinnedFolderIds) {
            try {
                library.setFolderFavoritePlaylist(playlistId, false)
            } catch (_: Exception) { error = favoriteFolderFailedMessage }
            return
        }
        scanningFolder = entry.effectiveId
        error = null
        scope.launch {
            try {
                val driveId = if (folder.effectiveId == DriveLibrary.SHARED_DRIVES)
                    entry.effectiveId else sharedDriveId
                val plan = library.planFolderPlaylist(entry.effectiveId, driveId)
                val tracks = plan.audio.map(container.driveAudio::track)
                container.library.saveDriveFolderAsPlaylist(
                    entry.name, plan.accountNamespace, entry.effectiveId, tracks)
                library.setFolderFavoritePlaylist(playlistId, true)
                playlistMessage = addedFavoriteFolderMessage
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = favoriteFolderFailedMessage
            } finally {
                scanningFolder = null
            }
        }
    }

    Column {
        TopAppBar(
            title = { Text(if (folders.size == 1) stringResource(R.string.drive_library_title) else folder.name) },
            navigationIcon = {
                IconButton(onClick = {
                    if (folders.size > 1) folders.removeAt(folders.lastIndex)
                    else onBack()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
            },
            actions = {
                if (folders.size > 1 && !folder.effectiveId.startsWith("virtual:")) {
                    val folderPlaylistId = access.namespace?.let {
                        DriveFolderPlaylistIdentity.forFolder(it, folder.effectiveId)
                    }
                    IconButton(
                        onClick = { toggleFavoriteFolder(folder) },
                        enabled = scanningFolder == null && runningFolderJob == null,
                    ) {
                        Icon(if (folderPlaylistId in pinnedFolderIds) Icons.Default.Favorite
                            else Icons.Default.FavoriteBorder,
                            stringResource(R.string.drive_folder_toggle_favorite))
                    }
                    IconButton(
                        onClick = { preparePlaylist(folder) },
                        enabled = scanningFolder == null && runningFolderJob == null,
                    ) {
                        Icon(Icons.Default.PlaylistAdd, stringResource(R.string.drive_save_folder_playlist))
                    }
                }
                IconButton(onClick = { refresh++ }, enabled = connected) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.local_audio_refresh))
                }
            },
        )
        if (!connected) {
            Button(onClick = ::connect, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.drive_connect))
            }
            Text(stringResource(R.string.drive_connect_hint), modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (error != null) {
            Text(error.orEmpty(), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        if (playlistMessage != null) {
            Text(playlistMessage.orEmpty(), modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.primary)
        }
        if (offlineMessage != null) {
            Text(offlineMessage.orEmpty(), modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.primary)
        }
        if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
        if (items.isEmpty() && !loading) {
            Text(stringResource(R.string.drive_empty), modifier = Modifier.padding(16.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 40.dp)) {
            items(items, key = { it.id }) { entry ->
                val track = remember(entry) {
                    if (entry.isAudio) container.driveAudio.track(entry) else null
                }
                val audioFavorite by if (track != null) {
                    container.library.isFavorite(track.id).collectAsState(false)
                } else {
                    remember { mutableStateOf(false) }
                }
                val folderPlaylistId = if (entry.isFolder && !entry.effectiveId.startsWith("virtual:"))
                    access.namespace?.let { DriveFolderPlaylistIdentity.forFolder(it, entry.effectiveId) }
                    else null
                val offline = remember(entry.effectiveId, offlineRevision) {
                    library.isOffline(entry.effectiveId)
                }
                val offlineFolderCount by produceState(0, entry.effectiveId, offlineRevision) {
                    value = if (entry.isFolder && !entry.effectiveId.startsWith("virtual:"))
                        runCatching {
                            library.planFolderOfflineRemoval(entry.effectiveId)
                                .audio.count { library.isOffline(it.effectiveId) }
                        }.getOrDefault(0) else 0
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(if (entry.isFolder) Icons.Default.Folder else Icons.Default.MusicNote, null)
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        if (!entry.isFolder && offline) {
                            Text(stringResource(R.string.drive_available_offline),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (entry.isFolder) {
                        TextButton(onClick = { folders.add(entry) }) {
                            Text(stringResource(R.string.action_open))
                        }
                        if (!entry.effectiveId.startsWith("virtual:")) {
                            IconButton(
                                onClick = { toggleFavoriteFolder(entry) },
                                enabled = scanningFolder == null && runningFolderJob == null,
                            ) {
                                Icon(if (folderPlaylistId in pinnedFolderIds) Icons.Default.Favorite
                                    else Icons.Default.FavoriteBorder,
                                    stringResource(R.string.drive_folder_toggle_favorite))
                            }
                            IconButton(
                                onClick = { preparePlaylist(entry) },
                                enabled = scanningFolder == null && runningFolderJob == null,
                            ) {
                                Icon(Icons.Default.PlaylistAdd,
                                    stringResource(R.string.drive_save_folder_playlist))
                            }
                        }
                        if (offlineFolderCount > 0) {
                            IconButton(onClick = { prepareFolderRemoval(entry) },
                                enabled = scanningFolder == null && runningFolderJob == null) {
                                Icon(Icons.Default.CloudDone,
                                    stringResource(R.string.drive_folder_remove_offline))
                            }
                        }
                        if (connected && !entry.effectiveId.startsWith("virtual:")) {
                            if (scanningFolder == entry.effectiveId) {
                                CircularProgressIndicator(Modifier.padding(8.dp))
                            } else {
                                IconButton(
                                    onClick = { prepareFolder(entry) },
                                    enabled = scanningFolder == null && runningFolderJob == null,
                                ) {
                                    Icon(Icons.Default.Download,
                                        stringResource(R.string.drive_download_folder))
                                }
                            }
                        }
                    } else {
                        if (track != null) {
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        container.library.toggleFavorite(track)
                                    } catch (_: Exception) {
                                        error = favoriteFolderFailedMessage
                                    }
                                }
                            }) {
                                Icon(if (audioFavorite) Icons.Default.Favorite
                                    else Icons.Default.FavoriteBorder,
                                    stringResource(if (audioFavorite) R.string.action_remove_favorite
                                        else R.string.action_add_favorite))
                            }
                        }
                        // A Play click is the only path from the catalog to the media player.
                        IconButton(onClick = {
                            val track = container.driveAudio.track(entry)
                            scope.launch {
                                try {
                                    container.library.saveTracks(listOf(track))
                                    container.resolver.remember(track)
                                    container.player.play(listOf(track))
                                } catch (_: Exception) { error = "Audio playback could not start." }
                            }
                        }, enabled = (connected || offline) && runningFolderJob == null) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.drive_play))
                        }
                        if (offline) {
                            IconButton(onClick = { toRemove = entry }) {
                                Icon(Icons.Default.CloudDone, stringResource(R.string.drive_remove_offline))
                            }
                        } else if (working[entry.effectiveId] == true) {
                            CircularProgressIndicator(Modifier.padding(8.dp))
                        } else {
                            IconButton(onClick = {
                                scope.launch {
                                    working[entry.effectiveId] = true
                                    try {
                                        val track = container.driveAudio.track(entry)
                                        container.library.saveTracks(listOf(track))
                                        container.resolver.remember(track)
                                        library.downloadSelected(entry)
                                        changed[entry.effectiveId] = true
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        error = "Could not download the selected audio file."
                                    } finally {
                                        working.remove(entry.effectiveId)
                                    }
                                }
                            }, enabled = connected && entry.canDownload && runningFolderJob == null) {
                                Icon(Icons.Default.Download, stringResource(R.string.drive_download))
                            }
                        }
                    }
                }
            }
        }
    }
    plannedPlaylist?.let { (entry, plan) ->
        AlertDialog(
            onDismissRequest = { plannedPlaylist = null },
            title = { Text(stringResource(R.string.drive_playlist_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name)
                    Text(stringResource(R.string.drive_playlist_confirm_summary,
                        plan.audio.size, plan.foldersVisited, plan.blockedFiles))
                    if (plan.audio.isEmpty()) {
                        Text(stringResource(R.string.drive_folder_no_audio))
                    }
                }
            },
            confirmButton = {
                if (plan.audio.isNotEmpty()) {
                    TextButton(onClick = {
                        plannedPlaylist = null
                        scope.launch {
                            try {
                                // A normal editable playlist, accessible from Library > Playlists.
                                // No Drive bytes are fetched and no player is started.
                                val tracks = plan.audio.map(container.driveAudio::track)
                                val (_, created) = container.library.saveDriveFolderAsPlaylist(
                                    entry.name, plan.accountNamespace, entry.effectiveId, tracks)
                                playlistMessage = if (created) playlistSavedMessage else playlistExistsMessage
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                error = playlistFailedMessage
                            }
                        }
                    }) { Text(stringResource(R.string.drive_playlist_save)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { plannedPlaylist = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    plannedFolder?.let { (entry, plan) ->
        AlertDialog(
            onDismissRequest = { plannedFolder = null },
            title = { Text(stringResource(R.string.drive_folder_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name)
                    Text(stringResource(R.string.drive_folder_confirm_summary,
                        plan.audio.size, plan.foldersVisited, plan.blockedFiles,
                        Formatter.formatShortFileSize(context, plan.totalKnownBytes), plan.unknownSizes))
                    if (plan.audio.isEmpty()) {
                        Text(stringResource(R.string.drive_folder_no_audio))
                    }
                }
            },
            confirmButton = {
                if (plan.audio.isNotEmpty()) {
                    TextButton(onClick = {
                        plannedFolder = null
                        folderBlocked = plan.blockedFiles
                        folderProgress = null
                        runningFolderJob = scope.launch {
                            try {
                                // Saving track identities makes the cached folder tree playable offline.
                                val tracks = plan.audio.map(container.driveAudio::track)
                                container.library.saveTracks(tracks)
                                tracks.forEach(container.resolver::remember)
                                val result = library.downloadFolder(plan) { progress ->
                                    withContext(Dispatchers.Main) { folderProgress = progress }
                                }
                                folderResult = result
                            } catch (e: CancellationException) {
                                // A stopped batch keeps complete downloads from earlier in the batch.
                                error = cancelledMessage
                                throw e
                            } catch (_: Exception) {
                                error = downloadFailedMessage
                            } finally {
                                runningFolderJob = null
                                folderProgress = null
                                refresh++
                            }
                        }
                    }) { Text(stringResource(R.string.drive_folder_start)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { plannedFolder = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (runningFolderJob != null) {
        val progress = folderProgress
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.drive_folder_progress_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    if (progress != null) {
                        Text(stringResource(R.string.drive_folder_progress,
                            progress.finished, progress.total, progress.downloaded,
                            progress.alreadySaved, progress.failed, progress.lastFile.orEmpty()))
                    } else {
                        Text(stringResource(R.string.drive_scanning_folder))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { runningFolderJob?.cancel() }) {
                    Text(stringResource(R.string.drive_folder_cancel))
                }
            },
        )
    }

    folderResult?.let { result ->
        AlertDialog(
            onDismissRequest = { folderResult = null },
            title = { Text(stringResource(R.string.drive_folder_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.drive_folder_done_summary,
                        result.downloaded, result.alreadySaved, result.failedFiles.size,
                        folderBlocked))
                    if (result.failedFiles.isNotEmpty()) {
                        Text(result.failedFiles.take(5).joinToString("\n"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { folderResult = null }) {
                    Text(stringResource(R.string.drive_folder_close))
                }
            },
        )
    }

    toRemoveFolder?.let { (entry, plan) ->
        val downloaded = remember(plan, offlineRevision) {
            plan.audio.count { library.isOffline(it.effectiveId) }
        }
        AlertDialog(
            onDismissRequest = { toRemoveFolder = null },
            title = { Text(stringResource(R.string.drive_folder_remove_offline)) },
            text = { Text(stringResource(R.string.drive_folder_remove_summary, entry.name, downloaded)) },
            confirmButton = {
                TextButton(enabled = downloaded > 0, onClick = {
                    toRemoveFolder = null
                    scope.launch {
                        try {
                            val result = library.removeFolderOffline(plan)
                            offlineMessage = folderRemovalDone.format(result.removed)
                            if (result.failedNames.isNotEmpty()) error = removalFailedMessage
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = removalFailedMessage
                        }
                    }
                }) { Text(stringResource(R.string.drive_remove_offline)) }
            },
            dismissButton = {
                TextButton(onClick = { toRemoveFolder = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    toRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text(stringResource(R.string.drive_remove_offline)) },
            text = { Text(entry.name) },
            confirmButton = {
                TextButton(onClick = {
                    toRemove = null
                    scope.launch {
                        try {
                            library.removeOffline(entry.effectiveId)
                            changed[entry.effectiveId] = false
                        } catch (_: Exception) {
                            error = removalFailedMessage
                        }
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
