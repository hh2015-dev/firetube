package com.palmerintech.firetube.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.R
import com.palmerintech.firetube.extractor.DownloadVariant
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import com.palmerintech.firetube.ui.artistLabel
import kotlinx.coroutines.launch

/** Opens the per-track actions sheet from anywhere in the UI. */
class TrackMenuController {
    var target by mutableStateOf<MenuTarget?>(null)

    fun open(track: Track, removeLabel: String? = null, onRemove: (() -> Unit)? = null) {
        target = MenuTarget(track, removeLabel, onRemove)
    }

    data class MenuTarget(val track: Track, val removeLabel: String?, val onRemove: (() -> Unit)?)
}

val LocalTrackMenu = staticCompositionLocalOf { TrackMenuController() }

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenuHost(container: AppContainer, controller: TrackMenuController, onShowMessage: (String) -> Unit) {
    val target = controller.target ?: return
    val track = target.track
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val isFavorite by container.library.isFavorite(track.id).collectAsState(false)
    val downloads by container.downloads.state.collectAsState()
    val download = downloads[track.id]
    var addToPlaylist by rememberSaveable { mutableStateOf(false) }
    var choosingDownload by remember(track.id) { mutableStateOf(false) }

    val dismiss = { controller.target = null }

    if (addToPlaylist) {
        AddToPlaylistDialog(
            container = container,
            tracks = listOf(track),
            onDone = { name ->
                addToPlaylist = false
                dismiss()
                if (name != null) onShowMessage(resources.getString(R.string.menu_added_to_playlist, name))
            },
        )
        return
    }

    if (choosingDownload) {
        DownloadQualityDialog(container, track, onCancel = { choosingDownload = false }) { variant ->
            choosingDownload = false
            container.downloads.download(track, variant.id)
            dismiss()
            onShowMessage(resources.getString(R.string.menu_downloading))
        }
        return
    }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.thumbnailUrl, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(artistLabel(track), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MenuItem(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.menu_play_next)) {
                container.player.playNext(track); dismiss(); onShowMessage(resources.getString(R.string.menu_playing_next))
            }
            MenuItem(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.menu_add_to_queue)) {
                container.player.enqueue(listOf(track)); dismiss(); onShowMessage(resources.getString(R.string.menu_added_to_queue))
            }
            MenuItem(Icons.Default.Radio, stringResource(R.string.menu_start_radio)) {
                // A one-song queue; autoplay fills it with related songs.
                container.player.play(listOf(track)); dismiss()
            }
            MenuItem(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(if (isFavorite) R.string.action_remove_favorite else R.string.action_add_favorite)) {
                scope.launch { container.library.toggleFavorite(track) }; dismiss()
            }
            MenuItem(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.action_add_to_playlist)) { addToPlaylist = true }
            when {
                track.sourceId != TrackIdentity.YOUTUBE -> Unit
                // A live stream has no end to download up to.
                track.isLive && download == null -> Unit
                download == null -> MenuItem(Icons.Default.Download, stringResource(R.string.menu_download)) {
                    choosingDownload = true
                }
                else -> MenuItem(Icons.Default.DownloadDone, stringResource(if (download.completed) R.string.menu_remove_download else R.string.menu_cancel_download)) {
                    container.downloads.remove(track.id); dismiss()
                }
            }
            track.url?.let { shareUrl ->
                MenuItem(Icons.Default.Share, stringResource(R.string.menu_share)) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareUrl)
                    context.startActivity(Intent.createChooser(send, null))
                    dismiss()
                }
            }
            if (target.removeLabel != null && target.onRemove != null) {
                MenuItem(Icons.Default.Delete, target.removeLabel) { target.onRemove.invoke(); dismiss() }
            }
        }
    }
}

/** Query before download; show only streams the provider actually exposes. */
@UnstableApi
@Composable
private fun DownloadQualityDialog(container: AppContainer, track: Track, onCancel: () -> Unit, onChoose: (DownloadVariant) -> Unit) {
    var options by remember(track.id) { mutableStateOf<List<DownloadVariant>?>(null) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }
    var selected by remember(track.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(track.id) {
        try {
            container.resolver.remember(track)
            val found = container.resolver.downloadVariants(track.id)
            options = found
            selected = found.maxByOrNull { it.bitrateKbps }?.id
        } catch (e: Exception) {
            error = e.localizedMessage ?: e.javaClass.simpleName
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.download_choose_quality)) },
        text = {
            when {
                error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                options == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.download_loading_qualities))
                }
                options!!.isEmpty() -> Text(stringResource(R.string.download_no_qualities))
                else -> LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items(options!!, key = { it.id }) { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = option.id }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == option.id, onClick = { selected = option.id })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "${if (option.bitrateKbps > 0) "${option.bitrateKbps} kbps" else "Unknown bitrate"} · ${option.mimeType?.substringAfter('/') ?: "Audio"}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    option.sizeBytes?.let {
                                        val mb = "%.1f".format(java.util.Locale.ROOT, it / 1_000_000.0)
                                        if (option.sizeEstimated) "~$mb MB (estimate)" else "$mb MB"
                                    } ?: "Size unknown",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null && options != null, onClick = {
                options?.firstOrNull { it.id == selected }?.let(onChoose)
            }) { Text(stringResource(R.string.menu_download)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Pick an existing playlist or create a new one. [onDone] gets the playlist name, or null if cancelled. */
@UnstableApi
@Composable
fun AddToPlaylistDialog(container: AppContainer, tracks: List<Track>, onDone: (String?) -> Unit) {
    val playlists by container.library.playlists.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    if (creating) {
        AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text(stringResource(R.string.library_new_playlist)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.playlist_name)) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { container.library.createPlaylist(name, tracks); onDone(name.trim()) }
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.action_add_to_playlist)) },
        text = {
            LazyColumn {
                item { MenuItem(Icons.Default.Add, stringResource(R.string.library_new_playlist)) { creating = true } }
                items(playlists, key = { it.id }) { p ->
                    MenuItem(Icons.AutoMirrored.Filled.QueueMusic, p.name) {
                        scope.launch { container.library.addToPlaylist(p.id, tracks); onDone(p.name) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
