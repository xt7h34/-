package com.rigen.volumeui

import android.content.Context
import android.text.format.DateFormat
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.util.Date

/*
 * One function per widget. Each builds its three designs (small, normal, large) from the same data,
 * so what a widget says never depends on the size it was given, only how much of it fits.
 */
object WidgetDesigns {

    private fun RemoteViews.txt(id: Int, s: String) = setTextViewText(id, s)

    private fun RemoteViews.paint(color: Int, vararg ids: Int) {
        for (i in ids) setTextColor(i, color)
    }

    private fun clock(c: Context, at: Long): String = DateFormat.getTimeFormat(c).format(Date(at))

    fun render(c: Context, kind: WidgetKind, id: Int): RemoteViews = when (kind) {
        WidgetKind.ALARM -> alarm(c, id)
        WidgetKind.PRAYER -> prayer(c, id)
        WidgetKind.REWARDS -> rewards(c, id)
        WidgetKind.SLEEP -> sleep(c, id)
        WidgetKind.HABITS -> habits(c, id)
        WidgetKind.REELS -> reels(c, id)
        WidgetKind.GOALS -> goals(c, id)
    }

    // ───────────────────────── Next alarm ─────────────────────────

    fun alarm(c: Context, id: Int): RemoteViews {
        val now = System.currentTimeMillis()
        val list = ClockStore.alarms(c).filter { it.enabled }.map { it to nextTrigger(it, now) }.sortedBy { it.second }
        val open = ClockWidgets.openPage(c, PAGE_ALARMS, 31)

        fun sub(p: Pair<AlarmItem, Long>): String =
            listOf(ClockWidgets.dayLabel(c, p.second, now), p.first.label).filter { it.isNotBlank() }.joinToString(" \u2022 ")

        fun more(p: Pair<AlarmItem, Long>): String = clock(c, p.second) + "  \u2022  " + sub(p)

        return WidgetFrame.build(SizeF(120f, 60f), SizeF(230f, 120f)) { size ->
            val layout = when (size) {
                WSize.COMPACT -> R.layout.widget_next_alarm_s
                WSize.NORMAL -> R.layout.widget_next_alarm
                WSize.EXPANDED -> R.layout.widget_next_alarm_l
            }
            val (rv, pal) = WidgetStyle.begin(c, id, WidgetKind.ALARM, layout)
            val first = list.firstOrNull()
            rv.txt(R.id.w_time, if (first == null) c.getString(R.string.widget_no_alarm) else clock(c, first.second))
            rv.txt(R.id.w_sub, if (first == null) "" else sub(first))
            rv.paint(pal.primary, R.id.w_time)
            rv.paint(pal.secondary, R.id.w_sub)
            if (size != WSize.COMPACT) rv.paint(pal.accent, R.id.w_title)
            if (size == WSize.EXPANDED) {
                rv.paint(pal.secondary, R.id.w_more_title)
                rv.txt(R.id.w_a2, list.getOrNull(1)?.let { more(it) } ?: "")
                rv.txt(R.id.w_a3, list.getOrNull(2)?.let { more(it) } ?: "")
                rv.paint(pal.primary, R.id.w_a2, R.id.w_a3)
            }
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv
        }
    }

    // ───────────────────────── Next prayer ─────────────────────────

