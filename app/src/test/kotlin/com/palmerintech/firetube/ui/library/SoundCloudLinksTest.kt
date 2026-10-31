package com.palmerintech.firetube.ui.library

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SoundCloudLinksTest {
    @Test fun acceptsPublicTrackAndPlaylist() {
        assertEquals("https://soundcloud.com/artist/track",
            SoundCloudLinks.normalize(" https://soundcloud.com/artist/track?utm_source=x#t=30s "))
        assertEquals("https://soundcloud.com/user/sets/playlist",
            SoundCloudLinks.normalize("https://soundcloud.com/user/sets/playlist"))
    }

    @Test fun rejectsUntrustedHostsAndSchemes() {
        for (url in listOf(
            "javascript:alert(1)",
            "http://soundcloud.com/user/song",
            "https://soundcloud.com.evil.example/user/song",
            "https://evil.example/?u=soundcloud.com",
            "https://soundcloud.com:8443/user/song",
            "https://soundcloud.com/",
            "https://soundcloud.com/user",
            "https://user:password@soundcloud.com/user/song"
        )) assertNull("Should reject: $url", SoundCloudLinks.normalize(url))
    }

    @Test fun widgetIsOfficialHttpsEmbedWithEscapedLink() {
        val link = "https://soundcloud.com/user/song"
        val url = Uri.parse(SoundCloudLinks.widgetUrl(link))
        assertEquals("https", url.scheme)
        assertEquals("w.soundcloud.com", url.host)
        assertEquals(link, url.getQueryParameter("url"))
        assertEquals("false", url.getQueryParameter("auto_play"))
    }
}
