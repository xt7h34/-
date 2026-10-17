package com.rigen.volumeui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.pow

/*
 * The keyboard's memory. It learns which words the person types and which word usually follows
 * which, and nothing else: no sentences, no app names, no text from password fields.
 * It lives in one file, encrypted with a key that never leaves the Android Keystore.
 */

internal fun kbIsWordChar(ch: Char): Boolean =
    Character.isLetterOrDigit(ch) || ch == '\'' || ch == '\u2019' ||
        Character.getType(ch) == Character.NON_SPACING_MARK.toInt()

/** The word that ends [text], or an empty string when the text ends with a space or a symbol. */
internal fun kbTrailingWord(text: CharSequence): String {
    var i = text.length
    while (i > 0 && kbIsWordChar(text[i - 1])) i--
    return text.substring(i)
}

internal fun kbIsArabic(s: String): Boolean = s.any { it in '\u0600'..'\u06FF' }

/** Matching key: ignores case, Arabic diacritics and the different forms of alef. */
internal fun kbNorm(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s.lowercase()) {
        when {
            ch in '\u064B'..'\u0652' || ch == '\u0670' || ch == '\u0640' -> {}
            ch == '\u0623' || ch == '\u0625' || ch == '\u0622' || ch == '\u0671' -> sb.append('\u0627')
            ch == '\u0649' -> sb.append('\u064A')
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

internal class KbLearner(context: Context) {

    private class Uni(var n: Float, var t: Long, val k: String)

    private val app = context.applicationContext
    private val uni = HashMap<String, Uni>()
    private val big = HashMap<String, HashMap<String, Float>>()
    private var dirty = false
    private val file = File(app.filesDir, FILE_NAME)

    val wordCount: Int get() = uni.size

    // ── Learning ──

    private fun eligible(w: String): Boolean {
        if (w.length < 2 || w.length > 24) return false
        for (ch in w) {
            if (Character.isDigit(ch)) return false
            if (!kbIsWordChar(ch)) return false
        }
        return w.any { Character.isLetter(it) }
    }

    private fun clean(w: String): String = w.replace("\u0640", "").lowercase()

    fun record(prev: String?, word: String) {
        val w = clean(word)
        if (!eligible(w)) return
        val now = System.currentTimeMillis()
        val u = uni[w]
        if (u == null) uni[w] = Uni(1f, now, kbNorm(w)) else {
            u.n += 1f
            u.t = now
        }
        val p = prev?.let { clean(it) }
        if (p != null && eligible(p)) {
            val m = big.getOrPut(p) { HashMap() }
            m[w] = (m[w] ?: 0f) + 1f
            if (m.size > MAX_NEXT) {
                val weakest = m.minByOrNull { it.value }?.key
                if (weakest != null && weakest != w) m.remove(weakest)
            }
        }
        dirty = true
        if (uni.size > MAX_WORDS) prune()
    }

    private fun prune() {
        val keep = uni.entries.sortedByDescending { it.value.n }.take(MAX_WORDS * 4 / 5).map { it.key }.toHashSet()
        uni.keys.retainAll(keep)
        big.keys.retainAll(keep)
        for (m in big.values) m.keys.retainAll(keep)
        if (big.size > MAX_PREVS) {
            val top = big.entries.sortedByDescending { e -> e.value.values.sum() }.take(MAX_PREVS * 4 / 5).map { it.key }.toHashSet()
            big.keys.retainAll(top)
        }
    }

    // ── Suggesting ──

    fun suggest(prefix: String, prev: String?, arabic: Boolean, limit: Int): List<String> {
        val p = kbNorm(prefix)
        val pk = prev?.let { clean(it) }
        val next = if (pk != null) big[pk] else null
        val scored = ArrayList<Pair<String, Float>>()
        if (p.isEmpty()) {
            if (next != null) {
                for ((w, n) in next) {
                    if (n >= 2f && kbIsArabic(w) == arabic) scored.add(w to n * 4f + (uni[w]?.n ?: 0f))
                }
            }
            if (scored.size < limit) {
                // Not enough follow-ups yet: add the words the person types most.
                val have = scored.map { it.first }.toHashSet()
                uni.entries
                    .filter { it.key !in have && kbIsArabic(it.key) == arabic && it.value.n >= 3f }
                    .sortedByDescending { it.value.n }
                    .take(limit)
                    .forEach { scored.add(it.key to it.value.n * 0.5f) }
            }
        } else {
            for ((w, u) in uni) {
                if (u.n >= 2f && u.k.length > p.length && u.k.startsWith(p)) {
                    scored.add(w to u.n + 4f * (next?.get(w) ?: 0f))
                }
            }
        }
        val capital = prefix.isNotEmpty() && prefix[0].isUpperCase()
        return scored.sortedByDescending { it.second }
            .take(limit)
            .map { if (capital) it.first.replaceFirstChar { c -> c.uppercase() } else it.first }
    }

    // ── Saving and loading ──

    fun save(async: Boolean = true) {
        if (!dirty) return
        dirty = false
        val json = JSONObject()
        json.put("ts", System.currentTimeMillis())
        val u = JSONObject()
        for ((w, e) in uni) u.put(w, JSONArray().put(e.n.toDouble()).put(e.t))
        json.put("u", u)
        val b = JSONObject()
        for ((p, m) in big) {
            val o = JSONObject()
            for ((w, n) in m) o.put(w, n.toDouble())
            b.put(p, o)
        }
        json.put("b", b)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val work = Runnable { writeEncrypted(bytes) }
        if (async) Thread(work).start() else work.run()
    }

    fun load() {
        uni.clear()
        big.clear()
        try {
            if (!file.exists()) return
            val all = file.readBytes()
            if (all.size < 13) return
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8))
            val days = ((System.currentTimeMillis() - json.optLong("ts", System.currentTimeMillis())) / DAY_MS).coerceAtLeast(0)
            val decay = 0.5.pow(days / 60.0).toFloat()
            val u = json.optJSONObject("u")
            if (u != null) {
                for (w in u.keys()) {
                    val a = u.getJSONArray(w)
                    val n = a.getDouble(0).toFloat() * decay
                    if (n >= 0.3f) uni[w] = Uni(n, a.getLong(1), kbNorm(w))
                }
            }
            val b = json.optJSONObject("b")
            if (b != null) {
                for (p in b.keys()) {
                    if (p !in uni) continue
                    val o = b.getJSONObject(p)
                    val m = HashMap<String, Float>()
                    for (w in o.keys()) {
                        val n = o.getDouble(w).toFloat() * decay
                        if (n >= 0.3f && w in uni) m[w] = n
                    }
                    if (m.isNotEmpty()) big[p] = m
                }
            }
        } catch (e: Exception) {
            Diag.error(app, "KbLearner.load", e)
        }
    }

    fun clearAll() {
        uni.clear()
        big.clear()
        dirty = false
        wipeFile(app)
    }

    private fun writeEncrypted(plain: ByteArray) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(plain)
            val tmp = File(app.filesDir, "$FILE_NAME.tmp")
            tmp.writeBytes(out)
            if (!tmp.renameTo(file)) {
                file.writeBytes(out)
                tmp.delete()
            }
        } catch (e: Exception) {
            Diag.error(app, "KbLearner.save", e)
        }
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
        private const val ALIAS = "kanade_keyboard"
        private const val FILE_NAME = "kb_memory.bin"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_WORDS = 6000
        private const val MAX_PREVS = 4000
        private const val MAX_NEXT = 10

        /** Deletes the learned data without needing a loaded instance. */
        fun wipeFile(c: Context) {
            try {
                File(c.applicationContext.filesDir, FILE_NAME).delete()
            } catch (e: Exception) {
                // Nothing more to do.
            }
        }
    }
}
