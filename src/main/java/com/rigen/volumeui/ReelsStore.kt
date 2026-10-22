package com.rigen.volumeui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** An app we can count short videos in. [color] is an ARGB value used for charts. */
class ReelsApp(val key: String, val label: String, val packages: List<String>, val color: Long)

val REELS_APPS = listOf(
    ReelsApp("yt", "YouTube", listOf("com.google.android.youtube"), 0xFFEA3323),
    ReelsApp("ig", "Instagram", listOf("com.instagram.android"), 0xFFEC5B8C),
    ReelsApp("tt", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), 0xFF6CF0E8),
    ReelsApp("fb", "Facebook", listOf("com.facebook.katana", "com.facebook.lite"), 0xFF4C8DFF),
    ReelsApp("sc", "Snapchat", listOf("com.snapchat.android"), 0xFFFFD84D),
)

/** A stretch of watching: it starts at [start] and [count] videos went by before a long pause. */
class ReelsSession(val start: Long, val count: Int)

/** Everything counted on one calendar day. */
class ReelsDay(
    val key: String,
    val counts: Map<String, Int>,
    val hours: IntArray,
    val sessions: List<ReelsSession>,
    val lastAt: Long,
) {
    val total: Int get() = counts.values.sum()
    val opens: Int get() = sessions.size
    val longest: Int get() = sessions.maxOfOrNull { it.count } ?: 0
}

/** What the accessibility service saw in one scroll event (no screen content is ever read). */
class ReelsSample(
    val pkg: String,
    val className: String,
    val from: Int,
    val to: Int,
    val items: Int,
    val scrollY: Int,
    val deltaY: Int,
    val deltaX: Int,
    val screenH: Int,
)

class ReelsVerdict(val counted: Boolean, val reason: String)

/**
 * Settings and counts, kept on this phone only (SharedPreferences, like the clock). Every change
 * bumps [tick] so the open screens and the floating badge redraw by themselves.
 */
object ReelsStore {
    var tick by mutableIntStateOf(0)
        private set

    /** A pause longer than this ends a "session" (one time you opened reels). */
    const val SESSION_GAP_MS = 120_000L

    /** Two videos can't really be swiped faster than this, so closer events are one swipe. */
    const val MIN_GAP_MS = 450L

    private const val KEEP_DAYS = 90

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("kanade_reels", Context.MODE_PRIVATE)

    /** Something changed: redraw the open screens, the floating brain, the lock and the widget. */
    private fun changed(c: Context) {
        tick++
        val svc = VolumeAccessibilityService.instance
        svc?.refreshReelsBadge()
        svc?.refreshReelsLock()
        ReelsWidgetUpdater.requestUpdate(c)
    }

