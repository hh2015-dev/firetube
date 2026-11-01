package com.palmerintech.firetube.drive

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * A metadata-only, explicit-user-action preview. This does NOT download or play anything.
 * Drive file IDs, not names, deduplicate shortcuts; visited folders prevent shortcut cycles.
 */
data class DriveFolderPlan(
    val accountNamespace: String,
    val rootFolderId: String,
    val audio: List<DriveEntry>,
    val foldersVisited: Int,
    val blockedFiles: Int,
    val totalKnownBytes: Long,
    val unknownSizes: Int,
)

object DriveFolderPlanner {
    private const val MAX_ITEMS = 20_000

    suspend fun scan(
        rootFolderId: String,
        accountNamespace: String,
        children: suspend (folderId: String) -> List<DriveEntry>,
    ): DriveFolderPlan {
        require(rootFolderId.isNotBlank() && !rootFolderId.startsWith("virtual:")) {
            "Select a real Google Drive folder"
        }
        val pending = ArrayDeque<String>()
        val seenFolders = mutableSetOf<String>()
        val seenAudio = mutableSetOf<String>()
        val chosen = mutableListOf<DriveEntry>()
        var blocked = 0
        var knownBytes = 0L
        var unknownSizes = 0
        pending.add(rootFolderId)

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folderId = pending.removeFirst()
            if (!seenFolders.add(folderId)) continue
            if (seenFolders.size > MAX_ITEMS) throw IOException("Folder contains too many subfolders")
            for (entry in children(folderId)) {
                if (entry.isFolder && !entry.effectiveId.startsWith("virtual:")) {
                    if (entry.effectiveId !in seenFolders) pending.add(entry.effectiveId)
                } else if (entry.isAudio && seenAudio.add(entry.effectiveId)) {
                    if (seenAudio.size > MAX_ITEMS) throw IOException("Folder contains too many audio files")
                    if (!entry.canDownload) {
                        blocked++
                        continue
                    }
                    chosen += entry
                    val size = entry.size?.toLongOrNull()?.takeIf { it >= 0 }
                    if (size == null) unknownSizes++
                    else knownBytes = if (Long.MAX_VALUE - knownBytes < size) Long.MAX_VALUE else knownBytes + size
                }
            }
        }
        return DriveFolderPlan(accountNamespace, rootFolderId, chosen, seenFolders.size,
            blocked, knownBytes, unknownSizes)
    }
}

data class DriveFolderProgress(
    val finished: Int,
    val total: Int,
    val downloaded: Int,
    val alreadySaved: Int,
    val failed: Int,
    val lastFile: String?,
)

data class DriveFolderResult(
    val downloaded: Int,
    val alreadySaved: Int,
    val failedFiles: List<String>,
)
