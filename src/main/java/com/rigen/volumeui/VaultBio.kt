package com.rigen.volumeui

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Opening the vault with a fingerprint or face. The vault's key is locked with a second key that
 * lives inside the phone's secure hardware and is released only by a successful fingerprint. The
 * master password always still works. Needs Android 9 or newer.
 */

internal object VaultBio {
    private const val ALIAS = "kanade_vault_bio"

    fun available(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val pm = c.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
            pm.hasSystemFeature(PackageManager.FEATURE_FACE) ||
            pm.hasSystemFeature(PackageManager.FEATURE_IRIS)
    }

    private fun newKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        ks.deleteEntry(ALIAS)
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(b.build())
        return gen.generateKey()
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    fun forget() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Nothing to forget.
        }
    }

    /** Asks for the fingerprint, then locks [vaultKey] with the secure key. */
    fun enroll(c: Context, title: String, cancel: String, vaultKey: ByteArray, done: (ByteArray, ByteArray) -> Unit, failed: () -> Unit) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, newKey())
            prompt(c, title, cancel, cipher, failed) { used ->
                val ct = used.doFinal(vaultKey)
                done(used.iv, ct)
            }
        } catch (e: Exception) {
            failed()
        }
    }

    /** Asks for the fingerprint, then gives back the vault's key. */
    fun unlock(c: Context, title: String, cancel: String, iv: ByteArray, wrapped: ByteArray, done: (ByteArray) -> Unit, failed: () -> Unit) {
        try {
            val key = existingKey()
            if (key == null) {
                failed()
                return
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            prompt(c, title, cancel, cipher, failed) { used -> done(used.doFinal(wrapped)) }
        } catch (e: Exception) {
            failed()
        }
    }

    private fun prompt(c: Context, title: String, cancel: String, cipher: Cipher, failed: () -> Unit, ok: (Cipher) -> Unit) {
        val prompt = BiometricPrompt.Builder(c)
            .setTitle(title)
            .setNegativeButton(cancel, c.mainExecutor) { _, _ -> failed() }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            c.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val used = result.cryptoObject?.cipher
                    if (used == null) {
                        failed()
                        return
                    }
                    try {
                        ok(used)
                    } catch (e: Exception) {
                        failed()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    failed()
                }
            },
        )
    }
}
