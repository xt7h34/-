package com.rigen.volumeui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home screen widget: the next alarm. */
class NextAlarmWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, ClockWidgets.nextAlarmViews(context)) }
    }
}

/** Home screen widget: last night's sleep against the goal. */
class SleepWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, ClockWidgets.sleepViews(context)) }
    }
}

/** Draws what we know right away (the caller did), then asks the bot in the background and draws again. */
private fun syncWidget(
    context: Context,
    manager: AppWidgetManager,
    ids: IntArray,
    pending: android.content.BroadcastReceiver.PendingResult,
    path: String,
    save: (ApiResult) -> Unit,
    draw: () -> RemoteViews,
) {
    Thread {
        try {
            val session = AccountStore.load(context)
            if (session != null) {
                val r = runBlocking { KanadeApi.call(path, token = session.token) }
                if (r.ok) {
                    save(r)
                } else if (r.status == 401) {
                    AccountStore.clear(context)
                    KanadeReminders.reset(context)
                }
                ids.forEach { manager.updateAppWidget(it, draw()) }
            }
        } catch (e: Throwable) {
            Diag.error(context, "widget sync $path", e)
        } finally {
            pending.finish()
        }
    }.start()
}

/** Home screen widget: the next prayer. */
class PrayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, ClockWidgets.prayerViews(context)) }
        syncWidget(
            context, appWidgetManager, appWidgetIds, goAsync(), "/me/prayer",
            save = { r ->
                val list = KanadeReminders.parsePrayers(r.body)
                KanadeReminders.cachePrayers(context, KanadeReminders.prayerState(r.body, list), list)
            },
            draw = { ClockWidgets.prayerViews(context) },
        )
    }
}

/** Home screen widget: today's habits, each with a check button. */
class HabitsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, ClockWidgets.habitsViews(context)) }
        syncWidget(
            context, appWidgetManager, appWidgetIds, goAsync(), "/me/habits",
            save = { r -> KanadeReminders.cacheHabits(context, KanadeReminders.parseHabits(r.body)) },
            draw = { ClockWidgets.habitsViews(context) },
        )
    }
}

