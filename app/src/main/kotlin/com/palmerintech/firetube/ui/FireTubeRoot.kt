package com.palmerintech.firetube.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.StringRes
import com.palmerintech.firetube.R
import com.palmerintech.firetube.ui.player.rememberPlayerSheetState
import com.palmerintech.firetube.ui.player.CollapseWhenNoSong
import com.palmerintech.firetube.ui.player.rememberArtworkColor
import com.palmerintech.firetube.ui.components.FlameIcon
import com.palmerintech.firetube.ui.components.focusRing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.extractor.NewPipeStreamSource
import com.palmerintech.firetube.extractor.SearchResult
import com.palmerintech.firetube.ui.components.LocalNowPlaying
import com.palmerintech.firetube.ui.components.LocalTrackMenu
import com.palmerintech.firetube.ui.components.NowPlaying
import com.palmerintech.firetube.ui.components.TrackMenuController
import com.palmerintech.firetube.ui.components.TrackMenuHost
import com.palmerintech.firetube.ui.home.HomeScreen
import com.palmerintech.firetube.ui.library.LibraryScreen
import com.palmerintech.firetube.ui.library.PlaylistScreen
import com.palmerintech.firetube.ui.library.RemotePlaylistScreen
import com.palmerintech.firetube.ui.library.StatsScreen
import com.palmerintech.firetube.ui.library.TrackList
import com.palmerintech.firetube.ui.library.TrackListScreen
import com.palmerintech.firetube.ui.player.MiniPlayer
import com.palmerintech.firetube.ui.player.NowPlayingScreen
import com.palmerintech.firetube.ui.search.SearchScreen
import com.palmerintech.firetube.ui.settings.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object HomeRoute
@Serializable data object SearchRoute
@Serializable data object LibraryRoute
@Serializable data object SettingsRoute
@Serializable data class PlaylistRoute(val id: String)
@Serializable data class RemotePlaylistRoute(val url: String)
@Serializable data class TrackListRoute(val list: String)
@Serializable data object LocalAudioRoute
@Serializable data object SoundCloudWidgetRoute
@Serializable data object StatsRoute

