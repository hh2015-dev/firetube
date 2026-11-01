package com.palmerintech.firetube.drive

import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveModelsTest {
    @Test fun catalogListsFoldersAndAudioButNeverTurnsVideosIntoAudio() {
        val items = listOf(
            DriveEntry("v", "video.mp4", "video/mp4"),
            DriveEntry("f", "עברית / العربية / Ελληνικά", DriveEntry.FOLDER_MIME),
            DriveEntry("a", "مزمور.mp3", "audio/mpeg"),
            DriveEntry("x", "notes.pdf", "application/pdf"),
            DriveEntry("o", "מזמור.ogg", "application/octet-stream"),
        )
        val listed = DriveAudioTypes.visible(items)
        assertEquals(setOf("f", "a", "o"), listed.map { it.id }.toSet())
        assertEquals("עברית / العربية / Ελληνικά", listed.first().name)
        assertFalse(DriveAudioTypes.isAudio("song.mp4", "video/mp4"))
        assertFalse(DriveAudioTypes.isAudio("song.mp3", "application/pdf"))
        assertTrue(DriveAudioTypes.isAudio("audio.ogg", "application/octet-stream"))
        assertTrue(DriveAudioTypes.isAudio("name", "audio/flac"))
    }

    @Test fun shortcutsRetainVisibleNamesButPointToTheirActualTargets() {
        val folder = DriveEntry("shortcut-1", "ملفاتي", DriveEntry.SHORTCUT_MIME,
            shortcutDetails = DriveShortcut("real-folder-id", DriveEntry.FOLDER_MIME))
        val audio = DriveEntry("shortcut-2", "שיר בעברית", DriveEntry.SHORTCUT_MIME,
            shortcutDetails = DriveShortcut("real-audio-id", "audio/mpeg"))
        assertTrue(folder.isFolder)
        assertTrue(audio.isAudio)
        assertEquals("real-folder-id", folder.effectiveId)
        assertEquals("real-audio-id", audio.effectiveId)
        assertEquals(listOf(folder, audio), DriveAudioTypes.visible(listOf(audio, folder)))
    }

    @Test fun driveIdentityIsStableAndNeverConflatedWithYouTubeOrLocal() {
        val id = "abc_123-drive"
        val drive = TrackIdentity.forSource(TrackIdentity.DRIVE, id)
        assertEquals(id, drive.externalId)
        assertEquals(TrackIdentity.DRIVE, drive.sourceId)
        assertEquals(drive, TrackIdentity.forSource(TrackIdentity.DRIVE, id))
        assertTrue(drive.id != TrackIdentity.youtube(id).id)
        assertTrue(drive.id != TrackIdentity.forSource(TrackIdentity.DOCUMENT, id).id)
    }

    @Test fun driveSourceDoesNotResolveUnknownIdsOrUseAccessTokensInUrl() = runTest {
        val source = DriveAudioSource()
        val resolved = source.resolve("abc_123")
        assertEquals("https://www.googleapis.com/drive/v3/files/abc_123?alt=media&supportsAllDrives=true", resolved.url)
        assertFalse(resolved.url.contains("Bearer"))
    }

    @Test fun readOnlyDownloadRestrictionsAreRespected() {
        val forbidden = DriveEntry("blocked", "file.mp3", "audio/mpeg",
            capabilities = DriveCapabilities(canDownload = false))
        assertTrue(forbidden.isAudio)
        assertFalse(forbidden.canDownload)
    }
}
