package com.drdisagree.teledrive.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.files.Urls
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.ThumbnailDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.ThumbnailEntity
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Cache files are sealed when thumbnail encryption is on, so private media never sits readable in
 * the cache.
 */
class AndroidThumbnailStore(
    private val context: Context,
    private val fileDao: FileDao,
    private val filePartDao: FilePartDao,
    private val thumbnailDao: ThumbnailDao,
    private val telegramClient: TelegramClient,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val settingsRepository: SettingsRepository
) : ThumbnailStore {

    private val generationSemaphore = Semaphore(3)

    override suspend fun thumbnailBytes(fileId: String): ByteArray? {
        if (!settingsRepository.preferences.first().linkPreviews && isNote(fileId)) {
            thumbnailDao.delete(fileId)
            return null
        }
        thumbnailDao.byFileId(fileId)?.let { cached ->
            val file = File(cached.path)
            if (file.exists()) {
                thumbnailDao.touch(fileId, System.currentTimeMillis())
                val raw = file.readBytes()
                return if (cached.encrypted) {
                    runCatching {
                        streamCrypto.decryptBytes(thumbnailKey(), raw)
                    }.getOrNull()
                } else raw
            }
            thumbnailDao.delete(fileId)
        }
        return generationSemaphore.withPermit { generate(fileId) }
    }

    override suspend fun uploadThumbnailFile(fileId: String): File? {
        val bytes = thumbnailBytes(fileId) ?: return null
        val dir = File(context.cacheDir, "upload-thumbs").apply { mkdirs() }
        val target = File(dir, "$fileId.jpg")
        return runCatching {
            target.writeBytes(bytes)
            target
        }.getOrNull()
    }

    private suspend fun isNote(fileId: String): Boolean =
        fileDao.byId(fileId)?.let { MimeTypes.isText(it.mimeType) } == true

    private suspend fun generate(fileId: String): ByteArray? {
        val entity = fileDao.byId(fileId) ?: return null
        val bitmap = entity.localPath?.let { path ->
            val file = File(path)
            when {
                !file.exists() -> null
                MimeTypes.isText(entity.mimeType) -> linkThumbnail(file)
                else -> decodeThumbnail(file, entity.mimeType)
            }
        }

        val jpegBytes = if (bitmap != null) {
            encodeBitmapToJpeg(bitmap)
        } else {
            val iconFileId = entity.iconFileId
            val remoteBytes = if (iconFileId != null) {
                fetchIconBytes(entity, iconFileId) ?: fetchRemoteThumbnail(entity)
            } else {
                fetchRemoteThumbnail(entity)
            }
            remoteBytes ?: generateEncryptedRemoteBitmap(entity)?.let(::encodeBitmapToJpeg)
        } ?: return null

        val encrypt = settingsRepository.preferences.first().encryptThumbnails
        val stored = if (encrypt) {
            streamCrypto.encryptBytes(thumbnailKey(), jpegBytes)
        } else jpegBytes

        val dir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
        val target = File(dir, "$fileId.thumb")
        runCatching { target.writeBytes(stored) }.onFailure { return jpegBytes }

        val now = System.currentTimeMillis()
        thumbnailDao.upsert(
            ThumbnailEntity(
                fileId = fileId,
                path = target.absolutePath,
                encrypted = encrypt,
                width = MAX_DIMENSION,
                height = MAX_DIMENSION,
                sizeBytes = stored.size.toLong(),
                generatedAt = now,
                lastAccessAt = now
            )
        )
        return jpegBytes
    }

    private suspend fun linkThumbnail(file: File): Bitmap? {
        if (file.length() > LINK_NOTE_LIMIT) return null
        if (!settingsRepository.preferences.first().linkPreviews) return null
        val url = Urls.sole(file.readText()) ?: return null
        val imagePath = telegramClient
            .linkPreview(Urls.normalize(url), withImage = true)
            ?.imagePath
            ?: return null
        return File(imagePath).takeIf { it.exists() }?.let(::decodeImageThumbnail)
    }

    private fun decodeThumbnail(file: File, mimeType: String): Bitmap? = runCatching {
        when {
            MimeTypes.isImage(mimeType) -> decodeImageThumbnail(file)
            MimeTypes.isVideo(mimeType) -> decodeVideoThumbnail(file)
            MimeTypes.isApk(mimeType, file.name) -> decodeApkIcon(file)
            else -> null
        }
    }.getOrNull()

    private fun decodeApkIcon(file: File): Bitmap? {
        val bitmapFromPm = runCatching {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
            val appInfo = info?.applicationInfo
            if (appInfo != null) {
                appInfo.sourceDir = file.absolutePath
                appInfo.publicSourceDir = file.absolutePath
                val drawable = appInfo.loadIcon(pm)
                if (drawable != null) {
                    return@runCatching drawableToBitmap(drawable)
                }
            }
            null
        }.getOrNull()

        if (bitmapFromPm != null) return bitmapFromPm

        val bytes = ApkIconExtractor.extractIconBytes(file) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else MAX_DIMENSION
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else MAX_DIMENSION
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun decodeImageThumbnail(file: File): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createImageThumbnail(file, Size(MAX_DIMENSION, MAX_DIMENSION), null)
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val sample = maxOf(
                1,
                maxOf(options.outWidth, options.outHeight) / MAX_DIMENSION
            )
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }

    private fun decodeVideoThumbnail(file: File): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(file, Size(MAX_DIMENSION, MAX_DIMENSION), null)
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.frameAtTime
            } finally {
                retriever.release()
            }
        }

    private suspend fun fetchIconBytes(entity: FileEntity, iconFileId: String): ByteArray? {
        var readyPath: String? = null
        val rawRemoteFileId = iconFileId.substringBefore(":")
        runCatching {
            telegramClient.downloadDocument(rawRemoteFileId).collect { event ->
                if (event is TelegramDownloadEvent.Completed) {
                    readyPath = event.localPath
                }
            }
        }
        val path = readyPath ?: return null
        val rawBytes = runCatching { File(path).readBytes() }.getOrNull() ?: return null
        return if (entity.isEncrypted) {
            val key = wrappedKeyRepository.get(entity.encryptionKey.ifBlank { CryptoKeys.CONTENT })
                ?: return null
            runCatching { streamCrypto.decryptBytes(key, rawBytes) }.getOrNull()
        } else {
            rawBytes
        }
    }

    private suspend fun fetchRemoteThumbnail(entity: FileEntity): ByteArray? {
        val chatId = entity.chatId ?: return null
        val messageId = entity.messageId ?: return null
        if (MimeTypes.isText(entity.mimeType) &&
            !settingsRepository.preferences.first().linkPreviews
        ) return null
        return runCatching { telegramClient.fetchThumbnail(chatId, messageId) }.getOrNull()
    }

    private suspend fun generateEncryptedRemoteBitmap(entity: FileEntity): Bitmap? {
        if (!entity.isEncrypted) return null
        val remoteFileId = entity.remoteFileId ?: return null
        val isImage = MimeTypes.isImage(entity.mimeType)
        val isVideo = MimeTypes.isVideo(entity.mimeType)
        val isApk = MimeTypes.isApk(entity.mimeType, entity.name)
        if (!isImage && !isVideo && !isApk) return null

        val keyName = entity.encryptionKey.ifBlank { CryptoKeys.CONTENT }
        val key = wrappedKeyRepository.get(keyName) ?: return null

        val safeName = sanitizeName(entity.name)
        val previewCached = File(File(context.cacheDir, "previews"), "${entity.id}-$safeName")
        if (previewCached.exists() && previewCached.length() > 0) {
            decodeThumbnail(previewCached, entity.mimeType)?.let { return it }
        }

        if (isVideo) {
            extractRemoteEncryptedVideoFrame(entity, remoteFileId, key)?.let { return it }
        }

        if (entity.partCount <= 1 && entity.sizeBytes in 1L..REMOTE_ENCRYPTED_THUMB_LIMIT) {
            return decodeSmallEncryptedRemoteFile(entity, remoteFileId, key, safeName)
        }
        return null
    }

    private suspend fun decodeSmallEncryptedRemoteFile(
        entity: FileEntity,
        remoteFileId: String,
        key: ByteArray,
        safeName: String
    ): Bitmap? {
        var readyPath: String? = null
        runCatching {
            telegramClient.downloadDocument(remoteFileId).collect { event ->
                if (event is TelegramDownloadEvent.Completed) {
                    readyPath = event.localPath
                }
            }
        }
        val source = readyPath?.let(::File)?.takeIf { it.exists() } ?: return null
        val tempDir = File(context.cacheDir, "thumb-temp").apply { mkdirs() }
        val tempFile = File(tempDir, "${entity.id}-$safeName")
        return try {
            val decrypted = runCatching {
                source.inputStream().buffered().use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        streamCrypto.decryptStream(key, input, output)
                    }
                }
            }.isSuccess
            if (!decrypted || !tempFile.exists() || tempFile.length() <= 0L) {
                null
            } else {
                decodeThumbnail(tempFile, entity.mimeType)
            }
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun extractRemoteEncryptedVideoFrame(
        entity: FileEntity,
        remoteFileId: String,
        key: ByteArray
    ): Bitmap? = withContext(Dispatchers.IO) {
        val parts = if (entity.partCount > 1) {
            filePartDao.partsOf(entity.id).mapNotNull { part ->
                part.remoteFileId?.let { MediaPart(it, part.plainOffset, part.plainSize) }
            }
        } else {
            if (entity.sizeBytes <= 0L) return@withContext null
            listOf(MediaPart(remoteFileId, 0L, entity.sizeBytes))
        }
        if (parts.isEmpty()) return@withContext null

        val source = PartedMediaByteSource(
            telegramClient = telegramClient,
            streamCrypto = streamCrypto,
            parts = parts,
            encrypted = true,
            contentKey = key
        )
        val totalSize = runCatching { source.size() }.getOrDefault(0L)
        if (totalSize <= 0L) {
            runCatching { source.close() }
            return@withContext null
        }

        val dataSource = object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position < 0L || position >= totalSize) return -1
                if (size <= 0) return 0
                val capped = minOf(size.toLong(), totalSize - position).toInt()
                var totalRead = 0
                while (totalRead < capped) {
                    val chunk = runCatching {
                        runBlocking { source.read(position + totalRead, capped - totalRead) }
                    }.getOrNull() ?: return if (totalRead > 0) totalRead else -1
                    if (chunk.isEmpty()) break
                    System.arraycopy(chunk, 0, buffer, offset + totalRead, chunk.size)
                    totalRead += chunk.size
                }
                return if (totalRead == 0) -1 else totalRead
            }

            override fun getSize(): Long = totalSize

            override fun close() {
                source.close()
            }
        }

        val retriever = MediaMetadataRetriever()
        try {
            runCatching {
                retriever.setDataSource(dataSource)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        -1L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        MAX_DIMENSION,
                        MAX_DIMENSION
                    ) ?: retriever.frameAtTime
                } else {
                    retriever.frameAtTime
                }
            }.getOrNull()
        } finally {
            runCatching { retriever.release() }
            runCatching { dataSource.close() }
        }
    }

    private fun encodeBitmapToJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            bitmap.recycle()
            output.toByteArray()
        }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }

    private fun thumbnailKey(): ByteArray =
        wrappedKeyRepository.getOrCreate(CryptoKeys.THUMBNAIL)

    companion object {
        private const val LINK_NOTE_LIMIT = 8L * 1024
        private const val REMOTE_ENCRYPTED_THUMB_LIMIT = 20L * 1024 * 1024
        private const val MAX_DIMENSION = 512
        private const val JPEG_QUALITY = 82
    }
}
