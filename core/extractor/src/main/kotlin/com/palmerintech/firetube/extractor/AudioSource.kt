package com.palmerintech.firetube.extractor

/** Playback contract independent of search, playlists, UI, and Media3. */
interface AudioSource {
    val sourceId: String
    /** [trackId] is the provider external identifier. URLs remain transient, never identity keys. */
    suspend fun resolve(trackId: String, preferLowBitrate: Boolean = false): ResolvedStream
    /** No fabricated quality choices: providers opt in only when real streams are available. */
    suspend fun downloadVariants(trackId: String): List<DownloadVariant> = emptyList()
    suspend fun resolveDownloadVariant(trackId: String, variantId: String, forceRefresh: Boolean = false): ResolvedStream =
        throw ExtractionException("Offline quality not supported by $sourceId")

}

/** Routes an internal identity to its provider. Only YouTube is registered in the application. */
class AudioSourceRegistry(sources: List<AudioSource>) {
    private val byId = sources.associateBy { it.sourceId }

    init {
        require(byId.size == sources.size) { "Duplicate audio source" }
    }

    suspend fun downloadVariants(identity: TrackIdentity): List<DownloadVariant> =
        byId[identity.sourceId]?.downloadVariants(identity.externalId).orEmpty()

    suspend fun resolveDownloadVariant(identity: TrackIdentity, variantId: String, forceRefresh: Boolean = false): ResolvedStream {
        val source = byId[identity.sourceId]
            ?: throw ExtractionException("Unsupported source: ${identity.sourceId}")
        return remap(identity, source.resolveDownloadVariant(identity.externalId, variantId, forceRefresh))
    }

    suspend fun resolve(identity: TrackIdentity, preferLowBitrate: Boolean = false): ResolvedStream {
        val source = byId[identity.sourceId]
            ?: throw ExtractionException("Unsupported source: ${identity.sourceId}").apply { permanent = true }
        val resolved = source.resolve(identity.externalId, preferLowBitrate)
        return remap(identity, resolved)
    }

    private fun remap(identity: TrackIdentity, resolved: ResolvedStream): ResolvedStream = resolved.copy(
            trackId = identity.id,
            related = resolved.related.map { track ->
                val ref = TrackIdentity.forSource(identity.sourceId, track.externalId)
                track.copy(id = ref.id, sourceId = ref.sourceId, externalId = ref.externalId)
            },
            track = resolved.track?.copy(id = identity.id, sourceId = identity.sourceId, externalId = identity.externalId),
        )
}
