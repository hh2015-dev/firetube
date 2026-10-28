package com.palmerintech.firetube.local

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import com.palmerintech.firetube.ui.library.LocalAudioRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "ldrtl")
@androidx.media3.common.util.UnstableApi
class LocalAudioDisplayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mixedDirectionUnicodeRemainsExactInDisplayedTitleAndFilename() {
        val title=LocalAudioTest.TITLE
        val name=LocalAudioTest.FILE_NAME
        val album=LocalAudioTest.ALBUM
        val track=Track("id",title,LocalAudioTest.ARTIST,3,null,sourceId=TrackIdentity.LOCAL,externalId="content://media/42",album=album,fileName=name)
        var plays=0
        compose.setContent { MaterialTheme { LocalAudioRow(track,{ plays++ },{}) } }
        compose.onNodeWithText(title).assertExists().performClick()
        compose.onNodeWithText("$name • $album").assertExists()
        assertEquals(1,plays)
    }
}
