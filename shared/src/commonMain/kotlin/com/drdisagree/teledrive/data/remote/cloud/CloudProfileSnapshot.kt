package com.drdisagree.teledrive.data.remote.cloud

import com.drdisagree.teledrive.domain.model.AppLanguage
import com.drdisagree.teledrive.domain.model.AppTheme
import com.drdisagree.teledrive.domain.model.DriveSyncMode
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.LayoutDensity
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.model.TelegramChatType
import com.drdisagree.teledrive.domain.model.ViewMode
import kotlinx.serialization.Serializable

@Serializable
data class CloudProfileSnapshot(
    val version: Int = CURRENT_VERSION,
    val username: String,
    val updatedAt: Long,
    val telegramApiId: Int? = null,
    val telegramApiHash: String? = null,
    val tdlibDatabaseZipBase64: String? = null,
    val tdlibDatabaseKeyBase64: String? = null,
    val activeStorageChatId: Long? = null,
    val drives: List<CloudDriveEntry> = emptyList(),
    val channelSyncModes: Map<Long, DriveSyncMode> = emptyMap(),
    val linkedChatTypes: Map<Long, TelegramChatType> = emptyMap(),
    val settings: CloudSettingsSnapshot? = null
) {
    companion object {
        const val CURRENT_VERSION = 3
    }
}

@Serializable
data class CloudSettingsSnapshot(
    val autoBackupEnabled: Boolean = false,
    val instantBackupEnabled: Boolean = true,
    val backupFolders: Set<String> = emptySet(),
    val backupWifiOnly: Boolean = false,
    val backupChargingOnly: Boolean = false,
    val backupIntervalHours: Int = 24,
    val backupMaxFileSizeMb: Int = 0,
    val maxCacheSizeMb: Int = 1024,
    val trashAutoClearDays: Int = 30,
    val blockScreenCapture: Boolean = false,
    val autoLockTimeoutMinutes: Int = 5,
    val encryptFiles: Boolean = false,
    val deleteAfterUpload: Boolean = false,
    val encryptThumbnails: Boolean = false,
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
    val backupNotifications: Boolean = true,
    val failureNotifications: Boolean = true,
    val transferConcurrency: Int = 2,
    val transferRetryCount: Int = 3,
    val allowMeteredTransfers: Boolean = true
)

@Serializable
data class CloudDriveEntry(
    val chatId: Long,
    val title: String,
    val backupFolders: String = "",
    val defaultsSeeded: Boolean = false,
    val remoteFileCount: Int = 0,
    val addedAt: Long,
    val lastOpenedAt: Long
)

@Serializable
data class CloudEnvelope(
    val formatVersion: Int = 1,
    val usernameHash: String,
    val kdfSaltBase64: String,
    val kdfIterations: Int = DEFAULT_ITERATIONS,
    val ciphertextBase64: String,
    val updatedAt: Long
) {
    companion object {
        const val DEFAULT_ITERATIONS = 120_000
    }
}
