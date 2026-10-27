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
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class RewardState(val id: String, val readyAt: Long)

class PrayerTime(val key: String, val name: String, val at: Long)

class HabitState(val name: String, val streak: Int, val done: Boolean, val due: Boolean, val frozen: Boolean)

/**
 * Reminders for the things the Kanade bot hands out (daily, treasure, weekly, monthly) and for the
 * prayer times. Every reminder is a local alarm, so it still rings if Termux is closed. A background
 * sync every few hours asks the bot for the new times once a reward was claimed.
 */
object KanadeReminders {
    const val ACTION_NOTIFY = "com.kanade.reminder.NOTIFY"
    const val ACTION_SYNC = "com.kanade.reminder.SYNC"
    const val ACTION_HABIT = "com.kanade.reminder.HABIT"
    const val ACTION_REFRESH = "com.kanade.reminder.REFRESH"

    val REWARD_IDS = listOf("daily", "treasure", "weekly", "monthly")

    private const val PREF = "kanade_reminders"
    private const val CHANNEL = "kanade_reminders"
    private const val RC_SYNC = 940001
    private const val RC_REFRESH = 940002
    private const val RC_REWARD = 940010 // + the reward's index
    private const val RC_PRAYER = 940100 // + the prayer's position
    private const val RC_PRAYER_LEAD = 940300 // + the prayer's position, for the early alert
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
    internal fun alarms(c: Context): AlarmManager = c.getSystemService(AlarmManager::class.java)

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

    internal fun setAt(c: Context, at: Long, pi: PendingIntent) {
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
        rcSet(c, "rcs_pl").forEach { am.cancel(notifyIntent(c, it, "", "")) }
        val used = mutableSetOf<Int>()
        val usedLead = mutableSetOf<Int>()
        if ("prayer" in enabled(c)) {
            val now = System.currentTimeMillis()
            val lead = PrayerAlerts.leadMinutes(c)
            list.filter { it.at > now }.take(10).forEachIndexed { i, p ->
                val rc = RC_PRAYER + i
                setAt(c, p.at, notifyIntent(c, rc, "prayer", p.name))
                used.add(rc)
                // The early alert, when there is still time for it.
                if (lead > 0 && p.at - lead * 60_000L > now) {
                    val rcl = RC_PRAYER_LEAD + i
                    setAt(c, p.at - lead * 60_000L, notifyIntent(c, rcl, "prayer_soon", p.name))
                    usedLead.add(rcl)
                }
            }
        }
        saveRcSet(c, "rcs_p", used)
        saveRcSet(c, "rcs_pl", usedLead)
    }

