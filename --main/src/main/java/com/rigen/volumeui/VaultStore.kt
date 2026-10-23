package com.rigen.volumeui

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/*
 * Kanade Vault: the passwords, kept on this phone only, in one encrypted file. Nothing here uses
 * the network. While the vault is open the entries are in memory; locking wipes them.
 */

internal data class VaultEntry(
    val id: String,
    val title: String,
    val user: String,
    val password: String,
    val url: String,
    val notes: String,
    val ts: Long,
    /** The package name of the app this account is for, so autofill can find it. */
    val app: String = "",
) {
    companion object {
        fun blank() = VaultEntry(UUID.randomUUID().toString(), "", "", "", "", "", System.currentTimeMillis())
    }
}

internal object VaultPrefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("kanade_vault", Context.MODE_PRIVATE)

    /** Lock the vault as soon as the app goes to the background. */
    fun lockOnLeave(c: Context): Boolean = sp(c).getBoolean("lock_on_leave", true)

    fun setLockOnLeave(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("lock_on_leave", on).apply()
    }
}

internal object Vault {
    private const val FILE = "kanade_vault.json"

    var unlocked by mutableStateOf(false)
        private set
    val entries = mutableStateListOf<VaultEntry>()
    private var key: ByteArray? = null

    /** True while a file picker is open, so leaving the app for it does not lock the vault. */
    @Volatile
    var suppressLock = false

    private fun file(c: Context) = File(c.applicationContext.filesDir, FILE)

    fun exists(c: Context): Boolean = file(c).exists()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    // ── Opening and closing ──

    /** Makes a new, empty vault locked by [password], and opens it. */
    suspend fun create(c: Context, password: CharArray) {
        val salt = VaultCrypto.randomBytes(16)
        val k = withContext(Dispatchers.Default) { VaultCrypto.deriveKey(password, salt, VaultCrypto.ITERATIONS) }
        val header = JSONObject().put("v", 1).put("iter", VaultCrypto.ITERATIONS).put("salt", b64(salt))
        write(c, header, k, emptyList())
        open(k, emptyList())
    }

    /** True when the password was right. */
    suspend fun unlock(c: Context, password: CharArray): Boolean {
        val result = withContext(Dispatchers.Default) {
            try {
                val h = JSONObject(file(c).readText())
                val k = VaultCrypto.deriveKey(password, unb64(h.getString("salt")), h.getInt("iter"))
                k to read(h, k)
            } catch (e: Exception) {
                null
            }
        } ?: return false
        open(result.first, result.second)
        return true
    }

