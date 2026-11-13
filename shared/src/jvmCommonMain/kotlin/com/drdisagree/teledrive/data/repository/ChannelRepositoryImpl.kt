package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.telegram.StorageChannel
import com.drdisagree.teledrive.core.telegram.TelegramAuthState
import com.drdisagree.teledrive.core.telegram.TelegramChatSummary
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.dao.StorageChannelDao
import com.drdisagree.teledrive.data.local.entity.StorageChannelEntity
import com.drdisagree.teledrive.domain.model.DriveChannel
import com.drdisagree.teledrive.domain.model.DriveSyncMode
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.ChannelRepository
import com.drdisagree.teledrive.domain.repository.ExclusionRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withTimeoutOrNull

/** Switching only points the app at another chat id; nothing is deleted or downloaded again. */
class ChannelRepositoryImpl(
    private val channelDao: StorageChannelDao,
    private val telegramClient: TelegramClient,
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val channelOwnership: ChannelOwnership,
    private val exclusionRepository: ExclusionRepository
) : ChannelRepository {

    override fun observeChannels(): Flow<List<DriveChannel>> = combine(
        channelDao.observeAll(),
        settingsRepository.preferences
    ) { channels, prefs ->
        channels.map { channel -> channel.toDomain(prefs) }
    }

    override suspend fun refresh(): AppResult<List<DriveChannel>> = runTelegram {
        val prefs = settingsRepository.preferences.first()
        if (telegramClient.authState.value != TelegramAuthState.Ready) {
            val allLocal = channelDao.all()
            return@runTelegram AppResult.Success(allLocal.map { it.toDomain(prefs) })
        }
        val remote = discoverChannels()
            ?: return@runTelegram AppResult.Failure(
                AppError.TelegramError(DISCOVERY_TIMEOUT_CODE, "Telegram did not respond")
            )
        val now = System.currentTimeMillis()
        for (channel in remote) {
            val known = channelDao.byId(channel.chatId)
            if (known == null) {
                channelDao.upsert(
                    StorageChannelEntity(
                        chatId = channel.chatId,
                        title = channel.title,
                        remoteFileCount = channel.documentCount,
                        addedAt = now,
                        lastOpenedAt = 0
                    )
                )
            } else {
                if (known.title != channel.title) {
                    channelDao.setTitle(channel.chatId, channel.title)
                }
                if (known.remoteFileCount != channel.documentCount) {
                    channelDao.setRemoteFileCount(channel.chatId, channel.documentCount)
                }
            }
            seedDefaultsOnce(channel.chatId)
            refreshPhoto(channel.chatId)
        }
        val activeId = prefs.storageChatId
        if (activeId != null && channelDao.byId(activeId)?.lastOpenedAt == 0L) {
            channelDao.touch(activeId, now)
        }
        val remoteIds = remote.map { it.chatId }.toSet()
        channelDao.all()
            .filter { it.chatId !in remoteIds && it.chatId != activeId }
            .forEach { stale ->
                if (prefs.isLinkedChat(stale.chatId)) {
                    refreshPhoto(stale.chatId)
                } else if (telegramClient.chatExists(stale.chatId) == false) {
                    SafeLog.w(TAG, "Dropping a drive no longer on the account #${stale.chatId}")
                    forget(stale.chatId)
                }
            }
        val latestPrefs = settingsRepository.preferences.first()
        AppResult.Success(channelDao.all().map { it.toDomain(latestPrefs) })
    }

    /**
     * Right after sign in TDLib has not loaded the chat list, so discovery retries until every
     * known drive is found.
     */
    private suspend fun discoverChannels(): List<StorageChannel>? {
        val prefs = settingsRepository.preferences.first()
        val allLocal = channelDao.all()
        val knownIds = allLocal
            .map { it.chatId }
            .filterNot { prefs.isLinkedChat(it) }

        var pass: List<StorageChannel> = emptyList()
        val attempts = if (knownIds.isEmpty()) 1 else 2
        withTimeoutOrNull(3_500L.milliseconds) {
            repeat(attempts) { attempt ->
                pass = runCatching { telegramClient.listStorageChannels(knownIds) }
                    .getOrDefault(pass)
                if (pass.isNotEmpty() && pass.map { it.chatId }.containsAll(knownIds)) {
                    return@withTimeoutOrNull
                }
                if (attempt < attempts - 1) delay(500L.milliseconds)
            }
        }
        return pass.ifEmpty {
            allLocal.map { entity ->
                StorageChannel(
                    chatId = entity.chatId,
                    title = entity.title,
                    photoPath = entity.photoPath,
                    documentCount = entity.remoteFileCount
                )
            }
        }
    }

    override suspend fun refreshKnown(): AppResult<Unit> = runTelegram {
        for (channel in channelDao.all()) {
            seedDefaultsOnce(channel.chatId)
            refreshPhoto(channel.chatId)
        }
        AppResult.Success(Unit)
    }

    override suspend fun activeDriveMissing(): Boolean {
        val prefs = settingsRepository.preferences.first()
        val activeId = prefs.storageChatId ?: return false
        return runCatching { telegramClient.chatExists(activeId) }.getOrNull() == false
    }

    override suspend fun pruneDeleted(): AppResult<Int> = runTelegram {
        if (telegramClient.authState.value != TelegramAuthState.Ready) {
            return@runTelegram AppResult.Success(0)
        }
        var removed = 0
        for (channel in channelDao.all()) {
            if (telegramClient.chatExists(channel.chatId) == false) {
                forget(channel.chatId)
                removed++
                SafeLog.d(TAG, "Dropped a drive whose channel is gone")
            }
        }
        val activeId = settingsRepository.preferences.first().storageChatId
        if (removed > 0 && activeId != null && channelDao.fileCount(activeId) == 0) {
            channelDao.touch(activeId, System.currentTimeMillis())
            seedDefaultsOnce(activeId)
            syncRepository.fullResync()
        }
        AppResult.Success(removed)
    }

    override suspend fun create(label: String): AppResult<DriveChannel> = runTelegram {
        val created = telegramClient.createStorageChannel(label)
        val now = System.currentTimeMillis()
        channelDao.upsert(
            StorageChannelEntity(
                chatId = created.chatId,
                title = created.title,
                addedAt = now,
                lastOpenedAt = 0
            )
        )
        settingsRepository.update { prefs ->
            prefs.copy(
                channelSyncModes = prefs.channelSyncModes + (created.chatId to DriveSyncMode.FULL)
            )
        }
        seedDefaultsOnce(created.chatId)
        SafeLog.d(TAG, "Registered a new drive channel")
        val latestPrefs = settingsRepository.preferences.first()
        AppResult.Success(channelDao.byId(created.chatId)!!.toDomain(latestPrefs))
    }

    override suspend fun listAvailableChats(): AppResult<List<TelegramChatSummary>> = runTelegram {
        val existingIds = channelDao.all().map { it.chatId }.toSet()
        val available = telegramClient.listLinkableChats()
            .filterNot { it.chatId in existingIds }
        AppResult.Success(available)
    }

    override suspend fun linkExistingChat(
        chat: TelegramChatSummary,
        syncMode: DriveSyncMode
    ): AppResult<DriveChannel> = runTelegram {
        val now = System.currentTimeMillis()
        channelDao.upsert(
            StorageChannelEntity(
                chatId = chat.chatId,
                title = chat.title,
                photoPath = chat.photoPath,
                remoteFileCount = 0,
                addedAt = now,
                lastOpenedAt = 0
            )
        )
        settingsRepository.update { prefs ->
            prefs.copy(
                linkedChatTypes = prefs.linkedChatTypes + (chat.chatId to chat.chatType),
                channelSyncModes = prefs.channelSyncModes + (chat.chatId to syncMode)
            )
        }
        seedDefaultsOnce(chat.chatId)
        refreshPhoto(chat.chatId)
        val latestPrefs = settingsRepository.preferences.first()
        AppResult.Success(channelDao.byId(chat.chatId)!!.toDomain(latestPrefs))
    }

    override suspend fun setSyncMode(chatId: Long, syncMode: DriveSyncMode) {
        settingsRepository.update { prefs ->
            prefs.copy(
                channelSyncModes = prefs.channelSyncModes + (chatId to syncMode)
            )
        }
    }

    override suspend fun unlinkLocal(chatId: Long): AppResult<Unit> {
        if (channelDao.all().size <= 1) {
            return AppResult.Failure(AppError.LastDriveRemaining)
        }
        forget(chatId)
        return AppResult.Success(Unit)
    }

    override suspend fun switchTo(chatId: Long, index: Boolean): AppResult<Unit> {
        val current = settingsRepository.preferences.first().storageChatId
        if (current == chatId) return AppResult.Success(Unit)

        val known = channelDao.byId(chatId) ?: return AppResult.Failure(AppError.NotFound)
        settingsRepository.update { it.copy(storageChatId = chatId) }
        channelDao.touch(chatId, System.currentTimeMillis())
        channelOwnership.claimUnowned(chatId)
        seedDefaultsOnce(chatId)

        if (!index) return AppResult.Success(Unit)

        if (telegramClient.authState.value != TelegramAuthState.Ready) {
            return AppResult.Success(Unit)
        }

        return if (channelDao.fileCount(chatId) == 0) {
            when (val result = syncRepository.fullResync()) {
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> AppResult.Failure(result.error)
            }
        } else {
            SafeLog.d(TAG, "Switched to an already indexed drive")
            AppResult.Success(Unit)
        }.also { if (known.lastOpenedAt == 0L) syncRepository.incrementalSync() }
    }

    override suspend fun rename(chatId: Long, label: String): AppResult<Unit> = runTelegram {
        val prefs = settingsRepository.preferences.first()
        if (prefs.isLinkedChat(chatId)) {
            channelDao.setTitle(chatId, label.trim())
            return@runTelegram AppResult.Success(Unit)
        }
        val title = telegramClient.renameStorageChannel(chatId, label)
        channelDao.setTitle(chatId, title)
        AppResult.Success(Unit)
    }

    override suspend fun deleteRemotely(chatId: Long): AppResult<Unit> {
        if (channelDao.all().size <= 1) {
            return AppResult.Failure(AppError.LastDriveRemaining)
        }
        val prefs = settingsRepository.preferences.first()
        if (prefs.isLinkedChat(chatId)) {
            forget(chatId)
            return AppResult.Success(Unit)
        }
        return deleteConfirmed(chatId)
    }

    private suspend fun deleteConfirmed(chatId: Long): AppResult<Unit> = runTelegram {
        telegramClient.deleteStorageChannel(chatId)
        forget(chatId)
        AppResult.Success(Unit)
    }

    /**
     * The first drive to ask adopts folders configured in the device preferences, so an upgrade
     * keeps them.
     */
    override suspend fun backupFolders(chatId: Long): Set<String> {
        val row = channelDao.byId(chatId) ?: return emptySet()
        val stored = row.backupFolders.toFolderSet()
        if (stored.isNotEmpty()) return stored

        val legacy = settingsRepository.preferences.first().backupFolders
        if (legacy.isEmpty()) return emptySet()
        setBackupFolders(chatId, legacy)
        SafeLog.d(TAG, "Adopted ${legacy.size} backup folders from device settings")
        return legacy
    }

    override suspend fun setBackupFolders(chatId: Long, folders: Set<String>) {
        channelDao.setBackupFolders(chatId, folders.joinToString(FOLDER_SEPARATOR))
    }

    /**
     * Fetched on every refresh so a changed picture propagates; a failed fetch keeps the last one.
     */
    private suspend fun refreshPhoto(chatId: Long) {
        val current = runCatching { telegramClient.fetchChannelPhoto(chatId) }
            .getOrElse { return }
        if (current != channelDao.byId(chatId)?.photoPath) {
            channelDao.setPhotoPath(chatId, current)
        }
    }

    private suspend fun seedDefaultsOnce(chatId: Long) {
        if (channelDao.byId(chatId)?.defaultsSeeded != false) return
        exclusionRepository.ensureDefaults(chatId)
        channelDao.markDefaultsSeeded(chatId)
    }

    private suspend fun forget(chatId: Long) {
        channelDao.deleteFiles(chatId)
        channelDao.deleteFolders(chatId)
        channelDao.delete(chatId)
        val currentPrefs = settingsRepository.preferences.first()
        val fallback = if (currentPrefs.storageChatId == chatId) {
            channelDao.all().firstOrNull()
        } else {
            null
        }
        settingsRepository.update { prefs ->
            prefs.copy(
                storageChatId = if (prefs.storageChatId == chatId) fallback?.chatId else prefs.storageChatId,
                channelSyncModes = prefs.channelSyncModes - chatId,
                linkedChatTypes = prefs.linkedChatTypes - chatId
            )
        }
        fallback?.let { channelOwnership.claimUnowned(it.chatId) }
        SafeLog.d(TAG, "Removed a drive from this device")
    }

    private suspend fun StorageChannelEntity.toDomain(prefs: UserPreferences) = DriveChannel(
        chatId = chatId,
        title = title,
        fileCount = channelDao.fileCount(chatId),
        remoteFileCount = remoteFileCount,
        storedBytes = channelDao.storedBytes(chatId),
        backupFolders = backupFolders.toFolderSet(),
        photoPath = photoPath?.takeIf { File(it).exists() },
        isActive = chatId == prefs.storageChatId,
        isIndexed = lastOpenedAt > 0 || channelDao.fileCount(chatId) > 0,
        lastOpenedAt = lastOpenedAt,
        syncMode = prefs.syncModeFor(chatId),
        isLinkedChat = prefs.isLinkedChat(chatId),
        chatType = prefs.chatTypeFor(chatId)
    )

    private fun String.toFolderSet(): Set<String> =
        split(FOLDER_SEPARATOR).filter { it.isNotBlank() }.toSet()

    private inline fun <T> runTelegram(block: () -> AppResult<T>): AppResult<T> = try {
        block()
    } catch (e: TelegramException) {
        AppResult.Failure(
            if (e.isChannelLimit) {
                AppError.ChannelLimitReached
            } else if (e.isRateLimit) {
                AppError.RateLimited(e.retryAfterSeconds ?: 0)
            } else {
                AppError.TelegramError(e.code, e.message)
            }
        )
    }

    private companion object {
        const val TAG = "ChannelRepository"
        const val DISCOVERY_BUDGET_MS = 60_000L
        const val DISCOVERY_TIMEOUT_CODE = 408
        const val DISCOVERY_ATTEMPTS = 4
        const val DISCOVERY_RETRY_MS = 1_500L
        const val FOLDER_SEPARATOR = "\n"
    }
}
