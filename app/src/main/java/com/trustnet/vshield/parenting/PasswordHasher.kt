package com.trustnet.vshield.parenting

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"


    private const val DEFAULT_ITERATIONS = 120_000
    private const val DEFAULT_KEY_LENGTH_BITS = 256


    private const val DEFAULT_SALT_LENGTH_BYTES = 16

    fun generateSalt(lengthBytes: Int = DEFAULT_SALT_LENGTH_BYTES): ByteArray {
        require(lengthBytes >= 16) { "Salt length should be at least 16 bytes." }
        return ByteArray(lengthBytes).also { SecureRandom().nextBytes(it) }
    }

    fun hash(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
        keyLengthBits: Int = DEFAULT_KEY_LENGTH_BITS
    ): ByteArray {
        require(iterations >= 50_000) { "PBKDF2 iterations too low." }
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    fun verify(
        password: CharArray,
        expectedHash: ByteArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
        keyLengthBits: Int = DEFAULT_KEY_LENGTH_BITS
    ): Boolean {
        val computed = hash(password, salt, iterations, keyLengthBits)
        return constantTimeEquals(computed, expectedHash)
    }

    fun encodeBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decodeBase64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}