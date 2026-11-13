package com.drdisagree.teledrive.domain.model

data class DriveChannel(
    val chatId: Long,
    val title: String,
    val fileCount: Int,
    val remoteFileCount: Int,
    val storedBytes: Long,
    val backupFolders: Set<String>,
    val photoPath: String?,
    val isActive: Boolean,
    val isIndexed: Boolean,
    val lastOpenedAt: Long,
    val syncMode: DriveSyncMode = DriveSyncMode.FULL,
    val isLinkedChat: Boolean = false,
    val chatType: TelegramChatType = TelegramChatType.DRIVE_CHANNEL
) {

    /** The part the user chose, empty for a drive that was never named. */
    val label: String
        get() = if (isLinkedChat) title else title.removePrefix(DRIVE_PREFIX).trim()

    val displayName: String
        get() = if (isLinkedChat) title.ifEmpty { DRIVE_PREFIX } else label.ifEmpty { DRIVE_PREFIX }

    companion object {
        const val DRIVE_PREFIX = "TeleDrive"
    }
}