    /** Opens with the key that the fingerprint released. */
    fun unlockWithKey(c: Context, k: ByteArray): Boolean {
        return try {
            val list = read(JSONObject(file(c).readText()), k)
            open(k, list)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun open(k: ByteArray, list: List<VaultEntry>) {
        key = k
        entries.clear()
        entries.addAll(list)
        unlocked = true
    }

    /** Wipes the key and the entries from memory. */
    fun lock() {
        key?.fill(0)
        key = null
        entries.clear()
        unlocked = false
    }

    /** The key, for the fingerprint to wrap. Only while the vault is open. */
    fun currentKey(): ByteArray? = key?.copyOf()

    // ── Entries ──

    fun upsert(c: Context, e: VaultEntry) {
        val i = entries.indexOfFirst { it.id == e.id }
        val updated = e.copy(ts = System.currentTimeMillis())
        if (i >= 0) entries[i] = updated else entries.add(0, updated)
        save(c)
    }

    fun delete(c: Context, id: String) {
        entries.removeAll { it.id == id }
        save(c)
    }

    private fun save(c: Context) {
        val k = key ?: return
        val header = try {
            JSONObject(file(c).readText())
        } catch (e: Exception) {
            return
        }
        write(c, header, k, entries.toList())
    }

    // ── Password, backup and the fingerprint ──

    /** True when [old] was right and the vault now opens with [new]. The fingerprint is switched off. */
    suspend fun changePassword(c: Context, old: CharArray, new: CharArray): Boolean {
        val h = try {
            JSONObject(file(c).readText())
        } catch (e: Exception) {
            return false
        }
        val prepared = withContext(Dispatchers.Default) {
            try {
                val oldKey = VaultCrypto.deriveKey(old, unb64(h.getString("salt")), h.getInt("iter"))
                val list = read(h, oldKey)
                oldKey.fill(0)
                val salt = VaultCrypto.randomBytes(16)
                Triple(salt, VaultCrypto.deriveKey(new, salt, VaultCrypto.ITERATIONS), list)
            } catch (e: Exception) {
                null
            }
        } ?: return false
        val header = JSONObject().put("v", 1).put("iter", VaultCrypto.ITERATIONS).put("salt", b64(prepared.first))
        write(c, header, prepared.second, prepared.third)
        key?.fill(0)
        key = prepared.second
        return true
    }

    /** The encrypted file, as it is. It can only be opened with the master password. */
    fun backupBytes(c: Context): ByteArray? = try {
        file(c).readBytes()
    } catch (e: Exception) {
        null
    }

    /** Adds the entries of a backup that are not here yet. Returns how many, or -1 on a wrong password. */
    suspend fun importBackup(c: Context, bytes: ByteArray, password: CharArray): Int {
        if (!unlocked) return -1
        val list = withContext(Dispatchers.Default) {
            try {
                val h = JSONObject(String(bytes))
                val k = VaultCrypto.deriveKey(password, unb64(h.getString("salt")), h.getInt("iter"))
                read(h, k).also { k.fill(0) }
            } catch (e: Exception) {
                null
            }
        } ?: return -1
        var added = 0
        for (e in list) {
            val same = entries.any { it.id == e.id || (it.title == e.title && it.user == e.user && it.url == e.url) }
            if (!same) {
                entries.add(e)
                added++
            }
        }
        if (added > 0) save(c)
        return added
    }

    fun bioWrapped(c: Context): Pair<ByteArray, ByteArray>? = try {
        val bio = JSONObject(file(c).readText()).optJSONObject("bio")
        if (bio == null) null else unb64(bio.getString("iv")) to unb64(bio.getString("ct"))
    } catch (e: Exception) {
        null
    }

    fun setBio(c: Context, iv: ByteArray?, ct: ByteArray?) {
        try {
            val h = JSONObject(file(c).readText())
            if (iv == null || ct == null) h.remove("bio") else h.put("bio", JSONObject().put("iv", b64(iv)).put("ct", b64(ct)))
            atomic(c, h.toString())
        } catch (e: Exception) {
            // The fingerprint setting is left as it was.
        }
    }

    /** Deletes the vault for good. */
    fun destroy(c: Context) {
        lock()
        file(c).delete()
    }

    // ── The file ──

    private fun read(h: JSONObject, k: ByteArray): List<VaultEntry> {
        val plain = VaultCrypto.decrypt(k, unb64(h.getString("iv")), unb64(h.getString("ct")))
        val arr = JSONObject(String(plain)).getJSONArray("entries")
        val out = ArrayList<VaultEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                VaultEntry(
                    o.getString("id"), o.optString("t"), o.optString("u"), o.optString("p"),
                    o.optString("l"), o.optString("n"), o.optLong("s"), o.optString("a"),
                ),
            )
        }
        return out
    }

    private fun write(c: Context, header: JSONObject, k: ByteArray, list: List<VaultEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().put("id", e.id).put("t", e.title).put("u", e.user).put("p", e.password)
                    .put("l", e.url).put("n", e.notes).put("s", e.ts).put("a", e.app),
            )
        }
        val (iv, ct) = VaultCrypto.encrypt(k, JSONObject().put("entries", arr).toString().toByteArray())
        header.put("iv", b64(iv)).put("ct", b64(ct))
        atomic(c, header.toString())
    }

    private fun atomic(c: Context, text: String) {
        val f = file(c)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}

// ───────────────────────── Which account is for which app or site ─────────────────────────

/** The site name inside an address, without "www". */
internal fun vaultHost(url: String): String? {
    val t = url.trim().lowercase()
    if (t.isEmpty()) return null
    val withScheme = if (t.contains("://")) t else "https://$t"
    val host = try {
        java.net.URI(withScheme).host
    } catch (e: Exception) {
        null
    } ?: return null
    return host.removePrefix("www.")
}

/** An account matches an app by its package name, or a website by its address. */
internal fun vaultMatches(e: VaultEntry, pkg: String, domain: String?): Boolean {
    if (pkg.isNotEmpty() && e.app.isNotEmpty() && e.app == pkg) return true
    if (domain.isNullOrEmpty()) return false
    val h = vaultHost(e.url) ?: return false
    return domain == h || domain.endsWith(".$h")
}
