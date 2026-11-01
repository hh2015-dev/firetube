package com.palmerintech.firetube.data

import java.nio.charset.StandardCharsets
import java.util.UUID

/** One stable local playlist per Drive folder and authorized account. Never stores a token. */
object DriveFolderPlaylistIdentity {
    fun forFolder(accountNamespace: String, folderId: String): String {
        require(accountNamespace.isNotBlank() && folderId.isNotBlank())
        return "drive-folder-" + UUID.nameUUIDFromBytes(
            ("firetube-drive-folder:v1\u0000" + accountNamespace + "\u0000" + folderId)
                .toByteArray(StandardCharsets.UTF_8)
        )
    }
}