    fun prayer(c: Context, id: Int): RemoteViews {
        val now = System.currentTimeMillis()
        val signedIn = AccountStore.load(c) != null
        val cached = KanadeReminders.cachedPrayers(c)
        val future = cached.second.filter { it.at > now }
        val open = ClockWidgets.openPage(c, PAGE_ACCOUNT, 34)
        var name = ""
        var time = ""
        var then = ""
        when {
            !signedIn -> name = c.getString(R.string.widget_rewards_signin)
            cached.first == "none" -> name = c.getString(R.string.widget_prayer_setup)
            future.isEmpty() -> name = "\u2014"
            else -> {
                val n = future[0]
                name = n.name
                time = if (dayKey(n.at) == dayKey(now)) clock(c, n.at) else ClockWidgets.dayLabel(c, n.at, now) + " " + clock(c, n.at)
                if (future.size > 1) {
                    then = c.getString(R.string.widget_prayer_then, future[1].name, clock(c, future[1].at))
                }
                KanadeReminders.scheduleWidgetRefresh(c, n.at + 30_000L)
            }
        }
        val upcoming = if (signedIn && cached.first != "none") future.take(5) else emptyList()
        val names = listOf(R.id.w_p1_n, R.id.w_p2_n, R.id.w_p3_n, R.id.w_p4_n, R.id.w_p5_n)
        val times = listOf(R.id.w_p1_t, R.id.w_p2_t, R.id.w_p3_t, R.id.w_p4_t, R.id.w_p5_t)

        return WidgetFrame.build(SizeF(110f, 110f), SizeF(270f, 140f)) { size ->
            val layout = when (size) {
                WSize.COMPACT -> R.layout.widget_prayer_s
                WSize.NORMAL -> R.layout.widget_prayer
                WSize.EXPANDED -> R.layout.widget_prayer_l
            }
            val (rv, pal) = WidgetStyle.begin(c, id, WidgetKind.PRAYER, layout)
            rv.txt(R.id.w_name, name)
            rv.txt(R.id.w_time, time)
            rv.paint(pal.primary, R.id.w_name)
            rv.paint(pal.secondary, R.id.w_time)
            if (size != WSize.COMPACT) {
                rv.txt(R.id.w_title, c.getString(R.string.widget_prayer_title))
                rv.paint(pal.accent, R.id.w_title)
            }
            if (size == WSize.NORMAL) {
                rv.txt(R.id.w_next, then)
                rv.paint(pal.secondary, R.id.w_next)
            }
            if (size == WSize.EXPANDED) {
                for (i in 0 until 5) {
                    val p = upcoming.getOrNull(i)
                    rv.txt(names[i], p?.name ?: "")
                    rv.txt(times[i], if (p == null) "" else clock(c, p.at))
                    rv.paint(if (i == 0) pal.accent else pal.secondary, names[i])
                    rv.paint(if (i == 0) pal.accent else pal.primary, times[i])
                }
            }
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv
        }
    }

    // ───────────────────────── Rewards ─────────────────────────

