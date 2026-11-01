package com.palmerintech.firetube.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFolderPlaylistIdentityTest {
    @Test fun sameDriveFolderUnderSameAccountHasStablePlaylistIdentity() {
        val a = DriveFolderPlaylistIdentity.forFolder("account-a", "folder-123")
        assertEquals(a, DriveFolderPlaylistIdentity.forFolder("account-a", "folder-123"))
        assertTrue(a.startsWith("drive-folder-"))
    }

    @Test fun separateAccountsAndFoldersNeverSharePlaylistIdentity() {
        val a = DriveFolderPlaylistIdentity.forFolder("account-a", "folder-123")
        assertNotEquals(a, DriveFolderPlaylistIdentity.forFolder("account-b", "folder-123"))
        assertNotEquals(a, DriveFolderPlaylistIdentity.forFolder("account-a", "folder-456"))
    }

    @Test fun supportsUnicodeFolderIdsWithoutPuttingNamesIntoPlaylistIds() {
        val id = DriveFolderPlaylistIdentity.forFolder("account", "קובץ-ملف-αρχείο")
        assertTrue(id.startsWith("drive-folder-"))
        assertTrue(id.all { it.code < 128 })
    }
}
