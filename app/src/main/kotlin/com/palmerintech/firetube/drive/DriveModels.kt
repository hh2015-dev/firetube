package com.palmerintech.firetube.drive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Drive's IDs, parents and Unicode names are kept verbatim; never derive identity from a filename. */
@Serializable
data class DriveEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val size: String? = null,
    val modifiedTime: String? = null,
    val capabilities: DriveCapabilities? = null,
    val shortcutDetails: DriveShortcut? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
    val isAudio: Boolean get() = DriveAudioTypes.isAudio(name, mimeType)
    val canDownload: Boolean get() = capabilities?.canDownload != false

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}

@Serializable data class DriveCapabilities(val canDownload: Boolean? = null)
@Serializable data class DriveShortcut(val targetId: String? = null, val targetMimeType: String? = null)

@Serializable
data class DrivePage(
    val files: List<DriveEntry> = emptyList(),
    val nextPageToken: String? = null,
    val incompleteSearch: Boolean = false,
)

object DriveAudioTypes {
    private val suffixes = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus",
        "wav", "wma", "aif", "aiff", "alac", "amr", "mp2", "mid", "midi", "ape")

    /** Only real audio blobs or unknown-MIME files with audio extensions, never audio video. */
    fun isAudio(name: String, mime: String): Boolean =
        when {
            mime.startsWith("audio/") -> true
            mime in setOf("application/ogg", "application/x-flac") -> true
            mime != "application/octet-stream" && mime != "binary/octet-stream" -> false
            else -> name.substringAfterLast('.', "").lowercase() in suffixes
        }

    fun visible(files: List<DriveEntry>): List<DriveEntry> =
        files.filter { it.isFolder || it.isAudio }
            .sortedWith(compareByDescending<DriveEntry> { it.isFolder }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
