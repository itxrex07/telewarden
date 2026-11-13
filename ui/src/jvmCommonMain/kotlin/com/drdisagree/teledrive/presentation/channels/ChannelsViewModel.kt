package com.drdisagree.teledrive.presentation.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.TelegramChatSummary
import com.drdisagree.teledrive.domain.model.DriveChannel
import com.drdisagree.teledrive.domain.model.DriveSyncMode
import com.drdisagree.teledrive.domain.repository.ChannelRepository
import com.drdisagree.teledrive.domain.repository.CloudSyncRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.toUiText
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.app_drive_recreated
import com.drdisagree.teledrive.resources.channels_creating_drive
import com.drdisagree.teledrive.resources.channels_deleting
import com.drdisagree.teledrive.resources.channels_looking_for_drives
import com.drdisagree.teledrive.resources.channels_opening_drive
import com.drdisagree.teledrive.resources.channels_removed_missing
import com.drdisagree.teledrive.resources.channels_renaming
import com.drdisagree.teledrive.resources.message_created_drive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChannelsViewModel(
    private val channelRepository: ChannelRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val channels: StateFlow<List<DriveChannel>> = channelRepository.observeChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _availableChats = MutableStateFlow<List<TelegramChatSummary>>(emptyList())
    val availableChats: StateFlow<List<TelegramChatSummary>> = _availableChats.asStateFlow()

    private val _loadingChats = MutableStateFlow(false)
    val loadingChats: StateFlow<Boolean> = _loadingChats.asStateFlow()

    private val _working = MutableStateFlow<UiText?>(null)
    val working: StateFlow<UiText?> = _working.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            val existing = channelRepository.observeChannels().first()
            if (existing.isEmpty()) {
                refresh()
            }
        }
    }

    fun refresh() = run(UiText.Resource(Res.string.channels_looking_for_drives)) {
        val pruned = channelRepository.pruneDeleted()
        if (pruned is AppResult.Success && pruned.value > 0) {
            _messages.tryEmit(
                UiText.Resource(Res.string.channels_removed_missing, pruned.value)
            )
        }
        val result = channelRepository.refresh()
        if (result is AppResult.Success && result.value.isEmpty()) {
            val existing = channelRepository.observeChannels().first()
            if (existing.isEmpty()) {
                channelRepository.create(DEFAULT_DRIVE_LABEL).let { created ->
                    if (created is AppResult.Success) {
                        channelRepository.switchTo(created.value.chatId)
                        _messages.tryEmit(UiText.Resource(Res.string.app_drive_recreated))
                    }
                }
            }
        }
        result
    }

    fun loadAvailableChats() {
        if (_loadingChats.value) return
        _loadingChats.value = true
        viewModelScope.launch {
            when (val result = channelRepository.listAvailableChats()) {
                is AppResult.Success -> _availableChats.value = result.value
                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
            _loadingChats.value = false
        }
    }

    fun linkExistingChat(chat: TelegramChatSummary, syncMode: DriveSyncMode) =
        run(UiText.Plain("Linking ${chat.title}…")) {
            when (val result = channelRepository.linkExistingChat(chat, syncMode)) {
                is AppResult.Success -> {
                    _messages.tryEmit(UiText.Plain("Linked \"${result.value.displayName}\""))
                    val switched = channelRepository.switchTo(result.value.chatId)
                    autoPushCloudProfile()
                    switched
                }

                is AppResult.Failure -> result
            }
        }

    fun setSyncMode(chatId: Long, mode: DriveSyncMode) {
        viewModelScope.launch {
            channelRepository.setSyncMode(chatId, mode)
            val label = when (mode) {
                DriveSyncMode.SAFE -> "Safe Mode"
                DriveSyncMode.READ_ONLY -> "Read-Only Mode"
                DriveSyncMode.FULL -> "Full TeleDrive Mode"
            }
            _messages.tryEmit(UiText.Plain("Drive mode updated to $label"))
            autoPushCloudProfile()
        }
    }

    fun unlinkLocal(chatId: Long) =
        run(UiText.Plain("Unlinking chat…")) {
            val res = channelRepository.unlinkLocal(chatId)
            autoPushCloudProfile()
            res
        }

    fun create(label: String) = run(UiText.Resource(Res.string.channels_creating_drive)) {
        when (val result = channelRepository.create(label)) {
            is AppResult.Success -> {
                _messages.tryEmit(
                    UiText.Resource(
                        Res.string.message_created_drive,
                        result.value.displayName
                    )
                )
                val switched = channelRepository.switchTo(result.value.chatId)
                autoPushCloudProfile()
                switched
            }

            is AppResult.Failure -> result
        }
    }

    fun switchTo(chatId: Long, onSwitched: (() -> Unit)? = null) =
        run(
            label = UiText.Resource(Res.string.channels_opening_drive),
            onSuccess = onSwitched
        ) {
            val res = channelRepository.switchTo(chatId)
            autoPushCloudProfile()
            res
        }

    fun rename(chatId: Long, label: String) = run(UiText.Resource(Res.string.channels_renaming)) {
        val res = channelRepository.rename(chatId, label)
        autoPushCloudProfile()
        res
    }

    fun deleteRemotely(chatId: Long) = run(UiText.Resource(Res.string.channels_deleting)) {
        val res = channelRepository.deleteRemotely(chatId)
        autoPushCloudProfile()
        res
    }

    private fun autoPushCloudProfile() {
        viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            if (prefs.isCloudConnected && prefs.cloudAutoSync) {
                cloudSyncRepository.pushToCloud()
            }
        }
    }

    private fun run(
        label: UiText,
        onSuccess: (() -> Unit)? = null,
        block: suspend () -> AppResult<*>
    ) {
        if (_working.value != null) return
        _working.value = label
        viewModelScope.launch {
            val result = block()
            _working.value = null
            when (result) {
                is AppResult.Success -> onSuccess?.invoke()
                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    private companion object {
        const val DEFAULT_DRIVE_LABEL = ""
    }
}
