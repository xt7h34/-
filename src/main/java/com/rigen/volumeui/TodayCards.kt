package com.rigen.volumeui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/** One line of "your day": a picture, what it is, what it says now, and the page it opens. */
private class DayRow(val emoji: String, val title: String, val detail: String, val page: String?, val warn: Boolean = false)

private fun hijriText(adjust: Int): String = try {
    val d = HijrahDate.now().plus(adjust.toLong(), ChronoUnit.DAYS)
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()).format(d)
} catch (_: Throwable) {
    ""
}

/**
 * The top of the home page: today's date (with the Hijri one), the next alarm, the next prayer, the
 * habits, the reels count, the top goal and the budget. Each line opens its page.
 */
@Composable
internal fun TodayCards(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("kanade_today", Context.MODE_PRIVATE) }
    var adjust by remember { mutableIntStateOf(prefs.getInt("hijri_adj", 0)) }
    val now = remember { System.currentTimeMillis() }

    val noAlarm = stringResource(R.string.today_alarm_none)
    val alarmTitle = stringResource(R.string.today_alarm)
    val prayerTitle = stringResource(R.string.today_prayer)
    val habitsTitle = stringResource(R.string.today_habits)
    val reelsTitle = stringResource(R.string.today_reels)
    val goalTitle = stringResource(R.string.today_goal)
    val budgetTitle = stringResource(R.string.today_budget)
    val azkarTitle = stringResource(if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 15) R.string.azkar_evening else R.string.azkar_morning)
    val notifTitle = stringResource(R.string.today_notif_off)
    val notifDetail = stringResource(R.string.today_notif_off_sum)

    val rows = remember {
        val out = ArrayList<DayRow>()
        // Notifications off means no reminder, alarm notice or Nero news can reach the person.
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm != null && !nm.areNotificationsEnabled()) out.add(DayRow("\uD83D\uDD15", notifTitle, notifDetail, null, warn = true))

        val next = ClockStore.alarms(ctx).filter { it.enabled }.map { it to nextTrigger(it, now) }.minByOrNull { it.second }
        out.add(
            DayRow(
                "\u23F0", alarmTitle,
                if (next == null) noAlarm else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(next.second)) + " \u00B7 " + ClockWidgets.dayLabel(ctx, next.second, now),
                PAGE_ALARMS,
            ),
        )

        if (AccountStore.load(ctx) != null) {
            val prayers = KanadeReminders.cachedPrayers(ctx).second.filter { it.at > now }
            prayers.firstOrNull()?.let {
                out.add(DayRow("\uD83D\uDD4C", prayerTitle, it.name + " \u00B7 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.at)), PAGE_DEEN))
            }
            val due = KanadeReminders.cachedHabits(ctx).filter { it.due && !it.frozen }
            if (due.isNotEmpty()) out.add(DayRow("\u2705", habitsTitle, "${due.count { it.done }}/${due.size}", PAGE_HABITS))
        }

        if (ReelsStore.enabled(ctx)) {
            out.add(DayRow("\uD83E\uDDE0", reelsTitle, "${ReelsStore.today(ctx).total} / ${ReelsStore.limit(ctx)}", PAGE_REELS))
        }

        val goal = GoalsStore.load(ctx).filter { !it.archived && it.completedAt == 0L }.firstOrNull()
        if (goal != null) {
            val p = GoalsEngine.plan(goal, now)
            out.add(DayRow(goal.emoji, goalTitle, goal.name + " \u00B7 " + (p.progress * 100).toInt() + "%", PAGE_GOALS))
        }

        val b = BudgetStore.load(ctx)
        if (b.monthly > 0L) {
            val spent = BudgetEngine.summary(b, BudgetEngine.month(now, 0)).spent
            val left = b.monthly - spent
            out.add(DayRow("\uD83D\uDCB3", budgetTitle, (if (left >= 0) "" else "-") + formatMoney(BudgetStore.magnitude(left), b.currency), PAGE_BUDGET))
        }

        out.add(DayRow("\uD83E\uDD32", azkarTitle, DeenStore.azkarProgress(ctx, if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 15) 1 else 0).let { "${it.first}/${it.second}" }, PAGE_AZKAR))
        out
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(stringResource(R.string.today_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    DateFormat.getDateInstance(DateFormat.FULL).format(Date(now)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                val h = hijriText(adjust)
                if (h.isNotEmpty()) {
                    // Tapping the Hijri date moves it a day either way, for places that see the moon a day apart.
                    Text(
                        h + if (adjust != 0) "  (" + (if (adjust > 0) "+" else "") + adjust + ")" else "",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable {
                            adjust = when (adjust) {
                                0 -> 1
                                1 -> -1
                                else -> 0
                            }
                            prefs.edit().putInt("hijri_adj", adjust).apply()
                        },
                    )
                }
            }
            for (r in rows) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (r.page != null) {
                            onOpen(r.page)
                        } else {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            r.detail,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (r.warn) ICON_ORANGE else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
