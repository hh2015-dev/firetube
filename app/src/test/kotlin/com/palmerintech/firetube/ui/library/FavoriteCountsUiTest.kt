package com.palmerintech.firetube.ui.library

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.data.db.PlaylistWithCount
import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FavoriteCountsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishCountersReactToFolderSongAndPlaylistChangesWithoutReloading() {
        val playlists = MutableStateFlow(listOf(PlaylistWithCount("a", "Music", 0L, 90, null)))
        val folderIds = MutableStateFlow(emptySet<String>())
        val songs = MutableStateFlow(emptyList<Track>())
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides Configuration().apply { setLocale(Locale.ENGLISH) }) {
                val lists by playlists.collectAsState()
                val folders by folderIds.collectAsState()
                val direct by songs.collectAsState()
                Text(favoriteCountsText(summarizeFavorites(lists, folders, direct)))
            }
        }
        compose.onNodeWithText("Folders: 0 · Songs: 0").assertIsDisplayed()
        compose.runOnIdle { folderIds.value = setOf("a") }
        compose.onNodeWithText("Folders: 1 · Songs: 0").assertIsDisplayed()
        compose.runOnIdle { songs.value = listOf(Track("video000001", "Song", "Artist", 120L, null)) }
        compose.onNodeWithText("Folders: 1 · Songs: 1").assertIsDisplayed()
        compose.runOnIdle { folderIds.value = emptySet() }
        compose.onNodeWithText("Folders: 0 · Songs: 1").assertIsDisplayed()
        compose.runOnIdle { folderIds.value = setOf("a"); playlists.value = emptyList() }
        compose.onNodeWithText("Folders: 0 · Songs: 1").assertIsDisplayed()
        compose.runOnIdle { songs.value = emptyList() }
        compose.onNodeWithText("Folders: 0 · Songs: 0").assertIsDisplayed()
    }

    @Test fun arabicLabelsKeepFolderAndDirectSongCountsSeparate() {
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides Configuration().apply { setLocale(Locale.forLanguageTag("ar")) }) {
                Text(favoriteCountsText(FavoriteCounts(2, 3)))
            }
        }
        compose.onNodeWithText("المجلدات: 2 · الأغاني: 3").assertIsDisplayed()
    }
}
