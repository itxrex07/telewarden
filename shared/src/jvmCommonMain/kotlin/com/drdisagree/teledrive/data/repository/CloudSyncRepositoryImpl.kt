package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.PassphraseKdf
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramAuthState
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramUploadEvent
import com.drdisagree.teledrive.data.local.dao.StorageChannelDao
import com.drdisagree.teledrive.data.local.entity.StorageChannelEntity
import com.drdisagree.teledrive.data.remote.cloud.CloudDriveEntry
import com.drdisagree.teledrive.data.remote.cloud.CloudEnvelope
import com.drdisagree.teledrive.data.remote.cloud.CloudProfileSnapshot
import com.drdisagree.teledrive.data.remote.cloud.CloudSettingsSnapshot
import com.drdisagree.teledrive.domain.model.CloudSyncSummary
import com.drdisagree.teledrive.domain.model.TelegramChatType
import com.drdisagree.teledrive.domain.repository.CloudSyncRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CloudSyncRepositoryImpl(
    private val storagePaths: AppStoragePaths,
    private val telegramClient: TelegramClient,
    private val passphraseKdf: PassphraseKdf,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val settingsRepository: SettingsRepository,
    private val storageChannelDao: StorageChannelDao
) : CloudSyncRepository {

    private val mutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    override val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun registerAndSave(
        username: String,
        password: String,
        serverUrl: String
    ): AppResult<CloudSyncSummary> {
        val cleanUsername = username.trim()
        if (cleanUsername.length < 2) {
            return AppResult.Failure(AppError.AuthenticationFailed("Username must be at least 2 characters"))
        }
        if (password.length < 4) {
            return AppResult.Failure(AppError.AuthenticationFailed("Password must be at least 4 characters"))
        }

        _syncing.value = true
        try {
            return withContext(Dispatchers.IO) {
                val prefs = settingsRepository.preferences.first()
                val cleanServerUrl = normalizeServerUrl(serverUrl.ifBlank { prefs.cloudServerUrl })
                val snapshot = buildLocalSnapshot(cleanUsername)
                val envelope = encryptSnapshot(cleanUsername, password, snapshot)
                val encodedEnvelope = json.encodeToString(CloudEnvelope.serializer(), envelope)

                var sessionToken = ""
                if (cleanServerUrl.isNotBlank()) {
                    val regResult = withTimeoutOrNull(12_000L) {
                        registerOnServer(cleanServerUrl, cleanUsername, password, encodedEnvelope)
                    } ?: AppResult.Failure(
                        AppError.AuthenticationFailed("Connection timed out reaching $cleanServerUrl")
                    )
                    when (regResult) {
                        is AppResult.Success -> sessionToken = regResult.value
                        is AppResult.Failure -> return@withContext regResult
                    }
                } else {
                    val pushRes = pushVaultEnvelope(cleanUsername, encodedEnvelope)
                    if (pushRes is AppResult.Failure) return@withContext pushRes
                }

                val now = System.currentTimeMillis()
                settingsRepository.setCloudSecret(password)
                settingsRepository.update {
                    it.copy(
                        cloudUsername = cleanUsername,
                        cloudServerUrl = cleanServerUrl,
                        cloudSessionToken = sessionToken,
                        cloudLastSyncedAt = now
                    )
                }

                AppResult.Success(
                    CloudSyncSummary(
                        username = cleanUsername,
                        syncedDrives = storageChannelDao.all().size,
                        syncedAt = now,
                        usedCustomServer = cleanServerUrl.isNotBlank()
                    )
                )
            }
        } finally {
            _syncing.value = false
        }
    }

    override suspend fun signInAndSync(
        username: String,
        password: String,
        serverUrl: String
    ): AppResult<CloudSyncSummary> {
        val cleanUsername = username.trim()
        if (cleanUsername.isBlank()) {
            return AppResult.Failure(AppError.AuthenticationFailed("Username is required"))
        }
        if (password.length < 4) {
            return AppResult.Failure(AppError.AuthenticationFailed("Password must be at least 4 characters"))
        }

        _syncing.value = true
        try {
            return withContext(Dispatchers.IO) {
                val prefs = settingsRepository.preferences.first()
                val cleanServerUrl = normalizeServerUrl(serverUrl.ifBlank { prefs.cloudServerUrl })

                var sessionToken = ""
                var remoteEnvelopeText: String? = null

                if (cleanServerUrl.isNotBlank()) {
                    val loginResult = withTimeoutOrNull(12_000L) {
                        loginOnServer(cleanServerUrl, cleanUsername, password)
                    } ?: AppResult.Failure(
                        AppError.AuthenticationFailed("Connection timed out reaching $cleanServerUrl")
                    )
                    when (loginResult) {
                        is AppResult.Success -> {
                            sessionToken = loginResult.value.first
                            remoteEnvelopeText = loginResult.value.second
                        }
                        is AppResult.Failure -> return@withContext loginResult
                    }
                } else {
                    val vaultPull = withTimeoutOrNull(5_000L) {
                        pullVaultEnvelope(cleanUsername)
                    }
                    if (vaultPull is AppResult.Success) {
                        remoteEnvelopeText = vaultPull.value
                    }
                }

                if (!remoteEnvelopeText.isNullOrBlank()) {
                    when (val decrypted = decryptSnapshot(cleanUsername, password, remoteEnvelopeText)) {
                        is AppResult.Success -> applySnapshotToLocal(decrypted.value)
                        is AppResult.Failure -> return@withContext decrypted
                    }
                }

                // Encrypt and save merged session & settings back to cloud
                val mergedSnapshot = buildLocalSnapshot(cleanUsername)
                val mergedEnvelope = encryptSnapshot(cleanUsername, password, mergedSnapshot)
                val encodedMerged = json.encodeToString(CloudEnvelope.serializer(), mergedEnvelope)

                if (cleanServerUrl.isNotBlank() && sessionToken.isNotBlank()) {
                    withTimeoutOrNull(10_000L) {
                        pushEnvelopeToServer(cleanServerUrl, sessionToken, encodedMerged)
                    }
                } else {
                    pushVaultEnvelope(cleanUsername, encodedMerged)
                }

                val now = System.currentTimeMillis()
                settingsRepository.setCloudSecret(password)
                settingsRepository.update {
                    it.copy(
                        cloudUsername = cleanUsername,
                        cloudServerUrl = cleanServerUrl,
                        cloudSessionToken = sessionToken,
                        cloudLastSyncedAt = now
                    )
                }

                AppResult.Success(
                    CloudSyncSummary(
                        username = cleanUsername,
                        syncedDrives = storageChannelDao.all().size,
                        syncedAt = now,
                        usedCustomServer = cleanServerUrl.isNotBlank()
                    )
                )
            }
        } finally {
            _syncing.value = false
        }
    }

    override suspend fun pullFromCloud(
        username: String?,
        password: String?,
        serverUrl: String?
    ): AppResult<CloudSyncSummary> {
        val prefs = settingsRepository.preferences.first()
        val effectiveUser = (username ?: prefs.cloudUsername).trim()
        val effectivePass = password ?: settingsRepository.getCloudSecret()
        val effectiveServer = normalizeServerUrl(serverUrl ?: prefs.cloudServerUrl)

        if (effectiveUser.isBlank() || effectivePass.isNullOrEmpty()) {
            return AppResult.Failure(AppError.AuthenticationRequired)
        }

        _syncing.value = true
        try {
            return withContext(Dispatchers.IO) {
                var token = prefs.cloudSessionToken
                var envelopeText: String? = null

                if (effectiveServer.isNotBlank()) {
                    if (token.isBlank()) {
                        when (val login = loginOnServer(effectiveServer, effectiveUser, effectivePass)) {
                            is AppResult.Success -> {
                                token = login.value.first
                                envelopeText = login.value.second
                            }
                            is AppResult.Failure -> return@withContext login
                        }
                    } else {
                        when (val fetched = fetchEnvelopeFromServer(effectiveServer, token)) {
                            is AppResult.Success -> envelopeText = fetched.value
                            is AppResult.Failure -> {
                                // Re-authenticate if token expired
                                when (val relogin = loginOnServer(effectiveServer, effectiveUser, effectivePass)) {
                                    is AppResult.Success -> {
                                        token = relogin.value.first
                                        envelopeText = relogin.value.second
                                    }
                                    is AppResult.Failure -> return@withContext relogin
                                }
                            }
                        }
                    }
                } else {
                    val vaultRes = pullVaultEnvelope(effectiveUser)
                    if (vaultRes is AppResult.Success) {
                        envelopeText = vaultRes.value
                    }
                }

                if (!envelopeText.isNullOrBlank()) {
                    when (val decrypted = decryptSnapshot(effectiveUser, effectivePass, envelopeText)) {
                        is AppResult.Success -> applySnapshotToLocal(decrypted.value)
                        is AppResult.Failure -> return@withContext decrypted
                    }
                }

                val now = System.currentTimeMillis()
                settingsRepository.update {
                    it.copy(
                        cloudSessionToken = token,
                        cloudLastSyncedAt = now
                    )
                }

                AppResult.Success(
                    CloudSyncSummary(
                        username = effectiveUser,
                        syncedDrives = storageChannelDao.all().size,
                        syncedAt = now,
                        usedCustomServer = effectiveServer.isNotBlank()
                    )
                )
            }
        } finally {
            _syncing.value = false
        }
    }

    override suspend fun pushToCloud(
        passwordOverride: String?
    ): AppResult<CloudSyncSummary> {
        val prefs = settingsRepository.preferences.first()
        val effectiveUser = prefs.cloudUsername.trim()
        val effectivePass = passwordOverride ?: settingsRepository.getCloudSecret()
        val effectiveServer = normalizeServerUrl(prefs.cloudServerUrl)

        if (effectiveUser.isBlank() || effectivePass.isNullOrEmpty()) {
            return AppResult.Failure(AppError.AuthenticationRequired)
        }

        _syncing.value = true
        try {
            return withContext(Dispatchers.IO) {
                val snapshot = buildLocalSnapshot(effectiveUser)
                val envelope = encryptSnapshot(effectiveUser, effectivePass, snapshot)
                val encodedEnvelope = json.encodeToString(CloudEnvelope.serializer(), envelope)

                var token = prefs.cloudSessionToken
                if (effectiveServer.isNotBlank()) {
                    if (token.isBlank()) {
                        when (val login = loginOnServer(effectiveServer, effectiveUser, effectivePass)) {
                            is AppResult.Success -> token = login.value.first
                            is AppResult.Failure -> return@withContext login
                        }
                    }
                    val pushRes = pushEnvelopeToServer(effectiveServer, token, encodedEnvelope)
                    if (pushRes is AppResult.Failure) return@withContext pushRes
                } else {
                    val pushRes = pushVaultEnvelope(effectiveUser, encodedEnvelope)
                    if (pushRes is AppResult.Failure) return@withContext pushRes
                }

                val now = System.currentTimeMillis()
                settingsRepository.update {
                    it.copy(
                        cloudSessionToken = token,
                        cloudLastSyncedAt = now
                    )
                }

                AppResult.Success(
                    CloudSyncSummary(
                        username = effectiveUser,
                        syncedDrives = storageChannelDao.all().size,
                        syncedAt = now,
                        usedCustomServer = effectiveServer.isNotBlank()
                    )
                )
            }
        } finally {
            _syncing.value = false
        }
    }

    override suspend fun syncNow(): AppResult<CloudSyncSummary> {
        val prefs = settingsRepository.preferences.first()
        val secret = settingsRepository.getCloudSecret()
        if (prefs.cloudUsername.isBlank() || secret.isNullOrEmpty()) {
            return AppResult.Failure(AppError.AuthenticationRequired)
        }
        return signInAndSync(
            username = prefs.cloudUsername,
            password = secret,
            serverUrl = prefs.cloudServerUrl
        )
    }

    override suspend fun signOut() {
        mutex.withLock {
            settingsRepository.clearCloudSecret()
            settingsRepository.update {
                it.copy(
                    cloudUsername = "",
                    cloudServerUrl = "",
                    cloudSessionToken = "",
                    cloudLastSyncedAt = 0L
                )
            }
        }
    }

    private fun encryptSnapshot(
        username: String,
        password: String,
        snapshot: CloudProfileSnapshot
    ): CloudEnvelope {
        val userHash = usernameHash(username)
        val rawJson = json.encodeToString(CloudProfileSnapshot.serializer(), snapshot)
        val compressed = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(rawJson.toByteArray(Charsets.UTF_8))
            }
            baos.toByteArray()
        }
        val salt = passphraseKdf.newSalt()
        val key = passphraseKdf.deriveKey(
            passphrase = credentialMaterial(username, password),
            salt = salt,
            iterations = CloudEnvelope.DEFAULT_ITERATIONS
        )
        val ciphertext = streamCrypto.encryptBytes(key, compressed)
        return CloudEnvelope(
            usernameHash = userHash,
            kdfSaltBase64 = Base64.getEncoder().encodeToString(salt),
            kdfIterations = CloudEnvelope.DEFAULT_ITERATIONS,
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            updatedAt = snapshot.updatedAt
        )
    }

    private fun decryptSnapshot(
        username: String,
        password: String,
        envelopeText: String
    ): AppResult<CloudProfileSnapshot> {
        val envelope = runCatching {
            json.decodeFromString(CloudEnvelope.serializer(), envelopeText)
        }.getOrElse {
            return AppResult.Failure(AppError.Unknown("Corrupted encrypted cloud userdata"))
        }

        val salt = runCatching { Base64.getDecoder().decode(envelope.kdfSaltBase64) }.getOrNull()
            ?: return AppResult.Failure(AppError.Unknown("Invalid cloud salt"))
        val ciphertext = runCatching { Base64.getDecoder().decode(envelope.ciphertextBase64) }.getOrNull()
            ?: return AppResult.Failure(AppError.Unknown("Invalid cloud ciphertext"))

        val key = passphraseKdf.deriveKey(
            passphrase = credentialMaterial(username, password),
            salt = salt,
            iterations = envelope.kdfIterations
        )
        val compressed = try {
            streamCrypto.decryptBytes(key, ciphertext)
        } catch (_: GeneralSecurityException) {
            return AppResult.Failure(AppError.IncorrectPassword)
        }

        val snapshotJson = runCatching {
            ByteArrayInputStream(compressed).use { bais ->
                GZIPInputStream(bais).use { gzip ->
                    gzip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }.getOrElse {
            return AppResult.Failure(AppError.Unknown("Failed decompressing cloud userdata"))
        }

        return runCatching {
            AppResult.Success(json.decodeFromString(CloudProfileSnapshot.serializer(), snapshotJson))
        }.getOrElse {
            AppResult.Failure(AppError.Unknown("Failed parsing cloud session & settings"))
        }
    }

    private fun packTdlibDatabase(): Pair<String?, String?> {
        val key = wrappedKeyRepository.get(CryptoKeys.TDLIB_DATABASE)
        val keyBase64 = key?.let { Base64.getEncoder().encodeToString(it) }

        val dbDir = File(File(storagePaths.filesDir, "tdlib"), "db")
        if (!dbDir.exists() || !dbDir.isDirectory) {
            return Pair(null, keyBase64)
        }

        val files = dbDir.listFiles()?.filter { it.isFile && it.length() > 0 }
        if (files.isNullOrEmpty()) {
            return Pair(null, keyBase64)
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for (file in files) {
                runCatching {
                    val bytes = file.readBytes()
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        val zipBytes = baos.toByteArray()
        val zipBase64 = if (zipBytes.isNotEmpty()) Base64.getEncoder().encodeToString(zipBytes) else null
        return Pair(zipBase64, keyBase64)
    }

    private fun unpackTdlibDatabase(zipBase64: String?, keyBase64: String?): Boolean {
        if (!keyBase64.isNullOrBlank()) {
            val key = runCatching { Base64.getDecoder().decode(keyBase64) }.getOrNull()
            if (key != null && key.isNotEmpty()) {
                wrappedKeyRepository.store(CryptoKeys.TDLIB_DATABASE, key)
            }
        }

        if (zipBase64.isNullOrBlank()) return false
        val zipBytes = runCatching { Base64.getDecoder().decode(zipBase64) }.getOrNull()
            ?: return false
        if (zipBytes.isEmpty()) return false

        val dbDir = File(File(storagePaths.filesDir, "tdlib"), "db")
        if (dbDir.exists()) {
            dbDir.listFiles()?.forEach { it.delete() }
        } else {
            dbDir.mkdirs()
        }

        var unpackedCount = 0
        runCatching {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && !entry.name.contains("..") && !entry.name.contains("/")) {
                        val targetFile = File(dbDir, entry.name)
                        targetFile.outputStream().use { zis.copyTo(it) }
                        unpackedCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return unpackedCount > 0
    }

    private suspend fun buildLocalSnapshot(username: String): CloudProfileSnapshot {
        val prefs = settingsRepository.preferences.first()
        val tgCredentials = settingsRepository.getTelegramCredentials()
        val drives = storageChannelDao.all().map { entity ->
            CloudDriveEntry(
                chatId = entity.chatId,
                title = entity.title,
                backupFolders = entity.backupFolders,
                defaultsSeeded = entity.defaultsSeeded,
                remoteFileCount = entity.remoteFileCount,
                addedAt = entity.addedAt,
                lastOpenedAt = entity.lastOpenedAt
            )
        }
        val (dbZip, dbKey) = packTdlibDatabase()
        val settingsSnapshot = CloudSettingsSnapshot(
            autoBackupEnabled = prefs.autoBackupEnabled,
            instantBackupEnabled = prefs.instantBackupEnabled,
            backupFolders = prefs.backupFolders,
            backupWifiOnly = prefs.backupWifiOnly,
            backupChargingOnly = prefs.backupChargingOnly,
            backupIntervalHours = prefs.backupIntervalHours,
            backupMaxFileSizeMb = prefs.backupMaxFileSizeMb,
            maxCacheSizeMb = prefs.maxCacheSizeMb,
            trashAutoClearDays = prefs.trashAutoClearDays,
            blockScreenCapture = prefs.blockScreenCapture,
            autoLockTimeoutMinutes = prefs.autoLockTimeoutMinutes,
            encryptFiles = prefs.encryptFiles,
            deleteAfterUpload = prefs.deleteAfterUpload,
            encryptThumbnails = prefs.encryptThumbnails,
            theme = prefs.theme,
            language = prefs.language,
            dynamicColor = prefs.dynamicColor,
            viewMode = prefs.viewMode,
            gridSize = prefs.gridSize,
            galleryViewMode = prefs.galleryViewMode,
            galleryGridSize = prefs.galleryGridSize,
            albumGridSize = prefs.albumGridSize,
            layoutDensity = prefs.layoutDensity,
            showHiddenFiles = prefs.showHiddenFiles,
            showArchivedFiles = prefs.showArchivedFiles,
            showRecentFiles = prefs.showRecentFiles,
            linkPreviews = prefs.linkPreviews,
            textPreviewScale = prefs.textPreviewScale,
            sortField = prefs.sortField,
            sortDirection = prefs.sortDirection,
            gallerySortField = prefs.gallerySortField,
            gallerySortDirection = prefs.gallerySortDirection,
            backgroundPlayback = prefs.backgroundPlayback,
            streamBeforeDownload = prefs.streamBeforeDownload,
            backupNotifications = prefs.backupNotifications,
            failureNotifications = prefs.failureNotifications,
            transferConcurrency = prefs.transferConcurrency,
            transferRetryCount = prefs.transferRetryCount,
            allowMeteredTransfers = prefs.allowMeteredTransfers
        )

        return CloudProfileSnapshot(
            username = username,
            updatedAt = System.currentTimeMillis(),
            telegramApiId = tgCredentials?.apiId,
            telegramApiHash = tgCredentials?.apiHash,
            tdlibDatabaseZipBase64 = dbZip,
            tdlibDatabaseKeyBase64 = dbKey,
            activeStorageChatId = prefs.storageChatId,
            drives = drives,
            channelSyncModes = prefs.channelSyncModes,
            linkedChatTypes = prefs.linkedChatTypes,
            settings = settingsSnapshot
        )
    }

    private suspend fun applySnapshotToLocal(snapshot: CloudProfileSnapshot) {
        val apiId = snapshot.telegramApiId
        val apiHash = snapshot.telegramApiHash
        val hasValidCreds = apiId != null && apiId > 0 && !apiHash.isNullOrBlank() && apiHash.length >= 16

        // 1. Restore TDLib session database & database encryption key if present in snapshot
        val hasRemoteSessionDb = !snapshot.tdlibDatabaseZipBase64.isNullOrBlank()
        var sessionUnpacked = false
        if (hasRemoteSessionDb && telegramClient.authState.value != TelegramAuthState.Ready) {
            runCatching { telegramClient.close() }
            sessionUnpacked = unpackTdlibDatabase(
                snapshot.tdlibDatabaseZipBase64,
                snapshot.tdlibDatabaseKeyBase64
            )
        }

        // 2. Restore Telegram API credentials
        if (hasValidCreds) {
            settingsRepository.setTelegramCredentials(TelegramCredentials(apiId!!, apiHash!!))
        }

        // 3. Restore known storage channels
        for (drive in snapshot.drives) {
            val existing = storageChannelDao.byId(drive.chatId)
            if (existing == null) {
                storageChannelDao.upsert(
                    StorageChannelEntity(
                        chatId = drive.chatId,
                        title = drive.title,
                        backupFolders = drive.backupFolders,
                        photoPath = null,
                        defaultsSeeded = drive.defaultsSeeded,
                        remoteFileCount = drive.remoteFileCount,
                        addedAt = drive.addedAt,
                        lastOpenedAt = drive.lastOpenedAt
                    )
                )
            }
        }

        // 4. Restore user settings & channel sync modes
        val s = snapshot.settings
        val restoredChatId = snapshot.activeStorageChatId ?: snapshot.drives.firstOrNull()?.chatId
        settingsRepository.update { current ->
            val base = current.copy(
                onboardingComplete = if (sessionUnpacked && restoredChatId != null) true else current.onboardingComplete,
                storageChatId = current.storageChatId ?: restoredChatId,
                channelSyncModes = snapshot.channelSyncModes + current.channelSyncModes,
                linkedChatTypes = snapshot.linkedChatTypes + current.linkedChatTypes
            )
            if (s == null) {
                base
            } else {
                base.copy(
                    autoBackupEnabled = s.autoBackupEnabled,
                    instantBackupEnabled = s.instantBackupEnabled,
                    backupFolders = if (current.backupFolders.isEmpty()) s.backupFolders else current.backupFolders,
                    backupWifiOnly = s.backupWifiOnly,
                    backupChargingOnly = s.backupChargingOnly,
                    backupIntervalHours = s.backupIntervalHours,
                    backupMaxFileSizeMb = s.backupMaxFileSizeMb,
                    maxCacheSizeMb = s.maxCacheSizeMb,
                    trashAutoClearDays = s.trashAutoClearDays,
                    blockScreenCapture = s.blockScreenCapture,
                    autoLockTimeoutMinutes = s.autoLockTimeoutMinutes,
                    encryptFiles = s.encryptFiles,
                    deleteAfterUpload = s.deleteAfterUpload,
                    encryptThumbnails = s.encryptThumbnails,
                    theme = s.theme,
                    language = s.language,
                    dynamicColor = s.dynamicColor,
                    viewMode = s.viewMode,
                    gridSize = s.gridSize,
                    galleryViewMode = s.galleryViewMode,
                    galleryGridSize = s.galleryGridSize,
                    albumGridSize = s.albumGridSize,
                    layoutDensity = s.layoutDensity,
                    showHiddenFiles = s.showHiddenFiles,
                    showArchivedFiles = s.showArchivedFiles,
                    showRecentFiles = s.showRecentFiles,
                    linkPreviews = s.linkPreviews,
                    textPreviewScale = s.textPreviewScale,
                    sortField = s.sortField,
                    sortDirection = s.sortDirection,
                    gallerySortField = s.gallerySortField,
                    gallerySortDirection = s.gallerySortDirection,
                    backgroundPlayback = s.backgroundPlayback,
                    streamBeforeDownload = s.streamBeforeDownload,
                    backupNotifications = s.backupNotifications,
                    failureNotifications = s.failureNotifications,
                    transferConcurrency = s.transferConcurrency,
                    transferRetryCount = s.transferRetryCount,
                    allowMeteredTransfers = s.allowMeteredTransfers
                )
            }
        }

        // 5. Start TDLib client on the restored session database so it logs in immediately
        if (sessionUnpacked && hasValidCreds) {
            runCatching {
                telegramClient.start(TelegramCredentials(apiId!!, apiHash!!))
            }
        }
    }

    private fun registerOnServer(
        baseUrl: String,
        username: String,
        password: String,
        encodedEnvelope: String
    ): AppResult<String> = runCatching {
        val payload = buildJsonObject {
            put("username", username)
            put("password", password)
            put("envelope", encodedEnvelope)
        }.toString().toByteArray(Charsets.UTF_8)

        val conn = (URL("$baseUrl/api/cloud/register").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()

        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (code !in 200..299) {
            val errMsg = obj?.get("error")?.jsonPrimitive?.contentOrNull
                ?: "Registration failed (HTTP $code)"
            return AppResult.Failure(AppError.AuthenticationFailed(errMsg))
        }
        val token = obj?.get("token")?.jsonPrimitive?.contentOrNull.orEmpty()
        AppResult.Success(token)
    }.getOrElse { e ->
        AppResult.Failure(AppError.AuthenticationFailed(e.message ?: "Could not reach cloud server"))
    }

    private fun loginOnServer(
        baseUrl: String,
        username: String,
        password: String
    ): AppResult<Pair<String, String?>> = runCatching {
        val payload = buildJsonObject {
            put("username", username)
            put("password", password)
        }.toString().toByteArray(Charsets.UTF_8)

        val conn = (URL("$baseUrl/api/cloud/login").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()

        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (code == 401 || code == 403) {
            return AppResult.Failure(AppError.IncorrectPassword)
        }
        if (code !in 200..299) {
            val errMsg = obj?.get("error")?.jsonPrimitive?.contentOrNull
                ?: "Sign in failed (HTTP $code)"
            return AppResult.Failure(AppError.AuthenticationFailed(errMsg))
        }
        val token = obj?.get("token")?.jsonPrimitive?.contentOrNull.orEmpty()
        val envelopeElem = obj?.get("envelope")
        val envelopeText = when (envelopeElem) {
            is JsonObject -> envelopeElem.toString()
            else -> envelopeElem?.jsonPrimitive?.contentOrNull
        }
        AppResult.Success(token to envelopeText)
    }.getOrElse { e ->
        AppResult.Failure(AppError.AuthenticationFailed(e.message ?: "Could not reach cloud server"))
    }

    private fun fetchEnvelopeFromServer(
        baseUrl: String,
        token: String
    ): AppResult<String?> = runCatching {
        val conn = (URL("$baseUrl/api/cloud/userdata").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()

        if (code !in 200..299) {
            return AppResult.Failure(AppError.AuthenticationFailed("HTTP $code"))
        }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val envelopeElem = obj?.get("envelope")
        val envelopeText = when (envelopeElem) {
            is JsonObject -> envelopeElem.toString()
            else -> envelopeElem?.jsonPrimitive?.contentOrNull
        }
        AppResult.Success(envelopeText)
    }.getOrElse { e ->
        AppResult.Failure(AppError.AuthenticationFailed(e.message ?: "Failed fetching cloud userdata"))
    }

    private fun pushEnvelopeToServer(
        baseUrl: String,
        token: String,
        encodedEnvelope: String
    ): AppResult<Unit> = runCatching {
        val payload = buildJsonObject {
            put("envelope", encodedEnvelope)
        }.toString().toByteArray(Charsets.UTF_8)

        val conn = (URL("$baseUrl/api/cloud/userdata").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 8_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        if (code !in 200..299) {
            return AppResult.Failure(AppError.AuthenticationFailed("Failed saving userdata (HTTP $code)"))
        }
        AppResult.Success(Unit)
    }.getOrElse { e ->
        AppResult.Failure(AppError.AuthenticationFailed(e.message ?: "Failed saving cloud userdata"))
    }

    private fun localVaultFile(userHash: String): File {
        val vaultDir = File(storagePaths.cacheDir, "cloud_vaults").apply { mkdirs() }
        return File(vaultDir, vaultFileName(userHash))
    }

    private suspend fun resolveVaultChatId(): Long? {
        val linkable = withTimeoutOrNull(2_000L) {
            runCatching { telegramClient.listLinkableChats() }.getOrDefault(emptyList())
        }.orEmpty()
        return linkable.firstOrNull { it.chatType == TelegramChatType.SAVED_MESSAGES }?.chatId
    }

    private suspend fun findExistingVaultDocuments(
        chatId: Long,
        userHash: String
    ): List<RemoteDocument> {
        val captionTag = "$VAULT_CAPTION_PREFIX$userHash"
        val fileName = vaultFileName(userHash)
        val matches = mutableListOf<RemoteDocument>()
        var fromMessageId = 0L
        var pages = 0
        while (pages++ < MAX_VAULT_PAGES) {
            val page = telegramClient.fetchDocuments(chatId, fromMessageId, VAULT_PAGE_SIZE)
            matches.addAll(
                page.documents.filter { doc ->
                    doc.fileName == fileName || doc.caption.startsWith(captionTag)
                }
            )
            if (matches.isNotEmpty() || page.nextFromMessageId == 0L) break
            fromMessageId = page.nextFromMessageId
        }
        return matches.sortedByDescending { it.dateSeconds }
    }

    private suspend fun pullVaultEnvelope(username: String): AppResult<String?> = runTelegram {
        val userHash = usernameHash(username)
        val localVault = localVaultFile(userHash)
        var envelopeText: String? = null

        if (telegramClient.authState.value == TelegramAuthState.Ready) {
            val chatId = runCatching { resolveVaultChatId() }.getOrNull()
            val matchingDoc = chatId?.let {
                runCatching { findExistingVaultDocuments(it, userHash).firstOrNull() }.getOrNull()
            }
            if (matchingDoc != null) {
                var downloadedPath: String? = null
                telegramClient.downloadDocument(matchingDoc.remoteFileId).collect { event ->
                    if (event is TelegramDownloadEvent.Completed) downloadedPath = event.localPath
                }
                val downloadedFile = downloadedPath?.let(::File)?.takeIf { it.exists() }
                if (downloadedFile != null) {
                    envelopeText = downloadedFile.readText(Charsets.UTF_8)
                    runCatching { localVault.writeText(envelopeText, Charsets.UTF_8) }
                }
            }
        }

        if (envelopeText == null && localVault.exists()) {
            envelopeText = localVault.readText(Charsets.UTF_8)
        }
        AppResult.Success(envelopeText)
    }

    private suspend fun pushVaultEnvelope(
        username: String,
        encodedEnvelope: String
    ): AppResult<Unit> = runTelegram {
        val userHash = usernameHash(username)
        val captionTag = "$VAULT_CAPTION_PREFIX$userHash"
        val fileName = vaultFileName(userHash)

        localVaultFile(userHash).writeText(encodedEnvelope, Charsets.UTF_8)

        if (telegramClient.authState.value == TelegramAuthState.Ready) {
            val chatId = resolveVaultChatId()
            if (chatId != null) {
                val existingDocs = runCatching { findExistingVaultDocuments(chatId, userHash) }.getOrDefault(emptyList())
                val tempFile = File(storagePaths.cacheDir, fileName)
                try {
                    tempFile.writeText(encodedEnvelope, Charsets.UTF_8)
                    telegramClient.uploadDocument(
                        chatId = chatId,
                        localPath = tempFile.absolutePath,
                        fileName = fileName,
                        mimeType = "application/octet-stream",
                        caption = captionTag
                    ).collect { event ->
                        if (event is TelegramUploadEvent.Completed) Unit
                    }
                    if (existingDocs.isNotEmpty()) {
                        telegramClient.deleteMessages(chatId, existingDocs.map { it.messageId })
                    }
                } finally {
                    tempFile.delete()
                }
            }
        }
        AppResult.Success(Unit)
    }

    private suspend fun <T> runTelegram(block: suspend () -> AppResult<T>): AppResult<T> = try {
        block()
    } catch (e: TelegramException) {
        AppResult.Failure(
            if (e.isRateLimit) AppError.RateLimited(e.retryAfterSeconds ?: 0)
            else AppError.TelegramError(e.code, e.message)
        )
    } catch (e: Exception) {
        AppResult.Failure(AppError.Unknown(e.message ?: "Cloud vault operation failed"))
    }

    private fun normalizeServerUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun usernameHash(username: String): String {
        val normalized = username.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("teledrive-cloud-user:" + normalized).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun credentialMaterial(username: String, password: String): CharArray {
        val normalizedUser = username.trim().lowercase()
        return "$normalizedUser:$password".toCharArray()
    }

    private fun vaultFileName(userHash: String): String =
        ".teledrive_session_$userHash.enc"

    private companion object {
        const val VAULT_CAPTION_PREFIX = "#teledrive_session_v1:"
        const val VAULT_PAGE_SIZE = 50
        const val MAX_VAULT_PAGES = 4
    }
}
