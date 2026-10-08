package com.rigen.volumeui

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

const val PAGE_CLOCK = "clock"
const val PAGE_CLOCK_TIME = "clock_time"
const val PAGE_ALARMS = "alarms"
const val PAGE_TIMER = "timer"
const val PAGE_SLEEP = "sleep"
const val PAGE_STOPWATCH = "stopwatch"

/** [days] is a bit mask, bit 0 = Sunday ... bit 6 = Saturday. Zero means "once". */
data class AlarmItem(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean,
    val days: Int,
    /** "" = the Kanade tone, otherwise the uri of a sound picked on the phone. */
    val ringtone: String = "",
    /** Minutes of snooze for this alarm. 0 = the general setting. */
    val snooze: Int = 0,
    /** "Skip next": the alarm does not ring at or before this time. */
    val skipUntil: Long = 0L,
)

class TimerState(
    val id: Int,
    val active: Boolean,
    val total: Long,
    val running: Boolean,
    val endAt: Long,
    val left: Long,
) {
    fun remaining(now: Long): Long = if (running) (endAt - now).coerceAtLeast(0L) else left
}

/** [startAt] is on the SystemClock.elapsedRealtime clock; [laps] are the elapsed times when Lap was pressed. */
class StopwatchState(val running: Boolean, val startAt: Long, val acc: Long, val laps: List<Long>) {
    fun elapsed(nowElapsed: Long): Long = acc + if (running) (nowElapsed - startAt).coerceAtLeast(0L) else 0L
}

class SleepEntry(val bed: Long, val wake: Long) {
    val minutes: Int get() = ((wake - bed) / 60_000L).toInt()
}

fun nextTrigger(a: AlarmItem, now: Long = System.currentTimeMillis()): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = now
    c.set(Calendar.HOUR_OF_DAY, a.hour)
    c.set(Calendar.MINUTE, a.minute)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    var found = -1L
    for (i in 0..8) {
        val dow = c.get(Calendar.DAY_OF_WEEK) - 1
        val dayOk = a.days == 0 || ((a.days shr dow) and 1) == 1
        if (c.timeInMillis > now && dayOk) {
            found = c.timeInMillis
            break
        }
        c.add(Calendar.DAY_OF_YEAR, 1)
    }
    val t = if (found >= 0L) found else c.timeInMillis
    // "Skip next": ignore the one occurrence the user skipped and take the one after it.
    return if (a.skipUntil > 0L && t <= a.skipUntil) nextTrigger(a.copy(skipUntil = 0L), t) else t
}