private data class Tab(val route: Any, val routeClass: KClass<*>, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(SearchRoute, SearchRoute::class, R.string.nav_search, Icons.Filled.Search, Icons.Filled.Search),
    Tab(LibraryRoute, LibraryRoute::class, R.string.library_title, Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
)

@UnstableApi
@Composable
fun FireTubeRoot(container: AppContainer, pendingLink: String?, onLinkHandled: (String) -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val menu = remember { TrackMenuController() }
    val scope = rememberCoroutineScope()
    val playerState by container.player.state.collectAsState()
    // Now Playing's position; survives rotation through playerOpen.
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberPlayerSheetState(initiallyExpanded = playerOpen)
    LaunchedEffect(sheet.expanded) { playerOpen = sheet.expanded }
    val backStack by nav.currentBackStackEntryAsState()
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val resources = LocalResources.current
    LaunchedEffect(playerState.localPlaybackError, playerState.localCastBlocked) {
        if (playerState.localPlaybackError) showMessage(resources.getString(R.string.local_audio_unavailable))
        if (playerState.localCastBlocked) showMessage(resources.getString(R.string.local_audio_cast))
    }

    // Links shared to / opened with FireTube.
    // Links shared to / opened with FireTube, voice searches and the widget. The link is cleared
    // only after it's handled: clearing it first would change this effect's key and cancel the work.
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        try {
            if (link == MainActivity.OPEN_PLAYER) {
                // From the widget. After a cold start the queue takes a moment to come back.
                val hasSong = withTimeoutOrNull(5_000) { container.player.state.first { it.current != null } }
                if (hasSong != null) sheet.expand()
            } else if (link.startsWith(MainActivity.SEARCH_PREFIX)) {
                val query = link.removePrefix(MainActivity.SEARCH_PREFIX)
                val top = attempt { container.source.search(query).items }.orEmpty()
                    .filterIsInstance<SearchResult.TrackResult>().firstOrNull()?.track
                if (top == null) showMessage(resources.getString(R.string.link_search_not_found, query)) else container.player.play(listOf(top))
            } else if (link.contains("list=")) {
                nav.navigate(RemotePlaylistRoute(link))
            } else {
                val id = NewPipeStreamSource.videoId(link)
                val track = id?.let { attempt { container.resolver.resolve(it).track } }
                when {
                    id == null -> showMessage(resources.getString(R.string.link_not_youtube))
                    track == null -> showMessage(resources.getString(R.string.link_open_failed))
                    else -> {
                        container.player.play(listOf(track))
                        // Open once the player has the song, so Now Playing slides up out of the
                        // mini player rather than appearing already open.
                        val arrived = withTimeoutOrNull(5_000) { container.player.state.first { it.current?.id == track.id } }
                        if (arrived != null) sheet.expand()
                    }
                }
            }
        } finally {
            onLinkHandled(link)
        }
    }

    val nowPlaying = NowPlaying(playerState.current?.id, playerState.isPlaying)
    // When Now Playing closes, put D-pad focus back on the mini player it was opened from
    // (closing drops focus; this is a no-op in touch mode).
    val miniPlayerFocus = remember { FocusRequester() }
    var playerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sheet.expanded) {
        if (playerWasOpen && !sheet.expanded) runCatching { miniPlayerFocus.requestFocus() }
        playerWasOpen = sheet.expanded
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = rememberArtworkColor(playerState.current?.thumbnailUrl, dark)
    val flingSpeed = with(LocalDensity.current) { 800.dp.toPx() }
    // The mini player card sits 4dp below its measured top; Now Playing's copy adds the same padding.
    val cardInset = with(LocalDensity.current) { 4.dp.toPx() }
    CollapseWhenNoSong(sheet, hasSong = playerState.current != null, connected = playerState.connected)
    CompositionLocalProvider(LocalTrackMenu provides menu, LocalNowPlaying provides nowPlaying) {
        // Until the mini player is laid out, Now Playing slides the screen's full height.
        Box(Modifier.fillMaxSize().onSizeChanged { sheet.fallbackTravel = it.height.toFloat() }) {
            val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
            val isSelected = { tab: Tab -> backStack?.destination?.hierarchy()?.any { it.hasRoute(tab.routeClass) } == true }
            val select = { tab: Tab ->
                nav.navigate(tab.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            // While Now Playing covers the screen, D-pad focus must not wander to what's behind it.
            Row(
                Modifier.fillMaxSize()
                    .focusProperties { onEnter = { if (sheet.expanded) cancelFocusChange() } }
                    .focusGroup(),
            ) {
            if (wide) {
                NavigationRail(header = { FlameIcon(Modifier.padding(vertical = 12.dp)) }) {
                    Spacer(Modifier.weight(1f))
                    tabs.forEach { tab ->
                        val selected = isSelected(tab)
                        NavigationRailItem(
                            selected = selected,
                            onClick = { select(tab) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                            label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.focusRing(RoundedCornerShape(16.dp)),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    // Without a NavigationBar below it (wide layout), the mini player must clear the system nav bar itself.
                    Column(if (wide) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier) {
                        MiniPlayer(
                            playerState, container.player::togglePlay, container.player::next, container.player::previousTrack,
                            onOpen = { sheet.expand() },
                            modifier = Modifier.focusRequester(miniPlayerFocus)
                                // Now Playing's top edge starts at the mini player's top.
                                .onGloballyPositioned {
                                    val at = it.positionInRoot()
                                    sheet.travel = at.y - cardInset
                                    sheet.miniLeft = at.x
                                    sheet.miniWidth = it.size.width.toFloat()
                                },
                            onDrag = sheet::dragBy,
                            onDragEnd = { velocity -> sheet.settle(velocity, flingSpeed) },
                            accent = accent,
                        )
                        if (!wide) {
                            NavigationBar {
                                tabs.forEach { tab ->
                                    val selected = isSelected(tab)
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { select(tab) },
                                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                                        label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                // Screens end above the mini player / nav bar rather than scrolling under them, so a
                // D-pad-focused item is never hidden behind the player.
                val content = PaddingValues(0.dp)
                NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                    composable<HomeRoute> {
                        HomeScreen(container, onOpenPlaylist = { nav.navigate(PlaylistRoute(it)) }, onOpenSettings = { nav.navigate(SettingsRoute) }, content, showMessage)
                    }
                    composable<SearchRoute> {
                        SearchScreen(container, onOpenRemotePlaylist = { nav.navigate(RemotePlaylistRoute(it)) }, content, autoFocus = true)
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(
                            container,
                            onOpenPlaylist = { nav.navigate(PlaylistRoute(it)) },
                            onOpenList = { nav.navigate(TrackListRoute(it.name)) },
                            onOpenRemotePlaylist = { nav.navigate(RemotePlaylistRoute(it)) },
                            onOpenStats = { nav.navigate(StatsRoute) },
                            onOpenLocal = { nav.navigate(LocalAudioRoute) },
                            onOpenSoundCloud = { nav.navigate(SoundCloudWidgetRoute) },
                            content,
                        )
                    }
                    composable<LocalAudioRoute> { com.palmerintech.firetube.ui.library.LocalAudioScreen(container, { nav.popBackStack() }, content) }
                    composable<SoundCloudWidgetRoute> { com.palmerintech.firetube.ui.library.SoundCloudWidgetScreen({ nav.popBackStack() }, content) }
                    composable<SettingsRoute> { SettingsScreen(container, onBack = { nav.popBackStack() }, showMessage, content) }
                    composable<PlaylistRoute> { entry ->
                        PlaylistScreen(container, entry.toRoute<PlaylistRoute>().id, onBack = { nav.popBackStack() }, content)
                    }
                    composable<RemotePlaylistRoute> { entry ->
                        RemotePlaylistScreen(
                            container, entry.toRoute<RemotePlaylistRoute>().url,
                            onBack = { nav.popBackStack() },
                            onSaved = { id ->
                                showMessage(resources.getString(R.string.remote_playlist_saved))
                                nav.navigate(PlaylistRoute(id)) { popUpTo<RemotePlaylistRoute> { inclusive = true } }
                            },
                            content,
                        )
                    }
                    composable<TrackListRoute> { entry ->
                        TrackListScreen(container, TrackList.valueOf(entry.toRoute<TrackListRoute>().list), onBack = { nav.popBackStack() }, content)
                    }
                    composable<StatsRoute> { StatsScreen(container, onBack = { nav.popBackStack() }, content) }
                }
            }

            }
            if (playerState.current != null && sheet.isVisible) {
                // Dims the app behind Now Playing as it comes up.
                Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = 0.5f * sheet.progress) })
                NowPlayingScreen(container, sheet, onClose = sheet::collapse, accent = accent)
            }
        }
        TrackMenuHost(container, menu, showMessage)
    }
}

/** Width at which navigation moves from a bottom bar to a side rail (tablets, landscape, TV). */
private const val WIDE_SCREEN_DP = 600

private fun androidx.navigation.NavDestination.hierarchy() = generateSequence(this) { it.parent }

/** Like runCatching, but never swallows coroutine cancellation. */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
