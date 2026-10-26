package net.metalstrapper.sparkwriter

import org.apache.commons.codec.digest.Crypt
import java.security.SecureRandom

private val cryptSaltRandom = SecureRandom()

internal fun hashPassword(password: String, algorithm: String?): String {
    val prefix = when (algorithm?.ifBlank { null } ?: "sha512_crypt") {
        "sha512_crypt", "sha512-crypt", "sha512crypt" -> "$6$"
        else -> error("Unsupported password hash algorithm: $algorithm")
    }
    return Crypt.crypt(password, prefix + randomCryptSalt())
}

private fun randomCryptSalt(): String {
    val alphabet = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    return buildString {
        repeat(16) {
            append(alphabet[cryptSaltRandom.nextInt(alphabet.length)])
        }
    }
}
