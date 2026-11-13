package com.drdisagree.teledrive.data.remote.telegram

import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

@Serializable
data class LoggerRecordMetadata(
    val protocol: String = "telewarden",
    val version: Int = 1,
    val record_type: String = "encrypted_media",
    val source_message_id: Long? = null,
    val source_chat_id: Long? = null,
    val source_thread_id: Long? = null,
    val source_date: Double? = null,
    val media_type: String = "media",
    val original_filename: String = "media.bin",
    val mime_type: String = "application/octet-stream",
    val caption: String = "",
    val size_bytes: Long = 0,
    val envelope: String = "TDE1"
)

/** Decodes media records emitted by open-tg's twlog.py without touching CONTENT. */
class LoggerRecordCodec(
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun isLoggerCaption(caption: String): Boolean = caption.startsWith(PREFIX)

    fun isLocked(caption: String): Boolean =
        isLoggerCaption(caption) && !wrappedKeyRepository.exists(CryptoKeys.LOGGER)

    fun decode(caption: String): LoggerRecordMetadata? = runCatching {
        if (!isLoggerCaption(caption)) return null
        val key = wrappedKeyRepository.get(CryptoKeys.LOGGER) ?: return null
        val encoded = caption.removePrefix(PREFIX)
        val blob = Base64.UrlSafe.decode(encoded)
        val plaintext = streamCrypto.decryptBytes(key, blob)
        json.decodeFromString(LoggerRecordMetadata.serializer(), plaintext.decodeToString())
            .takeIf { it.protocol == "telewarden" && it.version == 1 && it.record_type == "encrypted_media" }
    }.getOrNull()

    companion object {
        const val PREFIX = "tw1:"
    }
}