    // ── Settings (counting is off until the person turns it on) ──
    fun enabled(c: Context): Boolean = sp(c).getBoolean("on", false)
    fun setEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("on", v).apply()
        changed(c)
    }

    fun overlay(c: Context): Boolean = sp(c).getBoolean("overlay", true)
    fun setOverlay(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("overlay", v).apply()
        changed(c)
    }

    fun apps(c: Context): Set<String> =
        (sp(c).getString("apps", "yt,ig,tt") ?: "").split(",").filter { it.isNotBlank() }.toSet()

    fun setApp(c: Context, key: String, on: Boolean) {
        val s = apps(c).toMutableSet()
        if (on) s.add(key) else s.remove(key)
        sp(c).edit().putString("apps", s.joinToString(",")).apply()
        changed(c)
    }

    fun limit(c: Context): Int = sp(c).getInt("limit", 100)
    fun setLimit(c: Context, v: Int) {
        sp(c).edit().putInt("limit", v.coerceIn(10, 1000)).apply()
        changed(c)
    }

    fun secPerReel(c: Context): Int = sp(c).getInt("sec", 25)
    fun setSecPerReel(c: Context, v: Int) {
        sp(c).edit().putInt("sec", v.coerceIn(5, 120)).apply()
        changed(c)
    }

    /** Calibration mode: keeps a short in-memory log of the scroll events (never saved). */
    fun diagOn(c: Context): Boolean = sp(c).getBoolean("diag", false)
    fun setDiagOn(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("diag", v).apply()
        changed(c)
    }

    // ── Lock when the daily limit is reached ──
    /** After a lock ends, this many more videos are allowed before the next lock. */
    const val LOCK_GRACE = 10

    fun lockOn(c: Context): Boolean = sp(c).getBoolean("lock", false)
    fun setLockOn(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("lock", v).apply()
        changed(c)
    }

    fun lockMinutes(c: Context): Int = sp(c).getInt("lockMin", 60)
    fun setLockMinutes(c: Context, v: Int) {
        sp(c).edit().putInt("lockMin", v.coerceIn(5, 720)).apply()
        changed(c)
    }

    fun lockUntil(c: Context): Long = sp(c).getLong("lockUntil", 0L)

    fun isLocked(c: Context, now: Long): Boolean = lockOn(c) && lockUntil(c) > now

    /**
     * Starts the lock when today's [total] reached the limit (or, after a lock ended, the limit plus
     * the videos watched since). Returns true if the apps are locked right now.
     */
    fun startLockIfNeeded(c: Context, total: Int, now: Long): Boolean {
        if (!lockOn(c)) return false
        if (lockUntil(c) > now) return true
        val p = sp(c)
        val day = dayKey(now)
        val lim = limit(c)
        val at = if (p.getString("lockDay", "") == day) p.getInt("lockAt", lim) else lim
        if (total < at) return false
        p.edit()
            .putLong("lockUntil", now + lockMinutes(c) * 60_000L)
            .putString("lockDay", day)
            .putInt("lockAt", total + LOCK_GRACE)
            .apply()
        return true
    }

    // ── Sharing the daily totals with the bot (off until turned on) ──
    fun syncOn(c: Context): Boolean = sp(c).getBoolean("sync", false)
    fun setSyncOn(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("sync", v).apply()
        changed(c)
    }

    fun appOf(pkg: String): ReelsApp? = REELS_APPS.firstOrNull { pkg in it.packages }

    // ── Days ──
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun dayKey(ms: Long): String = fmt().format(Date(ms))

    fun dayKeyOffset(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return fmt().format(cal.time)
    }

    private fun empty(key: String) = ReelsDay(key, emptyMap(), IntArray(24), emptyList(), 0L)

    fun day(c: Context, key: String): ReelsDay {
        val raw = sp(c).getString("d_$key", null) ?: return empty(key)
        return try {
            parse(key, JSONObject(raw))
        } catch (e: Exception) {
            empty(key)
        }
    }

    fun today(c: Context): ReelsDay = day(c, dayKey(System.currentTimeMillis()))

    /** The last [n] days, oldest first, ending today. [skip] drops that many of the newest days. */
    fun lastDays(c: Context, n: Int, skip: Int = 0): List<ReelsDay> =
        (n - 1 + skip downTo skip).map { day(c, dayKeyOffset(it)) }

    private fun parse(key: String, o: JSONObject): ReelsDay {
        val counts = HashMap<String, Int>()
        val co = o.optJSONObject("c")
        if (co != null) {
            val names = co.keys()
            while (names.hasNext()) {
                val k = names.next()
                counts[k] = co.optInt(k, 0)
            }
        }
        val hours = IntArray(24)
        val ha = o.optJSONArray("h")
        if (ha != null) {
            for (i in 0 until minOf(24, ha.length())) hours[i] = ha.optInt(i, 0)
        }
        val sess = ArrayList<ReelsSession>()
        val sa = o.optJSONArray("s")
        if (sa != null) {
            for (i in 0 until sa.length()) {
                val p = sa.optJSONArray(i) ?: continue
                sess.add(ReelsSession(p.optLong(0, 0L), p.optInt(1, 0)))
            }
        }
        return ReelsDay(key, counts, hours, sess, o.optLong("l", 0L))
    }

    private fun save(c: Context, d: ReelsDay) {
        val o = JSONObject()
        val co = JSONObject()
        d.counts.forEach { (k, v) -> co.put(k, v) }
        o.put("c", co)
        val ha = JSONArray()
        d.hours.forEach { ha.put(it) }
        o.put("h", ha)
        val sa = JSONArray()
        d.sessions.forEach { sa.put(JSONArray().put(it.start).put(it.count)) }
        o.put("s", sa)
        o.put("l", d.lastAt)
        sp(c).edit().putString("d_${d.key}", o.toString()).apply()
    }

    /** Counts one short video for [appKey]. Returns today's new total. */
    fun record(c: Context, appKey: String, now: Long): Int {
        val key = dayKey(now)
        val d = day(c, key)
        val counts = d.counts.toMutableMap()
        counts[appKey] = (counts[appKey] ?: 0) + 1

        val hours = d.hours.copyOf()
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        hours[hour] = hours[hour] + 1

        val sess = d.sessions.toMutableList()
        if (sess.isEmpty() || now - d.lastAt > SESSION_GAP_MS) {
            sess.add(ReelsSession(now, 1))
        } else {
            val last = sess.removeAt(sess.size - 1)
            sess.add(ReelsSession(last.start, last.count + 1))
        }

        save(c, ReelsDay(key, counts, hours, sess, now))
        if (sess.size == 1 && sess[0].count == 1) prune(c)
        val total = counts.values.sum()
        startLockIfNeeded(c, total, now)
        changed(c)
        ReelsSync.request(c)
        return total
    }

    /** Forgets days older than [KEEP_DAYS] (runs once a day, on the first video of the day). */
    private fun prune(c: Context) {
        val p = sp(c)
        val cutoff = dayKeyOffset(KEEP_DAYS)
        val old = p.all.keys.filter { it.startsWith("d_") && it.substring(2) < cutoff }
        if (old.isNotEmpty()) {
            val e = p.edit()
            old.forEach { e.remove(it) }
            e.apply()
        }
    }

    fun clearAll(c: Context) {
        val p = sp(c)
        val e = p.edit()
        p.all.keys.filter { it.startsWith("d_") }.forEach { e.remove(it) }
        e.apply()
        changed(c)
    }
}

