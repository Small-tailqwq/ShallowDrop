package io.github.smalltailqwq.shallowdrop.data.settings

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Robolectric 不支持 AndroidKeyStore，这里注入软件 AES 密钥验证格式与 GCM 行为。 */
class KeyCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = KeyCipher { key }

    @Test
    fun roundTrip() {
        val plain = "sk-0123456789abcdef中文"
        assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test
    fun randomIvPerEncryption() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val bytes = Base64.getDecoder().decode(cipher.encrypt("sk-test"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val tampered = Base64.getEncoder().encodeToString(bytes)
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tampered) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encrypted = cipher.encrypt("sk-test")
        assertThrows(GeneralSecurityException::class.java) { KeyCipher { other }.decrypt(encrypted) }
    }

    @Test
    fun malformedInputIsRejected() {
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt("not base64 !!") }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt("") }
        assertThrows(GeneralSecurityException::class.java) {
            cipher.decrypt(Base64.getEncoder().encodeToString(byteArrayOf(9, 12, 1, 2)))
        }
    }
}
