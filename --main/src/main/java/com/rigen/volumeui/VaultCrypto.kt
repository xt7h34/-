package com.rigen.volumeui

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ln

/*
 * Kanade Vault: the cryptography. The master password is turned into a key with PBKDF2 (slow on
 * purpose, so guessing is expensive), and the vault is locked with AES-256-GCM, which also notices
 * if anyone changed the file. The key exists only in memory while the vault is open.
 */

internal object VaultCrypto {
    const val ITERATIONS = 600_000
    private const val ASSOCIATED = "KanadeVault1"
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Returns the random starting value and the encrypted bytes. */
    fun encrypt(key: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(ASSOCIATED.toByteArray())
        return iv to cipher.doFinal(plain)
    }

    /** Throws when the key is wrong or the data was changed. */
    fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(ASSOCIATED.toByteArray())
        return cipher.doFinal(data)
    }
}

// ───────────────────────── Password generator ─────────────────────────

private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val DIGITS = "23456789"
private const val SYMBOLS = "!@#$%^&*-_=+?"

/** A random password. Look-alike characters (l, I, O, 0, 1) are left out. */
internal fun vaultGenerate(length: Int, upper: Boolean, lower: Boolean, digits: Boolean, symbols: Boolean): String {
    val rnd = SecureRandom()
    val sets = ArrayList<String>()
    if (lower || (!upper && !digits && !symbols)) sets.add(LOWER)
    if (upper) sets.add(UPPER)
    if (digits) sets.add(DIGITS)
    if (symbols) sets.add(SYMBOLS)
    val len = length.coerceIn(sets.size, 128)
    val all = sets.joinToString("")
    val out = ArrayList<Char>()
    for (s in sets) out.add(s[rnd.nextInt(s.length)]) // at least one of each kind chosen
    while (out.size < len) out.add(all[rnd.nextInt(all.length)])
    out.shuffle(rnd)
    return out.joinToString("")
}

/** 0 weak, 1 fair, 2 good, 3 strong. A rough guess from length and the kinds of characters. */
internal fun vaultStrength(pw: String): Int {
    if (pw.isEmpty()) return 0
    var pool = 0
    if (pw.any { it.isLowerCase() }) pool += 26
    if (pw.any { it.isUpperCase() }) pool += 26
    if (pw.any { it.isDigit() }) pool += 10
    if (pw.any { !it.isLetterOrDigit() }) pool += 32
    val bits = pw.length * (ln(pool.coerceAtLeast(2).toDouble()) / ln(2.0))
    return when {
        bits < 40 -> 0
        bits < 60 -> 1
        bits < 80 -> 2
        else -> 3
    }
}
