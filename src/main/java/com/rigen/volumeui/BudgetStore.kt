package com.rigen.volumeui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/*
 * KanaBudget: what you earn and spend, by month, kept on this phone only.
 * Money is stored in minor units (1/100) in a Long, like the goals.
 */

/** One expense or one income. [amount] is always positive; [income] says which it is. */
class BudgetTx(val id: String, val amount: Long, val income: Boolean, val cat: String, val note: String, val at: Long)

class CustomCat(val id: String, val emoji: String, val name: String)

/** A category as the screens use it. Built-in ones are named from resources ([nameRes] is not 0). */
class BudgetCat(val id: String, val emoji: String, val name: String, val nameRes: Int, val limit: Long)

class BudgetData(
    val currency: String,
    /** The monthly budget, in minor units. 0 means none. */
    val monthly: Long,
    /** A monthly limit per category id, in minor units. */
    val limits: Map<String, Long>,
    val custom: List<CustomCat>,
    val txs: List<BudgetTx>,
) {
    fun cats(): List<BudgetCat> =
        BudgetStore.BUILTIN.map { (id, emoji, res) -> BudgetCat(id, emoji, "", res, limits[id] ?: 0L) } +
            custom.map { BudgetCat(it.id, it.emoji, it.name, 0, limits[it.id] ?: 0L) }

    /** The category with this id; "other" when it has been deleted. */
    fun cat(id: String): BudgetCat = cats().firstOrNull { it.id == id } ?: cats().first { it.id == "other" }
}

/** A calendar month: from [start] up to (not including) [end]. */
class BudgetMonth(val start: Long, val end: Long)

class BudgetSummary(val spent: Long, val income: Long, val byCat: Map<String, Long>)

object BudgetEngine {
    /** The month [offset] months from the one [now] is in (0 this month, -1 last month). */
    fun month(now: Long, offset: Int): BudgetMonth {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.MONTH, offset)
        val start = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return BudgetMonth(start, c.timeInMillis)
    }

    fun summary(d: BudgetData, m: BudgetMonth): BudgetSummary {
        var spent = 0L
        var income = 0L
        val by = HashMap<String, Long>()
        for (t in d.txs) {
            if (t.at < m.start || t.at >= m.end) continue
            if (t.income) {
                income += t.amount
            } else {
                spent += t.amount
                by[t.cat] = (by[t.cat] ?: 0L) + t.amount
            }
        }
        return BudgetSummary(spent, income, by)
    }

    /** What is left to spend per day for the rest of the month, today included. Null outside the current month. */
    fun dailyAllowance(left: Long, now: Long, m: BudgetMonth): Long? {
        if (now < m.start || now >= m.end || left <= 0L) return null
        val days = Math.round((m.end - GoalsEngine.startOfDay(now)).toDouble() / GoalsEngine.DAY_MS).coerceAtLeast(1L)
        return left / days
    }

    fun monthLabel(m: BudgetMonth): String = SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(Date(m.start))
}

private val SAFE = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val MAX = 100_000_000_000_000L

/**
 * One private SharedPreferences value (JSON). Every change bumps [tick] so open screens redraw. If the
 * saved text can't be read it is kept under another key instead of being overwritten by the next save.
 */
object BudgetStore {
    private const val FILE = "kanade_budget"
    private const val KEY = "budget"
    private const val KEY_BAD = "budget_unreadable"

    /** The categories every budget starts with: id, emoji, name. */
    val BUILTIN: List<Triple<String, String, Int>> = listOf(
        Triple("food", "\uD83C\uDF54", R.string.bud_cat_food),
        Triple("transport", "\uD83D\uDE8C", R.string.bud_cat_transport),
        Triple("bills", "\uD83D\uDCA1", R.string.bud_cat_bills),
        Triple("shopping", "\uD83D\uDECD\uFE0F", R.string.bud_cat_shopping),
        Triple("health", "\uD83D\uDC8A", R.string.bud_cat_health),
        Triple("fun", "\uD83C\uDFAE", R.string.bud_cat_fun),
        Triple("savings", "\uD83D\uDCB0", R.string.bud_cat_savings),
        Triple("other", "\uD83D\uDCE6", R.string.bud_cat_other),
    )

    var tick by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun empty(c: Context) = BudgetData(c.getString(R.string.goals_currency_default), 0L, emptyMap(), emptyList(), emptyList())

