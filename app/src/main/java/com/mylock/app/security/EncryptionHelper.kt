package com.mylock.app.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based AES-256-GCM encryption helper.
 *
 * Used by [backup.BaseSettingsBackupManager] to encrypt settings export files so they can
 * be safely stored externally (cloud, email, USB) without exposing API keys in plaintext.
 *
 * ## Algorithm
 * - **Key derivation**: PBKDF2WithHmacSHA256, 100 000 iterations, 256-bit key.
 *   The iteration count is deliberately high to slow down brute-force attacks on the password.
 * - **Encryption**: AES-256-GCM with a 96-bit (12-byte) random IV and 128-bit authentication tag.
 *   GCM provides both confidentiality and integrity — tampered ciphertext will fail to decrypt.
 * - **Random salt**: A fresh 16-byte salt is generated for each [encrypt] call, so encrypting
 *   the same plaintext with the same password produces different ciphertext every time.
 *
 * ## Binary file format
 * ```
 * [16-byte salt][12-byte IV][ciphertext + 16-byte GCM auth tag]
 * ```
 * This layout is what [EncryptedData.toByteArray] / [EncryptedData.fromByteArray] serialize.
 * [backup.BaseSettingsBackupManager] writes this directly to the output URI.
 *
 * ## Usage
 * ```kotlin
 * val encrypted = EncryptionHelper.encrypt(jsonString, userPassword)
 * val decrypted = EncryptionHelper.decrypt(encrypted, userPassword)
 * ```
 * Decryption throws [javax.crypto.AEADBadTagException] if the password is wrong or the
 * data has been tampered with.
 */
object EncryptionHelper {
    private const val PBKDF2_ITERATIONS = 100_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH = 256
    private const val TAG_LENGTH = 128

    /**
     * Encrypts [data] using AES-256-GCM with a key derived from [password].
     *
     * Generates a fresh random salt and IV for each call, so repeated calls with the
     * same inputs produce different [EncryptedData] instances.
     *
     * @param data The plaintext UTF-8 string to encrypt (e.g. a JSON settings export).
     * @param password The user-supplied password to derive the encryption key from.
     * @return [EncryptedData] containing the salt, IV, and ciphertext (with GCM tag appended).
     */
    fun encrypt(data: String, password: String): EncryptedData {
        val salt = ByteArray(SALT_LENGTH).apply {
            SecureRandom().nextBytes(this)
        }

        val key = deriveKey(password, salt)

        val iv = ByteArray(IV_LENGTH).apply {
            SecureRandom().nextBytes(this)
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

        return EncryptedData(salt, iv, ciphertext)
    }

    /**
     * Decrypts [encryptedData] using AES-256-GCM with a key derived from [password].
     *
     * @param encryptedData The encrypted container returned by [encrypt].
     * @param password The same password used during encryption.
     * @return The decrypted plaintext as a UTF-8 string.
     * @throws javax.crypto.AEADBadTagException if [password] is wrong or the data is corrupted/tampered.
     */
    fun decrypt(encryptedData: EncryptedData, password: String): String {
        val key = deriveKey(password, encryptedData.salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LENGTH, encryptedData.iv)
        )

        return cipher.doFinal(encryptedData.ciphertext).toString(Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        return factory.generateSecret(spec).encoded
    }
}

/**
 * Container for the three components that make up an AES-GCM encrypted blob.
 *
 * Binary layout (used by [EncryptionHelper] and [backup.BaseSettingsBackupManager]):
 * ```
 * [16-byte salt][12-byte IV][ciphertext + 16-byte GCM tag]
 * ```
 *
 * @property salt Random 16-byte salt used for PBKDF2 key derivation.
 * @property iv Random 12-byte initialization vector for AES-GCM.
 * @property ciphertext Encrypted bytes with the 16-byte GCM authentication tag appended.
 */
data class EncryptedData(
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray
) {
    /** Serializes to the binary file format: [salt][iv][ciphertext+tag]. */
    fun toByteArray(): ByteArray {
        return salt + iv + ciphertext
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedData
        if (!salt.contentEquals(other.salt)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12

        /** Deserializes from the binary file format: [salt][iv][ciphertext+tag]. */
        fun fromByteArray(data: ByteArray): EncryptedData {
            val salt = data.copyOfRange(0, SALT_LENGTH)
            val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val ciphertext = data.copyOfRange(SALT_LENGTH + IV_LENGTH, data.size)
            return EncryptedData(salt, iv, ciphertext)
        }
    }
}
