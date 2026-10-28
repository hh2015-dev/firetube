package com.palmerintech.firetube.extractor

/** Playback contract independent of search, playlists, UI, and Media3. */
interface AudioSource {
    val sourceId: String
    /** [trackId] is the provider external identifier. URLs remain transient, never identity keys. */
    suspend fun resolve(trackId: String, preferLowBitrate: Boolean = false): ResolvedStream
}

/** Routes an internal identity to its provider. Only YouTube is registered in the application. */
class AudioSourceRegistry(sources: List<AudioSource>) {
    private val byId = sources.associateBy { it.sourceId }

    init {
        require(byId.size == sources.size) { "Duplicate audio source" }
    }

    suspend fun resolve(identity: TrackIdentity, preferLowBitrate: Boolean = false): ResolvedStream {
        val source = byId[identity.sourceId]
            ?: throw ExtractionException("Unsupported source: ${identity.sourceId}").apply { permanent = true }
        val resolved = source.resolve(identity.externalId, preferLowBitrate)
        return resolved.copy(
            trackId = identity.id,
            related = resolved.related.map { track ->
                val ref = TrackIdentity.forSource(identity.sourceId, track.externalId)
                track.copy(id = ref.id, sourceId = ref.sourceId, externalId = ref.externalId)
            },
            track = resolved.track?.copy(id = identity.id, sourceId = identity.sourceId, externalId = identity.externalId),
        )
    }
}