    fun load(c: Context): BudgetData {
        val raw = prefs(c).getString(KEY, null) ?: return empty(c)
        return try {
            val o = JSONObject(raw)
            val limits = HashMap<String, Long>()
            o.optJSONObject("limits")?.let { l ->
                for (k in l.keys()) {
                    val v = l.getLong(k)
                    if (SAFE.matches(k) && v in 1..MAX) limits[k] = v
                }
            }
            val ca = o.optJSONArray("custom") ?: JSONArray()
            val custom = (0 until ca.length()).map {
                val x = ca.getJSONObject(it)
                val id = x.getString("id")
                require(SAFE.matches(id))
                CustomCat(id, x.optString("emoji", "\uD83D\uDCE6").take(8).ifBlank { "\uD83D\uDCE6" }, x.getString("name").trim().take(30))
            }
            val ta = o.optJSONArray("tx") ?: JSONArray()
            val txs = (0 until ta.length()).map {
                val x = ta.getJSONObject(it)
                val id = x.getString("id")
                val a = x.getLong("a")
                require(SAFE.matches(id) && a in 1..MAX)
                BudgetTx(id, a, x.optBoolean("i", false), x.optString("c", "other"), x.optString("n").take(120), x.optLong("t", 0L))
            }
            BudgetData(
                currency = o.optString("currency", c.getString(R.string.goals_currency_default)).trim().take(8),
                monthly = o.optLong("monthly", 0L).coerceIn(0L, MAX),
                limits = limits,
                custom = custom,
                txs = txs,
            )
        } catch (_: Exception) {
            prefs(c).edit().putString(KEY_BAD, raw).apply()
            empty(c)
        }
    }

    private fun save(c: Context, d: BudgetData) {
        val limits = JSONObject()
        for ((k, v) in d.limits) if (v > 0L) limits.put(k, v)
        val custom = JSONArray()
        for (x in d.custom) custom.put(JSONObject().put("id", x.id).put("emoji", x.emoji).put("name", x.name))
        val tx = JSONArray()
        for (t in d.txs) tx.put(JSONObject().put("id", t.id).put("a", t.amount).put("i", t.income).put("c", t.cat).put("n", t.note).put("t", t.at))
        val o = JSONObject().put("v", 1).put("currency", d.currency).put("monthly", d.monthly)
            .put("limits", limits).put("custom", custom).put("tx", tx)
        prefs(c).edit().putString(KEY, o.toString()).apply()
        tick++
    }

    private fun copy(
        d: BudgetData,
        currency: String = d.currency,
        monthly: Long = d.monthly,
        limits: Map<String, Long> = d.limits,
        custom: List<CustomCat> = d.custom,
        txs: List<BudgetTx> = d.txs,
    ) = BudgetData(currency, monthly, limits, custom, txs)

    fun setMain(c: Context, currency: String, monthly: Long, limits: Map<String, Long>) {
        save(c, copy(load(c), currency = currency.trim().take(8), monthly = monthly, limits = limits))
    }

    fun addCustom(c: Context, emoji: String, name: String) {
        val n = name.trim().take(30)
        if (n.isEmpty()) return
        val d = load(c)
        save(c, copy(d, custom = d.custom + CustomCat(UUID.randomUUID().toString(), emoji, n)))
    }

    fun deleteCustom(c: Context, id: String) {
        val d = load(c)
        save(c, copy(d, custom = d.custom.filter { it.id != id }, limits = d.limits - id))
    }

    fun addTx(c: Context, amount: Long, income: Boolean, cat: String, note: String, at: Long) {
        if (amount <= 0L) return
        val d = load(c)
        save(c, copy(d, txs = d.txs + BudgetTx(UUID.randomUUID().toString(), amount, income, cat, note.trim().take(120), at)))
    }

    /** Puts a deleted transaction back, with its original time. */
    fun restoreTx(c: Context, tx: BudgetTx) {
        val d = load(c)
        if (d.txs.any { it.id == tx.id }) return
        save(c, copy(d, txs = d.txs + tx))
    }

    fun deleteTx(c: Context, id: String) {
        val d = load(c)
        save(c, copy(d, txs = d.txs.filter { it.id != id }))
    }

    /** One row per transaction, with a byte order mark so spreadsheets read Arabic right. */
    fun exportCsv(c: Context): ByteArray {
        val d = load(c)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("\uFEFFDate,Type,Category,Amount,Currency,Note\r\n")
        for (t in d.txs.sortedBy { it.at }) {
            val cat = d.cat(t.cat)
            val label = if (cat.nameRes != 0) c.getString(cat.nameRes) else cat.name
            sb.append(fmt.format(Date(t.at))).append(',')
                .append(if (t.income) "income" else "expense").append(',')
                .append(cell(if (t.income) "" else label)).append(',')
                .append(editableMoney(t.amount)).append(',')
                .append(cell(d.currency)).append(',')
                .append(cell(t.note)).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Quoted, and defanged: a cell that starts with = + - @ would run as a formula in a spreadsheet. */
    private fun cell(s: String): String {
        val guarded = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return "\"" + guarded.replace("\"", "\"\"") + "\""
    }

    /** Absolute value for showing a difference as "N over". */
    fun magnitude(v: Long): Long = abs(v)
}
