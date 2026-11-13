package com.drdisagree.teledrive.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.CodeDeliveryChannel
import com.drdisagree.teledrive.core.telegram.TelegramAuthState
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.domain.model.Country
import com.drdisagree.teledrive.domain.model.DriveChannel
import com.drdisagree.teledrive.domain.repository.ChannelRepository
import com.drdisagree.teledrive.domain.repository.CloudSyncRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import com.drdisagree.teledrive.domain.repository.TelegramAuthRepository
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.toUiText
import com.drdisagree.teledrive.presentation.platform.PlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.StandardFolderPaths
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.onboarding_api_hash_short
import com.drdisagree.teledrive.resources.onboarding_api_id_number
import com.drdisagree.teledrive.resources.onboarding_enter_phone
import com.drdisagree.teledrive.resources.onboarding_no_account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class OnboardingViewModel(
    private val telegramAuthRepository: TelegramAuthRepository,
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val maintenanceScheduler: MaintenanceScheduler,
    private val channelRepository: ChannelRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val standardFolderPaths: StandardFolderPaths,
    private val platformCapabilities: PlatformCapabilities
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        telegramAuthRepository.authState
            .onEach(::onAuthStateChanged)
            .launchIn(viewModelScope)
    }

    private fun onAuthStateChanged(state: TelegramAuthState) {
        _uiState.update { current ->
            when (state) {
                is TelegramAuthState.WaitingForPhoneNumber -> when {
                    current.step == OnboardingStep.API_CREDENTIALS && current.working ->
                        current.copy(step = OnboardingStep.PHONE, working = false, error = null)

                    current.qrLink != null -> current.copy(working = false, qrLink = null)
                    else -> current
                }

                is TelegramAuthState.WaitingForCode -> current.copy(
                    step = OnboardingStep.CODE,
                    working = false,
                    error = null,
                    codePhoneNumber = state.phoneNumber,
                    codeChannel = state.channel,
                    codeLength = state.codeLength
                )
                is TelegramAuthState.WaitingForQrScan -> current.copy(
                    working = false,
                    qrLink = state.link
                )

                is TelegramAuthState.WaitingForEmailAddress -> current.copy(
                    step = OnboardingStep.EMAIL_ADDRESS,
                    working = false,
                    error = null
                )

                is TelegramAuthState.WaitingForEmailCode -> current.copy(
                    step = OnboardingStep.EMAIL_CODE,
                    working = false,
                    error = null,
                    codePhoneNumber = state.emailPattern,
                    codeChannel = CodeDeliveryChannel.EMAIL,
                    codeLength = state.codeLength
                )

                is TelegramAuthState.WaitingForPassword -> current.copy(
                    step = OnboardingStep.PASSWORD,
                    working = false,
                    error = null,
                    passwordHint = state.passwordHint.orEmpty()
                )

                is TelegramAuthState.RegistrationRequired -> current.copy(
                    working = false,
                    registrationRequired = true,
                    error = UiText.Resource(Res.string.onboarding_no_account)
                )

                is TelegramAuthState.Failed -> current.copy(
                    working = false,
                    error = UiText.Plain(state.message)
                )

                is TelegramAuthState.Ready ->
                    if (current.step in listOf(
                            OnboardingStep.WELCOME,
                            OnboardingStep.PHONE,
                            OnboardingStep.CODE,
                            OnboardingStep.PASSWORD,
                            OnboardingStep.API_CREDENTIALS
                        )
                    ) {
                        if (!platformCapabilities.requiresPermissions) {
                            viewModelScope.launch { loadOrCreateDrive() }
                            current.copy(working = true, error = null)
                        } else {
                            current.copy(
                                step = OnboardingStep.PERMISSIONS,
                                working = false,
                                error = null
                            )
                        }
                    } else current

                else -> current
            }
        }
    }

    fun start() {
        _uiState.update {
            it.copy(
                step = OnboardingStep.API_CREDENTIALS,
                error = null
            )
        }
    }

    fun submitCredentials(apiIdText: String, apiHash: String) {
        val apiId = apiIdText.trim().toIntOrNull()
        if (apiId == null || apiId <= 0) {
            _uiState.update { it.copy(error = UiText.Resource(Res.string.onboarding_api_id_number)) }
            return
        }
        if (apiHash.trim().length < 16) {
            _uiState.update { it.copy(error = UiText.Resource(Res.string.onboarding_api_hash_short)) }
            return
        }
        runAction {
            val result =
                telegramAuthRepository.configure(TelegramCredentials(apiId, apiHash.trim()))
            if (result is AppResult.Success) {
                val prefs = settingsRepository.preferences.first()
                if (prefs.isCloudConnected && prefs.cloudAutoSync) {
                    runCatching { cloudSyncRepository.pushToCloud() }
                }
                onAuthStateChanged(telegramAuthRepository.authState.value)
            }
            result
        }
    }

    fun signInWithCloudOnStep2(
        username: String,
        password: String,
        serverUrl: String,
        onSuccess: () -> Unit = {}
    ) {
        if (_uiState.value.working) return
        _uiState.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            when (val res = cloudSyncRepository.signInAndSync(username, password, serverUrl)) {
                is AppResult.Success -> {
                    onSuccess()
                    val restoredCreds = settingsRepository.getTelegramCredentials()
                    if (restoredCreds != null && restoredCreds.apiId > 0 && restoredCreds.apiHash.length >= 16) {
                        _uiState.update {
                            it.copy(
                                restoredApiId = restoredCreds.apiId.toString(),
                                restoredApiHash = restoredCreds.apiHash,
                                cloudConnectedUsername = res.value.username,
                                error = null
                            )
                        }
                        when (val cfg = telegramAuthRepository.configure(restoredCreds)) {
                            is AppResult.Success -> {
                                val settledState = withTimeoutOrNull(6_000L.milliseconds) {
                                    telegramAuthRepository.authState.first { state ->
                                        state is TelegramAuthState.Ready ||
                                            state is TelegramAuthState.WaitingForPhoneNumber ||
                                            state is TelegramAuthState.WaitingForCode ||
                                            state is TelegramAuthState.WaitingForPassword ||
                                            state is TelegramAuthState.Failed
                                    }
                                } ?: telegramAuthRepository.authState.value

                                val prefs = settingsRepository.preferences.first()
                                if (settledState is TelegramAuthState.Ready && prefs.storageChatId != null) {
                                    settingsRepository.update { it.copy(onboardingComplete = true) }
                                    viewModelScope.launch {
                                        runCatching { channelRepository.refresh() }
                                        runCatching { syncRepository.fullResync() }
                                    }
                                    _uiState.update {
                                        it.copy(step = OnboardingStep.DONE, working = false, error = null)
                                    }
                                } else {
                                    onAuthStateChanged(settledState)
                                    if (_uiState.value.step == OnboardingStep.API_CREDENTIALS &&
                                        settledState !is TelegramAuthState.Ready
                                    ) {
                                        _uiState.update { it.copy(step = OnboardingStep.PHONE, working = false) }
                                    }
                                }
                            }
                            is AppResult.Failure -> {
                                _uiState.update {
                                    it.copy(working = false, error = cfg.error.toUiText())
                                }
                            }
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                working = false,
                                cloudConnectedUsername = res.value.username,
                                error = UiText.Plain(
                                    "Cloud account '${res.value.username}' connected & settings restored! Enter your API ID & Hash once below to save your session to the cloud."
                                )
                            )
                        }
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(working = false, error = res.error.toUiText())
                    }
                }
            }
        }
    }

    fun loadCountries() {
        if (_uiState.value.countries.isNotEmpty()) return
        _uiState.update { it.copy(countryLoadState = CountryLoadState.LOADING) }
        viewModelScope.launch {
            val fetched = withTimeoutOrNull(COUNTRY_LOAD_TIMEOUT_MS.milliseconds) {
                telegramAuthRepository.countries()
            }
            if (fetched == null) {
                _uiState.update { it.copy(countryLoadState = CountryLoadState.FAILED) }
                return@launch
            }
            when (fetched) {
                is AppResult.Success -> _uiState.update { current ->
                    current.copy(
                        countries = fetched.value.countries,
                        selectedCountry = current.selectedCountry ?: fetched.value.detected,
                        countryLoadState = if (fetched.value.countries.isEmpty()) {
                            CountryLoadState.FAILED
                        } else {
                            CountryLoadState.READY
                        }
                    )
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(countryLoadState = CountryLoadState.FAILED)
                }
            }
        }
    }

    fun selectCountry(country: Country) {
        _uiState.update { it.copy(selectedCountry = country) }
    }

    fun submitPhone(phone: String) {
        if (phone.isBlank()) {
            _uiState.update { it.copy(error = UiText.Resource(Res.string.onboarding_enter_phone)) }
            return
        }
        _uiState.update { it.copy(qrLink = null, qrMode = false) }
        runAction { telegramAuthRepository.submitPhoneNumber(phone.trim()) }
    }

    fun startQrLogin() {
        _uiState.update { it.copy(qrMode = true, error = null) }
        if (_uiState.value.qrLink != null) return
        runAction { telegramAuthRepository.requestQrCodeAuthentication() }
    }

    fun cancelQrLogin() {
        _uiState.update { it.copy(qrMode = false, qrLink = null, error = null) }
        viewModelScope.launch { telegramAuthRepository.restartAuthentication() }
    }

    fun submitEmailAddress(email: String) {
        if (email.isBlank()) return
        runAction { telegramAuthRepository.submitEmailAddress(email.trim()) }
    }

    fun submitEmailCode(code: String) {
        if (code.isBlank()) return
        runAction { telegramAuthRepository.submitEmailCode(code.trim()) }
    }

    fun submitCode(code: String) {
        if (code.isBlank()) return
        runAction { telegramAuthRepository.submitCode(code.trim()) }
    }

    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        runAction { telegramAuthRepository.submitPassword(password) }
    }

    fun resendCode() {
        runAction { telegramAuthRepository.resendCode() }
    }

    fun onPermissionsResolved() {
        _uiState.update { it.copy(working = true, error = null) }
        viewModelScope.launch { loadOrCreateDrive() }
    }

    fun retryDriveSetup() {
        if (_uiState.value.working) return
        _uiState.update { it.copy(working = true, error = null) }
        viewModelScope.launch { loadOrCreateDrive() }
    }

    private suspend fun loadOrCreateDrive() {
        var channels = withTimeoutOrNull(14_000L.milliseconds) {
            refreshChannels()
        } ?: runCatching { channelRepository.observeChannels().first() }.getOrDefault(emptyList())

        var created = false
        var failure: UiText? = null
        if (channels.isEmpty()) {
            when (val result = withTimeoutOrNull(12_000L.milliseconds) {
                channelRepository.create(DEFAULT_DRIVE_LABEL)
            }) {
                is AppResult.Success -> {
                    created = true
                    channels = runCatching { channelRepository.observeChannels().first() }
                        .getOrDefault(listOf(result.value))
                        .ifEmpty { listOf(result.value) }
                }
                is AppResult.Failure -> failure = result.error.toUiText()
                null -> failure = UiText.Plain("Timed out creating storage channel. Check connection or link an existing chat below.")
            }
        }
        _uiState.update {
            it.copy(
                step = OnboardingStep.CHANNEL_SELECT,
                channels = channels,
                channelCreated = created,
                selectedChatId = channels.firstOrNull { channel -> channel.isActive }?.chatId
                    ?: channels.firstOrNull()?.chatId,
                error = failure.takeIf { channels.isEmpty() },
                working = false
            )
        }
    }

    private suspend fun refreshChannels(): List<DriveChannel> =
        when (val result = channelRepository.refresh()) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> runCatching {
                channelRepository.observeChannels().first()
            }.getOrDefault(emptyList())
        }

    fun selectChannel(chatId: Long) = _uiState.update { it.copy(selectedChatId = chatId) }

    fun loadAvailableChats() {
        if (_uiState.value.loadingAvailableChats) return
        _uiState.update { it.copy(loadingAvailableChats = true) }
        viewModelScope.launch {
            val chats = withTimeoutOrNull(12_000L.milliseconds) {
                when (val result = channelRepository.listAvailableChats()) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> emptyList()
                }
            }.orEmpty()
            _uiState.update {
                it.copy(
                    availableChats = chats,
                    loadingAvailableChats = false
                )
            }
        }
    }

    fun linkExistingChat(
        chat: com.drdisagree.teledrive.core.telegram.TelegramChatSummary,
        syncMode: com.drdisagree.teledrive.domain.model.DriveSyncMode,
        onComplete: () -> Unit = {}
    ) {
        _uiState.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            when (val result = channelRepository.linkExistingChat(chat, syncMode)) {
                is AppResult.Success -> {
                    val updatedChannels = runCatching {
                        channelRepository.observeChannels().first()
                    }.getOrDefault(emptyList()).ifEmpty { listOf(result.value) }
                    _uiState.update {
                        it.copy(
                            channels = updatedChannels,
                            selectedChatId = result.value.chatId,
                            working = false,
                            error = null
                        )
                    }
                    onComplete()
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            working = false,
                            error = result.error.toUiText()
                        )
                    }
                }
            }
        }
    }

    fun confirmChannel() {
        val chatId = _uiState.value.selectedChatId ?: run {
            advancePastChannel()
            return
        }
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            channelRepository.switchTo(chatId, index = false)
            _uiState.update { it.copy(working = false) }
            advancePastChannel()
        }
    }

    private fun advancePastChannel() {
        if (platformCapabilities.supportsAutoBackup) {
            _uiState.update { it.copy(step = OnboardingStep.BACKUP_SETUP) }
        } else {
            _uiState.update { it.copy(autoBackupEnabled = false) }
            finishSetup()
        }
    }

    fun setBackupDcim(enabled: Boolean) = _uiState.update { it.copy(backupDcim = enabled) }

    fun setBackupPictures(enabled: Boolean) = _uiState.update { it.copy(backupPictures = enabled) }

    fun setBackupMovies(enabled: Boolean) = _uiState.update { it.copy(backupMovies = enabled) }

    fun setAutoBackup(enabled: Boolean) = _uiState.update { it.copy(autoBackupEnabled = enabled) }

    fun setWifiOnly(enabled: Boolean) = _uiState.update { it.copy(wifiOnly = enabled) }

    fun finishSetup() {
        val state = _uiState.value
        _uiState.update { it.copy(finishing = true) }
        viewModelScope.launch {
            val folders = buildSet {
                if (state.backupDcim) standardFolderPaths.camera?.let(::add)
                if (state.backupPictures) standardFolderPaths.pictures?.let(::add)
                if (state.backupMovies) standardFolderPaths.movies?.let(::add)
            }
            settingsRepository.update {
                it.copy(
                    onboardingComplete = true,
                    autoBackupEnabled = state.autoBackupEnabled,
                    backupWifiOnly = state.wifiOnly
                )
            }
            maintenanceScheduler.scheduleAll(
                backupEnabled = state.autoBackupEnabled,
                backupIntervalHours = 24,
                wifiOnly = state.wifiOnly,
                chargingOnly = false,
                instantBackup = state.autoBackupEnabled
            )
            syncRepository.fullResync()
            channelRepository.refresh()
            val prefs = settingsRepository.preferences.first()
            prefs.storageChatId?.let { chatId ->
                channelRepository.setBackupFolders(chatId, folders)
            }
            if (prefs.isCloudConnected) {
                runCatching { cloudSyncRepository.pushToCloud() }
            }
            _uiState.update { it.copy(step = OnboardingStep.DONE, finishing = false) }
        }
    }

    private fun runAction(block: suspend () -> AppResult<*>) {
        _uiState.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            when (val result = block()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> _uiState.update {
                    it.copy(working = false, error = result.error.toUiText())
                }
            }
        }
    }

    private companion object {
        const val COUNTRY_LOAD_TIMEOUT_MS = 8_000L
        const val DEFAULT_DRIVE_LABEL = ""
    }
}