fun dayKey(ms: Long): Int {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

/** Everything the Clock section remembers. Small JSON documents in SharedPreferences. */
object ClockStore {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("kanade_clock", Context.MODE_PRIVATE)

    // ── Alarms ──
    fun alarms(c: Context): List<AlarmItem> = try {
        val arr = JSONArray(sp(c).getString("alarms", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            AlarmItem(
                o.getInt("id"), o.getInt("h"), o.getInt("m"),
                o.optString("label", ""), o.optBoolean("on", true), o.optInt("days", 0),
                o.optString("tone", ""),
                o.optInt("snz", 0), o.optLong("skip", 0L),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveAlarms(c: Context, list: List<AlarmItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("h", it.hour).put("m", it.minute)
                    .put("label", it.label).put("on", it.enabled).put("days", it.days)
                    .put("tone", it.ringtone).put("snz", it.snooze).put("skip", it.skipUntil),
            )
        }
        sp(c).edit().putString("alarms", arr.toString()).apply()
        ClockWidgets.updateAll(c)
    }

    // ── How the alarm rings and looks ──
    fun ringBg(c: Context): String = sp(c).getString("r_bg", "default") ?: "default"
    fun setRingBg(c: Context, v: String) = sp(c).edit().putString("r_bg", v).apply()
    fun ringBgVersion(c: Context): Long = sp(c).getLong("r_bg_ver", 0L)
    fun setRingBgVersion(c: Context, v: Long) = sp(c).edit().putLong("r_bg_ver", v).apply()
    fun ringClock(c: Context): Int = sp(c).getInt("r_clock", 2)
    fun setRingClock(c: Context, v: Int) = sp(c).edit().putInt("r_clock", v).apply()
    fun ringDismiss(c: Context): Int = sp(c).getInt("r_dismiss", DISMISS_DRAG)
    fun setRingDismiss(c: Context, v: Int) = sp(c).edit().putInt("r_dismiss", v).apply()
    fun ringSnooze(c: Context): Int = sp(c).getInt("r_snooze", 5).coerceIn(1, 30)
    fun setRingSnooze(c: Context, v: Int) = sp(c).edit().putInt("r_snooze", v.coerceIn(1, 30)).apply()

    /** Each switch: the alarm still rings when the phone is in that mode. Off = it follows the mode. */
    fun ringInDnd(c: Context): Boolean = sp(c).getBoolean("r_in_dnd", true)
    fun setRingInDnd(c: Context, v: Boolean) = sp(c).edit().putBoolean("r_in_dnd", v).apply()
    fun ringInSilent(c: Context): Boolean = sp(c).getBoolean("r_in_silent", true)
    fun setRingInSilent(c: Context, v: Boolean) = sp(c).edit().putBoolean("r_in_silent", v).apply()
    fun ringInVibrate(c: Context): Boolean = sp(c).getBoolean("r_in_vibrate", true)
    fun setRingInVibrate(c: Context, v: Boolean) = sp(c).edit().putBoolean("r_in_vibrate", v).apply()

    fun ringRamp(c: Context): Boolean = sp(c).getBoolean("r_ramp", false)
    fun setRingRamp(c: Context, v: Boolean) = sp(c).edit().putBoolean("r_ramp", v).apply()

    /** 0 = like the phone, 1 = 0 1 2, 2 = Arabic-Indic digits. */
    fun ringDigits(c: Context): Int = sp(c).getInt("r_digits", 0)
    fun setRingDigits(c: Context, v: Int) = sp(c).edit().putInt("r_digits", v).apply()

    fun ringLocale(c: Context): java.util.Locale {
        val base = java.util.Locale.getDefault()
        val digits = when (ringDigits(c)) {
            1 -> "latn"
            2 -> "arab"
            else -> return base
        }
        return java.util.Locale.Builder().setLocale(base).setUnicodeLocaleKeyword("nu", digits).build()
    }

    fun relDismissedAt(c: Context): Long = sp(c).getLong("rel_dismissed", 0L)
    fun setRelDismissedAt(c: Context, v: Long) = sp(c).edit().putLong("rel_dismissed", v).apply()

    fun nextAlarmId(c: Context): Int = (alarms(c).maxOfOrNull { it.id } ?: 0) + 1

    // ── World clock ──
    fun zones(c: Context): List<String> = try {
        val arr = JSONArray(sp(c).getString("zones", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveZones(c: Context, list: List<String>) {
        sp(c).edit().putString("zones", JSONArray(list).toString()).apply()
    }

    // ── Timers (several can run at once) ──
    fun timers(c: Context): List<TimerState> {
        val p = sp(c)
        val raw = p.getString("timers", null)
        if (raw == null) {
            // The old single timer, from before there could be several.
            if (p.getBoolean("t_active", false)) {
                val t = TimerState(
                    1, true, p.getLong("t_total", 0L), p.getBoolean("t_running", false),
                    p.getLong("t_end", 0L), p.getLong("t_left", 0L),
                )
                saveTimers(c, listOf(t))
                p.edit().putBoolean("t_active", false).apply()
                return listOf(t)
            }
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                TimerState(
                    o.getInt("id"), true, o.getLong("total"), o.getBoolean("run"),
                    o.getLong("end"), o.getLong("left"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTimers(c: Context, list: List<TimerState>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("total", it.total).put("run", it.running)
                    .put("end", it.endAt).put("left", it.left),
            )
        }
        sp(c).edit().putString("timers", arr.toString()).apply()
    }

    /** The timer the notification and the floating pill follow: the one that ends first, else the first. */
    fun primaryTimer(list: List<TimerState>): TimerState =
        list.filter { it.running }.minByOrNull { it.endAt }
            ?: list.firstOrNull()
            ?: TimerState(0, false, 0L, false, 0L, 0L)

    fun timer(c: Context): TimerState = primaryTimer(timers(c))

    fun nextTimerId(list: List<TimerState>): Int = (list.maxOfOrNull { it.id } ?: 0) + 1

    // ── Stopwatch ──
    fun stopwatch(c: Context): StopwatchState {
        val p = sp(c)
        var running = p.getBoolean("sw_running", false)
        val start = p.getLong("sw_start", 0L)
        // The clock behind it restarts at boot, so a stopwatch that was running cannot go on.
        if (running && start > android.os.SystemClock.elapsedRealtime()) running = false
        val laps = try {
            val arr = JSONArray(p.getString("sw_laps", "[]"))
            (0 until arr.length()).map { arr.getLong(it) }
        } catch (e: Exception) {
            emptyList()
        }
        return StopwatchState(running, start, p.getLong("sw_acc", 0L), laps)
    }

    fun saveStopwatch(c: Context, s: StopwatchState) {
        sp(c).edit().putBoolean("sw_running", s.running).putLong("sw_start", s.startAt)
            .putLong("sw_acc", s.acc).putString("sw_laps", JSONArray(s.laps).toString()).apply()
    }

    fun floatingEnabled(c: Context): Boolean = sp(c).getBoolean("t_float", true)
    fun setFloatingEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("t_float", v).apply()
    fun floatingHidden(c: Context): Boolean = sp(c).getBoolean("t_float_hidden", false)
    fun setFloatingHidden(c: Context, v: Boolean) = sp(c).edit().putBoolean("t_float_hidden", v).apply()

    // ── Sleep ──
    fun sleepGoal(c: Context): Int = sp(c).getInt("s_goal", 480)
    fun setSleepGoal(c: Context, v: Int) {
        sp(c).edit().putInt("s_goal", v).apply()
        ClockWidgets.updateAll(c)
    }
    fun sleepBedOverride(c: Context): Int = sp(c).getInt("s_bed", -1)
    fun setSleepBedOverride(c: Context, v: Int) = sp(c).edit().putInt("s_bed", v).apply()
    fun sleepManualWake(c: Context): Int = sp(c).getInt("s_wake", -1)
    fun setSleepManualWake(c: Context, v: Int) = sp(c).edit().putInt("s_wake", v).apply()
    fun sleepPromptDismissed(c: Context): Int = sp(c).getInt("s_prompt_day", 0)
    fun setSleepPromptDismissed(c: Context, v: Int) = sp(c).edit().putInt("s_prompt_day", v).apply()

    fun sleepEntries(c: Context): List<SleepEntry> = try {
        val arr = JSONArray(sp(c).getString("sleep", "[]"))
        (0 until arr.length()).map { SleepEntry(arr.getJSONObject(it).getLong("b"), arr.getJSONObject(it).getLong("w")) }
            .sortedBy { it.wake }
    } catch (e: Exception) {
        emptyList()
    }

    fun addSleep(c: Context, bed: Long, wake: Long) {
        val key = dayKey(wake)
        val list = sleepEntries(c).filter { dayKey(it.wake) != key } + SleepEntry(bed, wake)
        val arr = JSONArray()
        list.sortedBy { it.wake }.takeLast(60).forEach { arr.put(JSONObject().put("b", it.bed).put("w", it.wake)) }
        sp(c).edit().putString("sleep", arr.toString()).apply()
        ClockWidgets.updateAll(c)
    }
}

/** Alarms, the timer and their notifications. */
object ClockEngine {
    // A channel keeps the sound it was created with, so the new ringtone needs a new channel id.
    private const val CH_ALARM_OLD = "kanade_alarm"
    private const val CH_ALARM = "kanade_alarm_v2"
    private const val CH_TIMER = "kanade_timer"
    const val ACTION_ALARM = "com.kanade.clock.ALARM"
    const val ACTION_SNOOZE = "com.kanade.clock.SNOOZE"
    const val ACTION_DISMISS = "com.kanade.clock.DISMISS"
    const val ACTION_TIMER_END = "com.kanade.clock.TIMER_END"
    const val ACTION_TIMER_TOGGLE = "com.kanade.clock.TIMER_TOGGLE"
    const val ACTION_TIMER_CANCEL = "com.kanade.clock.TIMER_CANCEL"
    private const val NOTE_TIMER = 2000
    private const val NOTE_TIMER_DONE = 2100 // + the timer's id
    private const val RC_TIMER = 910000 // + the timer's id
    const val MAX_TIMERS = 8

    private fun alarmManager(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun notes(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun receiverIntent(c: Context, action: String, id: Int = 0): Intent =
        Intent(c, ClockReceiver::class.java).setAction(action).putExtra("id", id)

    internal fun broadcast(c: Context, action: String, id: Int, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, requestCode, receiverIntent(c, action, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(c: Context, page: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            c, requestCode,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notes(c)
        val sound = android.net.Uri.parse("android.resource://" + c.packageName + "/" + R.raw.kanade_alarm)
        nm.deleteNotificationChannel(CH_ALARM_OLD)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val alarm = NotificationChannel(CH_ALARM, c.getString(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH)
        alarm.setSound(sound, attrs)
        alarm.enableVibration(true)
        alarm.vibrationPattern = longArrayOf(0, 600, 400, 600)
        alarm.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(alarm)
        val timer = NotificationChannel(CH_TIMER, c.getString(R.string.ch_timer), NotificationManager.IMPORTANCE_LOW)
        timer.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        timer.setShowBadge(false)
        nm.createNotificationChannel(timer)
    }

    // ───────────── Alarms ─────────────

    fun scheduleAlarm(c: Context, a: AlarmItem) {
        val op = broadcast(c, ACTION_ALARM, a.id, a.id)
        if (!a.enabled) {
            alarmManager(c).cancel(op)
            return
        }
        val info = AlarmManager.AlarmClockInfo(nextTrigger(a), openApp(c, PAGE_ALARMS, 5))
        alarmManager(c).setAlarmClock(info, op)
    }

    fun rescheduleAll(c: Context) {
        ClockStore.alarms(c).forEach { scheduleAlarm(c, it) }
    }

    fun cancelAlarm(c: Context, id: Int) {
        alarmManager(c).cancel(broadcast(c, ACTION_ALARM, id, id))
    }

    fun onAlarmFired(c: Context, id: Int) {
        val list = ClockStore.alarms(c)
        val a = list.firstOrNull { it.id == id } ?: return
        ring(c, 1000 + id, a.label.ifBlank { c.getString(R.string.alarm_title) }, id, a.ringtone)
        if (a.days == 0) {
            ClockStore.saveAlarms(c, list.map { if (it.id == id) it.copy(enabled = false) else it })
        } else {
            scheduleAlarm(c, a)
        }
        ClockWidgets.updateAll(c)
    }

    fun snooze(c: Context, id: Int) {
        stopRinging(c)
        notes(c).cancel(1000 + id)
        val own = ClockStore.alarms(c).firstOrNull { it.id == id }?.snooze ?: 0
        val minutes = if (own > 0) own else ClockStore.ringSnooze(c)
        val at = System.currentTimeMillis() + minutes * 60_000L
        val op = broadcast(c, ACTION_ALARM, id, 500000 + id)
        alarmManager(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(c, PAGE_ALARMS, 5)), op)
        // The snoozed ring must still be able to find the alarm: keep it in the list as is.
    }

    private fun stopRinging(c: Context) {
        try {
            c.stopService(Intent(c, AlarmRingService::class.java))
        } catch (e: Throwable) {
            Diag.error(c, "stopRinging", e)
        }
    }

    /** Rings with the full ring screen. If Android refuses the service, a plain notification still rings. */
    private fun ring(c: Context, noteId: Int, title: String, alarmId: Int, tone: String = "") {
        try {
            AlarmRingService.start(c, noteId, title, alarmId, tone)
        } catch (e: Throwable) {
            Diag.error(c, "ring service, using a notification", e)
            ringFallback(c, noteId, title, alarmId)
        }
    }

    @Suppress("DEPRECATION")
    private fun ringFallback(c: Context, noteId: Int, title: String, alarmId: Int) {
        ensureChannels(c)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH_ALARM) else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(c.getString(R.string.alarm_ringing))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openApp(c, PAGE_ALARMS, 6))
            .setTimeoutAfter(5 * 60_000L)
            .addAction(0, c.getString(R.string.alarm_dismiss), broadcast(c, ACTION_DISMISS, noteId, 600000 + noteId))
        if (alarmId > 0) {
            b.addAction(0, c.getString(R.string.alarm_snooze), broadcast(c, ACTION_SNOOZE, alarmId, 700000 + alarmId))
        }
        if (Build.VERSION.SDK_INT < 26) {
            b.setSound(android.net.Uri.parse("android.resource://" + c.packageName + "/" + R.raw.kanade_alarm), AudioManager.STREAM_ALARM)
            b.setPriority(Notification.PRIORITY_MAX)
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        notes(c).notify(noteId, n)
    }

    /** Rings once with the real screen and sound, to check the look without waiting for an alarm. */
    fun testRing(c: Context) {
        ring(c, 1999, c.getString(R.string.alarm_title), -1, "")
    }

    fun dismiss(c: Context, noteId: Int) {
        stopRinging(c)
        notes(c).cancel(noteId)
    }

    // ───────────── Timers ─────────────

    private fun scheduleTimerEnd(c: Context, id: Int, at: Long) {
        val op = broadcast(c, ACTION_TIMER_END, id, RC_TIMER + id)
        val am = alarmManager(c)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        } else {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(c, PAGE_TIMER, 7)), op)
        }
    }

    private fun cancelTimerEnd(c: Context, id: Int) {
        alarmManager(c).cancel(broadcast(c, ACTION_TIMER_END, id, RC_TIMER + id))
    }

    private fun changed(c: Context) {
        updateTimerNote(c)
        VolumeAccessibilityService.instance?.refreshFloatingTimer()
    }

    /** The timer with this id, or (id 0) the one the notification follows. */
    private fun target(c: Context, id: Int): TimerState? {
        val list = ClockStore.timers(c)
        if (list.isEmpty()) return null
        return if (id > 0) list.firstOrNull { it.id == id } else ClockStore.primaryTimer(list)
    }

    private fun replaceTimer(c: Context, t: TimerState) {
        ClockStore.saveTimers(c, ClockStore.timers(c).map { if (it.id == t.id) t else it })
    }

    /** Starts a new timer and returns its id, or 0 when there are already too many. */
    fun timerStart(c: Context, total: Long): Int {
        val list = ClockStore.timers(c)
        if (list.size >= MAX_TIMERS) return 0
        val now = System.currentTimeMillis()
        val id = ClockStore.nextTimerId(list)
        ClockStore.setFloatingHidden(c, false)
        ClockStore.saveTimers(c, list + TimerState(id, true, total, true, now + total, total))
        scheduleTimerEnd(c, id, now + total)
        changed(c)
        return id
    }

    fun timerPause(c: Context, id: Int = 0) {
        val t = target(c, id) ?: return
        if (!t.running) return
        replaceTimer(c, TimerState(t.id, true, t.total, false, 0L, t.remaining(System.currentTimeMillis())))
        cancelTimerEnd(c, t.id)
        changed(c)
    }

    fun timerResume(c: Context, id: Int = 0) {
        val t = target(c, id) ?: return
        if (t.running) return
        val end = System.currentTimeMillis() + t.left
        replaceTimer(c, TimerState(t.id, true, t.total, true, end, t.left))
        scheduleTimerEnd(c, t.id, end)
        changed(c)
    }

    fun timerToggle(c: Context, id: Int = 0) {
        val t = target(c, id) ?: return
        if (t.running) timerPause(c, t.id) else timerResume(c, t.id)
    }

    fun timerCancel(c: Context, id: Int = 0) {
        val t = target(c, id) ?: return
        ClockStore.saveTimers(c, ClockStore.timers(c).filter { it.id != t.id })
        cancelTimerEnd(c, t.id)
        changed(c)
    }

    fun onTimerEnd(c: Context, id: Int) {
        val t = ClockStore.timers(c).firstOrNull { it.id == id } ?: return
        if (!t.running) return
        ClockStore.saveTimers(c, ClockStore.timers(c).filter { it.id != id })
        changed(c)
        ring(c, NOTE_TIMER_DONE + id, c.getString(R.string.timer_done), 0)
    }

    /** The always-there notification: it follows the timer that ends first. */
    @Suppress("DEPRECATION")
    fun updateTimerNote(c: Context) {
        val nm = notes(c)
        val list = ClockStore.timers(c)
        if (list.isEmpty()) {
            nm.cancel(NOTE_TIMER)
            return
        }
        val t = ClockStore.primaryTimer(list)
        ensureChannels(c)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH_TIMER) else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(c.getString(R.string.timer_title) + if (list.size > 1) " • " + list.size else "")
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF4F5BD5.toInt())
            .setContentIntent(openApp(c, PAGE_TIMER, 7))
        if (t.running) {
            b.setContentText(c.getString(R.string.timer_running_card))
            b.setUsesChronometer(true)
            b.setChronometerCountDown(true)
            b.setShowWhen(true)
            b.setWhen(t.endAt)
        } else {
            b.setShowWhen(false)
            b.setContentText(c.getString(R.string.timer_paused) + " • " + formatMs(t.left))
        }
        b.addAction(
            0,
            c.getString(if (t.running) R.string.timer_pause else R.string.timer_resume),
            broadcast(c, ACTION_TIMER_TOGGLE, t.id, 800001),
        )
        b.addAction(0, c.getString(R.string.timer_cancel), broadcast(c, ACTION_TIMER_CANCEL, t.id, 800002))
        // Android 16 shows an ongoing notification as a live update (a chip in the status bar) on
        // phones that support it. Older versions ignore this.
        b.addExtras(
            Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                if (!t.running) putString("android.shortCriticalText", formatMs(t.left))
            },
        )
        nm.notify(NOTE_TIMER, b.build())
    }

    /** Is battery optimization off for this app? When it is on, some phones may stop alarms. */
    fun batteryUnrestricted(c: Context): Boolean =
        c.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) ?: false

    fun openBatterySettings(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
            try {
                c.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + c.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e2: Throwable) {
                Diag.error(c, "openBatterySettings", e2)
            }
        }
    }

    /** Android 14+ asks the user to allow full-screen alarms. Older versions always allow. */
    fun canFullScreen(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) notes(c).canUseFullScreenIntent() else true

    fun openFullScreenSettings(c: Context) {
        try {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + c.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Throwable) {
            try {
                c.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e2: Throwable) {
                Diag.error(c, "openFullScreenSettings", e2)
            }
        }
    }

    /** After a reboot: put the alarms and the timers back. */
    fun restore(c: Context) {
        rescheduleAll(c)
        val now = System.currentTimeMillis()
        ClockStore.timers(c).filter { it.running }.forEach { t ->
            if (t.endAt <= now) onTimerEnd(c, t.id) else scheduleTimerEnd(c, t.id, t.endAt)
        }
        updateTimerNote(c)
        ClockWidgets.updateAll(c)
    }
}

/** mm:ss, or h:mm:ss for an hour or more. Rounds up, so it never shows 00:00 while time is left. */
fun formatMs(ms: Long): String {
    val total = ((ms + 999L) / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val id = intent.getIntExtra("id", 0)
            when (intent.action) {
                ClockEngine.ACTION_ALARM -> ClockEngine.onAlarmFired(context, id)
                ClockEngine.ACTION_SNOOZE -> ClockEngine.snooze(context, id)
                ClockEngine.ACTION_DISMISS -> ClockEngine.dismiss(context, id)
                ClockEngine.ACTION_TIMER_END -> ClockEngine.onTimerEnd(context, id)
                ClockEngine.ACTION_TIMER_TOGGLE -> ClockEngine.timerToggle(context, id)
                ClockEngine.ACTION_TIMER_CANCEL -> ClockEngine.timerCancel(context, id)
                Intent.ACTION_BOOT_COMPLETED -> ClockEngine.restore(context)
            }
        } catch (e: Throwable) {
            Diag.error(context, "ClockReceiver", e)
        }
    }
}
