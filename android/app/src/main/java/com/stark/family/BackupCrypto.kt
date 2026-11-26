package com.stark.family

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional passphrase lock for the memory backup file: PBKDF2-HMAC-SHA256 (120,000 rounds) -> AES-256-GCM.
 * File layout: "STARKENC1" + salt(16) + iv(12) + ciphertext. A wrong passphrase or a damaged file simply fails to open.
 * There is no way to recover a forgotten passphrase.
 */
object BackupCrypto {
    private val MAGIC = "STARKENC1".toByteArray(Charsets.US_ASCII)
    private const val ITER = 120_000

    fun isEncrypted(b: ByteArray): Boolean = b.size > MAGIC.size + 28 && b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun key(pass: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass.toCharArray(), salt, ITER, 256)
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(k, "AES")
    }

    fun encrypt(plain: ByteArray, pass: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    /** null = wrong passphrase or damaged file. */
    fun decrypt(blob: ByteArray, pass: String): ByteArray? = try {
        val o = MAGIC.size
        val salt = blob.copyOfRange(o, o + 16)
        val iv = blob.copyOfRange(o + 16, o + 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        c.doFinal(blob, o + 28, blob.size - o - 28)
    } catch (e: Exception) {
        null
    }
}
