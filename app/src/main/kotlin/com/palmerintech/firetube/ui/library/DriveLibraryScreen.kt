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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
    var refresh by remember { mutableStateOf(0) }
    var scanningFolder by remember { mutableStateOf<String?>(null) }
    var plannedFolder by remember { mutableStateOf<Pair<DriveEntry, DriveFolderPlan>?>(null) }
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
                error = context.getString(R.string.drive_folder_scan_failed)
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
        if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
        if (items.isEmpty() && !loading) {
            Text(stringResource(R.string.drive_empty), modifier = Modifier.padding(16.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 40.dp)) {
            items(items, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(if (entry.isFolder) Icons.Default.Folder else Icons.Default.MusicNote, null)
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        if (!entry.isFolder && library.isOffline(entry.effectiveId)) {
                            Text(stringResource(R.string.drive_available_offline),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (entry.isFolder) {
                        TextButton(onClick = { folders.add(entry) }) {
                            Text(stringResource(R.string.action_open))
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
                        }, enabled = (connected || library.isOffline(entry.effectiveId)) && runningFolderJob == null) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.drive_play))
                        }
                        if (library.isOffline(entry.effectiveId)) {
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
                                error = context.getString(R.string.drive_folder_cancelled)
                                throw e
                            } catch (_: Exception) {
                                error = context.getString(R.string.drive_folder_download_failed)
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

    toRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text(stringResource(R.string.drive_remove_offline)) },
            text = { Text(entry.name) },
            confirmButton = {
                TextButton(onClick = {
                    toRemove = null
                    scope.launch {
                        library.removeOffline(entry.effectiveId)
                        changed[entry.effectiveId] = false
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
