package com.drdisagree.teledrive.presentation.onboarding

import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.core.telegram.CodeDeliveryChannel
import com.drdisagree.teledrive.core.telegram.TelegramChatSummary
import com.drdisagree.teledrive.domain.model.Country
import com.drdisagree.teledrive.domain.model.DriveChannel

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val working: Boolean = false,
    val error: UiText? = null,
    val restoredApiId: String = "",
    val restoredApiHash: String = "",
    val cloudConnectedUsername: String = "",
    val codePhoneNumber: String = "",
    val codeChannel: CodeDeliveryChannel = CodeDeliveryChannel.TELEGRAM_APP,
    val codeLength: Int? = null,
    val passwordHint: String = "",
    val registrationRequired: Boolean = false,
    val qrLink: String? = null,
    val qrMode: Boolean = false,
    val countries: List<Country> = emptyList(),
    val countryLoadState: CountryLoadState = CountryLoadState.LOADING,
    val selectedCountry: Country? = null,
    val channels: List<DriveChannel> = emptyList(),
    val availableChats: List<TelegramChatSummary> = emptyList(),
    val loadingAvailableChats: Boolean = false,
    val selectedChatId: Long? = null,
    val channelCreated: Boolean = false,
    val backupDcim: Boolean = true,
    val backupPictures: Boolean = true,
    val backupMovies: Boolean = false,
    val autoBackupEnabled: Boolean = true,
    val wifiOnly: Boolean = false,
    val finishing: Boolean = false
)
