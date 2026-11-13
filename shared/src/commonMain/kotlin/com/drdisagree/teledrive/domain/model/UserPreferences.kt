package com.drdisagree.teledrive.domain.model

data class UserPreferences(
    val onboardingComplete: Boolean = false,
    val storageChatId: Long? = null,

    val autoBackupEnabled: Boolean = false,
    val instantBackupEnabled: Boolean = true,
    val backupFolders: Set<String> = emptySet(),
    val backupWifiOnly: Boolean = false,
    val downloadDirectory: String? = null,
    val backupChargingOnly: Boolean = false,
    val backupIntervalHours: Int = 24,
    val backupMaxFileSizeMb: Int = 0,

    val maxCacheSizeMb: Int = 1024,
    val trashAutoClearDays: Int = 30,

    val appLockEnabled: Boolean = false,
    val blockScreenCapture: Boolean = false,
    val autoLockTimeoutMinutes: Int = 5,
    val encryptFiles: Boolean = false,
    val deleteAfterUpload: Boolean = false,
    val encryptThumbnails: Boolean = false,
    val keyBackupCreated: Boolean = false,
    val folderOwnershipRepaired: Boolean = false,

    val theme: AppTheme = AppTheme.DARK,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val dynamicColor: Boolean = false,
    val viewMode: ViewMode = ViewMode.GRID,
    val gridSize: Int = 3,
    val galleryViewMode: ViewMode = ViewMode.GRID,
    val galleryGridSize: Int = 3,
    val albumGridSize: Int = 3,
    val layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    val showHiddenFiles: Boolean = true,
    val showArchivedFiles: Boolean = true,
    val showRecentFiles: Boolean = true,
    val linkPreviews: Boolean = true,
    val textPreviewScale: Float = 1f,

    val sortField: FileSortField = FileSortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val gallerySortField: FileSortField = FileSortField.DATE_MODIFIED,
    val gallerySortDirection: SortDirection = SortDirection.DESCENDING,

    val backgroundPlayback: Boolean = false,
    val streamBeforeDownload: Boolean = true,
    val preferredAudioLanguage: String = "",
    val preferredSubtitleLanguage: String = "",

    val backupNotifications: Boolean = true,
    val failureNotifications: Boolean = true,

    val transferConcurrency: Int = 2,
    val transferRetryCount: Int = 3,
    val allowMeteredTransfers: Boolean = true,
    val debugLogging: Boolean = false,

    val updateCheckEnabled: Boolean = true,
    val lastUpdateCheckAt: Long = 0L,
    val notifiedUpdateVersion: String = "",
    val skippedUpdateVersion: String = "",

    val proxyEnabled: Boolean = false,
    val activeProxyId: String = "",

    val channelSyncModes: Map<Long, DriveSyncMode> = emptyMap(),
    val linkedChatTypes: Map<Long, TelegramChatType> = emptyMap(),

    val cloudUsername: String = "",
    val cloudServerUrl: String = "",
    val cloudSessionToken: String = "",
    val cloudLastSyncedAt: Long = 0L,
    val cloudAutoSync: Boolean = true
) {
    val isCloudConnected: Boolean
        get() = cloudUsername.isNotBlank()

    fun syncModeFor(chatId: Long?): DriveSyncMode {
        if (chatId == null) return DriveSyncMode.FULL
        return channelSyncModes[chatId]
            ?: if (chatId in linkedChatTypes) DriveSyncMode.SAFE else DriveSyncMode.FULL
    }

    fun isLinkedChat(chatId: Long?): Boolean = chatId != null && chatId in linkedChatTypes

    fun chatTypeFor(chatId: Long?): TelegramChatType =
        (if (chatId != null) linkedChatTypes[chatId] else null) ?: TelegramChatType.DRIVE_CHANNEL
}