    /** Turns one reminder off right away, even if the bot cannot be reached. */
    fun cancelItem(c: Context, id: String) {
        val am = alarms(c)
        if (id == "prayer") {
            rcSet(c, "rcs_p").forEach { am.cancel(notifyIntent(c, it, "", "")) }
            rcSet(c, "rcs_pl").forEach { am.cancel(notifyIntent(c, it, "", "")) }
            saveRcSet(c, "rcs_p", emptySet())
            saveRcSet(c, "rcs_pl", emptySet())
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
        (rcSet(c, "rcs_r") + rcSet(c, "rcs_p") + rcSet(c, "rcs_pl")).forEach { am.cancel(notifyIntent(c, it, "", "")) }
        saveRcSet(c, "rcs_r", emptySet())
        saveRcSet(c, "rcs_p", emptySet())
        saveRcSet(c, "rcs_pl", emptySet())
    }

    /** Signing out: no alarms, nothing stays switched on, and the widget forgets the account. */
    fun reset(c: Context) {
        cancelAll(c)
        alarms(c).cancel(refreshIntent(c))
        sp(c).edit().remove("on").remove("rw").remove("pr").remove("hb").apply()
        MyReminders.reset(c)
        ClockWidgets.updateAll(c)
    }

    // ── The last reward times the bot gave. The widget draws from this, so it works offline. ──
    fun cacheRewards(c: Context, list: List<RewardState>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("at", it.readyAt)) }
        sp(c).edit().putString("rw", arr.toString()).apply()
    }

    fun cachedRewards(c: Context): List<RewardState> = try {
        val a = JSONArray(sp(c).getString("rw", "[]"))
        (0 until a.length()).map { RewardState(a.getJSONObject(it).getString("id"), a.getJSONObject(it).getLong("at")) }
    } catch (e: Exception) {
        emptyList()
    }

    fun scheduleSync(c: Context, delayMs: Long = SYNC_EVERY_MS) {
        if (enabled(c).isEmpty() && !MyReminders.any(c)) {
            alarms(c).cancel(syncIntent(c))
            return
        }
        alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, syncIntent(c))
    }

    /** After a reboot the alarms are gone: sync again a minute later. */
    fun onBoot(c: Context) {
        MyReminders.reschedule(c)
        if (enabled(c).isNotEmpty() || MyReminders.any(c)) scheduleSync(c, 60_000L)
    }

    // ── Prayer times and habits as the bot last said. The widgets draw from these, so they work offline. ──
    fun prayerState(body: JSONObject?, list: List<PrayerTime>): String = when {
        body?.optBoolean("configured") != true -> "none"
        list.isEmpty() -> "off"
        else -> "ok"
    }

    fun cachePrayers(c: Context, state: String, list: List<PrayerTime>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("k", it.key).put("n", it.name).put("a", it.at)) }
        sp(c).edit().putString("pr", JSONObject().put("s", state).put("t", arr).toString()).apply()
    }

    fun cachedPrayers(c: Context): Pair<String, List<PrayerTime>> {
        return try {
            val raw = sp(c).getString("pr", null) ?: return Pair("", emptyList())
            val o = JSONObject(raw)
            val a = o.getJSONArray("t")
            Pair(
                o.optString("s"),
                (0 until a.length()).map {
                    val x = a.getJSONObject(it)
                    PrayerTime(x.optString("k"), x.optString("n"), x.optLong("a"))
                },
            )
        } catch (e: Exception) {
            Pair("", emptyList())
        }
    }

    fun parseHabits(b: JSONObject?): List<HabitState> {
        val a = b?.optJSONArray("habits") ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            HabitState(
                o.optString("name"), o.optInt("streak"), o.optBoolean("done"),
                o.optBoolean("due"), o.optBoolean("frozen"),
            )
        }
    }

    fun cacheHabits(c: Context, list: List<HabitState>) {
        HabitLog.record(c, list)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("n", it.name).put("s", it.streak).put("d", it.done)
                    .put("u", it.due).put("f", it.frozen),
            )
        }
        sp(c).edit().putString("hb", arr.toString()).apply()
    }

    fun cachedHabits(c: Context): List<HabitState> = try {
        val a = JSONArray(sp(c).getString("hb", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            HabitState(o.getString("n"), o.optInt("s"), o.optBoolean("d"), o.optBoolean("u"), o.optBoolean("f"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun refreshIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, RC_REFRESH,
            Intent(c, KanadeReminderReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Redraws the widgets at [at] (a prayer time passing), even if the app is closed. */
    fun scheduleWidgetRefresh(c: Context, at: Long) {
        alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, refreshIntent(c))
    }

    private fun toast(c: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(c.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    /** The check button of the habits widget: checks the habit in the bot, then redraws. */
    suspend fun checkHabit(c: Context, name: String) {
        val s = AccountStore.load(c) ?: return
        val r = KanadeApi.call("/me/habits/check", JSONObject().put("name", name), s.token)
        if (r.status == 401) {
            AccountStore.clear(c)
            reset(c)
            return
        }
        if (r.body?.has("habits") == true) cacheHabits(c, parseHabits(r.body))
        ClockWidgets.updateAll(c)
        toast(
            c,
            if (r.ok) c.getString(R.string.habit_checked, r.body?.optInt("streak") ?: 0) else r.message(c),
        )
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
        if (s == null) {
            reset(c)
            return
        }
        // The reminders made with the bot's .ذكرني: they come first, because they matter even when nothing else is on.
        val mine = MyReminders.sync(c, s.token)
        if (mine.status == 401) {
            AccountStore.clear(c)
            reset(c)
            return
        }
        if (enabled(c).isEmpty()) {
            if (!MyReminders.any(c)) cancelAll(c)
            return
        }
        val t = KanadeApi.call("/me/timers", token = s.token)
        if (t.status == 401) {
            AccountStore.clear(c)
            reset(c)
            return
        }
        if (t.ok) {
            val list = parseRewards(t.body)
            cacheRewards(c, list)
            scheduleRewards(c, list)
            ClockWidgets.updateAll(c)
        }
        val p = KanadeApi.call("/me/prayer", token = s.token)
        if (p.ok) {
            val list = parsePrayers(p.body)
            schedulePrayers(c, list)
            cachePrayers(c, prayerState(p.body, list), list)
            ClockWidgets.updateAll(c)
        }
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
            "prayer_soon" -> c.getString(R.string.rem_prayer_soon, name, PrayerAlerts.leadMinutes(c))
            "myrem" -> name
            else -> c.getString(R.string.rem_prayer_now, name)
        }
        // Prayer alerts have their own channel, so they can have the sound the person chose.
        val channel = if (id == "prayer" || id == "prayer_soon") PrayerAlerts.channel(c, nm) else CHANNEL
        val open = PendingIntent.getActivity(
            c, 940900,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_PAGE, PAGE_ACCOUNT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(c.getString(R.string.rem_title))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(3000 + ((id + name).hashCode() and 0xFFFF), n)
        ClockWidgets.updateAll(c) // the widget shows "ready" now
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
            MyReminders.ACTION -> {
                try {
                    MyReminders.onFire(context, intent)
                } catch (e: Throwable) {
                    Diag.error(context, "KanadeReminderReceiver myrem", e)
                }
            }
            KanadeReminders.ACTION_REFRESH -> ClockWidgets.updateAll(context)
            KanadeReminders.ACTION_HABIT -> {
                val name = intent.getStringExtra("name").orEmpty()
                val pending = goAsync()
                Thread {
                    try {
                        runBlocking { KanadeReminders.checkHabit(context, name) }
                    } catch (e: Throwable) {
                        Diag.error(context, "KanadeReminderReceiver habit", e)
                    } finally {
                        pending.finish()
                    }
                }.start()
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