/**
 * Turns scroll events into a count of short videos. A reels/shorts screen is a full-page pager: one
 * item on screen at a time, and each swipe moves to the next item. So a video is counted when the
 * first visible item changes in a pager-like list, or when a single event jumps about a screen.
 */
object ReelsEngine {
    var logTick by mutableIntStateOf(0)
        private set

    /** Newest line first. Only kept in memory while calibration mode is on. */
    val log = ArrayList<String>()

    private val lastFrom = HashMap<String, Int>()
    private var lastPkg = ""
    private var lastCountAt = 0L

    /** The app in front changed: forget the old list positions so the first scroll isn't counted. */
    fun onForeground(pkg: String) {
        if (pkg != lastPkg) {
            lastFrom.clear()
            lastPkg = pkg
        }
    }

    fun judge(s: ReelsSample, now: Long): ReelsVerdict {
        val prev = lastFrom[s.pkg] ?: -1
        if (s.from >= 0) lastFrom[s.pkg] = s.from

        val horizontal = abs(s.deltaX) > abs(s.deltaY) && abs(s.deltaX) > 0
        if (horizontal) return ReelsVerdict(false, "horizontal")

        val pagerLike = s.from >= 0 && s.to >= s.from && s.to - s.from <= 1
        val indexMoved = pagerLike && prev >= 0 && s.from != prev
        val bigJump = s.screenH > 0 && abs(s.deltaY) >= s.screenH / 2
        if (!indexMoved && !bigJump) return ReelsVerdict(false, "not a page change")

        if (now - lastCountAt < ReelsStore.MIN_GAP_MS) return ReelsVerdict(false, "too fast")
        lastCountAt = now
        return ReelsVerdict(true, if (indexMoved) "index" else "jump")
    }

    fun onScroll(c: Context, s: ReelsSample, now: Long) {
        val app = ReelsStore.appOf(s.pkg) ?: return
        val on = ReelsStore.enabled(c)
        val appOn = ReelsStore.apps(c).contains(app.key)
        val verdict = when {
            !on -> ReelsVerdict(false, "counter off")
            !appOn -> ReelsVerdict(false, "app off")
            else -> judge(s, now)
        }
        if (ReelsStore.diagOn(c)) addLog(app, s, verdict, now)
        if (verdict.counted) ReelsStore.record(c, app.key, now)
    }

    private fun addLog(app: ReelsApp, s: ReelsSample, v: ReelsVerdict, now: Long) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(now))
        val cls = s.className.substringAfterLast('.')
        val mark = if (v.counted) "+1" else "--"
        log.add(
            0,
            "$time ${app.key} $mark ${v.reason}\n   $cls from=${s.from} to=${s.to} n=${s.items} " +
                "y=${s.scrollY} dy=${s.deltaY} dx=${s.deltaX}",
        )
        while (log.size > 80) log.removeAt(log.size - 1)
        logTick++
    }

    fun clearLog() {
        log.clear()
        logTick++
    }
}
