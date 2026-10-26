package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * One line per day of how many of the day's habits were done, kept on this phone. The bot only knows
 * streaks, so the history starts from the day this was first running and grows from there.
 */
object HabitLog {
    private const val DAY_MS = 86_400_000L

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("kanade_habit_log", Context.MODE_PRIVATE)

    private fun load(c: Context): JSONObject = try {
        JSONObject(sp(c).getString("log", "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    /** Today's score, from the habits as they stand now. Called whenever the habits are read. */
    fun record(c: Context, list: List<HabitState>) {
        val due = list.filter { it.due && !it.frozen }
        if (due.isEmpty()) return
        val o = load(c)
        o.put(dayKey(System.currentTimeMillis()).toString(), JSONArray().put(due.count { it.done }).put(due.size))
        // Keep about half a year.
        val keys = o.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().toList()
        if (keys.size > 200) keys.take(keys.size - 200).forEach { o.remove(it.toString()) }
        sp(c).edit().putString("log", o.toString()).apply()
    }

    /** The last [n] days, oldest first: (done, due) for a day that was recorded, or null. */
    fun last(c: Context, n: Int, now: Long): List<Pair<Int, Int>?> {
        val o = load(c)
        return (n - 1 downTo 0).map {
            val a = o.optJSONArray(dayKey(now - it * DAY_MS).toString())
            if (a == null) null else a.optInt(0) to a.optInt(1)
        }
    }
}

private const val WEEKS = 12

/** Twelve weeks of habit days as a grid of squares, the way a contribution map looks. */
@Composable
fun HabitHeatmap() {
    val ctx = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val days = remember { WEEKS * 7 }
    val data = remember { HabitLog.last(ctx, days, now) }

    // Lay the days out by weekday, one column per week, with the week starting where the phone's calendar says.
    val first = Calendar.getInstance().firstDayOfWeek
    val cells = remember {
        val cols = ArrayList<Array<Pair<Int, Int>?>>()
        var col = arrayOfNulls<Pair<Int, Int>>(7)
        data.forEachIndexed { i, v ->
            val cal = Calendar.getInstance()
            cal.timeInMillis = now - (days - 1 - i) * 86_400_000L
            val row = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
            if (row == 0 && i != 0) {
                cols.add(col)
                col = arrayOfNulls(7)
            }
            col[row] = v
        }
        cols.add(col)
        cols
    }
    val perfect = data.count { it != null && it.second > 0 && it.first >= it.second }
    val tracked = data.count { it != null }

    Section(stringResource(R.string.habit_heat_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (col in cells) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (v in col) {
                        val color = when {
                            v == null -> MaterialTheme.colorScheme.surfaceVariant
                            v.first <= 0 -> Color(0x55F29900)
                            v.first >= v.second -> Color(0xFF2E7D32)
                            v.first * 2 >= v.second -> Color(0xFF66BB6A)
                            else -> Color(0xFFA5D6A7)
                        }
                        androidx.compose.foundation.layout.Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(color))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.habit_heat_stats, perfect, tracked),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.habit_heat_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
