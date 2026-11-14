package com.drdisagree.teledrive.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.files.DeleteConsentRequest
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.logging.DebugLogRecorder
import com.drdisagree.teledrive.core.permissions.PermissionChecker
import com.drdisagree.teledrive.core.security.AppLockManager
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramUser
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.core.update.UpdateChecker
import com.drdisagree.teledrive.data.repository.LocalDataWiper
import com.drdisagree.teledrive.domain.model.BackupTrigger
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.BackupRepository
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.domain.repository.ChannelRepository
import com.drdisagree.teledrive.domain.repository.CloudSyncRepository
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.KeyBackupRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import com.drdisagree.teledrive.domain.repository.TelegramAuthRepository
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.toUiText
import com.drdisagree.teledrive.presentation.files.ShareRequest
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.about_no_update
import com.drdisagree.teledrive.resources.files_removed_local_copies
import com.drdisagree.teledrive.resources.message_backing_up_new_folder
import com.drdisagree.teledrive.resources.message_cache_cleared
import com.drdisagree.teledrive.resources.message_key_backup_saved
import com.drdisagree.teledrive.resources.message_key_restored
import com.drdisagree.teledrive.resources.message_rebuild_done
import com.drdisagree.teledrive.resources.message_thumbnails_cleared
import com.drdisagree.teledrive.resources.message_wrong_passphrase
import com.drdisagree.teledrive.resources.rebuild_locked_files
import com.drdisagree.teledrive.resources.settings_debug_log_empty
import com.drdisagree.teledrive.resources.settings_free_up_none
import com.drdisagree.teledrive.resources.settings_nothing_to_remove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import java.security.SecureRandom

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val telegramAuthRepository: TelegramAuthRepository,
    private val telegramClient: TelegramClient,
    private val cacheRepository: CacheRepository,
    private val fileRepository: FileRepository,
    private val keyBackupRepository: KeyBackupRepository,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val syncRepository: SyncRepository,
    private val localDataWiper: LocalDataWiper,
    private val channelRepository: ChannelRepository,
    private val backupRepository: BackupRepository,
    private val appLockManager: AppLockManager,
    private val maintenanceScheduler: MaintenanceScheduler,
    private val transferScheduler: TransferScheduler,
    val permissionChecker: PermissionChecker,
    private val updateChecker: UpdateChecker,
    private val debugLogRecorder: DebugLogRecorder
) : ViewModel() {

    private val user = MutableStateFlow<TelegramUser?>(null)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _shareRequests = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 1)
    val shareRequests = _shareRequests.asSharedFlow()

    private val _keyBackupWorking = MutableStateFlow(false)
    val keyBackupWorking: StateFlow<Boolean> = _keyBackupWorking.asStateFlow()

    private val _loggerKeyConfigured = MutableStateFlow(wrappedKeyRepository.exists(CryptoKeys.LOGGER))
    val loggerKeyConfigured: StateFlow<Boolean> = _loggerKeyConfigured.asStateFlow()

    private val _loggerKeyExport = MutableStateFlow<String?>(null)
    val loggerKeyExport: StateFlow<String?> = _loggerKeyExport.asStateFlow()

    val cloudSyncing: StateFlow<Boolean> = cloudSyncRepository.syncing

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.preferences,
        user,
        telegramAuthRepository.connectionState,
        cacheRepository.observeStats(),
        combine(syncRepository.syncing, cloudSyncRepository.syncing) { s, cs -> s to cs }
    ) { prefs, currentUser, connection, cacheStats, (syncing, cloudSync) ->
        SettingsUiState(
            preferences = prefs,
            user = currentUser,
            connection = connection,
            cacheStats = cacheStats,
            syncing = syncing,
            cloudSyncing = cloudSync,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            cacheRepository.refreshStats()
            when (val result = telegramAuthRepository.getCurrentUser()) {
                is AppResult.Success -> user.value = result.value
                is AppResult.Failure -> Unit
            }
        }
    }

    fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch {
            val previous = settingsRepository.preferences.first()
            settingsRepository.update(transform)
            val current = settingsRepository.preferences.first()
            if (current.linkPreviews != previous.linkPreviews) {
                cacheRepository.clearLinkThumbnails()
            }
            if (current.allowMeteredTransfers != previous.allowMeteredTransfers ||
                current.backupChargingOnly != previous.backupChargingOnly ||
                current.backupWifiOnly != previous.backupWifiOnly
            ) {
                transferScheduler.rekick(current.allowMeteredTransfers)
            }
            if (current.updateCheckEnabled != previous.updateCheckEnabled) {
                maintenanceScheduler.scheduleUpdateCheck(current.updateCheckEnabled)
            }
            backupRepository.syncActiveSessionWithSelection()
            rescheduleWork()
            if (current.isCloudConnected && current.cloudAutoSync) {
                runCatching { cloudSyncRepository.pushSessionMigration() }
            }
        }
    }

    private suspend fun scanForNewBackupWork() {
        when (val result = backupRepository.startBackup(BackupTrigger.MANUAL)) {
            is AppResult.Success -> result.value?.let {
                _messages.tryEmit(UiText.Resource(Res.string.message_backing_up_new_folder))
            }

            is AppResult.Failure -> Unit
        }
    }

    private suspend fun rescheduleWork() {
        val prefs = settingsRepository.preferences.first()
        maintenanceScheduler.scheduleAll(
            backupEnabled = prefs.autoBackupEnabled && prefs.backupIntervalHours > 0,
            backupIntervalHours = prefs.backupIntervalHours,
            wifiOnly = prefs.backupWifiOnly,
            chargingOnly = prefs.backupChargingOnly,
            instantBackup = prefs.instantBackupEnabled,
            updateChecks = prefs.updateCheckEnabled
        )
    }

    val backupFolders: StateFlow<Set<String>> = settingsRepository.preferences
        .map { it.storageChatId }
        .distinctUntilChanged()
        .flatMapLatest { chatId ->
            channelRepository.observeChannels().map { channels ->
                channels.firstOrNull { it.chatId == chatId }?.backupFolders.orEmpty()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun addBackupFolder(path: String) {
        editBackupFolders { it + path }
    }

    fun removeBackupFolder(path: String) {
        editBackupFolders { it - path }
    }

    private fun editBackupFolders(transform: (Set<String>) -> Set<String>) {
        viewModelScope.launch {
            val chatId = settingsRepository.preferences.first().storageChatId ?: return@launch
            val before = channelRepository.backupFolders(chatId)
            val after = transform(before)
            if (after == before) return@launch
            channelRepository.setBackupFolders(chatId, after)
            backupRepository.syncActiveSessionWithSelection()
            rescheduleWork()
            if ((after - before).isNotEmpty()) scanForNewBackupWork()
        }
    }

    fun notify(message: UiText) {
        _messages.tryEmit(message)
    }

    /**
     * Turns encryption on only after the key backup is saved, so nothing is sealed with an
     * unrecoverable key.
     */
    fun backUpEncryptionKey(passphrase: String, hint: String, enableEncryption: Boolean) {
        _keyBackupWorking.value = true
        viewModelScope.launch {
            val result = keyBackupRepository.createBackup(
                passphrase.toCharArray(),
                hint.takeIf { it.isNotBlank() }
            )
            _keyBackupWorking.value = false
            when (result) {
                is AppResult.Success -> {
                    settingsRepository.update {
                        it.copy(
                            keyBackupCreated = true,
                            encryptFiles = if (enableEncryption) true else it.encryptFiles
                        )
                    }
                    _messages.tryEmit(UiText.Resource(Res.string.message_key_backup_saved))
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    /** Only callable once a key backup exists. */
    fun enableEncryption() {
        update { it.copy(encryptFiles = true) }
    }

    fun disableEncryption() {
        update { it.copy(encryptFiles = false) }
    }

    private val _keyHint = MutableStateFlow<KeyHint>(KeyHint.Unknown)
    val keyHint: StateFlow<KeyHint> = _keyHint.asStateFlow()

    fun loadKeyHint() {
        _keyHint.value = KeyHint.Loading
        viewModelScope.launch {
            _keyHint.value = when (val result = keyBackupRepository.backupHint()) {
                is AppResult.Success -> KeyHint.Loaded(result.value)
                is AppResult.Failure -> KeyHint.Missing
            }
        }
    }

    fun restoreEncryptionKey(passphrase: String) {
        _keyBackupWorking.value = true
        viewModelScope.launch {
            val result = keyBackupRepository.restore(passphrase.toCharArray())
            _keyBackupWorking.value = false
            when (result) {
                is AppResult.Success -> {
                    if (result.value) {
                        settingsRepository.update { it.copy(keyBackupCreated = true) }
                        _messages.tryEmit(UiText.Resource(Res.string.message_key_restored))
                        syncRepository.fullResync()
                    } else {
                        _messages.tryEmit(UiText.Resource(Res.string.message_wrong_passphrase))
                    }
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun generateLoggerKey() {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        wrappedKeyRepository.store(CryptoKeys.LOGGER, key)
        _loggerKeyConfigured.value = true
        _loggerKeyExport.value = Base64.UrlSafe.encode(key).trimEnd('=')
        _messages.tryEmit(UiText.Plain("Logger key generated. Export it to twlog.py and keep it safe."))
    }

    fun exportLoggerKey() {
        val key = wrappedKeyRepository.get(CryptoKeys.LOGGER)
        _loggerKeyExport.value = key?.let { Base64.UrlSafe.encode(it).trimEnd('=') }
        if (key == null) _messages.tryEmit(UiText.Plain("No logger key is configured."))
    }

    fun importLoggerKey(encoded: String) {
        runCatching {
            val normalized = encoded.trim()
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val key = Base64.UrlSafe.decode(padded)
            require(key.size == 32)
            wrappedKeyRepository.store(CryptoKeys.LOGGER, key)
        }.onSuccess {
            _loggerKeyConfigured.value = true
            _loggerKeyExport.value = null
            _messages.tryEmit(UiText.Plain("Logger key imported."))
            viewModelScope.launch { syncRepository.fullResync() }
        }.onFailure {
            _messages.tryEmit(UiText.Plain("Invalid logger key. Use the URL-safe base64 32-byte key from twlog.py."))
        }
    }

    fun removeLoggerKey() {
        wrappedKeyRepository.remove(CryptoKeys.LOGGER)
        _loggerKeyConfigured.value = false
        _loggerKeyExport.value = null
        _messages.tryEmit(UiText.Plain("Logger key removed. Logger media will remain locked."))
    }

    val reclaimableBytes: StateFlow<Long> = fileRepository.observeReclaimableBytes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _deleteConsentRequests = MutableSharedFlow<DeleteConsentRequest>(extraBufferCapacity = 1)
    val deleteConsentRequests = _deleteConsentRequests.asSharedFlow()

    val indexedSoFar: StateFlow<Int> = syncRepository.indexedSoFar
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun reportNothingToFree() {
        _messages.tryEmit(UiText.Resource(Res.string.settings_free_up_none))
    }

    fun freeUpSpace() {
        viewModelScope.launch {
            when (val result = fileRepository.freeUpSpace()) {
                is AppResult.Success -> {
                    val consent = result.value.consentRequest
                    if (consent != null) {
                        _deleteConsentRequests.tryEmit(consent)
                    } else {
                        _messages.tryEmit(
                            if (result.value.deletedCount == 0) {
                                UiText.Resource(Res.string.settings_nothing_to_remove)
                            } else {
                                UiText.Resource(
                                    Res.string.files_removed_local_copies,
                                    result.value.deletedCount
                                )
                            }
                        )
                    }
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            cacheRepository.clearAll()
            _messages.tryEmit(UiText.Resource(Res.string.message_cache_cleared))
        }
    }

    fun clearThumbnails() {
        viewModelScope.launch {
            cacheRepository.clearThumbnails()
            _messages.tryEmit(UiText.Resource(Res.string.message_thumbnails_cleared))
        }
    }

    fun checkForUpdates() {
        if (_updateState.value is UpdateState.Checking) return
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            val release = updateChecker.newerRelease()
            settingsRepository.update { it.copy(notifiedUpdateVersion = release?.version ?: "") }
            settingsRepository.update { it.copy(lastUpdateCheckAt = System.currentTimeMillis()) }
            _updateState.value = if (release == null) {
                _messages.tryEmit(UiText.Resource(Res.string.about_no_update))
                UpdateState.Idle
            } else {
                UpdateState.Available(release)
            }
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }

    fun shareDebugLog() {
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) { debugLogRecorder.export() }
            if (log == null) {
                _messages.tryEmit(UiText.Resource(Res.string.settings_debug_log_empty))
            } else {
                _shareRequests.tryEmit(ShareRequest(listOf(log.absolutePath), "text/plain"))
            }
        }
    }

    fun resync() {
        viewModelScope.launch {
            when (val result = syncRepository.fullResync()) {
                is AppResult.Success -> {
                    val prefs = settingsRepository.preferences.first()
                    if (prefs.isCloudConnected && prefs.cloudAutoSync) {
                        cloudSyncRepository.pushSessionMigration()
                    }
                    _messages.tryEmit(
                        if (result.value.lockedFiles > 0) {
                            UiText.PluralResource(
                                Res.plurals.rebuild_locked_files,
                                result.value.lockedFiles,
                                result.value.lockedFiles
                            )
                        } else {
                            UiText.Resource(
                                Res.string.message_rebuild_done,
                                result.value.inserted,
                                result.value.updated,
                                result.value.detachedFromRemote
                            )
                        }
                    )
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun registerCloudAccount(
        username: String,
        password: String,
        serverUrl: String,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            when (val result = cloudSyncRepository.registerAndSave(username, password, serverUrl)) {
                is AppResult.Success -> {
                    onComplete()
                    _messages.tryEmit(
                        UiText.Plain("Registered & saved encrypted session and settings for ${result.value.username}")
                    )
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun signInCloudAccount(
        username: String,
        password: String,
        serverUrl: String,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            when (val result = cloudSyncRepository.signInAndSync(username, password, serverUrl)) {
                is AppResult.Success -> {
                    onComplete()
                    _messages.tryEmit(
                        UiText.Plain("Signed in & synced session and settings (${result.value.username})")
                    )
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun saveCloudSessionNow() {
        viewModelScope.launch {
            when (val result = cloudSyncRepository.pushSessionMigration()) {
                is AppResult.Success -> {
                    _messages.tryEmit(
                        UiText.Plain("Encrypted Telegram session and key files saved to cloud")
                    )
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun restoreCloudSessionNow() {
        viewModelScope.launch {
            when (val result = cloudSyncRepository.pullSessionMigration()) {
                is AppResult.Success -> {
                    _messages.tryEmit(
                        UiText.Plain("Restored Telegram session and key files from cloud")
                    )
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun signOutCloudAccount() {
        viewModelScope.launch {
            cloudSyncRepository.signOut()
            _messages.tryEmit(UiText.Plain("Cloud connection disconnected on this device"))
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            if (prefs.isCloudConnected) {
                runCatching { cloudSyncRepository.pushSessionMigration() }
            }
            when (val result = telegramAuthRepository.logout()) {
                is AppResult.Success -> {
                    withContext(NonCancellable) {
                        localDataWiper.wipe()
                        settingsRepository.clearTelegramCredentials()
                        settingsRepository.clearCloudSecret()
                        settingsRepository.update {
                            it.copy(
                                onboardingComplete = false,
                                storageChatId = null,
                                backupFolders = emptySet(),
                                cloudUsername = "",
                                cloudServerUrl = "",
                                cloudSessionToken = "",
                                cloudLastSyncedAt = 0L
                            )
                        }
                    }
                    onLoggedOut()
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }
}
