package com.palmerintech.firetube.extractor

import java.util.UUID

/** Internal keys are opaque. A provider must only receive [externalId], never [id]. */
data class TrackIdentity(val id: String, val sourceId: String, val externalId: String) {
    companion object {
        const val YOUTUBE = "youtube"
        const val LOCAL = "local"
        const val DOCUMENT = "document"
        const val DRIVE = "drive"
        private val generatedKey = Regex("src_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun isGeneratedId(id: String): Boolean = generatedKey.matches(id)

        /** Deliberately retain legacy keys: Room FKs, downloads, chapters and caches use them. */
        fun youtube(videoId: String) = TrackIdentity(videoId, YOUTUBE, videoId)

        /** Stable, filesystem-safe keys; names and external identifiers are never normalized. */
        fun forSource(sourceId: String, externalId: String): TrackIdentity {
            require(sourceId.isNotBlank() && externalId.isNotEmpty())
            if (sourceId == YOUTUBE) return youtube(externalId)
            val key = UUID.nameUUIDFromBytes("${sourceId.length}:$sourceId$externalId".toByteArray(Charsets.UTF_8))
            return TrackIdentity("src_$key", sourceId, externalId)
        }
    }
}