/** Home screen widget: the Kanade rewards (daily, treasure, weekly, monthly) and when each is ready. */
class RewardsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Draw what we know right away, then ask the bot for fresh times in the background.
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, ClockWidgets.rewardsViews(context)) }
        val pending = goAsync()
        Thread {
            try {
                val session = AccountStore.load(context)
                if (session != null) {
                    val r = runBlocking { KanadeApi.call("/me/timers", token = session.token) }
                    if (r.ok) {
                        KanadeReminders.cacheRewards(context, KanadeReminders.parseRewards(r.body))
                    } else if (r.status == 401) {
                        AccountStore.clear(context)
                        KanadeReminders.reset(context)
                    }
                    appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, ClockWidgets.rewardsViews(context)) }
                }
            } catch (e: Throwable) {
                Diag.error(context, "RewardsWidget", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

object ClockWidgets {

    /** Redraws every widget that is on a home screen. Called whenever alarms or sleep data change. */
    fun updateAll(c: Context) {
        try {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, NextAlarmWidget::class.java))
                .forEach { m.updateAppWidget(it, nextAlarmViews(c)) }
            m.getAppWidgetIds(ComponentName(c, SleepWidget::class.java))
                .forEach { m.updateAppWidget(it, sleepViews(c)) }
            m.getAppWidgetIds(ComponentName(c, RewardsWidget::class.java))
                .forEach { m.updateAppWidget(it, rewardsViews(c)) }
            m.getAppWidgetIds(ComponentName(c, PrayerWidget::class.java))
                .forEach { m.updateAppWidget(it, prayerViews(c)) }
            m.getAppWidgetIds(ComponentName(c, HabitsWidget::class.java))
                .forEach { m.updateAppWidget(it, habitsViews(c)) }
        } catch (e: Throwable) {
            Diag.error(c, "widgets", e)
        }
    }

    private fun openPage(c: Context, page: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            c, requestCode,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The enabled alarm that rings first, with the time it rings. */
    private fun nextAlarm(c: Context, now: Long): Pair<AlarmItem, Long>? =
        ClockStore.alarms(c).filter { it.enabled }.map { it to nextTrigger(it, now) }.minByOrNull { it.second }

    private fun dayLabel(c: Context, at: Long, now: Long): String {
        val key = dayKey(at)
        return when {
            key == dayKey(now) -> c.getString(R.string.widget_today)
            key == dayKey(now + 24L * 3_600_000L) -> c.getString(R.string.widget_tomorrow)
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(at))
        }
    }

    private fun hm(c: Context, minutes: Int): String =
        c.getString(R.string.widget_hm, minutes / 60, minutes % 60)

    fun nextAlarmViews(c: Context): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_next_alarm)
        val now = System.currentTimeMillis()
        val next = nextAlarm(c, now)
        if (next == null) {
            rv.setTextViewText(R.id.w_time, c.getString(R.string.widget_no_alarm))
            rv.setTextViewText(R.id.w_sub, "")
        } else {
            val a = next.first
            val at = next.second
            rv.setTextViewText(R.id.w_time, DateFormat.getTimeFormat(c).format(Date(at)))
            rv.setTextViewText(
                R.id.w_sub,
                listOf(dayLabel(c, at, now), a.label).filter { it.isNotBlank() }.joinToString(" \u2022 "),
            )
        }
        rv.setOnClickPendingIntent(R.id.w_root, openPage(c, PAGE_ALARMS, 31))
        return rv
    }

    fun sleepViews(c: Context): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_sleep)
        val now = System.currentTimeMillis()
        val goal = ClockStore.sleepGoal(c).coerceAtLeast(1)
        val entries = ClockStore.sleepEntries(c)
        val last = entries.lastOrNull()
        if (last == null) {
            rv.setTextViewText(R.id.w_title, c.getString(R.string.widget_sleep_label))
            rv.setTextViewText(R.id.w_hours, "\u2014")
            rv.setProgressBar(R.id.w_progress, 100, 0, false)
            rv.setTextViewText(R.id.w_detail, c.getString(R.string.widget_no_sleep))
        } else {
            val title = if (dayKey(last.wake) == dayKey(now)) R.string.widget_sleep_last_night else R.string.widget_sleep_last_logged
            rv.setTextViewText(R.id.w_title, c.getString(title))
            rv.setTextViewText(R.id.w_hours, hm(c, last.minutes))
            rv.setProgressBar(R.id.w_progress, 100, (last.minutes * 100 / goal).coerceIn(0, 100), false)
            val avg = entries.takeLast(7).map { it.minutes }.average().toInt()
            rv.setTextViewText(R.id.w_detail, c.getString(R.string.widget_sleep_detail, hm(c, goal), hm(c, avg)))
        }
        // When to go to bed to reach the goal before the next alarm.
        val next = nextAlarm(c, now)
        val bed = if (next != null) next.second - goal * 60_000L else 0L
        val bedText = if (next != null && bed > now && next.second - now < 24L * 3_600_000L) {
            c.getString(R.string.widget_bedtime, DateFormat.getTimeFormat(c).format(Date(bed)))
        } else {
            ""
        }
        rv.setTextViewText(R.id.w_bed, bedText)
        rv.setOnClickPendingIntent(R.id.w_root, openPage(c, PAGE_SLEEP, 32))
        return rv
    }

    private fun rewardName(id: String): Int = when (id) {
        "daily" -> R.string.rem_daily
        "treasure" -> R.string.rem_treasure
        "weekly" -> R.string.rem_weekly
        else -> R.string.rem_monthly
    }

    /** Drawn from the last times the bot gave. A time of day is shown, not a countdown, so it never goes stale. */
    fun rewardsViews(c: Context): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_rewards)
        val rows = listOf(R.id.w_r1, R.id.w_r2, R.id.w_r3, R.id.w_r4)
        val now = System.currentTimeMillis()
        rv.setTextViewText(R.id.w_title, c.getString(R.string.widget_rewards_title))
        if (AccountStore.load(c) == null) {
            rv.setTextViewText(R.id.w_count, c.getString(R.string.widget_rewards_signin))
            rows.forEach { rv.setTextViewText(it, "") }
        } else {
            val list = KanadeReminders.cachedRewards(c)
            val ready = list.count { it.readyAt <= now }
            rv.setTextViewText(
                R.id.w_count,
                if (list.isEmpty()) "\u2014" else c.getString(R.string.widget_rewards_ready, ready),
            )
            KanadeReminders.REWARD_IDS.forEachIndexed { i, id ->
                val r = list.firstOrNull { it.id == id }
                val status = when {
                    r == null -> "\u2014"
                    r.readyAt <= now -> c.getString(R.string.rem_ready)
                    else -> dayLabel(c, r.readyAt, now) + " " + DateFormat.getTimeFormat(c).format(Date(r.readyAt))
                }
                rv.setTextViewText(rows[i], c.getString(rewardName(id)) + ": " + status)
            }
        }
        rv.setOnClickPendingIntent(R.id.w_root, openPage(c, PAGE_ACCOUNT, 33))
        return rv
    }

    /** The next prayer, from the last times the bot gave. It redraws itself when that prayer passes. */
    fun prayerViews(c: Context): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_prayer)
        val now = System.currentTimeMillis()
        rv.setTextViewText(R.id.w_title, c.getString(R.string.widget_prayer_title))
        rv.setTextViewText(R.id.w_time, "")
        rv.setTextViewText(R.id.w_next, "")
        if (AccountStore.load(c) == null) {
            rv.setTextViewText(R.id.w_name, c.getString(R.string.widget_rewards_signin))
        } else {
            val cached = KanadeReminders.cachedPrayers(c)
            val future = cached.second.filter { it.at > now }
            if (cached.first == "none") {
                rv.setTextViewText(R.id.w_name, c.getString(R.string.widget_prayer_setup))
            } else if (future.isEmpty()) {
                rv.setTextViewText(R.id.w_name, "\u2014")
            } else {
                val next = future[0]
                val clock = DateFormat.getTimeFormat(c).format(Date(next.at))
                rv.setTextViewText(R.id.w_name, next.name)
                rv.setTextViewText(
                    R.id.w_time,
                    if (dayKey(next.at) == dayKey(now)) clock else dayLabel(c, next.at, now) + " " + clock,
                )
                if (future.size > 1) {
                    rv.setTextViewText(
                        R.id.w_next,
                        c.getString(R.string.widget_prayer_then, future[1].name, DateFormat.getTimeFormat(c).format(Date(future[1].at))),
                    )
                }
                KanadeReminders.scheduleWidgetRefresh(c, next.at + 30_000L)
            }
        }
        rv.setOnClickPendingIntent(R.id.w_root, openPage(c, PAGE_ACCOUNT, 34))
        return rv
    }

    private fun habitIntent(c: Context, requestCode: Int, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, requestCode,
            Intent(c, KanadeReminderReceiver::class.java).setAction(KanadeReminders.ACTION_HABIT).putExtra("name", name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Up to three habits that are due today (not done first), each with a check button. */
    fun habitsViews(c: Context): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_habits)
        val rows = listOf(
            Triple(R.id.w_h1, R.id.w_h1_text, R.id.w_h1_btn),
            Triple(R.id.w_h2, R.id.w_h2_text, R.id.w_h2_btn),
            Triple(R.id.w_h3, R.id.w_h3_text, R.id.w_h3_btn),
        )
        val signedIn = AccountStore.load(c) != null
        val all = if (signedIn) KanadeReminders.cachedHabits(c) else emptyList()
        val due = all.filter { it.due && !it.frozen }.sortedBy { it.done }
        val shown = due.take(3)
        rv.setTextViewText(
            R.id.w_title,
            when {
                !signedIn -> c.getString(R.string.widget_rewards_signin)
                due.isEmpty() -> c.getString(R.string.widget_habits_plain)
                else -> c.getString(R.string.widget_habits_title, due.count { it.done }, due.size)
            },
        )
        rows.forEachIndexed { i, ids ->
            val h = shown.getOrNull(i)
            if (h == null) {
                rv.setViewVisibility(ids.first, View.GONE)
            } else {
                rv.setViewVisibility(ids.first, View.VISIBLE)
                val streak = if (h.streak > 0) "  \uD83D\uDD25" + h.streak else ""
                rv.setTextViewText(ids.second, (if (h.done) "\u2713 " else "") + h.name + streak)
                if (h.done) {
                    rv.setViewVisibility(ids.third, View.GONE)
                } else {
                    rv.setViewVisibility(ids.third, View.VISIBLE)
                    rv.setOnClickPendingIntent(ids.third, habitIntent(c, 950000 + i, h.name))
                }
            }
        }
        val empty = when {
            !signedIn -> ""
            all.isEmpty() -> c.getString(R.string.widget_habits_none)
            due.isEmpty() -> c.getString(R.string.widget_habits_nodue)
            else -> ""
        }
        rv.setTextViewText(R.id.w_empty, empty)
        rv.setViewVisibility(R.id.w_empty, if (empty.isEmpty()) View.GONE else View.VISIBLE)
        rv.setOnClickPendingIntent(R.id.w_root, openPage(c, PAGE_ACCOUNT, 35))
        return rv
    }
}
