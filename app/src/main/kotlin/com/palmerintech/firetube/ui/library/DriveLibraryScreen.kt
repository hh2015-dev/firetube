package com.palmerintech.firetube.ui.library

import android.app.Activity
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
import kotlinx.coroutines.CancellationException
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

    suspend fun reload(folderId: String, online: Boolean) {
        loading = true
        error = null
        items = library.cached(folderId)
        if (online) {
            try {
                items = library.list(folderId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Could not refresh Google Drive. Showing saved folder information, if available."
            }
        }
        loading = false
    }

    LaunchedEffect(folder.id, connected, refresh) { reload(folder.id, connected) }

    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                access.acceptIntent(requireNotNull(result.data))
                connected = true
                refresh++
            } catch (_: Exception) {
                error = "Google Drive permission was not granted."
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
                    connected = true
                    refresh++
                }
            } catch (_: Exception) {
                error = "Google Drive connection failed. Check OAuth configuration and try again."
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
                        if (!entry.isFolder && library.isOffline(entry.id)) {
                            Text(stringResource(R.string.drive_available_offline),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (entry.isFolder) {
                        TextButton(onClick = { folders.add(entry) }) {
                            Text(stringResource(R.string.action_open))
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
                        }, enabled = connected || library.isOffline(entry.id)) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.drive_play))
                        }
                        if (library.isOffline(entry.id)) {
                            IconButton(onClick = { toRemove = entry }) {
                                Icon(Icons.Default.CloudDone, stringResource(R.string.drive_remove_offline))
                            }
                        } else if (working[entry.id] == true) {
                            CircularProgressIndicator(Modifier.padding(8.dp))
                        } else {
                            IconButton(onClick = {
                                scope.launch {
                                    working[entry.id] = true
                                    try {
                                        val track = container.driveAudio.track(entry)
                                        container.library.saveTracks(listOf(track))
                                        container.resolver.remember(track)
                                        library.downloadSelected(entry)
                                        changed[entry.id] = true
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        error = "Could not download the selected audio file."
                                    } finally {
                                        working.remove(entry.id)
                                    }
                                }
                            }, enabled = connected && entry.canDownload) {
                                Icon(Icons.Default.Download, stringResource(R.string.drive_download))
                            }
                        }
                    }
                }
            }
        }
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
                        library.removeOffline(entry.id)
                        changed[entry.id] = false
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