    fun rewards(c: Context, id: Int): RemoteViews {
        val now = System.currentTimeMillis()
        val signedIn = AccountStore.load(c) != null
        val list = if (signedIn) KanadeReminders.cachedRewards(c) else emptyList()
        val ready = list.count { it.readyAt <= now }
        val open = ClockWidgets.openPage(c, PAGE_ACCOUNT, 33)
        val count = when {
            !signedIn -> c.getString(R.string.widget_rewards_signin)
            list.isEmpty() -> "\u2014"
            else -> c.getString(R.string.widget_rewards_ready, ready)
        }
        val emoji = mapOf("daily" to "\uD83C\uDF05", "treasure" to "\uD83D\uDC8E", "weekly" to "\uD83D\uDCC5", "monthly" to "\uD83C\uDFC6")
        val rewardNames = KanadeReminders.REWARD_IDS.map { c.getString(ClockWidgets.rewardName(it)) }
        val isReady = KanadeReminders.REWARD_IDS.map { rid -> list.firstOrNull { it.id == rid }?.let { it.readyAt <= now } ?: false }
        val status = KanadeReminders.REWARD_IDS.map { rid ->
            val r = list.firstOrNull { it.id == rid }
            when {
                !signedIn -> ""
                r == null -> "\u2014"
                r.readyAt <= now -> c.getString(R.string.rem_ready)
                else -> ClockWidgets.dayLabel(c, r.readyAt, now) + " " + clock(c, r.readyAt)
            }
        }
        val lines = listOf(R.id.w_r1, R.id.w_r2, R.id.w_r3, R.id.w_r4)
        val tiles = listOf(
            Triple(R.id.w_t1_e, R.id.w_t1_n, R.id.w_t1_s),
            Triple(R.id.w_t2_e, R.id.w_t2_n, R.id.w_t2_s),
            Triple(R.id.w_t3_e, R.id.w_t3_n, R.id.w_t3_s),
            Triple(R.id.w_t4_e, R.id.w_t4_n, R.id.w_t4_s),
        )

        return WidgetFrame.build(SizeF(180f, 130f), SizeF(220f, 200f)) { size ->
            val layout = when (size) {
                WSize.COMPACT -> R.layout.widget_rewards_s
                WSize.NORMAL -> R.layout.widget_rewards
                WSize.EXPANDED -> R.layout.widget_rewards_l
            }
            val (rv, pal) = WidgetStyle.begin(c, id, WidgetKind.REWARDS, layout)
            rv.txt(R.id.w_title, c.getString(R.string.widget_rewards_title))
            rv.txt(R.id.w_count, count)
            rv.paint(pal.primary, R.id.w_count)
            rv.paint(if (size == WSize.COMPACT) pal.secondary else pal.accent, R.id.w_title)
            if (size == WSize.NORMAL) {
                for (i in lines.indices) {
                    rv.txt(lines[i], if (status[i].isEmpty()) "" else "${rewardNames[i]}: ${status[i]}")
                    rv.paint(if (isReady[i]) pal.accent else pal.secondary, lines[i])
                }
            }
            if (size == WSize.EXPANDED) {
                KanadeReminders.REWARD_IDS.forEachIndexed { i, rid ->
                    rv.txt(tiles[i].first, emoji[rid] ?: "\uD83C\uDF81")
                    rv.txt(tiles[i].second, rewardNames[i])
                    rv.txt(tiles[i].third, status[i])
                    rv.paint(pal.primary, tiles[i].second)
                    rv.paint(if (isReady[i]) pal.accent else pal.secondary, tiles[i].third)
                }
            }
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv
        }
    }

    // ───────────────────────── Sleep ─────────────────────────

    fun sleep(c: Context, id: Int): RemoteViews {
        val now = System.currentTimeMillis()
        val goal = ClockStore.sleepGoal(c).coerceAtLeast(1)
        val entries = ClockStore.sleepEntries(c)
        val last = entries.lastOrNull()
        val open = ClockWidgets.openPage(c, PAGE_SLEEP, 32)
        val title = c.getString(
            when {
                last == null -> R.string.widget_sleep_label
                dayKey(last.wake) == dayKey(now) -> R.string.widget_sleep_last_night
                else -> R.string.widget_sleep_last_logged
            },
        )
        val hours = if (last == null) "\u2014" else ClockWidgets.hm(c, last.minutes)
        val progress = if (last == null) 0f else (last.minutes.toFloat() / goal).coerceIn(0f, 1f)
        val detail = if (last == null) {
            c.getString(R.string.widget_no_sleep)
        } else {
            val avg = entries.takeLast(7).map { it.minutes }.average().toInt()
            c.getString(R.string.widget_sleep_detail, ClockWidgets.hm(c, goal), ClockWidgets.hm(c, avg))
        }
        // When to go to bed to reach the goal before the next alarm.
        val next = ClockStore.alarms(c).filter { it.enabled }.map { nextTrigger(it, now) }.minOrNull()
        val bed = if (next != null) next - goal * 60_000L else 0L
        val bedText = if (next != null && bed > now && next - now < 24L * 3_600_000L) {
            c.getString(R.string.widget_bedtime, clock(c, bed))
        } else {
            ""
        }
        val week = entries.takeLast(7).map { it.minutes }.let { List(7 - it.size) { 0 } + it }

        return WidgetFrame.build(SizeF(180f, 130f), SizeF(270f, 130f)) { size ->
            val layout = when (size) {
                WSize.COMPACT -> R.layout.widget_sleep_s
                WSize.NORMAL -> R.layout.widget_sleep
                WSize.EXPANDED -> R.layout.widget_sleep_l
            }
            val (rv, pal) = WidgetStyle.begin(c, id, WidgetKind.SLEEP, layout)
            rv.txt(R.id.w_hours, hours)
            rv.paint(pal.primary, R.id.w_hours)
            if (size == WSize.COMPACT) {
                rv.txt(R.id.w_sub, title)
                rv.paint(pal.secondary, R.id.w_sub)
            } else {
                rv.txt(R.id.w_title, title)
                rv.txt(R.id.w_detail, detail)
                rv.txt(R.id.w_bed, bedText)
                rv.paint(pal.accent, R.id.w_title, R.id.w_bed)
                rv.paint(pal.secondary, R.id.w_detail)
            }
            if (size == WSize.NORMAL) {
                rv.setImageViewBitmap(R.id.w_bar, WidgetGfx.bar(400, 24, progress, pal.accent, pal.track))
            }
            if (size == WSize.EXPANDED) {
                rv.setImageViewBitmap(R.id.w_chart, WidgetGfx.weekBars(week, goal, 480, 240, pal.accent, pal.track))
                rv.paint(pal.secondary, R.id.w_chart_label)
            }
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv
        }
    }

