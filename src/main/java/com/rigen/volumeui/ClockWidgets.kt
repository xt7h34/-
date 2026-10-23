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
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetDesigns.alarm(context, it)) }
    }
}

/** Home screen widget: last night's sleep against the goal. */
class SleepWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetDesigns.sleep(context, it)) }
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
    draw: (Int) -> RemoteViews,
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
                ids.forEach { manager.updateAppWidget(it, draw(it)) }
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
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetDesigns.prayer(context, it)) }
        syncWidget(
            context, appWidgetManager, appWidgetIds, goAsync(), "/me/prayer",
            save = { r ->
                val list = KanadeReminders.parsePrayers(r.body)
                KanadeReminders.cachePrayers(context, KanadeReminders.prayerState(r.body, list), list)
            },
            draw = { WidgetDesigns.prayer(context, it) },
        )
    }
}

/** Home screen widget: today's habits, each with a check button. */
class HabitsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetDesigns.habits(context, it)) }
        syncWidget(
            context, appWidgetManager, appWidgetIds, goAsync(), "/me/habits",
            save = { r -> KanadeReminders.cacheHabits(context, KanadeReminders.parseHabits(r.body)) },
            draw = { WidgetDesigns.habits(context, it) },
        )
    }
}

/** Home screen widget: the Kanade rewards (daily, treasure, weekly, monthly) and when each is ready. */
class RewardsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Draw what we know right away, then ask the bot for fresh times in the background.
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetDesigns.rewards(context, it)) }
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
                    appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetDesigns.rewards(context, it)) }
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
                .forEach { m.updateAppWidget(it, WidgetDesigns.alarm(c, it)) }
            m.getAppWidgetIds(ComponentName(c, SleepWidget::class.java))
                .forEach { m.updateAppWidget(it, WidgetDesigns.sleep(c, it)) }
            m.getAppWidgetIds(ComponentName(c, RewardsWidget::class.java))
                .forEach { m.updateAppWidget(it, WidgetDesigns.rewards(c, it)) }
            m.getAppWidgetIds(ComponentName(c, PrayerWidget::class.java))
                .forEach { m.updateAppWidget(it, WidgetDesigns.prayer(c, it)) }
            m.getAppWidgetIds(ComponentName(c, HabitsWidget::class.java))
                .forEach { m.updateAppWidget(it, WidgetDesigns.habits(c, it)) }
        } catch (e: Throwable) {
            Diag.error(c, "widgets", e)
        }
    }

    internal fun openPage(c: Context, page: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            c, requestCode,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun dayLabel(c: Context, at: Long, now: Long): String {
        val key = dayKey(at)
        return when {
            key == dayKey(now) -> c.getString(R.string.widget_today)
            key == dayKey(now + 24L * 3_600_000L) -> c.getString(R.string.widget_tomorrow)
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(at))
        }
    }

    internal fun hm(c: Context, minutes: Int): String =
        c.getString(R.string.widget_hm, minutes / 60, minutes % 60)

    internal fun rewardName(id: String): Int = when (id) {
        "daily" -> R.string.rem_daily
        "treasure" -> R.string.rem_treasure
        "weekly" -> R.string.rem_weekly
        else -> R.string.rem_monthly
    }

    internal fun habitIntent(c: Context, requestCode: Int, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, requestCode,
            Intent(c, KanadeReminderReceiver::class.java).setAction(KanadeReminders.ACTION_HABIT).putExtra("name", name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
