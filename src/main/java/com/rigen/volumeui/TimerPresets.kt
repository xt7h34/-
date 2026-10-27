package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

class TimerPreset(val name: String, val ms: Long)

/** Timers worth keeping under a name ("Tea 4:00", "Workout 30:00"), kept on this phone. */
object TimerPresetStore {
    const val MAX = 12
    private const val PREF = "kanade_timer_presets"

    var tick by mutableIntStateOf(0)
        private set

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(c: Context): List<TimerPreset> = try {
        val a = JSONArray(sp(c).getString("list", "[]") ?: "[]")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            TimerPreset(o.getString("n"), o.getLong("m"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(c: Context, list: List<TimerPreset>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("n", p.name).put("m", p.ms))
        sp(c).edit().putString("list", a.toString()).apply()
        tick++
    }

    /** Adds the preset, or replaces the one with the same name. */
    fun add(c: Context, name: String, ms: Long) {
        val n = name.trim().take(24)
        if (n.isEmpty() || ms <= 0L) return
        val rest = all(c).filter { !it.name.equals(n, ignoreCase = true) }
        if (rest.size >= MAX) return
        save(c, rest + TimerPreset(n, ms))
    }

    fun remove(c: Context, name: String) {
        save(c, all(c).filter { it.name != name })
    }
}

private fun clockText(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

/** The saved timers: start one with a tap, save the time on the dial under a name, delete with Undo. */
@Composable
fun TimerPresetsSection(setMs: Long, canStart: Boolean, askNotifications: () -> Unit, onStarted: (Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = TimerPresetStore.tick
    val list = remember(tick) { TimerPresetStore.all(ctx) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Section(stringResource(R.string.timer_saved_title)) {
        if (list.isEmpty()) {
            Text(stringResource(R.string.timer_saved_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (p in list) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(clockText(p.ms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    enabled = canStart,
                    onClick = {
                        askNotifications()
                        onStarted(ClockEngine.timerStart(ctx, p.ms))
                    },
                ) { Text(stringResource(R.string.timer_start)) }
                TextButton(onClick = {
                    TimerPresetStore.remove(ctx, p.name)
                    Undo.show(ctx.getString(R.string.timer_saved_deleted)) { TimerPresetStore.add(ctx, p.name, p.ms) }
                }) { Text(stringResource(R.string.goals_delete)) }
            }
        }
        OutlinedButton(
            onClick = { name = ""; naming = true },
            enabled = setMs > 0L && list.size < TimerPresetStore.MAX,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.timer_saved_add, clockText(setMs))) }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.timer_saved_name_title, clockText(setMs))) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    label = { Text(stringResource(R.string.timer_saved_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        TimerPresetStore.add(ctx, name, setMs)
                        naming = false
                    },
                ) { Text(stringResource(R.string.goals_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.goals_cancel)) } },
        )
    }
}
