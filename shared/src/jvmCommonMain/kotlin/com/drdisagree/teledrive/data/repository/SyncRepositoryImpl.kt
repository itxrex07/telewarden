package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.crypto.KeyBackupCodec
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.transfer.FileParts
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.PendingDeleteDao
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.database.inImmediateTransaction
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.data.remote.telegram.ExtraFields
import com.drdisagree.teledrive.data.remote.telegram.IndexSnapshotFile
import com.drdisagree.teledrive.data.remote.telegram.LoggerRecordCodec
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderState
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveSyncMode
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import com.drdisagree.teledrive.domain.repository.SyncStats
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * The chat is the source of truth for remote-backed files; after a local wipe this rebuilds the
 * whole drive.
 */
class SyncRepositoryImpl(
    private val telegramClient: TelegramClient,
    private val fileDao: FileDao,
    private val manifestCodec: ManifestCodec,
    private val loggerRecordCodec: LoggerRecordCodec,
    private val pendingDeleteDao: PendingDeleteDao,
    private val filePartDao: FilePartDao,
    private val folderDao: FolderDao,
    private val folderPathResolver: FolderPathResolver,
    private val activeChannel: ActiveChannel,
    private val channelOwnership: ChannelOwnership,
    private val folderStateSynchronizer: FolderStateSynchronizer,
    private val folderMerger: FolderMerger,
    private val indexSnapshot: IndexSnapshot,
    private val publishScheduler: PublishScheduler,
    private val settingsRepository: SettingsRepository,
    private val database: TeleDriveDatabase
) : SyncRepository {

    private val _syncing = MutableStateFlow(false)
    override val syncing: Flow<Boolean> = _syncing

    private val _indexedSoFar = MutableStateFlow(0)
    override val indexedSoFar: Flow<Int> = _indexedSoFar
    private val syncMutex = Mutex()
    private val captionRepairsQueued = mutableSetOf<String>()

    @Volatile
    private var lastFullSyncAt = 0L

    override suspend fun fullResync(): AppResult<SyncStats> =
        runSync(incremental = false)

    override suspend fun incrementalSync(): AppResult<SyncStats> =
        runSync(incremental = true)

    override suspend fun syncOnStart(): AppResult<SyncStats> {
        fileDao.repairBackedUpStates()
        return runSync(incremental = fileDao.fileCount(activeChannel.id()) > 0)
    }

    override suspend fun catchUpWithRemote(): AppResult<SyncStats>? {
        if (syncMutex.isLocked) return null
        val since = System.currentTimeMillis() - lastFullSyncAt
        if (since in 0 until CATCH_UP_INTERVAL_MS) return null
        return runSync(incremental = false, quiet = true)
    }

    private suspend fun runSync(
        incremental: Boolean,
        quiet: Boolean = false
    ): AppResult<SyncStats> =
        syncMutex.withLock {
            if (!quiet) {
                _syncing.value = true
                _indexedSoFar.value = 0
            }
            try {
                doSync(incremental, quiet).also { result ->
                    if (!incremental && result is AppResult.Success) {
                        lastFullSyncAt = System.currentTimeMillis()
                    }
                }
            } catch (e: TelegramException) {
                AppResult.Failure(
                    if (e.isRateLimit) AppError.RateLimited(e.retryAfterSeconds ?: 0)
                    else AppError.TelegramError(e.code, e.message)
                )
            } finally {
                if (!quiet) _syncing.value = false
            }
        }

    private suspend fun forgetChat(chatId: Long) {
        val removed = fileDao.deleteRemoteOnlyInChat(chatId)
        val detached = fileDao.detachChat(chatId)
        SafeLog.d(TAG, "Storage chat changed: dropped $removed, detached $detached")
    }

    private suspend fun syncForumTopics(chatId: Long): Set<Long> {
        val topics = runCatching { telegramClient.fetchForumTopics(chatId) }
            .getOrDefault(emptyList())
        if (topics.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        for (topic in topics) {
            val id = topicFolderId(chatId, topic.topicId)
            val existing = folderDao.byId(id)
            val createdMs = if (topic.createdAtSeconds > 0) topic.createdAtSeconds * 1000L else now
            folderDao.upsert(
                FolderEntity(
                    id = id,
                    chatId = chatId,
                    parentId = null,
                    name = topic.name,
                    isHidden = existing?.isHidden ?: false,
                    isArchived = existing?.isArchived ?: false,
                    isFavorite = existing?.isFavorite ?: false,
                    isAvailableOffline = existing?.isAvailableOffline ?: false,
                    trashedAt = existing?.trashedAt,
                    pendingPublish = false,
                    createdAt = existing?.createdAt ?: createdMs,
                    modifiedAt = existing?.modifiedAt ?: createdMs,
                    changedAt = existing?.changedAt ?: createdMs
                )
            )
        }
        return topics.map { it.topicId }.toSet()
    }

    private fun topicFolderId(chatId: Long, topicId: Long): String =
        "topic_${chatId}_$topicId"

    private suspend fun doSync(
        incremental: Boolean,
        quiet: Boolean
    ): AppResult<SyncStats> {
        val prefs = settingsRepository.preferences.first()
        val chatId = telegramClient.ensureStorageChat(prefs.storageChatId)
        channelOwnership.claimUnowned(chatId)
        if (chatId != prefs.storageChatId) {
            prefs.storageChatId?.let { previous -> forgetChat(previous) }
            settingsRepository.update { it.copy(storageChatId = chatId) }
        }

        val syncMode = prefs.syncModeFor(chatId)
        val isFullMode = syncMode == DriveSyncMode.FULL
        val topicIds = syncForumTopics(chatId)

        var fromSnapshot = 0
        if (!incremental && isFullMode) {
            if (folderDao.pendingPublishCount() > 0) publishScheduler.kick()
            runCatching { folderStateSynchronizer.pull() }
                .onFailure { SafeLog.w(TAG, "Folder state pull failed", it) }
            folderMerger.mergeDuplicates(chatId)
            if (folderDao.pendingPublishCount() > 0) publishScheduler.kick()
            if (fileDao.fileCount(chatId) == 0) fromSnapshot = applySnapshot(chatId, quiet)
        }

        val pendingDeletes = pendingDeleteDao.messageIdsIn(chatId).toSet()
        val mappedBeforeScan = if (incremental) {
            emptyMap()
        } else {
            fileDao.filesWithRemote().associate { it.id to it.messageId }
        }
        val folderCache = mutableMapOf<String, String?>()
        SafeLog.d(TAG, "Sync start chat=$chatId incremental=$incremental mode=$syncMode")
        var inserted = fromSnapshot
        var updated = 0
        var locked = 0
        val repairsBefore = captionRepairsQueued.size
        val seenMessageIds = mutableSetOf<Long>()
        val channelDocuments = mutableListOf<RemoteDocument>()
        val snapshots = mutableListOf<RemoteDocument>()
        var fromMessageId = 0L
        var pages = 0

        while (pages++ < MAX_PAGES) {
            val page = try {
                telegramClient.fetchDocuments(chatId, fromMessageId, PAGE_SIZE)
            } catch (e: TelegramException) {
                if (e.isRateLimit) {
                    delay((((e.retryAfterSeconds ?: 5) + 1) * 1000L).milliseconds)
                    continue
                }
                throw e
            }
            val decodedPage = page.copy(documents = page.documents.map(::decodeLoggerDocument))
            SafeLog.d(TAG, "Sync page ${decodedPage.documents.size} documents")
            if (decodedPage.documents.isEmpty() && decodedPage.nextFromMessageId == 0L) break

            var reachedKnown = false
            val known = existingForPage(decodedPage.documents)
            database.inImmediateTransaction {
                for (document in decodedPage.documents) {
                    seenMessageIds.add(document.messageId)
                    // Still in the chat, but a delete is owed for it.
                    if (document.messageId in pendingDeletes) continue
                    if (indexSnapshot.isSnapshot(document)) snapshots += document
                    if (isInternalDocument(document)) continue
                    channelDocuments += document
                    if (manifestCodec.isLocked(document.caption) || loggerRecordCodec.isLocked(document.caption)) {
                        locked++
                        continue
                    }
                    if (incremental && !isNewToLocal(document, known)) {
                        reachedKnown = true
                        break
                    }
                    when (reconcile(document, folderCache, known, isFullMode, topicIds)) {
                        ReconcileResult.INSERTED -> {
                            inserted++
                            if (!quiet) _indexedSoFar.value = inserted
                        }

                        ReconcileResult.UPDATED -> updated++
                        ReconcileResult.UNCHANGED -> Unit
                    }
                }
            }
            if (reachedKnown) {
                if (isFullMode && captionRepairsQueued.size > repairsBefore) publishScheduler.kick()
                return finishSync(
                    chatId, seenMessageIds, mappedBeforeScan, inserted, updated, locked,
                    partial = true
                )
            }
            if (decodedPage.nextFromMessageId == 0L) break
            fromMessageId = decodedPage.nextFromMessageId
        }

        if (isFullMode && captionRepairsQueued.size > repairsBefore) publishScheduler.kick()
        val result = finishSync(
            chatId, seenMessageIds, mappedBeforeScan, inserted, updated, locked,
            partial = incremental
        )
        if (!incremental && isFullMode) {
            runCatching { indexSnapshot.refresh(chatId, channelDocuments, snapshots) }
                .onFailure { SafeLog.w(TAG, "Index snapshot refresh failed", it) }
        }
        return result
    }

    private suspend fun applySnapshot(chatId: Long, quiet: Boolean): Int {
        val documents = runCatching { indexSnapshot.load(chatId) }
            .onFailure { SafeLog.w(TAG, "Index snapshot load failed", it) }
            .getOrNull()
            .orEmpty()
            .filter { !isInternalDocument(it) && !manifestCodec.isLocked(it.caption) }
        if (documents.isEmpty()) return 0
        val folderCache = mutableMapOf<String, String?>()
        var inserted = 0
        documents.sortedByDescending { it.messageId }.chunked(PAGE_SIZE).forEach { page ->
            val known = existingForPage(page)
            database.inImmediateTransaction {
                for (document in page) {
                    if (reconcile(document, folderCache, known, isFullMode = true, topicIds = emptySet()) == ReconcileResult.INSERTED) {
                        inserted++
                    }
                }
            }
            if (!quiet) _indexedSoFar.value = inserted
        }
        SafeLog.d(TAG, "Index snapshot added $inserted files")
        return inserted
    }

    private suspend fun finishSync(
        chatId: Long,
        seenMessageIds: Set<Long>,
        mappedBeforeScan: Map<String, Long?>,
        inserted: Int,
        updated: Int,
        locked: Int,
        partial: Boolean
    ): AppResult<SyncStats> {
        var detached = 0
        // Locked files count as seen, so pruning needs no restored key. Uploads that finish
        // mid-scan are newer than the pages read.
        if (!partial) {
            val stale = fileDao.filesWithRemote().filter { entity ->
                val messageId = entity.messageId
                messageId != null && entity.chatId == chatId && messageId !in seenMessageIds &&
                        mappedBeforeScan[entity.id] == messageId
            }
            detached = detach(stale)
        }
        SafeLog.d(TAG, "Sync done: +$inserted ~$updated -$detached, $locked locked")
        return AppResult.Success(
            SyncStats(inserted, updated, detached, locked)
        )
    }

    private suspend fun detach(stale: List<FileEntity>): Int {
        if (stale.isEmpty()) return 0
        database.inImmediateTransaction {
            val (orphaned, localOnly) = stale.partition { it.localPath == null }
            if (orphaned.isNotEmpty()) {
                fileDao.deleteByIds(orphaned.map { it.id })
            }
            localOnly.forEach { entity -> fileDao.detachRemote(entity.id) }
        }
        return stale.size
    }

    override suspend fun followRemoteChanges() {
        activeChannel.observe().filterNotNull().collectLatest { chatId ->
            runCatching { telegramClient.openChat(chatId) }
            try {
                ChangeBatcher(LIVE_BATCH_MS).run(telegramClient.messageChanges(chatId)) { updated, deleted ->
                    applyRemoteChanges(chatId, updated, deleted)
                }
            } finally {
                withContext(NonCancellable) { runCatching { telegramClient.closeChat(chatId) } }
            }
        }
    }

    /**
     * Only writes the local database, so applying another device's edit never publishes it back.
     */
    internal suspend fun applyRemoteChanges(
        chatId: Long,
        updated: Set<Long>,
        deleted: Set<Long>
    ) = syncMutex.withLock {
        try {
            val prefs = settingsRepository.preferences.first()
            val isFullMode = prefs.syncModeFor(chatId) == DriveSyncMode.FULL
            val topicIds = syncForumTopics(chatId)
            val pendingDeletes = pendingDeleteDao.messageIdsIn(chatId).toSet()
            val documents = updated.sortedDescending()
                .filter { it !in pendingDeletes }
                .mapNotNull { telegramClient.getDocument(chatId, it) }
                .map(::decodeLoggerDocument)

            if (isFullMode && documents.any { it.isFolderState() }) {
                runCatching { folderStateSynchronizer.pull() }
                    .onFailure { SafeLog.w(TAG, "Folder state pull failed", it) }
                if (folderDao.pendingPublishCount() > 0) publishScheduler.kick()
            }

            val files = documents.filter {
                !isInternalDocument(it) && !manifestCodec.isLocked(it.caption) &&
                        !loggerRecordCodec.isLocked(it.caption)
            }
            if (files.isNotEmpty()) {
                val repairsBefore = captionRepairsQueued.size
                val known = existingForPage(files)
                val folderCache = mutableMapOf<String, String?>()
                database.inImmediateTransaction {
                    files.forEach { reconcile(it, folderCache, known, isFullMode, topicIds) }
                }
                if (isFullMode && captionRepairsQueued.size > repairsBefore) publishScheduler.kick()
            }

            val gone = deleted - pendingDeletes
            if (gone.isNotEmpty()) {
                detach(fileDao.filesWithRemote().filter { it.chatId == chatId && it.messageId in gone })
            }
        } catch (e: TelegramException) {
            SafeLog.w(TAG, "Applying remote changes failed: ${e.code}")
        }
    }

    private fun RemoteDocument.isFolderState(): Boolean =
        fileName == RemoteFolderState.FILE_NAME || caption.startsWith(RemoteFolderState.MARKER)

    /** App-managed bookkeeping documents must never surface as user files. */
    private fun isInternalDocument(document: RemoteDocument): Boolean =
        document.fileName == RemoteFolderState.FILE_NAME ||
                document.fileName == KeyBackupCodec.BACKUP_FILE_NAME ||
                document.fileName == IndexSnapshotFile.FILE_NAME ||
                document.caption.startsWith(RemoteFolderState.MARKER) ||
                document.caption.startsWith(IndexSnapshotFile.MARKER) ||
                document.caption.startsWith(KEY_BACKUP_MARKER) ||
                document.caption.startsWith(ICON_MARKER)

    private fun decodeLoggerDocument(document: RemoteDocument): RemoteDocument {
        val metadata = loggerRecordCodec.decode(document.caption) ?: return document
        return document.copy(
            fileName = metadata.original_filename,
            mimeType = metadata.mime_type,
            sizeBytes = metadata.size_bytes,
            encryptionKey = CryptoKeys.LOGGER,
            displayCaption = metadata.caption
        )
    }

    private fun isNewToLocal(document: RemoteDocument, known: KnownRows): Boolean {
        val existing = known.byUniqueId[document.uniqueFileId] ?: return true
        return existing.messageId != document.messageId
    }

    private suspend fun existingForPage(documents: List<RemoteDocument>): KnownRows {
        val manifests = documents.associateWith { manifestCodec.decode(it.caption) }
        val fileIds = manifests.values.mapNotNull { it?.fileId }.distinct()
        val uniqueIds = documents.map { it.uniqueFileId }.distinct()
        val byId = if (fileIds.isEmpty()) {
            emptyMap()
        } else {
            fileDao.byIds(fileIds).associateBy { it.id }
        }
        val byUniqueId = if (uniqueIds.isEmpty()) {
            emptyMap()
        } else {
            fileDao.byRemoteUniqueIds(uniqueIds).mapNotNull { entity ->
                entity.remoteUniqueId?.let { it to entity }
            }.toMap()
        }
        val parts = if (fileIds.isEmpty()) emptyList() else filePartDao.partsOfAll(fileIds)
        return KnownRows(
            manifests = manifests,
            byId = byId,
            byUniqueId = byUniqueId,
            splitCounts = splitCounts(manifests.values, byId, parts),
            firstPartStored = parts.filter { it.partIndex == 0 }.map { it.fileId }.toSet()
        )
    }

    private fun splitCounts(
        manifests: Collection<RemoteFileManifest?>,
        byId: Map<String, FileEntity>,
        parts: List<FilePartEntity>
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        fun note(fileId: String, count: Int) {
            if (count > 1) counts[fileId] = maxOf(counts[fileId] ?: 0, count)
        }
        manifests.forEach { manifest ->
            if (manifest?.isPart == true) note(manifest.fileId, manifest.partCount)
        }
        byId.values.forEach { entity -> note(entity.id, entity.partCount) }
        parts.groupBy { it.fileId }
            .forEach { (fileId, stored) -> note(fileId, stored.maxOf { it.partIndex } + 1) }
        return counts
    }

    /**
     * A part 0 caption can lose its part fields; its siblings, part rows or recorded count still
     * prove the split.
     */
    private fun splitCountOfDamagedFirstPart(document: RemoteDocument, known: KnownRows): Int? {
        val manifest = known.manifests[document] ?: return null
        if (manifest.isPart) return null
        return known.splitCounts[manifest.fileId]
    }

    /**
     * Paths are only a fallback: two folders can share a name, and a trashed file must not recreate
     * its folder.
     */
    private suspend fun resolveFolder(
        manifest: RemoteFileManifest,
        cache: MutableMap<String, String?>,
        chatId: Long,
        messageId: Long
    ): String? {
        manifest.folderId?.let { id ->
            if (cache.containsValue(id)) return id
            if (folderPathResolver.exists(id, chatId)) {
                cache[manifest.folderPath] = id
                return id
            }
        }
        if (cache.containsKey(manifest.folderPath)) return cache[manifest.folderPath]
        val resolved = if (manifest.trashedAt != null) {
            folderPathResolver.resolveExisting(manifest.folderPath, chatId)
        } else {
            folderPathResolver.resolveExisting(manifest.folderPath, chatId)
                ?: folderPathResolver.resolveOrCreate(
                    manifest.folderPath,
                    manifest.folderId,
                    chatId = chatId
                ).also { created ->
                    SafeLog.d(
                        TAG,
                        "Message $messageId recreated its folder path as $created " +
                                "(part ${manifest.partIndex} of ${manifest.partCount})"
                    )
                }
        }
        cache[manifest.folderPath] = resolved
        return resolved
    }

    private suspend fun ensureTopicFolder(chatId: Long, topicId: Long, knownTopicIds: Set<Long>): String {
        val id = topicFolderId(chatId, topicId)
        if (topicId !in knownTopicIds && folderDao.byId(id) == null) {
            val now = System.currentTimeMillis()
            val label = if (topicId == 1L) "General" else "Topic #$topicId"
            folderDao.upsert(
                FolderEntity(
                    id = id,
                    chatId = chatId,
                    parentId = null,
                    name = label,
                    pendingPublish = false,
                    createdAt = now,
                    modifiedAt = now,
                    changedAt = now
                )
            )
        }
        return id
    }

    private suspend fun reconcile(
        document: RemoteDocument,
        folderCache: MutableMap<String, String?>,
        known: KnownRows,
        isFullMode: Boolean,
        topicIds: Set<Long>
    ): ReconcileResult {
        val manifest = known.manifests[document]
        val existing = manifest?.let { known.byId[it.fileId] }
            ?: known.byUniqueId[document.uniqueFileId]

        val damagedSplitCount = splitCountOfDamagedFirstPart(document, known)
        if (manifest != null && manifest.isPart) {
            recordPart(document, manifest)
        } else if (manifest != null && damagedSplitCount != null &&
            manifest.fileId !in known.firstPartStored
        ) {
            recordPart(document, FileParts.asFirstPart(manifest, damagedSplitCount))
        }
        val queueCaptionRepair = isFullMode && manifest != null && damagedSplitCount != null &&
                existing?.pendingPublish != true && captionRepairsQueued.add(manifest.fileId)

        val laterPart = manifest != null && manifest.isPart && manifest.partIndex != 0
        if (laterPart && existing != null) return ReconcileResult.UNCHANGED

        val local = existing?.takeIf { it.pendingPublish }
        val choice = FieldChoice(
            local = local?.let { FileBase.of(it) },
            remote = manifest?.let { FileBase.of(it) },
            base = FileBase.fromText(existing?.remoteBase),
            localIsNewer = local != null &&
                    (manifest == null || local.modifiedAt >= manifest.modifiedAt)
        )

        val folderId = when {
            local != null && choice.keepsLocal { it.folderId } ->
                local.folderId ?: local.preTrashFolderId

            manifest == null -> {
                val docTopicId = document.topicId
                if (docTopicId != null && (docTopicId in topicIds || topicIds.isNotEmpty())) {
                    ensureTopicFolder(document.chatId, docTopicId, topicIds)
                } else {
                    existing?.folderId
                }
            }
            laterPart -> manifest.folderId?.takeIf {
                folderPathResolver.exists(it, document.chatId)
            }
            else -> resolveFolder(manifest, folderCache, document.chatId, document.messageId)
        }
        val folderTrashedAt = folderId?.let { folderDao.byId(it)?.trashedAt }

        val name = local?.name?.takeIf { choice.keepsLocal { it.name } }
            ?: manifest?.name ?: existing?.name
            ?: document.fileName.ifBlank { "file-${document.messageId}" }
        val mime = manifest?.mimeType ?: document.mimeType.ifBlank {
            MimeTypes.fromFileName(name)
        }

        val now = System.currentTimeMillis()
        val trashedAt = when {
            local != null && choice.keepsLocal { it.trashedAt } -> local.trashedAt
            manifest != null && choice.remoteChanged { it.trashedAt } ->
                manifest.trashedAt ?: folderTrashedAt

            else -> manifest?.trashedAt ?: folderTrashedAt ?: existing?.trashedAt
        }
        val manifestExtraText = when {
            document.textContent != null -> ExtraFields.toText(
                (manifest?.extra.orEmpty()) + ("tg_text" to JsonPrimitive(document.textContent))
            )
            manifest != null -> ExtraFields.toText(manifest.extra)
            else -> existing?.manifestExtra
        }
        val entity = FileEntity(
            id = manifest?.fileId ?: existing?.id ?: UUID.randomUUID().toString(),
            folderId = if (trashedAt != null) null else folderId,
            name = name,
            sizeBytes = manifest?.sizeBytes ?: document.sizeBytes,
            mimeType = mime,
            category = FileCategory.fromMimeType(mime),
            localPath = existing?.localPath,
            contentHash = manifest?.contentHash ?: existing?.contentHash,
            chatId = document.chatId,
            messageId = document.messageId,
            remoteFileId = document.remoteFileId,
            remoteUniqueId = document.uniqueFileId,
            backupState = BackupState.BACKED_UP,
            isHidden = local?.isHidden?.takeIf { choice.keepsLocal { it.hidden } }
                ?: manifest?.hidden ?: existing?.isHidden ?: false,
            isArchived = local?.isArchived?.takeIf { choice.keepsLocal { it.archived } }
                ?: manifest?.archived ?: existing?.isArchived ?: false,
            isFavorite = local?.isFavorite?.takeIf { choice.keepsLocal { it.favorite } }
                ?: manifest?.favorite ?: existing?.isFavorite ?: false,
            isAvailableOffline = existing?.isAvailableOffline == true,
            isEncrypted = manifest?.encrypted ?: (
                document.encryptionKey != null || manifestCodec.isEncryptedManifest(document.caption)
            ),
            encryptionKey = document.encryptionKey ?: existing?.encryptionKey ?: CryptoKeys.CONTENT,
            width = manifest?.width ?: existing?.width,
            height = manifest?.height ?: existing?.height,
            durationMs = manifest?.durationMs ?: existing?.durationMs,
            trashedAt = trashedAt,
            preTrashFolderId = if (trashedAt != null) {
                folderId ?: existing?.preTrashFolderId
            } else {
                existing?.preTrashFolderId
            },
            pendingPublish = existing?.pendingPublish == true || queueCaptionRepair,
            createdAt = manifest?.createdAt ?: existing?.createdAt
            ?: (document.dateSeconds * 1000L),
            modifiedAt = maxOf(
                local?.modifiedAt ?: Long.MIN_VALUE,
                manifest?.modifiedAt ?: existing?.modifiedAt ?: (document.dateSeconds * 1000L)
            ),
            addedAt = existing?.addedAt ?: now,
            partCount = damagedSplitCount ?: manifest?.partCount ?: existing?.partCount ?: 0,
            iconFileId = manifest?.iconFileId ?: existing?.iconFileId,
            manifestExtra = manifestExtraText,
            remoteBase = manifest?.let { FileBase.of(it).toText() } ?: existing?.remoteBase
        )

        if (laterPart) {
            fileDao.upsert(
                entity.copy(
                    chatId = null,
                    messageId = null,
                    remoteFileId = null,
                    remoteUniqueId = null
                )
            )
            return ReconcileResult.INSERTED
        }

        return when {
            existing == null -> {
                fileDao.upsert(entity)
                ReconcileResult.INSERTED
            }

            existing.id != entity.id -> {
                fileDao.deleteByIds(listOf(existing.id))
                fileDao.upsert(entity)
                ReconcileResult.UPDATED
            }

            existing != entity -> {
                fileDao.upsert(entity)
                ReconcileResult.UPDATED
            }

            else -> ReconcileResult.UNCHANGED
        }
    }

    private suspend fun recordPart(document: RemoteDocument, manifest: RemoteFileManifest) {
        filePartDao.upsert(
            FilePartEntity(
                fileId = manifest.fileId,
                partIndex = manifest.partIndex,
                chatId = document.chatId,
                messageId = document.messageId,
                remoteFileId = document.remoteFileId,
                remoteUniqueId = document.uniqueFileId,
                plainOffset = manifest.partOffset,
                plainSize = manifest.partSize,
                storedSize = document.sizeBytes,
                uploadedAt = document.dateSeconds * 1000L
            )
        )
    }

    companion object {
        private const val TAG = "SyncRepository"
        private const val KEY_BACKUP_MARKER = "#teledrive-keybackup"
        private const val ICON_MARKER = "#teledrive-icon"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 2000
        private const val CATCH_UP_INTERVAL_MS = 60_000L
        private const val LIVE_BATCH_MS = 2_000L
    }
}
