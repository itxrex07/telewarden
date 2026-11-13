package com.drdisagree.teledrive.domain.model

enum class DriveSyncMode {
    /** Full TeleDrive metadata sync (writes folder JSON, index snapshots, and manifest captions). */
    FULL,

    /** Allows uploads and downloads without writing internal JSON files or modifying existing message captions. */
    SAFE,

    /** Strictly browse, preview, stream, and download; blocks uploads, edits, and remote deletions. */
    READ_ONLY
}

enum class TelegramChatType {
    DRIVE_CHANNEL,
    CHANNEL,
    FORUM_GROUP,
    GROUP,
    SAVED_MESSAGES
}
