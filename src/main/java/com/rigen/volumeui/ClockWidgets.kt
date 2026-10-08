package com.rigen.volumeui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
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

object ClockWidgets {

    /** Redraws every widget that is on a home screen. Called whenever alarms or sleep data change. */
    fun updateAll(c: Context) {
        try {
            val m = AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c, NextAlarmWidget::class.java))
                .forEach { m.updateAppWidget(it, nextAlarmViews(c)) }
            m.getAppWidgetIds(ComponentName(c, SleepWidget::class.java))
                .forEach { m.updateAppWidget(it, sleepViews(c)) }
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
}
