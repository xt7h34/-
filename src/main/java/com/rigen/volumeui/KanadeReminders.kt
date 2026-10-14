package com.rigen.volumeui

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class RewardState(val id: String, val readyAt: Long)

class PrayerTime(val key: String, val name: String, val at: Long)

/**
 * Reminders for the things the Kanade bot hands out (daily, treasure, weekly, monthly) and for the
 * prayer times. Every reminder is a local alarm, so it still rings if Termux is closed. A background
 * sync every few hours asks the bot for the new times once a reward was claimed.
 */
object KanadeReminders {
    const val ACTION_NOTIFY = "com.kanade.reminder.NOTIFY"
    const val ACTION_SYNC = "com.kanade.reminder.SYNC"

    val REWARD_IDS = listOf("daily", "treasure", "weekly", "monthly")

    private const val PREF = "kanade_reminders"
    private const val CHANNEL = "kanade_reminders"
    private const val RC_SYNC = 940001
    private const val RC_REWARD = 940010 // + the reward's index
    private const val RC_PRAYER = 940100 // + the prayer's position
    private const val SYNC_EVERY_MS = 3 * 3_600_000L

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ── Which reminders the user turned on ──
    fun enabled(c: Context): Set<String> = (sp(c).getStringSet("on", emptySet()) ?: emptySet()).toSet()

    fun setEnabled(c: Context, id: String, on: Boolean) {
        val s = enabled(c).toMutableSet()
        if (on) s.add(id) else s.remove(id)
        sp(c).edit().putStringSet("on", s).apply()
    }

    // ── Alarms ──
    private fun alarms(c: Context): AlarmManager = c.getSystemService(AlarmManager::class.java)

