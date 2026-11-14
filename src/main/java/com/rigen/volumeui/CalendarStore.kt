package com.rigen.volumeui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoUnit
import java.util.UUID

// What an entry you make yourself is.
const val CAL_EVENT = 0
const val CAL_OCCASION = 1
const val CAL_REMINDER = 2
const val CAL_BILL = 3

// What shows up in a day. The first four are yours; the others are read from the rest of the app.
const val CT_EVENT = 0
const val CT_OCCASION = 1
const val CT_REMINDER = 2
const val CT_BILL = 3
const val CT_ALARM = 4
const val CT_PRAYER = 5
const val CT_GOAL = 6

// How an entry repeats.
const val REP_NONE = 0
const val REP_DAILY = 1
const val REP_WEEKLY = 2
const val REP_MONTHLY = 3
const val REP_YEARLY = 4

/**
 * One entry of the calendar. [remindBefore] is in minutes (-1 for no reminder). [source] says who made it:
 * empty for you, "bloom" for the ones Kanade Bloom suggested.
 */
data class CalEvent(
    val id: String,
    val title: String,
    val notes: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val kind: Int,
    val repeat: Int,
    val remindBefore: Int,
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** One line in a day. [at] is the start of the day for all-day lines. */
class CalItem(
    val type: Int,
    val title: String,
    val at: Long,
    val allDay: Boolean,
    val eventId: String = "",
    val sub: String = "",
)

/** Your own entries, saved on the phone. Everything else in the calendar is read from the other screens. */
object CalendarStore {
    private const val PREF = "kanade_calendar"

    /** Screens read this so they redraw after a change. */
    var tick by mutableIntStateOf(0)
        private set

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(c: Context): List<CalEvent> = try {
        val a = JSONArray(sp(c).getString("events", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CalEvent(
                id = o.optString("id"),
                title = o.optString("t"),
                notes = o.optString("n"),
                start = o.optLong("s"),
                end = o.optLong("e"),
                allDay = o.optBoolean("ad"),
                kind = o.optInt("k"),
                repeat = o.optInt("r"),
                remindBefore = o.optInt("rb", -1),
                source = o.optString("src"),
                createdAt = o.optLong("ca"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(c: Context, list: List<CalEvent>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("t", it.title).put("n", it.notes).put("s", it.start)
                    .put("e", it.end).put("ad", it.allDay).put("k", it.kind).put("r", it.repeat)
                    .put("rb", it.remindBefore).put("src", it.source).put("ca", it.createdAt),
            )
        }
        sp(c).edit().putString("events", a.toString()).apply()
        tick++
        CalendarEngine.reschedule(c)
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun upsert(c: Context, e: CalEvent) {
        val list = load(c).toMutableList()
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e else list.add(e)
        save(c, list)
    }

    fun delete(c: Context, id: String) {
        save(c, load(c).filter { it.id != id })
    }
}

/** A snapshot of everything that can show in a day, so a whole month is drawn from one read. */
class CalSnapshot(
    private val ctx: Context,
    val events: List<CalEvent>,
    private val alarms: List<AlarmItem>,
    private val goals: List<Goal>,
    private val prayers: List<PrayerTime>,
) {
    fun itemsFor(d: LocalDate): List<CalItem> {
        val out = mutableListOf<CalItem>()
        val dayStart = CalendarEngine.startMs(d)

        events.forEach { e ->
            val at = CalendarEngine.occursOn(e, d)
            if (at >= 0) {
                val type = when (e.kind) {
                    CAL_OCCASION -> CT_OCCASION
                    CAL_REMINDER -> CT_REMINDER
                    CAL_BILL -> CT_BILL
                    else -> CT_EVENT
                }
                out.add(CalItem(type, e.title, if (e.allDay) dayStart else at, e.allDay, e.id, e.notes))
            }
        }

        CalendarEngine.islamicOccasion(ctx, d)?.let {
            out.add(CalItem(CT_OCCASION, it, dayStart, true, "", ctx.getString(R.string.cal_hijri_tag)))
        }

        val today = LocalDate.now()
        alarms.filter { it.enabled }.forEach { a ->
            val at = dayStart + (a.hour * 60L + a.minute) * 60_000L
            val rings = if (a.days == 0) {
                val next = nextTrigger(a)
                next > 0 && CalendarEngine.ld(next) == d
            } else {
                !d.isBefore(today) && ((a.days shr (d.dayOfWeek.value % 7)) and 1) == 1
            }
            if (rings && a.skipUntil < at) {
                out.add(CalItem(CT_ALARM, a.label.ifBlank { ctx.getString(R.string.cal_alarm) }, at, false))
            }
        }

        prayers.filter { CalendarEngine.ld(it.at) == d }.forEach {
            out.add(CalItem(CT_PRAYER, it.name, it.at, false))
        }

        goals.filter { !it.archived && it.completedAt == 0L && it.deadline > 0L && CalendarEngine.ld(it.deadline) == d }
            .forEach {
                out.add(CalItem(CT_GOAL, (it.emoji + " " + it.name).trim(), dayStart, true, "", ctx.getString(R.string.cal_goal_due)))
            }

        return out.sortedWith(compareBy({ !it.allDay }, { it.at }, { it.type }))
    }
}

object CalendarEngine {
    const val ACTION = "com.kanade.reminder.CALENDAR"
    private const val PREF = "kanade_calendar_rem"
    private const val CHANNEL = "kanade_calendar"
    private const val RC_BASE = 942000
    private const val MAX_ALARMS = 60
    private const val ALL_DAY_ALERT_MS = 9 * 3_600_000L

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun ld(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun startMs(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    fun startOfDay(ms: Long): Long = startMs(ld(ms))

    fun snapshot(c: Context): CalSnapshot = CalSnapshot(
        c.applicationContext,
        CalendarStore.load(c),
        try { ClockStore.alarms(c) } catch (e: Exception) { emptyList() },
        try { GoalsStore.load(c) } catch (e: Exception) { emptyList() },
        try { KanadeReminders.cachedPrayers(c).second } catch (e: Exception) { emptyList() },
    )

    /** When [e] happens on [d] (the moment it starts), or -1 if it does not happen that day. */
    fun occursOn(e: CalEvent, d: LocalDate): Long {
        val sd = ld(e.start)
        if (d.isBefore(sd)) return -1L
        val last = d.lengthOfMonth()
        val ok = when (e.repeat) {
            REP_NONE -> d == sd
            REP_DAILY -> true
            REP_WEEKLY -> ChronoUnit.DAYS.between(sd, d) % 7L == 0L
            REP_MONTHLY -> d.dayOfMonth == minOf(sd.dayOfMonth, last)
            REP_YEARLY -> d.monthValue == sd.monthValue && d.dayOfMonth == minOf(sd.dayOfMonth, last)
            else -> false
        }
        if (!ok) return -1L
        return startMs(d) + (e.start - startMs(sd))
    }

    // ───────────── Hijri date and the Islamic occasions ─────────────

    private fun hijri(d: LocalDate): HijrahDate? = try {
        HijrahDate.from(d)
    } catch (e: Exception) {
        null
    }

    /** "12 Ramadan 1448", or empty if the date cannot be converted. */
    fun hijriLabel(c: Context, d: LocalDate): String {
        val h = hijri(d) ?: return ""
        val months = c.resources.getStringArray(R.array.cal_hijri_months)
        val m = h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
        return "${h.get(java.time.temporal.ChronoField.DAY_OF_MONTH)} ${months.getOrElse(m - 1) { "" }} ${h.get(java.time.temporal.ChronoField.YEAR)}"
    }

    /** The name of the occasion on this day by the Hijri calendar, or null. */
    fun islamicOccasion(c: Context, d: LocalDate): String? {
        val h = hijri(d) ?: return null
        val month = h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
        val day = h.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
        val res = when {
            month == 1 && day == 1 -> R.string.cal_occ_new_year
            month == 1 && day == 10 -> R.string.cal_occ_ashura
            month == 3 && day == 12 -> R.string.cal_occ_mawlid
            month == 9 && day == 1 -> R.string.cal_occ_ramadan
            month == 10 && day == 1 -> R.string.cal_occ_eid_fitr
            month == 12 && day == 9 -> R.string.cal_occ_arafah
            month == 12 && day == 10 -> R.string.cal_occ_eid_adha
            else -> return null
        }
        return c.getString(res)
    }

    // ───────────── Reminders ─────────────

    private fun pending(c: Context, rc: Int, id: String, title: String, notes: String, occ: Long): PendingIntent =
        PendingIntent.getBroadcast(
            c, rc,
            Intent(c, KanadeReminderReceiver::class.java).setAction(ACTION)
                .putExtra("id", id).putExtra("title", title).putExtra("notes", notes).putExtra("occ", occ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** When the next reminder of [e] should ring, and when the thing itself starts, or null. */
    private fun nextFire(e: CalEvent, now: Long): Pair<Long, Long>? {
        val before = e.remindBefore * 60_000L
        val shift = if (e.allDay) ALL_DAY_ALERT_MS else 0L
        if (e.repeat == REP_NONE) {
            val occ = e.start + shift
            return if (occ - before > now) Pair(occ - before, occ) else null
        }
        var d = ld(now)
        for (i in 0 until 400) {
            val at = occursOn(e, d)
            if (at >= 0) {
                val occ = at + shift
                if (occ - before > now) return Pair(occ - before, occ)
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** One alarm per entry that has a reminder, for its next time. Called after every change, at boot and when one rings. */
    fun reschedule(c: Context) {
        try {
            val am = KanadeReminders.alarms(c)
            val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            (sp.getStringSet("rcs", emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.forEach {
                am.cancel(pending(c, it, "", "", "", 0L))
            }
            val now = System.currentTimeMillis()
            val due = CalendarStore.load(c)
                .filter { it.remindBefore >= 0 }
                .mapNotNull { e -> nextFire(e, now)?.let { Triple(it.first, it.second, e) } }
                .sortedBy { it.first }
                .take(MAX_ALARMS)
            val used = mutableSetOf<Int>()
            due.forEachIndexed { i, (fireAt, occ, e) ->
                val rc = RC_BASE + i
                KanadeReminders.setAt(c, fireAt, pending(c, rc, e.id, e.title, e.notes, occ))
                used.add(rc)
            }
            sp.edit().putStringSet("rcs", used.map { it.toString() }.toSet()).apply()
        } catch (e: Exception) {
            Diag.error(c, "CalendarEngine reschedule", e)
        }
    }

    /** A reminder rang: show it, then set the next alarms (a repeating entry moves to its next time). */
    fun onFire(c: Context, intent: Intent) {
        val title = intent.getStringExtra("title").orEmpty()
        val notes = intent.getStringExtra("notes").orEmpty()
        val occ = intent.getLongExtra("occ", 0L)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.cal_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val time = if (occ > 0L) android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(occ)) else ""
        val text = listOf(time, notes).filter { it.isNotBlank() }.joinToString(" · ")
        val open = PendingIntent.getActivity(
            c, 942900,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_PAGE, PAGE_CALENDAR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title.ifBlank { c.getString(R.string.cal_title) })
            .setContentText(text)
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(RC_BASE + (title.hashCode() and 0xFFFF), n)
        reschedule(c)
    }
}
