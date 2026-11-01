package com.palmerintech.firetube.drive

import com.palmerintech.firetube.extractor.AudioSource
import com.palmerintech.firetube.extractor.ExtractionException
import com.palmerintech.firetube.extractor.ResolvedStream
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity

/** File IDs are persistent; bearer tokens and Google download redirects never appear here. */
class DriveAudioSource : AudioSource {
    override val sourceId = TrackIdentity.DRIVE

    override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream {
        if (!Regex("[A-Za-z0-9_-]{3,256}").matches(trackId))
            throw ExtractionException("Invalid Google Drive file id")
        return ResolvedStream(
            TrackIdentity.forSource(sourceId, trackId).id,
            "https://www.googleapis.com/drive/v3/files/$trackId?alt=media&supportsAllDrives=true",
            null, 0, Long.MAX_VALUE,
        )
    }

    fun track(entry: DriveEntry): Track {
        require(entry.isAudio)
        val identity = TrackIdentity.forSource(sourceId, entry.effectiveId)
        return Track(identity.id, entry.name, "", 0L, null, sourceId = sourceId,
            externalId = entry.effectiveId, fileName = entry.name)
    }
}
