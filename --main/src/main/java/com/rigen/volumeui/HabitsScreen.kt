package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private class HabitInfo(
    val name: String,
    val streak: Int,
    val longest: Int,
    val done: Boolean,
    val due: Boolean,
    val frozen: Boolean,
    val category: String,
    val daily: Boolean,
    val days: List<String>,
)

private val WEEK = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

private fun dayName(c: Context, key: String): String = c.getString(
    when (key) {
        "sun" -> R.string.day_sun
        "mon" -> R.string.day_mon
        "tue" -> R.string.day_tue
        "wed" -> R.string.day_wed
        "thu" -> R.string.day_thu
        "fri" -> R.string.day_fri
        else -> R.string.day_sat
    },
)

private fun parseHabitInfos(b: JSONObject?): List<HabitInfo> {
    val a = b?.optJSONArray("habits") ?: return emptyList()
    return (0 until a.length()).map {
        val o = a.getJSONObject(it)
        val daysAny = o.opt("days")
        val days = if (daysAny is JSONArray) (0 until daysAny.length()).map { i -> daysAny.getString(i) } else emptyList()
        HabitInfo(
            name = o.optString("name"),
            streak = o.optInt("streak"),
            longest = o.optInt("longest"),
            done = o.optBoolean("done"),
            due = o.optBoolean("due"),
            frozen = o.optBoolean("frozen"),
            category = o.optString("category"),
            daily = daysAny !is JSONArray,
            days = days,
        )
    }
}

/** Your habits from the Kanade bot: see the streaks, check a habit, undo today's check, add a new one. */
@Composable
fun HabitsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(AccountStore.load(ctx)) }
    var habits by remember { mutableStateOf<List<HabitInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf<String>()) }

    // Applies what the bot answered: the new list (even when it refused), or "sign in again".
    fun apply(r: ApiResult) {
        if (r.status == 401) {
            AccountStore.clear(ctx)
            KanadeReminders.reset(ctx)
            session = null
            return
        }
        if (r.body?.has("habits") == true) {
            habits = parseHabitInfos(r.body)
            KanadeReminders.cacheHabits(ctx, KanadeReminders.parseHabits(r.body))
            ClockWidgets.updateAll(ctx)
        }
        loaded = true
    }

    fun act(path: String, payload: JSONObject, onOk: (ApiResult) -> Unit = {}) {
        scope.launch {
            busy = true
            error = ""
            info = ""
            val r = KanadeApi.call(path, payload, session?.token)
            busy = false
            apply(r)
            if (r.ok) onOk(r) else if (r.status != 401) error = r.message(ctx)
        }
    }

    LaunchedEffect(Unit) {
        val s = session ?: return@LaunchedEffect
        val r = KanadeApi.call("/me/habits", token = s.token)
        apply(r)
        if (!r.ok && r.status != 401) error = r.message(ctx)
    }

    PageScaffold(stringResource(R.string.habits_title), onBack) { padding ->
        PageColumn(padding) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (session == null) {
                Text(stringResource(R.string.habit_signin), style = MaterialTheme.typography.bodyMedium)
            } else {
                if (adding) {
                    Field(stringResource(R.string.habit_name), name, { name = it.take(30) })
                    Field(stringResource(R.string.habit_category), category, { category = it.take(20) })
                    Text(stringResource(R.string.habit_days_title), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = days.isEmpty(),
                            onClick = { days = emptySet() },
                            label = { Text(stringResource(R.string.habit_daily)) },
                        )
                        WEEK.forEach { k ->
                            FilterChip(
                                selected = k in days,
                                onClick = { days = if (k in days) days - k else days + k },
                                label = { Text(dayName(ctx, k)) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                act(
                                    "/me/habits/add",
                                    JSONObject().put("name", name.trim()).put("category", category.trim())
                                        .put("days", JSONArray(days.toList())),
                                ) {
                                    adding = false
                                    name = ""
                                    category = ""
                                    days = emptySet()
                                }
                            },
                            enabled = !busy && name.isNotBlank(),
                        ) { Text(stringResource(R.string.habit_save)) }
                        TextButton(
                            onClick = {
                                adding = false
                                error = ""
                            },
                        ) { Text(stringResource(R.string.acc_cancel)) }
                    }
                } else {
                    Button(
                        onClick = {
                            error = ""
                            info = ""
                            adding = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.habit_add)) }
                }
                if (info.isNotBlank()) {
                    Text(info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                ErrorText(error)
                if (loaded && habits.isEmpty()) {
                    Text(stringResource(R.string.habit_none), style = MaterialTheme.typography.bodyMedium)
                }
                habits.forEach { h ->
                    HabitCard(
                        h = h,
                        busy = busy,
                        onCheck = {
                            act("/me/habits/check", JSONObject().put("name", h.name)) { r ->
                                info = ctx.getString(R.string.habit_checked, r.body?.optInt("streak") ?: 0)
                            }
                        },
                        onUndo = { act("/me/habits/undo", JSONObject().put("name", h.name)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitCard(h: HabitInfo, busy: Boolean, onCheck: () -> Unit, onUndo: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(h.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val daysText = if (h.daily) {
                    ctx.getString(R.string.habit_daily)
                } else {
                    h.days.joinToString(", ") { dayName(ctx, it) }
                }
                Text(
                    listOf(h.category, daysText).filter { it.isNotBlank() }.joinToString(" \u2022 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.habit_streak, h.streak, h.longest), style = MaterialTheme.typography.bodyMedium)
                val status = when {
                    h.frozen -> R.string.habit_frozen
                    h.done -> R.string.habit_done_today
                    h.due -> R.string.habit_due_today
                    else -> R.string.habit_not_today
                }
                Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            when {
                h.frozen -> {}
                h.done -> OutlinedButton(onClick = onUndo, enabled = !busy) { Text(stringResource(R.string.habit_undo)) }
                else -> Button(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.habit_check)) }
            }
        }
    }
}
