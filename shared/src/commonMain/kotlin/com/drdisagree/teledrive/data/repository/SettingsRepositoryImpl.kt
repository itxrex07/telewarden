package com.drdisagree.teledrive.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.drdisagree.teledrive.core.crypto.CredentialCipher
import kotlin.io.encoding.Base64
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.data.local.preferences.PreferenceKeys
import com.drdisagree.teledrive.domain.model.AppLanguage
import com.drdisagree.teledrive.domain.model.DriveSyncMode
import com.drdisagree.teledrive.domain.model.TelegramChatType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val credentialCipher: CredentialCipher
) : SettingsRepository {

    override val preferences: Flow<UserPreferences> = dataStore.data.map { it.toUserPreferences() }

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        dataStore.edit { mutable ->
            val updated = transform(mutable.toUserPreferences())
            mutable.write(updated)
        }
    }

    override suspend fun hasStoredTelegramCredentials(): Boolean {
        val data = dataStore.data.first()
        return data[PreferenceKeys.API_ID_ENCRYPTED] != null &&
                data[PreferenceKeys.API_HASH_ENCRYPTED] != null
    }

    override suspend fun getTelegramCredentials(): TelegramCredentials? {
        val data = dataStore.data.first()
        val apiIdEnc = data[PreferenceKeys.API_ID_ENCRYPTED] ?: return null
        val apiHashEnc = data[PreferenceKeys.API_HASH_ENCRYPTED] ?: return null
        return runCatching {
            TelegramCredentials(
                apiId = credentialCipher.decrypt(Base64.decode(apiIdEnc))
                    .decodeToString()
                    .toInt(),
                apiHash = credentialCipher.decrypt(Base64.decode(apiHashEnc)).decodeToString()
            )
        }.getOrNull()
    }

    override suspend fun setTelegramCredentials(credentials: TelegramCredentials) {
        val apiIdEnc = Base64.encode(credentialCipher.encrypt(credentials.apiId.toString().encodeToByteArray()))
        val apiHashEnc = Base64.encode(credentialCipher.encrypt(credentials.apiHash.encodeToByteArray()))
        dataStore.edit {
            it[PreferenceKeys.API_ID_ENCRYPTED] = apiIdEnc
            it[PreferenceKeys.API_HASH_ENCRYPTED] = apiHashEnc
        }
    }

    override suspend fun clearTelegramCredentials() {
        dataStore.edit {
            it.remove(PreferenceKeys.API_ID_ENCRYPTED)
            it.remove(PreferenceKeys.API_HASH_ENCRYPTED)
        }
    }

    override suspend fun getCloudSecret(): String? {
        val data = dataStore.data.first()
        val enc = data[PreferenceKeys.CLOUD_AUTH_TOKEN_ENC] ?: return null
        return runCatching {
            credentialCipher.decrypt(Base64.decode(enc)).decodeToString()
        }.getOrNull()
    }

    override suspend fun setCloudSecret(secret: String) {
        val enc = Base64.encode(credentialCipher.encrypt(secret.encodeToByteArray()))
        dataStore.edit {
            it[PreferenceKeys.CLOUD_AUTH_TOKEN_ENC] = enc
        }
    }

    override suspend fun clearCloudSecret() {
        dataStore.edit {
            it.remove(PreferenceKeys.CLOUD_AUTH_TOKEN_ENC)
        }
    }

    private fun Preferences.toUserPreferences(): UserPreferences {
        val defaults = UserPreferences()
        return UserPreferences(
            onboardingComplete = this[PreferenceKeys.ONBOARDING_COMPLETE]
                ?: defaults.onboardingComplete,
            storageChatId = this[PreferenceKeys.STORAGE_CHAT_ID],
            autoBackupEnabled = this[PreferenceKeys.AUTO_BACKUP_ENABLED]
                ?: defaults.autoBackupEnabled,
            instantBackupEnabled = this[PreferenceKeys.INSTANT_BACKUP_ENABLED]
                ?: defaults.instantBackupEnabled,
            backupFolders = this[PreferenceKeys.BACKUP_FOLDERS] ?: defaults.backupFolders,
            backupWifiOnly = this[PreferenceKeys.BACKUP_WIFI_ONLY] ?: defaults.backupWifiOnly,
            downloadDirectory = this[PreferenceKeys.DOWNLOAD_DIRECTORY],
            backupChargingOnly = this[PreferenceKeys.BACKUP_CHARGING_ONLY]
                ?: defaults.backupChargingOnly,
            backupIntervalHours = this[PreferenceKeys.BACKUP_INTERVAL_HOURS]
                ?: defaults.backupIntervalHours,
            backupMaxFileSizeMb = this[PreferenceKeys.BACKUP_MAX_FILE_SIZE_MB]
                ?: defaults.backupMaxFileSizeMb,
            maxCacheSizeMb = this[PreferenceKeys.MAX_CACHE_SIZE_MB] ?: defaults.maxCacheSizeMb,
            trashAutoClearDays = this[PreferenceKeys.TRASH_AUTO_CLEAR_DAYS]
                ?: defaults.trashAutoClearDays,
            blockScreenCapture = this[PreferenceKeys.BLOCK_SCREEN_CAPTURE]
                ?: defaults.blockScreenCapture,
            appLockEnabled = this[PreferenceKeys.APP_LOCK_ENABLED] ?: defaults.appLockEnabled,
            autoLockTimeoutMinutes = this[PreferenceKeys.AUTO_LOCK_TIMEOUT_MINUTES]
                ?: defaults.autoLockTimeoutMinutes,
            encryptFiles = this[PreferenceKeys.ENCRYPT_FILES] ?: defaults.encryptFiles,
            deleteAfterUpload = this[PreferenceKeys.DELETE_AFTER_UPLOAD]
                ?: defaults.deleteAfterUpload,
            encryptThumbnails = this[PreferenceKeys.ENCRYPT_THUMBNAILS]
                ?: defaults.encryptThumbnails,
            folderOwnershipRepaired = this[PreferenceKeys.FOLDER_OWNERSHIP_REPAIRED]
                ?: defaults.folderOwnershipRepaired,
            keyBackupCreated = this[PreferenceKeys.KEY_BACKUP_CREATED]
                ?: defaults.keyBackupCreated,
            theme = enumOrDefault(this[PreferenceKeys.THEME], defaults.theme),
            language = AppLanguage.fromCode(this[PreferenceKeys.LANGUAGE]),
            dynamicColor = this[PreferenceKeys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            viewMode = enumOrDefault(this[PreferenceKeys.VIEW_MODE], defaults.viewMode),
            galleryViewMode = enumOrDefault(
                this[PreferenceKeys.GALLERY_VIEW_MODE],
                enumOrDefault(this[PreferenceKeys.VIEW_MODE], defaults.galleryViewMode)
            ),
            galleryGridSize = this[PreferenceKeys.GALLERY_GRID_SIZE]
                ?: this[PreferenceKeys.GRID_SIZE]
                ?: defaults.galleryGridSize,
            gridSize = this[PreferenceKeys.GRID_SIZE] ?: defaults.gridSize,
            albumGridSize = this[PreferenceKeys.ALBUM_GRID_SIZE] ?: defaults.albumGridSize,
            layoutDensity = enumOrDefault(
                this[PreferenceKeys.LAYOUT_DENSITY],
                defaults.layoutDensity
            ),
            showHiddenFiles = this[PreferenceKeys.SHOW_HIDDEN_FILES] ?: defaults.showHiddenFiles,
            showArchivedFiles = this[PreferenceKeys.SHOW_ARCHIVED_FILES]
                ?: defaults.showArchivedFiles,
            showRecentFiles = this[PreferenceKeys.SHOW_RECENT_FILES] ?: defaults.showRecentFiles,
            linkPreviews = this[PreferenceKeys.LINK_PREVIEWS] ?: defaults.linkPreviews,
            updateCheckEnabled = this[PreferenceKeys.UPDATE_CHECK_ENABLED]
                ?: defaults.updateCheckEnabled,
            lastUpdateCheckAt = this[PreferenceKeys.LAST_UPDATE_CHECK_AT]
                ?: defaults.lastUpdateCheckAt,
            notifiedUpdateVersion = this[PreferenceKeys.NOTIFIED_UPDATE_VERSION]
                ?: defaults.notifiedUpdateVersion,
            skippedUpdateVersion = this[PreferenceKeys.SKIPPED_UPDATE_VERSION]
                ?: defaults.skippedUpdateVersion,
            proxyEnabled = this[PreferenceKeys.PROXY_ENABLED] ?: defaults.proxyEnabled,
            activeProxyId = this[PreferenceKeys.ACTIVE_PROXY_ID] ?: defaults.activeProxyId,
            textPreviewScale = this[PreferenceKeys.TEXT_PREVIEW_SCALE]
                ?: defaults.textPreviewScale,
            sortField = enumOrDefault(this[PreferenceKeys.SORT_FIELD], defaults.sortField),
            sortDirection = enumOrDefault(
                this[PreferenceKeys.SORT_DIRECTION],
                defaults.sortDirection
            ),
            gallerySortField = enumOrDefault(
                this[PreferenceKeys.GALLERY_SORT_FIELD],
                defaults.gallerySortField
            ),
            gallerySortDirection = enumOrDefault(
                this[PreferenceKeys.GALLERY_SORT_DIRECTION],
                defaults.gallerySortDirection
            ),
            backgroundPlayback = this[PreferenceKeys.BACKGROUND_PLAYBACK]
                ?: defaults.backgroundPlayback,
            streamBeforeDownload = this[PreferenceKeys.STREAM_BEFORE_DOWNLOAD]
                ?: defaults.streamBeforeDownload,
            preferredAudioLanguage = this[PreferenceKeys.PREFERRED_AUDIO_LANGUAGE]
                ?: defaults.preferredAudioLanguage,
            preferredSubtitleLanguage = this[PreferenceKeys.PREFERRED_SUBTITLE_LANGUAGE]
                ?: defaults.preferredSubtitleLanguage,
            backupNotifications = this[PreferenceKeys.BACKUP_NOTIFICATIONS]
                ?: defaults.backupNotifications,
            failureNotifications = this[PreferenceKeys.FAILURE_NOTIFICATIONS]
                ?: defaults.failureNotifications,
            transferConcurrency = this[PreferenceKeys.TRANSFER_CONCURRENCY]
                ?: defaults.transferConcurrency,
            transferRetryCount = this[PreferenceKeys.TRANSFER_RETRY_COUNT]
                ?: defaults.transferRetryCount,
            allowMeteredTransfers = this[PreferenceKeys.ALLOW_METERED_TRANSFERS]
                ?: defaults.allowMeteredTransfers,
            debugLogging = this[PreferenceKeys.DEBUG_LOGGING] ?: defaults.debugLogging,
            channelSyncModes = parseEnumMap(this[PreferenceKeys.CHANNEL_SYNC_MODES]),
            linkedChatTypes = parseEnumMap(this[PreferenceKeys.LINKED_CHAT_TYPES]),
            cloudUsername = this[PreferenceKeys.CLOUD_USERNAME] ?: defaults.cloudUsername,
            cloudServerUrl = this[PreferenceKeys.CLOUD_SERVER_URL] ?: defaults.cloudServerUrl,
            cloudLastSyncedAt = this[PreferenceKeys.CLOUD_LAST_SYNCED_AT]
                ?: defaults.cloudLastSyncedAt,
            cloudAutoSync = this[PreferenceKeys.CLOUD_AUTO_SYNC] ?: defaults.cloudAutoSync
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(prefs: UserPreferences) {
        this[PreferenceKeys.ONBOARDING_COMPLETE] = prefs.onboardingComplete
        prefs.storageChatId?.let { this[PreferenceKeys.STORAGE_CHAT_ID] = it }
            ?: remove(PreferenceKeys.STORAGE_CHAT_ID)
        this[PreferenceKeys.AUTO_BACKUP_ENABLED] = prefs.autoBackupEnabled
        this[PreferenceKeys.INSTANT_BACKUP_ENABLED] = prefs.instantBackupEnabled
        this[PreferenceKeys.BACKUP_FOLDERS] = prefs.backupFolders
        this[PreferenceKeys.BACKUP_WIFI_ONLY] = prefs.backupWifiOnly
        prefs.downloadDirectory?.let { this[PreferenceKeys.DOWNLOAD_DIRECTORY] = it }
            ?: remove(PreferenceKeys.DOWNLOAD_DIRECTORY)
        this[PreferenceKeys.BACKUP_CHARGING_ONLY] = prefs.backupChargingOnly
        this[PreferenceKeys.BACKUP_INTERVAL_HOURS] = prefs.backupIntervalHours
        this[PreferenceKeys.BACKUP_MAX_FILE_SIZE_MB] = prefs.backupMaxFileSizeMb
        this[PreferenceKeys.MAX_CACHE_SIZE_MB] = prefs.maxCacheSizeMb
        this[PreferenceKeys.TRASH_AUTO_CLEAR_DAYS] = prefs.trashAutoClearDays
        this[PreferenceKeys.BLOCK_SCREEN_CAPTURE] = prefs.blockScreenCapture
        this[PreferenceKeys.APP_LOCK_ENABLED] = prefs.appLockEnabled
        this[PreferenceKeys.AUTO_LOCK_TIMEOUT_MINUTES] = prefs.autoLockTimeoutMinutes
        this[PreferenceKeys.ENCRYPT_FILES] = prefs.encryptFiles
        this[PreferenceKeys.DELETE_AFTER_UPLOAD] = prefs.deleteAfterUpload
        this[PreferenceKeys.ENCRYPT_THUMBNAILS] = prefs.encryptThumbnails
        this[PreferenceKeys.KEY_BACKUP_CREATED] = prefs.keyBackupCreated
        this[PreferenceKeys.FOLDER_OWNERSHIP_REPAIRED] = prefs.folderOwnershipRepaired
        this[PreferenceKeys.THEME] = prefs.theme.name
        this[PreferenceKeys.LANGUAGE] = prefs.language.code
        this[PreferenceKeys.DYNAMIC_COLOR] = prefs.dynamicColor
        this[PreferenceKeys.VIEW_MODE] = prefs.viewMode.name
        this[PreferenceKeys.GALLERY_VIEW_MODE] = prefs.galleryViewMode.name
        this[PreferenceKeys.GALLERY_GRID_SIZE] = prefs.galleryGridSize
        this[PreferenceKeys.GRID_SIZE] = prefs.gridSize
        this[PreferenceKeys.ALBUM_GRID_SIZE] = prefs.albumGridSize
        this[PreferenceKeys.LAYOUT_DENSITY] = prefs.layoutDensity.name
        this[PreferenceKeys.SHOW_HIDDEN_FILES] = prefs.showHiddenFiles
        this[PreferenceKeys.SHOW_ARCHIVED_FILES] = prefs.showArchivedFiles
        this[PreferenceKeys.SHOW_RECENT_FILES] = prefs.showRecentFiles
        this[PreferenceKeys.LINK_PREVIEWS] = prefs.linkPreviews
        this[PreferenceKeys.UPDATE_CHECK_ENABLED] = prefs.updateCheckEnabled
        this[PreferenceKeys.LAST_UPDATE_CHECK_AT] = prefs.lastUpdateCheckAt
        this[PreferenceKeys.NOTIFIED_UPDATE_VERSION] = prefs.notifiedUpdateVersion
        this[PreferenceKeys.SKIPPED_UPDATE_VERSION] = prefs.skippedUpdateVersion
        this[PreferenceKeys.PROXY_ENABLED] = prefs.proxyEnabled
        this[PreferenceKeys.ACTIVE_PROXY_ID] = prefs.activeProxyId
        this[PreferenceKeys.TEXT_PREVIEW_SCALE] = prefs.textPreviewScale
        this[PreferenceKeys.SORT_FIELD] = prefs.sortField.name
        this[PreferenceKeys.SORT_DIRECTION] = prefs.sortDirection.name
        this[PreferenceKeys.GALLERY_SORT_FIELD] = prefs.gallerySortField.name
        this[PreferenceKeys.GALLERY_SORT_DIRECTION] = prefs.gallerySortDirection.name
        this[PreferenceKeys.BACKGROUND_PLAYBACK] = prefs.backgroundPlayback
        this[PreferenceKeys.STREAM_BEFORE_DOWNLOAD] = prefs.streamBeforeDownload
        this[PreferenceKeys.PREFERRED_AUDIO_LANGUAGE] = prefs.preferredAudioLanguage
        this[PreferenceKeys.PREFERRED_SUBTITLE_LANGUAGE] = prefs.preferredSubtitleLanguage
        this[PreferenceKeys.BACKUP_NOTIFICATIONS] = prefs.backupNotifications
        this[PreferenceKeys.FAILURE_NOTIFICATIONS] = prefs.failureNotifications
        this[PreferenceKeys.TRANSFER_CONCURRENCY] = prefs.transferConcurrency
        this[PreferenceKeys.TRANSFER_RETRY_COUNT] = prefs.transferRetryCount
        this[PreferenceKeys.ALLOW_METERED_TRANSFERS] = prefs.allowMeteredTransfers
        this[PreferenceKeys.DEBUG_LOGGING] = prefs.debugLogging
        this[PreferenceKeys.CHANNEL_SYNC_MODES] = encodeEnumMap(prefs.channelSyncModes)
        this[PreferenceKeys.LINKED_CHAT_TYPES] = encodeEnumMap(prefs.linkedChatTypes)
        this[PreferenceKeys.CLOUD_USERNAME] = prefs.cloudUsername
        this[PreferenceKeys.CLOUD_SERVER_URL] = prefs.cloudServerUrl
        this[PreferenceKeys.CLOUD_LAST_SYNCED_AT] = prefs.cloudLastSyncedAt
        this[PreferenceKeys.CLOUD_AUTO_SYNC] = prefs.cloudAutoSync
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private inline fun <reified T : Enum<T>> parseEnumMap(entries: Set<String>?): Map<Long, T> {
        if (entries.isNullOrEmpty()) return emptyMap()
        return entries.mapNotNull { entry ->
            val parts = entry.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val chatId = parts[0].toLongOrNull() ?: return@mapNotNull null
            val value = runCatching { enumValueOf<T>(parts[1]) }.getOrNull() ?: return@mapNotNull null
            chatId to value
        }.toMap()
    }

    private fun <T : Enum<T>> encodeEnumMap(map: Map<Long, T>): Set<String> =
        map.entries.mapTo(mutableSetOf()) { (chatId, value) -> "$chatId:${value.name}" }
}
