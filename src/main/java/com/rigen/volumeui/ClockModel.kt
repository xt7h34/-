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
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

const val PAGE_CLOCK = "clock"
const val PAGE_CLOCK_TIME = "clock_time"
const val PAGE_ALARMS = "alarms"
const val PAGE_TIMER = "timer"
const val PAGE_SLEEP = "sleep"

/** [days] is a bit mask, bit 0 = Sunday ... bit 6 = Saturday. Zero means "once". */
data class AlarmItem(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean,
    val days: Int,
)

class TimerState(
    val active: Boolean,
    val total: Long,
    val running: Boolean,
    val endAt: Long,
    val left: Long,
) {
    fun remaining(now: Long): Long = if (running) (endAt - now).coerceAtLeast(0L) else left
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
    for (i in 0..8) {
        val dow = c.get(Calendar.DAY_OF_WEEK) - 1
        val dayOk = a.days == 0 || ((a.days shr dow) and 1) == 1
        if (c.timeInMillis > now && dayOk) return c.timeInMillis
        c.add(Calendar.DAY_OF_YEAR, 1)
    }
    return c.timeInMillis
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
                    .put("label", it.label).put("on", it.enabled).put("days", it.days),
            )
        }
        sp(c).edit().putString("alarms", arr.toString()).apply()
    }

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

    // ── Timer ──
    fun timer(c: Context): TimerState {
        val p = sp(c)
        return TimerState(
            p.getBoolean("t_active", false), p.getLong("t_total", 0L), p.getBoolean("t_running", false),
            p.getLong("t_end", 0L), p.getLong("t_left", 0L),
        )
    }

    fun saveTimer(c: Context, t: TimerState) {
        sp(c).edit().putBoolean("t_active", t.active).putLong("t_total", t.total)
            .putBoolean("t_running", t.running).putLong("t_end", t.endAt).putLong("t_left", t.left).apply()
    }

    fun floatingEnabled(c: Context): Boolean = sp(c).getBoolean("t_float", true)
    fun setFloatingEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("t_float", v).apply()
    fun floatingHidden(c: Context): Boolean = sp(c).getBoolean("t_float_hidden", false)
    fun setFloatingHidden(c: Context, v: Boolean) = sp(c).edit().putBoolean("t_float_hidden", v).apply()

    // ── Sleep ──
    fun sleepGoal(c: Context): Int = sp(c).getInt("s_goal", 480)
    fun setSleepGoal(c: Context, v: Int) = sp(c).edit().putInt("s_goal", v).apply()
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
    }
}

/** Alarms, the timer and their notifications. */
object ClockEngine {
    private const val CH_ALARM = "kanade_alarm"
    private const val CH_TIMER = "kanade_timer"
    const val ACTION_ALARM = "com.kanade.clock.ALARM"
    const val ACTION_SNOOZE = "com.kanade.clock.SNOOZE"
    const val ACTION_DISMISS = "com.kanade.clock.DISMISS"
    const val ACTION_TIMER_END = "com.kanade.clock.TIMER_END"
    const val ACTION_TIMER_TOGGLE = "com.kanade.clock.TIMER_TOGGLE"
    private const val NOTE_TIMER = 2000
    private const val NOTE_TIMER_DONE = 2001
    private const val RC_TIMER = 900001
    private const val SNOOZE_MS = 10 * 60_000L

    private fun alarmManager(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun notes(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun receiverIntent(c: Context, action: String, id: Int = 0): Intent =
        Intent(c, ClockReceiver::class.java).setAction(action).putExtra("id", id)

    private fun broadcast(c: Context, action: String, id: Int, requestCode: Int): PendingIntent =
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
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
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
        ring(c, 1000 + id, a.label.ifBlank { c.getString(R.string.alarm_title) }, id)
        if (a.days == 0) {
            ClockStore.saveAlarms(c, list.map { if (it.id == id) it.copy(enabled = false) else it })
        } else {
            scheduleAlarm(c, a)
        }
    }

    fun snooze(c: Context, id: Int) {
        notes(c).cancel(1000 + id)
        val at = System.currentTimeMillis() + SNOOZE_MS
        val op = broadcast(c, ACTION_ALARM, id, 500000 + id)
        alarmManager(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(c, PAGE_ALARMS, 5)), op)
        // The snoozed ring must still be able to find the alarm: keep it in the list as is.
    }

