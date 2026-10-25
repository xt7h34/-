package com.rigen.volumeui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * KanaGoals: savings goals kept on this phone only. Nothing here talks to the bot or the network.
 * Money is stored as whole "minor units" (1/100) in a Long, so sums never drift like a Double would.
 */

/** One deposit (positive) or withdrawal (negative) on a goal. */
class GoalEntry(val id: String, val amount: Long, val at: Long, val note: String)

class Goal(
    val id: String,
    val name: String,
    val emoji: String,
    /** The price, in minor units. Always above zero. */
    val target: Long,
    val currency: String,
    /** Local midnight of the target day, or 0 for no date. */
    val deadline: Long,
    /** An http(s) link to the thing, or empty. */
    val link: String,
    val createdAt: Long,
    val entries: List<GoalEntry>,
    /** Archived goals leave the main list. */
    val archived: Boolean = false,
    /** When the price was reached, or 0 while it has not been. */
    val completedAt: Long = 0L,
    /** When the photo was last set (0 for no photo). Also tells the screen to reload it. */
    val photoAt: Long = 0L,
    /** Reminder: 0 off, 1 daily, 2 weekly, 3 monthly. */
    val remind: Int = 0,
    /** Minutes after midnight the reminder rings. */
    val remindMinutes: Int = 20 * 60,
    /** Weekly: Calendar.DAY_OF_WEEK (1 = Sunday). Monthly: day of month, 1 to 28. */
    val remindDay: Int = 0,
    /** What to put aside each time the reminder comes round, in minor units. 0 means no plan. */
    val plan: Long = 0L,
    /** With a plan: add the amount to the goal by itself instead of asking. */
    val autoAdd: Boolean = false,
    /** The last scheduled time the plan was dealt with. Plans never reach back before it. */
    val planSince: Long = 0L,
    /** The amounts on the quick buttons, in minor units. Empty means round ones worked out from the price. */
    val quick: List<Long> = emptyList(),
) {
    val saved: Long get() = entries.sumOf { it.amount }

    fun copyOf(
        entries: List<GoalEntry> = this.entries,
        archived: Boolean = this.archived,
        completedAt: Long = this.completedAt,
        photoAt: Long = this.photoAt,
        planSince: Long = this.planSince,
    ) = Goal(
        id, name, emoji, target, currency, deadline, link, createdAt, entries,
        archived, completedAt, photoAt, remind, remindMinutes, remindDay,
        plan, autoAdd, planSince, quick,
    )
}

enum class GoalStatus { NEW, DONE, AHEAD, ON_TRACK, BEHIND, NONE }

enum class NeedUnit { DAY, WEEK, MONTH }

class GoalPlan(
    val saved: Long,
    val remaining: Long,
    val progress: Float,
    val status: GoalStatus,
    /** Whole days from today to the target day; negative once it has passed. Null with no date. */
    val daysLeft: Int?,
    /** What to put aside per [needUnit] to make the date. Null with no date, when done, or when late. */
    val need: Long?,
    val needUnit: NeedUnit,
    /** When the current pace reaches the price. Null when there is no pace to go by. */
    val projectedFinish: Long?,
)

object GoalsEngine {
    const val DAY_MS = 86_400_000L

    /** The amounts for the quick buttons: the person's own, or round ones worked out from the price. */
    fun quickAmounts(g: Goal): List<Long> {
        val own = g.quick.filter { it > 0L }
        if (own.isNotEmpty()) return own.take(3)
        // 1-2-5 steps, never below one whole unit.
        fun nice(minor: Double): Long {
            val units = max(1.0, minor / 100.0)
            val base = 10.0.pow(floor(log10(units)))
            val f = units / base
            val n = if (f < 1.5) 1.0 else if (f < 3.5) 2.0 else if (f < 7.5) 5.0 else 10.0
            return (n * base * 100.0).toLong()
        }
        return listOf(g.target / 50.0, g.target / 20.0, g.target / 10.0).map { nice(it) }.distinct()
    }

    /** The quick amounts that still make sense: none above what is left to save. */
    fun quickFor(g: Goal, remaining: Long): List<Long> =
        quickAmounts(g).map { min(it, remaining) }.filter { it > 0L }.distinct()

    fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * The pace is the net amount saved per day since the first entry. The span counts as at least a
     * week, so one big deposit today does not look like a huge daily habit.
     */
    fun plan(g: Goal, now: Long): GoalPlan {
        val saved = g.saved
        val remaining = max(0L, g.target - saved)
        val progress = if (g.target > 0) (saved.toDouble() / g.target).coerceIn(0.0, 1.0).toFloat() else 0f
        val done = g.target > 0 && saved >= g.target

        val daysLeft: Int? = if (g.deadline > 0) {
            Math.round((g.deadline - startOfDay(now)).toDouble() / DAY_MS).toInt()
        } else {
            null
        }

        val paceDays = if (g.entries.isEmpty()) 7.0 else max(7.0, (now - g.entries.minOf { it.at }).toDouble() / DAY_MS)
        val pace = saved.toDouble() / paceDays // minor units per day, can be zero or negative

        val projectedDays = if (!done && pace > 0) remaining / pace else null
        val projectedFinish = when {
            done -> null
            projectedDays == null || projectedDays > 36_500.0 -> null
            else -> now + (ceil(projectedDays) * DAY_MS).toLong()
        }

        val status = when {
            done -> GoalStatus.DONE
            g.entries.isEmpty() -> GoalStatus.NEW
            daysLeft == null -> GoalStatus.NONE
            daysLeft < 0 -> GoalStatus.BEHIND
            projectedDays == null -> GoalStatus.BEHIND
            else -> {
                val available = max(1, daysLeft).toDouble()
                when {
                    projectedDays <= available * 0.9 -> GoalStatus.AHEAD
                    projectedDays <= available -> GoalStatus.ON_TRACK
                    else -> GoalStatus.BEHIND
                }
            }
        }

        var need: Long? = null
        var unit = NeedUnit.DAY
        if (!done && daysLeft != null && daysLeft >= 0) {
            val d = max(1, daysLeft)
            unit = if (d >= 45) NeedUnit.MONTH else if (d >= 10) NeedUnit.WEEK else NeedUnit.DAY
            val periods = when (unit) {
                NeedUnit.MONTH -> d / 30.4375
                NeedUnit.WEEK -> d / 7.0
                NeedUnit.DAY -> d.toDouble()
            }
            need = ceil(remaining / periods).toLong()
        }
        return GoalPlan(saved, remaining, progress, status, daysLeft, need, unit, projectedFinish)
    }
}

// ───────────────────────── Money text ─────────────────────────

