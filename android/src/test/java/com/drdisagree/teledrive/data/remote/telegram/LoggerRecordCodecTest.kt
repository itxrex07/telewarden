package com.drdisagree.teledrive.data.remote.telegram

import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerRecordCodecTest {

    private class InMemoryWrappedKeyRepository : WrappedKeyRepository {
        private val keys = mutableMapOf<String, ByteArray>()

        override fun getOrCreate(name: String, sizeBytes: Int): ByteArray =
            keys.getOrPut(name) { ByteArray(sizeBytes) { it.toByte() } }

        override fun wasRecreated(name: String): Boolean = false

        override fun get(name: String): ByteArray? = keys[name]?.copyOf()

        override fun exists(name: String): Boolean = name in keys

        override fun store(name: String, key: ByteArray) {
            keys[name] = key.copyOf()
        }

        override fun remove(name: String) {
            keys.remove(name)
        }
    }

    private val streamCrypto = StreamCrypto()
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val wrongKey = ByteArray(32) { (it + 1).toByte() }

    private fun encodeTw1(
        key: ByteArray,
        jsonPayload: String,
        stripPadding: Boolean
    ): String {
        val blob = streamCrypto.encryptBytes(key, jsonPayload.encodeToByteArray())
        val encoded = Base64.UrlSafe.encode(blob).let {
            if (stripPadding) it.trimEnd('=') else it
        }
        return "${LoggerRecordCodec.PREFIX}$encoded"
    }

    private fun encryptTwlogTde1(key: ByteArray, salt: ByteArray, plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("TDE1".encodeToByteArray())
        out.write(1)
        out.write(salt)

        val secretKey = SecretKeySpec(key, "AES")
        val chunkSize = StreamCrypto.CHUNK_SIZE
        var offset = 0
        var chunkIndex = 0
        while (true) {
            val end = minOf(offset + chunkSize, plaintext.size)
            val isFinal = end == plaintext.size
            val chunk = plaintext.copyOfRange(offset, end)
            val nonce = ByteBuffer.allocate(12).put(salt).putInt(chunkIndex).array()
            val aad = ByteBuffer.allocate(9)
                .putInt(1)
                .putInt(chunkIndex)
                .put(if (isFinal) 1 else 0)
                .array()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val sealed = cipher.doFinal(chunk)

            val marker = sealed.size or (if (isFinal) (1 shl 31) else 0)
            out.write(ByteBuffer.allocate(4).putInt(marker).array())
            out.write(sealed)

            if (isFinal) break
            offset = end
            chunkIndex++
        }
        return out.toByteArray()
    }

    @Test
    fun `decodes unpadded base64url tw1 captions across all remainder lengths`() {
        val keys = InMemoryWrappedKeyRepository().apply { store(CryptoKeys.LOGGER, key) }
        val codec = LoggerRecordCodec(streamCrypto, keys)

        for (extraLen in 0..2) {
            val fileName = "photo_${"a".repeat(extraLen)}.jpg"
            val json = """{"protocol":"telewarden","version":1,"record_type":"encrypted_media","media_type":"photo","original_filename":"$fileName","mime_type":"image/jpeg","size_bytes":12345,"envelope":"TDE1"}"""
            val caption = encodeTw1(key, json, stripPadding = true)

            val decoded = codec.decode(caption)
            assertNotNull("Expected decode to succeed for extraLen=$extraLen", decoded)
            assertEquals(fileName, decoded!!.original_filename)
            assertEquals("image/jpeg", decoded.mime_type)
            assertEquals(12345L, decoded.size_bytes)
        }
    }

    @Test
    fun `decodes padded base64url tw1 captions with surrounding whitespace`() {
        val keys = InMemoryWrappedKeyRepository().apply { store(CryptoKeys.LOGGER, key) }
        val codec = LoggerRecordCodec(streamCrypto, keys)

        val json = """{"protocol":"telewarden","version":1,"record_type":"encrypted_media","original_filename":"clip.mp4","mime_type":"video/mp4","size_bytes":987654}"""
        val caption = encodeTw1(key, json, stripPadding = false) + "  "

        val decoded = codec.decode(caption)
        assertNotNull(decoded)
        assertEquals("clip.mp4", decoded!!.original_filename)
        assertEquals("video/mp4", decoded.mime_type)
        assertEquals(987654L, decoded.size_bytes)
    }

    @Test
    fun `returns null for wrong key invalid prefix or wrong record type`() {
        val keys = InMemoryWrappedKeyRepository().apply { store(CryptoKeys.LOGGER, wrongKey) }
        val codec = LoggerRecordCodec(streamCrypto, keys)

        val validJson = """{"protocol":"telewarden","version":1,"record_type":"encrypted_media","original_filename":"a.jpg"}"""
        val caption = encodeTw1(key, validJson, stripPadding = true)
        assertNull(codec.decode(caption))
        assertNull(codec.decode("not-tw1"))
        assertNull(codec.decode("tw1:"))

        keys.store(CryptoKeys.LOGGER, key)
        val wrongTypeJson = """{"protocol":"telewarden","version":1,"record_type":"other","original_filename":"a.jpg"}"""
        assertNull(codec.decode(encodeTw1(key, wrongTypeJson, stripPadding = true)))
    }

    @Test
    fun `isLocked reflects whether logger key exists`() {
        val keys = InMemoryWrappedKeyRepository()
        val codec = LoggerRecordCodec(streamCrypto, keys)
        val caption = "tw1:abc123"

        assertTrue(codec.isLocked(caption))
        assertFalse(codec.isLocked("plain caption"))

        keys.store(CryptoKeys.LOGGER, key)
        assertFalse(codec.isLocked(caption))
    }

    @Test
    fun `decrypts twlog TDE1 multi-chunk stream and individual frames`() {
        val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val plaintext = ByteArray(StreamCrypto.CHUNK_SIZE + 512) { (it % 251).toByte() }
        val tde1 = encryptTwlogTde1(key, salt, plaintext)

        val decrypted = ByteArrayOutputStream()
        streamCrypto.decryptStream(key, ByteArrayInputStream(tde1), decrypted)
        assertArrayEquals(plaintext, decrypted.toByteArray())

        val headerSalt = streamCrypto.saltOf(tde1.copyOfRange(0, streamCrypto.headerSize()))
        assertArrayEquals(salt, headerSalt)

        val frame0Start = streamCrypto.frameStart(0).toInt()
        val frame0End = streamCrypto.frameStart(1).toInt()
        val frame0 = streamCrypto.decryptFrame(key, headerSalt, 0, tde1.copyOfRange(frame0Start, frame0End))
        assertArrayEquals(plaintext.copyOfRange(0, StreamCrypto.CHUNK_SIZE), frame0)

        val frame1 = streamCrypto.decryptFrame(key, headerSalt, 1, tde1.copyOfRange(frame0End, tde1.size))
        assertArrayEquals(plaintext.copyOfRange(StreamCrypto.CHUNK_SIZE, plaintext.size), frame1)
    }
}