    // ───────────────────────── Habits ─────────────────────────

    fun habits(c: Context, id: Int): RemoteViews {
        val signedIn = AccountStore.load(c) != null
        val all = if (signedIn) KanadeReminders.cachedHabits(c) else emptyList()
        val due = all.filter { it.due && !it.frozen }.sortedBy { it.done }
        val doneCount = due.count { it.done }
        val open = ClockWidgets.openPage(c, PAGE_ACCOUNT, 35)
        val title = when {
            !signedIn -> c.getString(R.string.widget_rewards_signin)
            due.isEmpty() -> c.getString(R.string.widget_habits_plain)
            else -> c.getString(R.string.widget_habits_title, doneCount, due.size)
        }
        val big = if (due.isEmpty()) "\u2014" else "$doneCount/${due.size}"
        val progress = if (due.isEmpty()) 0f else doneCount.toFloat() / due.size
        val empty = when {
            !signedIn -> ""
            all.isEmpty() -> c.getString(R.string.widget_habits_none)
            due.isEmpty() -> c.getString(R.string.widget_habits_nodue)
            else -> ""
        }
        val rows = listOf(
            Triple(R.id.w_h1, R.id.w_h1_text, R.id.w_h1_btn),
            Triple(R.id.w_h2, R.id.w_h2_text, R.id.w_h2_btn),
            Triple(R.id.w_h3, R.id.w_h3_text, R.id.w_h3_btn),
            Triple(R.id.w_h4, R.id.w_h4_text, R.id.w_h4_btn),
        )

        fun fillRows(rv: RemoteViews, pal: WidgetPal, count: Int) {
            val shown = due.take(count)
            for (i in 0 until count) {
                val ids = rows[i]
                val h = shown.getOrNull(i)
                if (h == null) {
                    rv.setViewVisibility(ids.first, View.GONE)
                } else {
                    rv.setViewVisibility(ids.first, View.VISIBLE)
                    val streak = if (h.streak > 0) "  \uD83D\uDD25" + h.streak else ""
                    rv.txt(ids.second, (if (h.done) "\u2713 " else "") + h.name + streak)
                    rv.paint(pal.primary, ids.second, ids.third)
                    if (h.done) {
                        rv.setViewVisibility(ids.third, View.GONE)
                    } else {
                        rv.setViewVisibility(ids.third, View.VISIBLE)
                        rv.setOnClickPendingIntent(ids.third, ClockWidgets.habitIntent(c, 950000 + i, h.name))
                    }
                }
            }
        }

        return WidgetFrame.build(SizeF(180f, 150f), SizeF(220f, 175f)) { size ->
            val layout = when (size) {
                WSize.COMPACT -> R.layout.widget_habits_s
                WSize.NORMAL -> R.layout.widget_habits
                WSize.EXPANDED -> R.layout.widget_habits_l
            }
            val (rv, pal) = WidgetStyle.begin(c, id, WidgetKind.HABITS, layout)
            rv.txt(R.id.w_title, title)
            rv.paint(if (size == WSize.COMPACT) pal.primary else pal.accent, R.id.w_title)
            rv.txt(R.id.w_empty, empty)
            rv.paint(if (size == WSize.COMPACT) pal.secondary else pal.primary, R.id.w_empty)
            rv.setViewVisibility(R.id.w_empty, if (empty.isEmpty()) View.GONE else View.VISIBLE)
            if (size != WSize.NORMAL) {
                rv.setImageViewBitmap(R.id.w_ring, WidgetGfx.ring(if (size == WSize.COMPACT) 120 else 220, progress, pal.accent, pal.track))
                rv.txt(R.id.w_big, big)
                rv.paint(pal.primary, R.id.w_big)
            }
            when (size) {
                WSize.COMPACT -> Unit
                WSize.NORMAL -> fillRows(rv, pal, 3)
                WSize.EXPANDED -> fillRows(rv, pal, 4)
            }
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv
        }
    }

