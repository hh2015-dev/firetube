package com.palmerintech.firetube.ui.library

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.R
import com.palmerintech.firetube.local.LocalAudio
import com.palmerintech.firetube.local.LocalAudioState
import com.palmerintech.firetube.local.LocalAudioViewModel
import com.palmerintech.firetube.ui.appViewModel
import com.palmerintech.firetube.ui.components.LocalTrackMenu
import com.palmerintech.firetube.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@androidx.media3.common.util.UnstableApi
fun LocalAudioScreen(container: AppContainer, onBack: () -> Unit, contentPadding: PaddingValues) {
    val vm = appViewModel { LocalAudioViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val menu = LocalTrackMenu.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    Column {
        TopAppBar(title = { Text(stringResource(R.string.local_audio_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            actions = { IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.local_audio_refresh)) } })
        when (val current = state) {
            LocalAudioState.Permission -> Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.local_audio_permission))
                Button(onClick = { permission.launch(LocalAudio.permission()) }) { Text(stringResource(R.string.local_audio_allow)) }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) {
                    Text(stringResource(R.string.local_audio_settings))
                }
            }
            LocalAudioState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            LocalAudioState.Failed -> Text(stringResource(R.string.local_audio_failed), Modifier.padding(16.dp))
            is LocalAudioState.Ready -> LazyColumn(contentPadding = contentPadding) {
                if (current.tracks.isEmpty()) item { Text(stringResource(R.string.local_audio_empty), Modifier.padding(16.dp)) }
                itemsIndexed(current.tracks, key = { _, track -> track.id }) { index, track ->
                    LocalAudioRow(track, onPlay = { container.player.play(current.tracks, index) }, onMore = { menu.open(track) })
                }
            }
        }
    }
}

@androidx.media3.common.util.UnstableApi
@Composable
internal fun LocalAudioRow(track: com.palmerintech.firetube.extractor.Track, onPlay: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TrackRow(track, onClick = onPlay, onMore = onMore)
        Text(listOfNotNull(track.fileName, track.album).joinToString(" • "),
            Modifier.padding(start = 80.dp, end = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall.merge(TextStyle(textDirection = TextDirection.Content)))
    }
}
