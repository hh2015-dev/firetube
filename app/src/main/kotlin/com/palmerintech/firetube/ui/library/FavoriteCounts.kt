package com.palmerintech.firetube.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.palmerintech.firetube.R
import com.palmerintech.firetube.data.db.PlaylistWithCount
import com.palmerintech.firetube.extractor.Track

internal data class FavoriteCounts(val folders: Int, val songs: Int)

/** Use the same visible folders for the overview counter and the Favorites screen.
 * Deleted/missing playlist ids must not leave a phantom folder in the counter.
 */
internal fun pinnedFavoriteFolders(
    playlists: List<PlaylistWithCount>,
    favoriteFolderIds: Set<String>,
): List<PlaylistWithCount> =
    playlists.filter { it.id in favoriteFolderIds }.distinctBy { it.id }

/** Direct favorites only: a folder's trackCount never contributes to songs. */
internal fun summarizeFavorites(
    playlists: List<PlaylistWithCount>,
    favoriteFolderIds: Set<String>,
    directFavorites: List<Track>,
): FavoriteCounts = FavoriteCounts(
    folders = pinnedFavoriteFolders(playlists, favoriteFolderIds).size,
    songs = directFavorites.size,
)

@Composable
internal fun favoriteCountsText(counts: FavoriteCounts): String {
    // Only this summary is translated into Arabic; do not register a partially translated
    // app-wide Arabic locale or change the user's existing language selection.
    val arabic = LocalConfiguration.current.locales[0]?.language == "ar"
    return stringResource(
        if (arabic) R.string.library_favorites_counts_ar else R.string.library_favorites_counts,
        counts.folders,
        counts.songs,
    )
}