    // ───────────────────────── Reels brain ─────────────────────────

    fun reels(c: Context, id: Int): RemoteViews {
        val today = ReelsStore.today(c)
        val limit = ReelsStore.limit(c)
        val level = reelsLevel(today.total, limit)
        val color = when (level) {
            0 -> 0xFF34A853
            1 -> 0xFFF29900
            2 -> 0xFFE5484D
            else -> 0xFFB3261E
        }.toInt()
        val week = ReelsStore.lastDays(c, 7).map { it.total }
        val avg = if (week.isEmpty()) 0 else week.average().toInt()
        val mood = c.getString(reelsMoodRes(level))
        val sub = if (ReelsStore.enabled(c)) c.getString(R.string.reels_of_limit, limit) else c.getString(R.string.widget_reels_off)
        val open = ClockWidgets.openPage(c, PAGE_REELS, 960001)

        return WidgetFrame.build(SizeF(180f, 92f), SizeF(240f, 136f)) { size ->
            val layout = when (size) {
                WSize.COMPACT -> R.layout.widget_reels_s
                WSize.NORMAL -> R.layout.widget_reels
                WSize.EXPANDED -> R.layout.widget_reels_l
            }
            val (rv, pal) = WidgetStyle.begin(c, id, WidgetKind.REELS, layout)
            val px = when (size) {
                WSize.COMPACT -> 128
                WSize.NORMAL -> 160
                WSize.EXPANDED -> 256
            }
            rv.setImageViewBitmap(R.id.w_reels_brain, ReelsBrainBitmap.render(level, px))
            rv.txt(R.id.w_reels_count, today.total.toString())
            rv.setTextColor(R.id.w_reels_count, color)
            if (size != WSize.COMPACT) {
                rv.txt(R.id.w_reels_mood, mood)
                rv.txt(R.id.w_reels_sub, sub)
                rv.paint(pal.primary, R.id.w_reels_mood)
                rv.paint(pal.secondary, R.id.w_reels_sub)
            }
            if (size == WSize.EXPANDED) {
                rv.txt(R.id.w_reels_week, c.getString(R.string.widget_reels_week_avg, avg))
                rv.paint(pal.accent, R.id.w_reels_week)
            }
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv
        }
    }

    // ───────────────────────── KanaGoals ─────────────────────────

    private fun goalsStatus(c: Context, p: GoalPlan): String = c.getString(
        when (p.status) {
            GoalStatus.NEW -> R.string.goals_st_new
            GoalStatus.DONE -> R.string.goals_st_done
            GoalStatus.AHEAD -> R.string.goals_st_ahead
            GoalStatus.ON_TRACK -> R.string.goals_st_on
            GoalStatus.BEHIND -> if ((p.daysLeft ?: 0) < 0) R.string.goals_st_overdue else R.string.goals_st_behind
            GoalStatus.NONE -> R.string.goals_st_none
        },
    )

