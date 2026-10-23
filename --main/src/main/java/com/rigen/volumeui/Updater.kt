package com.rigen.volumeui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * App updates. The app asks GitHub for the newest release of its own repository, and if there is
 * a newer version it can download the APK, check it, and hand it to Android's installer.
 * Nothing about the person is sent: it is one plain request for a public page.
 */

internal class UpdateInfo(
    val code: Int,
    val name: String,
    val notes: String,
    val page: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

internal const val UPD_NEW = 0
internal const val UPD_UP_TO_DATE = 1
internal const val UPD_NOT_CONFIGURED = 2
internal const val UPD_FAILED = 3

internal class CheckResult(val kind: Int, val info: UpdateInfo?)

internal object UpdatePrefs {
    private const val FILE = "kanade_update"
    private const val DAY = 24L * 60 * 60 * 1000

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun auto(c: Context): Boolean = sp(c).getBoolean("auto", true)

    fun setAuto(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto", on).apply()
    }

    /** A repository typed by the person, for builds that were not told where they come from. */
    fun repoOverride(c: Context): String = sp(c).getString("repo", "") ?: ""

    fun setRepoOverride(c: Context, repo: String) {
        sp(c).edit().putString("repo", repo.trim()).apply()
    }

    fun repo(c: Context): String = repoOverride(c).ifBlank { BuildConfig.UPDATE_REPO }

    fun skipped(c: Context): Int = sp(c).getInt("skipped", 0)

    fun skip(c: Context, code: Int) {
        sp(c).edit().putInt("skipped", code).apply()
    }

    /** It is time for the automatic look when the last one was more than about a day ago. */
    fun isDue(c: Context): Boolean = System.currentTimeMillis() - sp(c).getLong("last", 0L) > DAY - 4 * 3_600_000L

    fun markChecked(c: Context, info: UpdateInfo?) {
        val e = sp(c).edit().putLong("last", System.currentTimeMillis())
        if (info != null) {
            e.putString(
                "info",
                JSONObject()
                    .put("code", info.code).put("name", info.name).put("notes", info.notes)
                    .put("page", info.page).put("url", info.url).put("size", info.size)
                    .put("sha", info.sha256 ?: "")
                    .toString(),
            )
        }
        e.apply()
    }

    /** The newest release seen at the last check. */
    fun cached(c: Context): UpdateInfo? {
        val raw = sp(c).getString("info", null) ?: return null
        return try {
            val o = JSONObject(raw)
            UpdateInfo(
                o.getInt("code"), o.getString("name"), o.getString("notes"), o.getString("page"),
                o.getString("url"), o.getLong("size"), o.optString("sha").takeIf { it.length == 64 },
            )
        } catch (e: Exception) {
            null
        }
    }
}

internal object Updater {
    private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun installedCode(c: Context): Int {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(pi).toInt()
    }

    fun installedName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""

    /** Looks for a newer version. */
    suspend fun check(c: Context): CheckResult {
        val repo = UpdatePrefs.repo(c)
        if (!REPO.matches(repo)) return CheckResult(UPD_NOT_CONFIGURED, null)
        return try {
            val info = fetchLatest(repo)
            UpdatePrefs.markChecked(c, info)
            if (info != null && info.code > installedCode(c)) CheckResult(UPD_NEW, info) else CheckResult(UPD_UP_TO_DATE, info)
        } catch (e: Exception) {
            CheckResult(UPD_FAILED, null)
        }
    }

    /** The newest release, or null when the repository has no release yet. */
    private suspend fun fetchLatest(repo: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "KanadeSystem")
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            parse(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** The release is named build-NUMBER, and the number is compared with this app's version code. */
    fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (a.optString("name").endsWith(".apk") && url.startsWith("https://")) {
                return UpdateInfo(
                    code = code,
                    name = o.optString("name").ifBlank { tag },
                    notes = o.optString("body"),
                    page = o.optString("html_url"),
                    url = url,
                    size = a.optLong("size"),
                    sha256 = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                )
            }
        }
        return null
    }

    /**
     * Downloads the APK into the app's own cache and checks it: the checksum GitHub published,
     * that it is this same app, and that it is newer. A file that fails is deleted.
     */
    suspend fun download(c: Context, info: UpdateInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(c.cacheDir, "updates")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "kanade-${info.code}.apk")
            val conn = URL(info.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "KanadeSystem")
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
                val md = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var read = input.read(buf)
                        var done = 0L
                        var last = 0L
                        while (read != -1) {
                            os.write(buf, 0, read)
                            md.update(buf, 0, read)
                            done += read
                            val now = System.nanoTime()
                            if (now - last > 100_000_000L) {
                                last = now
                                onProgress(done, total)
                            }
                            read = input.read(buf)
                        }
                        onProgress(done, total)
                    }
                }
                val sum = md.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256 != null && !sum.equals(info.sha256, ignoreCase = true)) {
                    out.delete()
                    throw IOException("checksum")
                }
                val pi = c.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
                if (pi == null || pi.packageName != c.packageName ||
                    PackageInfoCompat.getLongVersionCode(pi) <= installedCode(c)
                ) {
                    out.delete()
                    throw IOException("not a newer version of this app")
                }
                out
            } finally {
                conn.disconnect()
            }
        }

    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    /** Opens the screen where the person allows this app to install apps. */
    fun openInstallPermission(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hands the downloaded file to Android's installer, which asks the person to confirm. */
    fun install(c: Context, file: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.updates", file)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