    @Suppress("DEPRECATION")
    private fun ring(c: Context, noteId: Int, title: String, alarmId: Int) {
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
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioManager.STREAM_ALARM)
            b.setPriority(Notification.PRIORITY_MAX)
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        notes(c).notify(noteId, n)
    }

    fun dismiss(c: Context, noteId: Int) {
        notes(c).cancel(noteId)
    }

    // ───────────── Timer ─────────────

    private fun scheduleTimerEnd(c: Context, at: Long) {
        val op = broadcast(c, ACTION_TIMER_END, 0, RC_TIMER)
        val am = alarmManager(c)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        } else {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(c, PAGE_TIMER, 7)), op)
        }
    }

    private fun cancelTimerEnd(c: Context) {
        alarmManager(c).cancel(broadcast(c, ACTION_TIMER_END, 0, RC_TIMER))
    }

    private fun changed(c: Context) {
        updateTimerNote(c)
        VolumeAccessibilityService.instance?.refreshFloatingTimer()
    }

    fun timerStart(c: Context, total: Long) {
        val now = System.currentTimeMillis()
        ClockStore.setFloatingHidden(c, false)
        notes(c).cancel(NOTE_TIMER_DONE)
        ClockStore.saveTimer(c, TimerState(true, total, true, now + total, total))
        scheduleTimerEnd(c, now + total)
        changed(c)
    }

    fun timerPause(c: Context) {
        val t = ClockStore.timer(c)
        if (!t.active || !t.running) return
        val left = t.remaining(System.currentTimeMillis())
        ClockStore.saveTimer(c, TimerState(true, t.total, false, 0L, left))
        cancelTimerEnd(c)
        changed(c)
    }

    fun timerResume(c: Context) {
        val t = ClockStore.timer(c)
        if (!t.active || t.running) return
        val end = System.currentTimeMillis() + t.left
        ClockStore.saveTimer(c, TimerState(true, t.total, true, end, t.left))
        scheduleTimerEnd(c, end)
        changed(c)
    }

    fun timerToggle(c: Context) {
        if (ClockStore.timer(c).running) timerPause(c) else timerResume(c)
    }

    fun timerCancel(c: Context) {
        ClockStore.saveTimer(c, TimerState(false, 0L, false, 0L, 0L))
        cancelTimerEnd(c)
        changed(c)
    }

    fun onTimerEnd(c: Context) {
        val t = ClockStore.timer(c)
        if (!t.active || !t.running) return
        ClockStore.saveTimer(c, TimerState(false, 0L, false, 0L, 0L))
        changed(c)
        ring(c, NOTE_TIMER_DONE, c.getString(R.string.timer_done), 0)
    }

    /** The always-there notification: it is what shows the timer next to the other notifications. */
    @Suppress("DEPRECATION")
    fun updateTimerNote(c: Context) {
        val nm = notes(c)
        val t = ClockStore.timer(c)
        if (!t.active) {
            nm.cancel(NOTE_TIMER)
            return
        }
        ensureChannels(c)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH_TIMER) else Notification.Builder(c)
        b.setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(c.getString(R.string.timer_title))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(c, PAGE_TIMER, 7))
        if (t.running) {
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
            broadcast(c, ACTION_TIMER_TOGGLE, 0, 800001),
        )
        // Android 16 can show an ongoing notification as a chip in the status bar. Older versions ignore this.
        b.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        nm.notify(NOTE_TIMER, b.build())
    }

    /** After a reboot: put the alarms and the timer back. */
    fun restore(c: Context) {
        rescheduleAll(c)
        val t = ClockStore.timer(c)
        if (t.active && t.running) {
            if (t.endAt <= System.currentTimeMillis()) onTimerEnd(c) else {
                scheduleTimerEnd(c, t.endAt)
                updateTimerNote(c)
            }
        }
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
                ClockEngine.ACTION_TIMER_END -> ClockEngine.onTimerEnd(context)
                ClockEngine.ACTION_TIMER_TOGGLE -> ClockEngine.timerToggle(context)
                Intent.ACTION_BOOT_COMPLETED -> ClockEngine.restore(context)
            }
        } catch (e: Throwable) {
            Diag.error(context, "ClockReceiver", e)
        }
    }
}
