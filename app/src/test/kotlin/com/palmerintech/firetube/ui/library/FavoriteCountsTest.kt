package com.palmerintech.firetube.ui.library

import com.palmerintech.firetube.data.db.PlaylistWithCount
import com.palmerintech.firetube.extractor.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteCountsTest {
    private fun folder(id: String, songs: Int) = PlaylistWithCount(id, id, 0L, songs, null)
    private fun song(id: String) = Track(id, id, "Artist", 120L, null)

    @Test fun emptyFavoritesHaveTwoZeroCounters() {
        assertEquals(FavoriteCounts(0, 0), summarizeFavorites(emptyList(), emptySet(), emptyList()))
    }

    @Test fun favoriteFolderDoesNotAddItsContentsToDirectSongs() {
        assertEquals(FavoriteCounts(1, 0),
            summarizeFavorites(listOf(folder("a", 150)), setOf("a"), emptyList()))
    }

    @Test fun directlyFavoritedSongsDoNotCreateFolders() {
        assertEquals(FavoriteCounts(0, 2),
            summarizeFavorites(emptyList(), emptySet(), listOf(song("video000001"), song("video000002"))))
    }

    @Test fun mixedFavoritesExcludeUnpinnedAndMissingFolders() {
        val playlists = listOf(folder("a", 80), folder("b", 20), folder("not-pinned", 500))
        assertEquals(FavoriteCounts(2, 1),
            summarizeFavorites(playlists, setOf("a", "b", "deleted"), listOf(song("video000001"))))
    }

    @Test fun emptyFavoriteFolderStillCountsAndDuplicateIdsCountOnce() {
        val playlists = listOf(folder("a", 0), folder("a", 0))
        assertEquals(FavoriteCounts(1, 0), summarizeFavorites(playlists, setOf("a"), emptyList()))
        assertEquals(listOf(playlists.first()), pinnedFavoriteFolders(playlists, setOf("a")))
    }

    @Test fun songFavoritedDirectlyAndPresentInFolderCountsOnlyItsDirectEntry() {
        assertEquals(FavoriteCounts(1, 1),
            summarizeFavorites(listOf(folder("a", 10)), setOf("a"), listOf(song("video000001"))))
    }

    @Test fun removingFolderOrDeletingPlaylistPreservesDirectSongCount() {
        val playlists = listOf(folder("a", 10))
        val songs = listOf(song("video000001"))
        assertEquals(FavoriteCounts(0, 1), summarizeFavorites(playlists, emptySet(), songs))
        assertEquals(FavoriteCounts(0, 1), summarizeFavorites(emptyList(), setOf("a"), songs))
    }
}