    private fun notifyIntent(c: Context, rc: Int, id: String, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, rc,
            Intent(c, KanadeReminderReceiver::class.java).setAction(ACTION_NOTIFY)
                .putExtra("id", id).putExtra("name", name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun syncIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, RC_SYNC,
            Intent(c, KanadeReminderReceiver::class.java).setAction(ACTION_SYNC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun setAt(c: Context, at: Long, pi: PendingIntent) {
        val am = alarms(c)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun rcSet(c: Context, key: String): Set<Int> =
        (sp(c).getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()

    private fun saveRcSet(c: Context, key: String, s: Set<Int>) {
        sp(c).edit().putStringSet(key, s.map { it.toString() }.toSet()).apply()
    }

    /** One alarm per enabled reward, at the moment it is ready. Rewards that are ready already get none. */
    fun scheduleRewards(c: Context, list: List<RewardState>) {
        val am = alarms(c)
        rcSet(c, "rcs_r").forEach { am.cancel(notifyIntent(c, it, "", "")) }
        val on = enabled(c)
        val now = System.currentTimeMillis()
        val used = mutableSetOf<Int>()
        list.forEach { r ->
            val idx = REWARD_IDS.indexOf(r.id)
            if (idx >= 0 && r.id in on && r.readyAt > now) {
                val rc = RC_REWARD + idx
                setAt(c, r.readyAt, notifyIntent(c, rc, r.id, ""))
                used.add(rc)
            }
        }
        saveRcSet(c, "rcs_r", used)
    }

    /** One alarm per upcoming prayer (today and tomorrow). */
    fun schedulePrayers(c: Context, list: List<PrayerTime>) {
        val am = alarms(c)
        rcSet(c, "rcs_p").forEach { am.cancel(notifyIntent(c, it, "", "")) }
        val used = mutableSetOf<Int>()
        if ("prayer" in enabled(c)) {
            val now = System.currentTimeMillis()
            list.filter { it.at > now }.take(10).forEachIndexed { i, p ->
                val rc = RC_PRAYER + i
                setAt(c, p.at, notifyIntent(c, rc, "prayer", p.name))
                used.add(rc)
            }
        }
        saveRcSet(c, "rcs_p", used)
    }

    /** Turns one reminder off right away, even if the bot cannot be reached. */
    fun cancelItem(c: Context, id: String) {
        val am = alarms(c)
        if (id == "prayer") {
            rcSet(c, "rcs_p").forEach { am.cancel(notifyIntent(c, it, "", "")) }
            saveRcSet(c, "rcs_p", emptySet())
        } else {
            val idx = REWARD_IDS.indexOf(id)
            if (idx >= 0) {
                am.cancel(notifyIntent(c, RC_REWARD + idx, "", ""))
                saveRcSet(c, "rcs_r", rcSet(c, "rcs_r") - (RC_REWARD + idx))
            }
        }
    }

    fun cancelAll(c: Context) {
        val am = alarms(c)
        am.cancel(syncIntent(c))
        (rcSet(c, "rcs_r") + rcSet(c, "rcs_p")).forEach { am.cancel(notifyIntent(c, it, "", "")) }
        saveRcSet(c, "rcs_r", emptySet())
        saveRcSet(c, "rcs_p", emptySet())
    }

    /** Signing out: no alarms, and nothing stays switched on. */
    fun reset(c: Context) {
        cancelAll(c)
        sp(c).edit().remove("on").apply()
    }

    fun scheduleSync(c: Context, delayMs: Long = SYNC_EVERY_MS) {
        if (enabled(c).isEmpty()) {
            alarms(c).cancel(syncIntent(c))
            return
        }
        alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, syncIntent(c))
    }

    /** After a reboot the alarms are gone: sync again a minute later. */
    fun onBoot(c: Context) {
        if (enabled(c).isNotEmpty()) scheduleSync(c, 60_000L)
    }

    // ── Reading what the bot says ──
    fun parseRewards(b: JSONObject?): List<RewardState> {
        val a = b?.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            RewardState(o.optString("id"), o.optLong("readyAt"))
        }
    }

    fun parsePrayers(b: JSONObject?): List<PrayerTime> {
        val a = b?.optJSONArray("times") ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            PrayerTime(o.optString("key"), o.optString("name"), o.optLong("at"))
        }
    }

    /** Asks the bot for the current times and sets the alarms. If the bot is off, what was set stays. */
    suspend fun syncNow(c: Context) {
        val s = AccountStore.load(c)
        if (s == null || enabled(c).isEmpty()) {
            if (s == null) reset(c) else cancelAll(c)
            return
        }
        val t = KanadeApi.call("/me/timers", token = s.token)
        if (t.status == 401) {
            AccountStore.clear(c)
            reset(c)
            return
        }
        if (t.ok) scheduleRewards(c, parseRewards(t.body))
        val p = KanadeApi.call("/me/prayer", token = s.token)
        if (p.ok) schedulePrayers(c, parsePrayers(p.body))
    }

    // ── The notification ──
    fun notify(c: Context, id: String, name: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.rem_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val text = when (id) {
            "daily" -> c.getString(R.string.rem_daily_ready)
            "treasure" -> c.getString(R.string.rem_treasure_ready)
            "weekly" -> c.getString(R.string.rem_weekly_ready)
            "monthly" -> c.getString(R.string.rem_monthly_ready)
            else -> c.getString(R.string.rem_prayer_now, name)
        }
        val open = PendingIntent.getActivity(
            c, 940900,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_PAGE, PAGE_ACCOUNT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(c.getString(R.string.rem_title))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(3000 + ((id + name).hashCode() and 0xFFFF), n)
    }
}

class KanadeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            KanadeReminders.ACTION_NOTIFY -> {
                try {
                    KanadeReminders.notify(
                        context,
                        intent.getStringExtra("id").orEmpty(),
                        intent.getStringExtra("name").orEmpty(),
                    )
                } catch (e: Throwable) {
                    Diag.error(context, "KanadeReminderReceiver notify", e)
                }
            }
            KanadeReminders.ACTION_SYNC -> {
                val pending = goAsync()
                Thread {
                    try {
                        runBlocking { KanadeReminders.syncNow(context) }
                    } catch (e: Throwable) {
                        Diag.error(context, "KanadeReminderReceiver sync", e)
                    } finally {
                        // The chain goes on while there is something to remind about.
                        KanadeReminders.scheduleSync(context)
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
