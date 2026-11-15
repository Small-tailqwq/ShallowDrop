package io.github.smalltailqwq.shallowdrop.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加解密：AES-256-GCM。
 * 密文格式（Base64）：[版本 1 字节][IV 长度 1 字节][IV][密文 + 128 位认证标签]。
 * 密钥由 [keyProvider] 提供；生产环境使用 Android Keystore 中不可导出的密钥（[androidKeystore]），
 * 单元测试可注入软件密钥。
 */
class KeyCipher(private val keyProvider: () -> SecretKey) {

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Keystore 密钥要求由 Cipher 自行生成随机 IV
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteBuffer.allocate(2 + iv.size + body.size)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(body)
            .array()
        return Base64.getEncoder().encodeToString(out)
    }

    /** 格式错误、密钥失效或密文被篡改时抛 [GeneralSecurityException]。 */
    fun decrypt(encoded: String): String {
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("密文不是有效的 Base64", e)
        }
        if (bytes.size < 2 || bytes[0] != VERSION) {
            throw GeneralSecurityException("不支持的密文版本")
        }
        val ivLength = bytes[1].toInt() and 0xFF
        if (ivLength == 0 || bytes.size <= 2 + ivLength) {
            throw GeneralSecurityException("密文长度不足")
        }
        val iv = bytes.copyOfRange(2, 2 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(bytes, 2 + ivLength, bytes.size - 2 - ivLength).toString(Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val VERSION: Byte = 1
        private const val KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "shallowdrop_api_key"

        /** 使用 Android Keystore 中的密钥，首次使用时生成。 */
        fun androidKeystore(alias: String = DEFAULT_ALIAS) = KeyCipher { keystoreKey(alias) }

        @Synchronized
        private fun keystoreKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
