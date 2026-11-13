package com.drdisagree.teledrive.domain.repository

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.TelegramChatSummary
import com.drdisagree.teledrive.domain.model.DriveChannel
import com.drdisagree.teledrive.domain.model.DriveSyncMode
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {

    fun observeChannels(): Flow<List<DriveChannel>>

    suspend fun refresh(): AppResult<List<DriveChannel>>

    suspend fun refreshKnown(): AppResult<Unit>

    /** True only when the active drive's channel is confirmed gone. */
    suspend fun activeDriveMissing(): Boolean

    suspend fun pruneDeleted(): AppResult<Int>

    suspend fun create(label: String): AppResult<DriveChannel>

    /** Lists existing Telegram channels, supergroups, basic groups, and Saved Messages not yet linked. */
    suspend fun listAvailableChats(): AppResult<List<TelegramChatSummary>>

    /** Links an existing Telegram chat as a browsable drive with the chosen [syncMode]. */
    suspend fun linkExistingChat(
        chat: TelegramChatSummary,
        syncMode: DriveSyncMode = DriveSyncMode.SAFE
    ): AppResult<DriveChannel>

    /** Updates the sync mode (FULL, SAFE, or READ_ONLY) for a drive or linked chat. */
    suspend fun setSyncMode(chatId: Long, syncMode: DriveSyncMode)

    /** Removes a linked chat from this device without deleting the remote Telegram chat. */
    suspend fun unlinkLocal(chatId: Long): AppResult<Unit>

    /** [index] false lets onboarding defer indexing to the pass it runs at the end of setup. */
    suspend fun switchTo(chatId: Long, index: Boolean = true): AppResult<Unit>

    suspend fun rename(chatId: Long, label: String): AppResult<Unit>

    suspend fun deleteRemotely(chatId: Long): AppResult<Unit>

    suspend fun backupFolders(chatId: Long): Set<String>

    suspend fun setBackupFolders(chatId: Long, folders: Set<String>)
}