    fun goals(c: Context, id: Int): RemoteViews {
        val now = System.currentTimeMillis()
        val locked = GoalsLock.enabled(c)
        val active = if (locked) emptyList() else GoalsStore.load(c).filter { !it.archived }
        val g = active.firstOrNull { it.completedAt == 0L } ?: active.firstOrNull()
        val plan = if (g == null) null else GoalsEngine.plan(g, now)
        val open = ClockWidgets.openPage(c, PAGE_GOALS, 3400)
        val title = when {
            locked -> c.getString(R.string.goals_title)
            g == null -> c.getString(R.string.goals_title)
            else -> "${g.emoji} ${g.name}"
        }
        val big = when {
            locked -> "\uD83D\uDD12"
            plan == null -> "\u2014"
            else -> "${(plan.progress * 100).toInt()}%"
        }
        val detail = when {
            locked -> c.getString(R.string.widget_goals_locked)
            g == null || plan == null -> c.getString(R.string.widget_goals_none)
            else -> c.getString(R.string.goals_saved_of, formatMoney(plan.saved, g.currency), formatMoney(g.target, g.currency))
        }
        val status = when {
            g == null || plan == null -> ""
            // With no target date there is no plan to judge, so show what is left instead.
            plan.status == GoalStatus.NONE -> c.getString(R.string.goals_remaining, formatMoney(plan.remaining, g.currency))
            else -> goalsStatus(c, plan)
        }
        val progress = plan?.progress ?: 0f
        val others = if (g == null) emptyList() else active.filter { it.id != g.id }.take(2)
        val rowIds = listOf(
            listOf(R.id.w_g2, R.id.w_g2_title, R.id.w_g2_pct, R.id.w_g2_bar),
            listOf(R.id.w_g3, R.id.w_g3_title, R.id.w_g3_pct, R.id.w_g3_bar),
        )

        return WidgetFrame.build(SizeF(180f, 108f), SizeF(200f, 170f)) { size ->
            val layout = when (size) {
                WSize.COMPACT -> R.layout.widget_goals_s
                WSize.NORMAL -> R.layout.widget_goals
                WSize.EXPANDED -> R.layout.widget_goals_l
            }
            val (rv, pal) = WidgetStyle.begin(c, id, WidgetKind.GOALS, layout)
            val ringPx = if (size == WSize.COMPACT) 120 else 200
            rv.setImageViewBitmap(R.id.w_ring, WidgetGfx.ring(ringPx, progress, pal.accent, pal.track))
            rv.txt(R.id.w_big, big)
            rv.txt(R.id.w_title, title)
            rv.txt(R.id.w_detail, detail)
            rv.paint(pal.primary, R.id.w_big, R.id.w_title)
            rv.paint(pal.secondary, R.id.w_detail)
            if (size != WSize.COMPACT) {
                rv.txt(R.id.w_status, status)
                rv.paint(pal.accent, R.id.w_status)
            }
            if (size == WSize.EXPANDED) {
                for (i in 0 until 2) {
                    val o = others.getOrNull(i)
                    val ids = rowIds[i]
                    if (o == null) {
                        rv.setViewVisibility(ids[0], View.GONE)
                    } else {
                        val p = GoalsEngine.plan(o, now)
                        rv.setViewVisibility(ids[0], View.VISIBLE)
                        rv.txt(ids[1], "${o.emoji} ${o.name}")
                        rv.txt(ids[2], "${(p.progress * 100).toInt()}%")
                        rv.paint(pal.primary, ids[1])
                        rv.paint(pal.accent, ids[2])
                        rv.setImageViewBitmap(ids[3], WidgetGfx.bar(360, 18, p.progress, pal.accent, pal.track))
                    }
                }
            }
            rv.setOnClickPendingIntent(R.id.w_root, open)
            rv
        }
    }
}
