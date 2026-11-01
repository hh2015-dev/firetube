package com.palmerintech.firetube.drive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFolderPlannerTest {
    @Test fun includesNestedAudioButNeverVideoOrDocuments() = runTest {
        val names = mutableListOf<String>()
        val folders = mapOf(
            "top" to listOf(
                DriveEntry("sub", "العربية / עברית", DriveEntry.FOLDER_MIME),
                DriveEntry("a1", "صوت.mp3", "audio/mpeg", size = "1200"),
                DriveEntry("video", "clip.mp4", "video/mp4"),
                DriveEntry("doc", "notes.pdf", "application/pdf"),
            ),
            "sub" to listOf(
                DriveEntry("a2", "ελληνικά.ogg", "audio/ogg", size = "350"),
            ),
        )
        val plan = DriveFolderPlanner.scan("top", "account") { id ->
            names += id
            folders[id].orEmpty()
        }
        assertEquals(listOf("top", "sub"), names)
        assertEquals(listOf("a1", "a2"), plan.audio.map { it.effectiveId })
        assertEquals("العربية / עברית", folders.getValue("top")[0].name)
        assertEquals(2, plan.foldersVisited)
        assertEquals(1550L, plan.totalKnownBytes)
        assertEquals(0, plan.unknownSizes)
        assertEquals(0, plan.blockedFiles)
    }

    @Test fun deduplicatesShortcutCyclesAudioTargetsAndReportsRestrictions() = runTest {
        val folders = mapOf(
            "root-folder" to listOf(
                DriveEntry("real-sub", "Subfolder", DriveEntry.FOLDER_MIME),
                DriveEntry("alias-sub", "Subfolder again", DriveEntry.SHORTCUT_MIME,
                    shortcutDetails = DriveShortcut("real-sub", DriveEntry.FOLDER_MIME)),
                DriveEntry("blocked", "no-download.mp3", "audio/mpeg",
                    capabilities = DriveCapabilities(canDownload = false)),
                DriveEntry("s", "שיר", DriveEntry.SHORTCUT_MIME,
                    shortcutDetails = DriveShortcut("real-audio", "audio/mpeg")),
            ),
            "real-sub" to listOf(
                DriveEntry("back", "Back to top", DriveEntry.SHORTCUT_MIME,
                    shortcutDetails = DriveShortcut("root-folder", DriveEntry.FOLDER_MIME)),
                DriveEntry("real-audio", "שיר", "audio/mpeg"),
                DriveEntry("unknown", "No Size", "audio/mpeg"),
            ),
        )
        val plan = DriveFolderPlanner.scan("root-folder", "account") { folders[it].orEmpty() }
        assertEquals(2, plan.foldersVisited)
        assertEquals(2, plan.audio.size)
        assertEquals(listOf("real-audio", "unknown"), plan.audio.map { it.effectiveId })
        assertEquals(1, plan.blockedFiles)
        assertEquals(2, plan.unknownSizes)
    }

    @Test fun refusesVirtualRootMassDownload() = runTest {
        val error = runCatching {
            DriveFolderPlanner.scan(DriveLibrary.SHARED_DRIVES, "account") { emptyList() }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
