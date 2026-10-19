package com.rigen.volumeui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * The clipboard history. Everything the person copies is kept here, on the phone only, in a file
 * encrypted with a key that never leaves the Android Keystore. Pinned items stay until deleted.
 */

internal class ClipItem(val id: String, val text: String, val pinned: Boolean, val ts: Long)

internal class KbClipStore(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, FILE_NAME)
    private var items = ArrayList<ClipItem>()

    // ── Reading and changing the history ──

    /** The history to show: pinned items first, then the newest. Old and surplus items are dropped. */
    fun list(cfg: KbConfig): List<ClipItem> {
        prune(cfg)
        return items.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.ts })
    }

    /** Adds copied text. Returns true when the history changed. */
    fun add(text: String, cfg: KbConfig): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH) return false
        val now = System.currentTimeMillis()
        val same = items.indexOfFirst { it.text == t }
        if (same >= 0) {
            val old = items[same]
            // Copying the same text again just brings it back to the top.
            if (items.maxOf { it.ts } == old.ts && !old.pinned) return false
            items[same] = ClipItem(old.id, old.text, old.pinned, now)
        } else {
            items.add(ClipItem(UUID.randomUUID().toString(), t, false, now))
        }
        prune(cfg)
        save()
        return true
    }

    fun setPinned(id: String, pinned: Boolean) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        if (pinned && items.count { it.pinned } >= MAX_PINNED) return
        val o = items[i]
        items[i] = ClipItem(o.id, o.text, pinned, o.ts)
        save()
    }

    fun delete(id: String) {
        if (items.removeAll { it.id == id }) save()
    }

    fun clearAll() {
        items.clear()
        wipeFile(app)
    }

    private fun prune(cfg: KbConfig) {
        val before = items.size
        if (cfg.clipHours > 0) {
            val limit = System.currentTimeMillis() - cfg.clipHours * 3_600_000L
            items.removeAll { !it.pinned && it.ts < limit }
        }
        val loose = items.filter { !it.pinned }.sortedByDescending { it.ts }
        if (loose.size > cfg.clipMax) {
            val drop = loose.drop(cfg.clipMax).map { it.id }.toHashSet()
            items.removeAll { it.id in drop }
        }
        if (items.size != before) save()
    }

    // ── Saving and loading (encrypted) ──

    fun load() {
        items.clear()
        try {
            if (!file.exists()) return
            val all = file.readBytes()
            if (all.size < 13) return
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            val arr = JSONArray(String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(ClipItem(o.getString("i"), o.getString("t"), o.optBoolean("p"), o.optLong("s")))
            }
        } catch (e: Exception) {
            Diag.error(app, "KbClipStore.load", e)
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (c in items) {
            arr.put(JSONObject().put("i", c.id).put("t", c.text).put("p", c.pinned).put("s", c.ts))
        }
        val bytes = arr.toString().toByteArray(Charsets.UTF_8)
        Thread {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val out = cipher.iv + cipher.doFinal(bytes)
                val tmp = File(app.filesDir, "$FILE_NAME.tmp")
                tmp.writeBytes(out)
                if (!tmp.renameTo(file)) {
                    file.writeBytes(out)
                    tmp.delete()
                }
            } catch (e: Exception) {
                Diag.error(app, "KbClipStore.save", e)
            }
        }.start()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val ALIAS = "kanade_keyboard_clip"
        private const val FILE_NAME = "kb_clips.bin"
        private const val MAX_LENGTH = 5000
        private const val MAX_PINNED = 30

        fun wipeFile(c: Context) {
            try {
                File(c.applicationContext.filesDir, FILE_NAME).delete()
            } catch (e: Exception) {
                // Nothing more to do.
            }
        }
    }
}