private val THOUSANDS = Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""")
private val PLAIN_NUMBER = Regex("""^(\d{1,13}(\.\d+)?|\.\d+)$""")

/**
 * Reads what a person typed ("1500", "1,500.50", "١٥٠٠", "12,5") into minor units.
 * Returns null for anything that is not a plain positive-or-zero number, or is absurdly large.
 */
fun parseMoney(raw: String): Long? {
    val sb = StringBuilder()
    for (c in raw.trim()) {
        when {
            c in '٠'..'٩' -> sb.append('0' + (c - '٠'))
            c in '۰'..'۹' -> sb.append('0' + (c - '۰'))
            c == '٫' -> sb.append('.')
            c == '٬' || c.isWhitespace() -> Unit
            else -> sb.append(c)
        }
    }
    var t = sb.toString().replace('،', ',')
    t = if (THOUSANDS.matches(t)) t.replace(",", "") else t.replace(',', '.')
    if (!PLAIN_NUMBER.matches(t)) return null
    return try {
        BigDecimal(t).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

/** "1,500" or "1,500.50" in the phone's own number style, then the currency. */
fun formatMoney(minor: Long, currency: String): String {
    val sign = if (minor < 0) "-" else ""
    val a = abs(minor)
    val whole = NumberFormat.getIntegerInstance().format(a / 100)
    val frac = (a % 100).toInt()
    val body = if (frac == 0) whole else whole + DecimalFormatSymbols.getInstance().decimalSeparator + frac.toString().padStart(2, '0')
    return if (currency.isBlank()) sign + body else "$sign$body $currency"
}

/** A plain number for putting back into a text field. */
fun editableMoney(minor: Long): String {
    val a = abs(minor)
    val frac = (a % 100).toInt()
    return if (frac == 0) (a / 100).toString() else "${a / 100}.${frac.toString().padStart(2, '0')}"
}

/** Accepts only http(s) links; adds https:// when the scheme is missing. Empty in, empty out. Null when invalid. */
fun cleanLink(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (t.length > 500 || t.any { it.isWhitespace() }) return null
    val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
    val lower = withScheme.lowercase()
    if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return null
    val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return if (host.contains('.') && host.length > 3) withScheme else null
}

// ───────────────────────── Storage ─────────────────────────

private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val MAX_MINOR = 100_000_000_000_000L

/**
 * Goals live in one private SharedPreferences value (JSON). Every change bumps [tick] so open
 * screens redraw, and refreshes the reminders and the widget. If the saved text can't be read,
 * it is kept under another key instead of being overwritten by the next save.
 * The order of the list is the priority: the first goal is the most important.
 */
object GoalsStore {
    private const val FILE = "kanade_goals"
    private const val KEY = "goals"
    private const val KEY_UNREADABLE = "goals_unreadable"

    var tick by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): List<Goal> {
        val raw = prefs(c).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONObject(raw).getJSONArray("goals")
            (0 until arr.length()).map { goalFromJson(arr.getJSONObject(it), false) }
        } catch (_: Exception) {
            prefs(c).edit().putString(KEY_UNREADABLE, raw).apply()
            emptyList()
        }
    }

    private fun save(c: Context, goals: List<Goal>) {
        prefs(c).edit().putString(KEY, toJson(goals)).apply()
        tick++
        GoalsExtras.afterSave(c, goals)
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** A goal is complete when the saved amount reaches the price; this keeps [Goal.completedAt] honest. */
    private fun settle(g: Goal, now: Long): Goal {
        val done = g.target > 0 && g.saved >= g.target
        return when {
            done && g.completedAt == 0L -> g.copyOf(completedAt = now)
            !done && g.completedAt != 0L -> g.copyOf(completedAt = 0L)
            else -> g
        }
    }

    /** Adds the goal at the top, or replaces the one with the same id. */
    fun upsert(c: Context, goal: Goal) {
        val all = load(c).toMutableList()
        val i = all.indexOfFirst { it.id == goal.id }
        val settled = settle(goal, System.currentTimeMillis())
        if (i >= 0) all[i] = settled else all.add(0, settled)
        save(c, all)
    }

    /** Removes a goal but keeps its photo, so it can be put back. Returns the goal and the place it was in. */
    fun remove(c: Context, goalId: String): Pair<Goal, Int>? {
        val all = load(c)
        val i = all.indexOfFirst { it.id == goalId }
        if (i < 0) return null
        save(c, all.filter { it.id != goalId })
        return all[i] to i
    }

    /** Puts a removed goal back where it was. */
    fun restore(c: Context, goal: Goal, index: Int) {
        val all = load(c).toMutableList()
        if (all.any { it.id == goal.id }) return
        all.add(index.coerceIn(0, all.size), goal)
        save(c, all)
    }

    /** Puts a removed deposit or withdrawal back with its original time. */
    fun restoreEntry(c: Context, goalId: String, entry: GoalEntry) {
        val now = System.currentTimeMillis()
        save(
            c,
            load(c).map {
                if (it.id != goalId || it.entries.any { e -> e.id == entry.id }) {
                    it
                } else {
                    settle(it.copyOf(entries = (it.entries + entry).sortedBy { e -> e.at }), now)
                }
            },
        )
    }

    fun delete(c: Context, goalId: String) {
        GoalsPhotos.delete(c, goalId)
        save(c, load(c).filter { it.id != goalId })
    }

    /**
     * [amount] is positive for a deposit and negative for a withdrawal.
     * Returns true when this entry is the one that reached the price.
     */
    fun addEntry(c: Context, goalId: String, amount: Long, note: String): Boolean {
        val now = System.currentTimeMillis()
        var reached = false
        val out = load(c).map {
            if (it.id != goalId) {
                it
            } else {
                val e = GoalEntry(UUID.randomUUID().toString(), amount, now, note.trim().take(120))
                val updated = settle(it.copyOf(entries = it.entries + e), now)
                reached = it.completedAt == 0L && updated.completedAt != 0L
                updated
            }
        }
        save(c, out)
        return reached
    }

    fun deleteEntry(c: Context, goalId: String, entryId: String) {
        val now = System.currentTimeMillis()
        save(
            c,
            load(c).map {
                if (it.id != goalId) it else settle(it.copyOf(entries = it.entries.filter { e -> e.id != entryId }), now)
            },
        )
    }

    /** Both goals must be active and use the same currency. Returns true when the receiving goal reached its price. */
    fun transfer(c: Context, fromId: String, toId: String, amount: Long): Boolean {
        val all = load(c)
        val from = all.firstOrNull { it.id == fromId } ?: return false
        val to = all.firstOrNull { it.id == toId } ?: return false
        if (fromId == toId || amount <= 0L || from.saved < amount || from.archived || to.archived) return false
        if (!sameCurrency(from, to)) return false
        val now = System.currentTimeMillis()
        var reached = false
        save(
            c,
            all.map {
                when (it.id) {
                    fromId -> settle(
                        it.copyOf(entries = it.entries + GoalEntry(newId(), -amount, now, "→ ${to.name}".take(120))),
                        now,
                    )
                    toId -> {
                        val updated = settle(
                            it.copyOf(entries = it.entries + GoalEntry(newId(), amount, now, "← ${from.name}".take(120))),
                            now,
                        )
                        reached = it.completedAt == 0L && updated.completedAt != 0L
                        updated
                    }
                    else -> it
                }
            },
        )
        return reached
    }

    /**
     * Adds the goal's saving plan once for each time in [times] (the scheduled moments that have
     * passed), stopping once the price is reached. Returns true when that is what reached it.
     */
    fun applyPlan(c: Context, goalId: String, times: List<Long>): Boolean {
        if (times.isEmpty()) return false
        val now = System.currentTimeMillis()
        var reached = false
        val out = load(c).map {
            if (it.id != goalId || it.plan <= 0L) {
                it
            } else {
                var g = it
                for (t in times) {
                    if (g.target > 0 && g.saved >= g.target) break
                    g = g.copyOf(entries = g.entries + GoalEntry(newId(), it.plan, t, "\u21BB"))
                }
                val updated = settle(g.copyOf(planSince = times.last()), now)
                reached = it.completedAt == 0L && updated.completedAt != 0L
                updated
            }
        }
        save(c, out)
        return reached
    }

    fun sameCurrency(a: Goal, b: Goal): Boolean = a.currency.trim().equals(b.currency.trim(), ignoreCase = true)

    fun setArchived(c: Context, goalId: String, archived: Boolean) {
        save(c, load(c).map { if (it.id == goalId) it.copyOf(archived = archived) else it })
    }

    /** Moves a goal up ([delta] < 0) or down ([delta] > 0) past the next active goal. */
    fun move(c: Context, goalId: String, delta: Int) {
        val all = load(c).toMutableList()
        val idx = all.indexOfFirst { it.id == goalId }
        if (idx < 0) return
        val step = if (delta < 0) -1 else 1
        var j = idx + step
        while (j in all.indices && all[j].archived) j += step
        if (j !in all.indices) return
        java.util.Collections.swap(all, idx, j)
        save(c, all)
    }

    // ── JSON ──

    private fun goalToJson(g: Goal): JSONObject {
        val es = JSONArray()
        for (e in g.entries) {
            es.put(JSONObject().put("id", e.id).put("amount", e.amount).put("at", e.at).put("note", e.note))
        }
        return JSONObject()
            .put("id", g.id).put("name", g.name).put("emoji", g.emoji)
            .put("target", g.target).put("currency", g.currency)
            .put("deadline", g.deadline).put("link", g.link)
            .put("createdAt", g.createdAt).put("entries", es)
            .put("archived", g.archived).put("completedAt", g.completedAt).put("photoAt", g.photoAt)
            .put("remind", g.remind).put("remindMinutes", g.remindMinutes).put("remindDay", g.remindDay)
            .put("plan", g.plan).put("autoAdd", g.autoAdd).put("planSince", g.planSince)
            .put("quick", JSONArray().also { a -> g.quick.forEach { a.put(it) } })
    }

    private fun toJson(goals: List<Goal>): String {
        val arr = JSONArray()
        for (g in goals) arr.put(goalToJson(g))
        return JSONObject().put("v", 2).put("goals", arr).toString()
    }

    /**
     * Reads one goal and checks every field, because the text may come from a backup file.
     * With [lenient], a bad entry is skipped; otherwise it throws, and nothing is lost silently.
     */
    private fun goalFromJson(o: JSONObject, lenient: Boolean): Goal {
        val id = o.getString("id")
        require(SAFE_ID.matches(id))
        val name = o.getString("name").trim().take(60)
        require(name.isNotEmpty())
        val target = o.getLong("target")
        require(target in 1..MAX_MINOR)
        val es = o.optJSONArray("entries") ?: JSONArray()
        val seen = HashSet<String>()
        val entries = ArrayList<GoalEntry>()
        for (j in 0 until es.length()) {
            try {
                val e = es.getJSONObject(j)
                val eid = e.getString("id")
                val amount = e.getLong("amount")
                require(SAFE_ID.matches(eid) && amount != 0L && abs(amount) <= MAX_MINOR && seen.add(eid))
                entries.add(GoalEntry(eid, amount, e.optLong("at", 0L), e.optString("note").take(120)))
            } catch (ex: Exception) {
                if (!lenient) throw ex
            }
        }
        return Goal(
            id = id,
            name = name,
            emoji = o.optString("emoji", "🎯").take(8).ifBlank { "🎯" },
            target = target,
            currency = o.optString("currency").trim().take(8),
            deadline = o.optLong("deadline", 0L).coerceAtLeast(0L),
            link = cleanLink(o.optString("link")) ?: "",
            createdAt = o.optLong("createdAt", 0L),
            entries = entries,
            archived = o.optBoolean("archived", false),
            completedAt = o.optLong("completedAt", 0L).coerceAtLeast(0L),
            photoAt = o.optLong("photoAt", 0L).coerceAtLeast(0L),
            remind = o.optInt("remind", 0).coerceIn(0, 3),
            remindMinutes = o.optInt("remindMinutes", 20 * 60).coerceIn(0, 1439),
            remindDay = o.optInt("remindDay", 0).coerceIn(0, 31),
            plan = o.optLong("plan", 0L).coerceIn(0L, MAX_MINOR),
            autoAdd = o.optBoolean("autoAdd", false),
            planSince = o.optLong("planSince", 0L).coerceAtLeast(0L),
            quick = o.optJSONArray("quick")?.let { a ->
                (0 until a.length()).map { a.optLong(it, 0L) }.filter { it in 1..MAX_MINOR }.take(3)
            } ?: emptyList(),
        )
    }

    // ── Backup (the bytes are encrypted by GoalsBackup before they leave the app) ──

    /** Every goal with its history and photo, as JSON text. */
    fun exportBytes(c: Context): ByteArray {
        val arr = JSONArray()
        for (g in load(c)) {
            val o = goalToJson(g)
            if (g.photoAt > 0) GoalsPhotos.readBase64(c, g.id)?.let { o.put("photo", it) }
            arr.put(o)
        }
        return JSONObject().put("v", 2).put("goals", arr).toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Adds the goals that are not here yet, and the entries that are missing from goals that are.
     * Nothing is overwritten. Returns how many were added, or -1 when the text can't be read.
     */
    fun importBytes(c: Context, bytes: ByteArray): Int {
        val arr = try {
            JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("goals")
        } catch (_: Exception) {
            return -1
        }
        val all = load(c).toMutableList()
        val now = System.currentTimeMillis()
        var added = 0
        for (i in 0 until arr.length()) {
            val o = try { arr.getJSONObject(i) } catch (_: Exception) { continue }
            val incoming = try { goalFromJson(o, true) } catch (_: Exception) { continue }
            val idx = all.indexOfFirst { it.id == incoming.id }
            if (idx < 0) {
                val photo = o.optString("photo")
                val hasPhoto = photo.isNotEmpty() && GoalsPhotos.importBase64(c, incoming.id, photo)
                all.add(settle(incoming.copyOf(photoAt = if (hasPhoto) now else 0L), now))
                added++
            } else {
                val known = all[idx].entries.map { it.id }.toSet()
                val fresh = incoming.entries.filter { it.id !in known }
                if (fresh.isNotEmpty()) {
                    all[idx] = settle(all[idx].copyOf(entries = all[idx].entries + fresh), now)
                    added += fresh.size
                }
            }
        }
        if (added > 0) save(c, all)
        return added
    }

    /** One row per deposit or withdrawal, with a byte order mark so spreadsheets read Arabic right. */
    fun exportCsv(c: Context): ByteArray {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("\uFEFFGoal,Currency,Type,Amount,Date,Note\r\n")
        for (g in load(c)) {
            for (e in g.entries.sortedBy { it.at }) {
                sb.append(csvText(g.name)).append(',')
                    .append(csvText(g.currency)).append(',')
                    .append(if (e.amount > 0) "deposit" else "withdraw").append(',')
                    .append(if (e.amount < 0) "-" else "").append(editableMoney(e.amount)).append(',')
                    .append(fmt.format(Date(e.at))).append(',')
                    .append(csvText(e.note)).append("\r\n")
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Quoted, and defanged: a cell that starts with = + - @ would run as a formula in a spreadsheet. */
    private fun csvText(s: String): String {
        val guarded = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return "\"" + guarded.replace("\"", "\"\"") + "\""
    }
}
