package com.palmerintech.firetube.ui.library

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.palmerintech.firetube.R

/**
 * SoundCloud's official embedded player, not a stream extractor or an API client.
 * It is separate from Media3: no background-playback, lock-screen or queue claims.
 */
internal object SoundCloudLinks {
    fun normalize(input: String): String? {
        val uri = Uri.parse(input.trim())
        if (uri.scheme != "https" || uri.host !in setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com")) return null
        if (uri.port != -1 || uri.encodedUserInfo != null || uri.pathSegments.size < 2) return null
        return uri.buildUpon().clearQuery().fragment(null).build().toString()
    }

    fun widgetUrl(publicUrl: String): String =
        Uri.Builder().scheme("https").authority("w.soundcloud.com").appendPath("player")
            .appendQueryParameter("url", publicUrl)
            .appendQueryParameter("show_artwork", "true")
            .appendQueryParameter("auto_play", "false")
            .appendQueryParameter("sharing", "false")
            .build().toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudWidgetScreen(onBack: () -> Unit, contentPadding: PaddingValues) {
    var input by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column {
        TopAppBar(
            title = { Text(stringResource(R.string.soundcloud_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            }
        )
        Text(stringResource(R.string.soundcloud_note),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = input, onValueChange = { input = it; invalid = false },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text(stringResource(R.string.soundcloud_link)) },
            singleLine = true,
        )
        if (invalid) Text(stringResource(R.string.soundcloud_invalid),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            selected = SoundCloudLinks.normalize(input)
            invalid = selected == null
        }, modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.soundcloud_open))
        }
        val playbackUrl = selected?.let(SoundCloudLinks::widgetUrl)
        if (playbackUrl != null) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true // Required by the official SoundCloud player
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val u = request?.url ?: return true
                                if (!request.isForMainFrame) return false
                                return u.scheme != "https" || u.host != "w.soundcloud.com"
                            }
                        }
                        loadUrl(playbackUrl)
                    }
                },
                update = { if (it.url != playbackUrl) it.loadUrl(playbackUrl) },
                onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() },
                modifier = Modifier.fillMaxWidth().height(280.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}
