package com.rigen.volumeui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/**
 * What the volume-button service has just recognised, for the practice page. The service only records
 * while that page is open, so this costs nothing the rest of the time.
 */
object GestureLog {
    const val UP = 0
    const val DOWN = 1
    const val HOLD = 2
    const val MUTE = 3

    class Entry(val id: Long, val kind: Int, val at: Long)

    /** True while the practice page is open. */
    @Volatile
    var recording = false

    var entries by mutableStateOf(listOf<Entry>())
        private set
    var seen by mutableStateOf(setOf<Int>())
        private set
    private var next = 0L

    fun add(kind: Int) {
        if (!recording) return
        entries = (listOf(Entry(++next, kind, System.currentTimeMillis())) + entries).take(8)
        seen = seen + kind
    }

    fun clear() {
        entries = emptyList()
        seen = emptySet()
    }
}

private fun label(kind: Int): Int = when (kind) {
    GestureLog.UP -> R.string.gest_ev_up
    GestureLog.DOWN -> R.string.gest_ev_down
    GestureLog.HOLD -> R.string.gest_ev_hold
    else -> R.string.gest_ev_mute
}

@Composable
fun GesturePracticePage(serviceEnabled: Boolean, settings: PanelSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        GestureLog.clear()
        GestureLog.recording = true
        onDispose { GestureLog.recording = false }
    }
    val seen = GestureLog.seen
    val entries = GestureLog.entries
    val muteSummary = when (settings.doublePressKey) {
        1 -> R.string.gest_mute_s_up
        2 -> R.string.gest_mute_s_down
        else -> R.string.gest_mute_off
    }
    val steps = listOf(
        Triple(GestureLog.UP, R.string.gest_up, R.string.gest_up_s),
        Triple(GestureLog.DOWN, R.string.gest_down, R.string.gest_down_s),
        Triple(GestureLog.HOLD, R.string.gest_hold, R.string.gest_hold_s),
        Triple(GestureLog.MUTE, R.string.gest_mute, muteSummary),
    )
    // Double press can be switched off in the settings; then it is not something to practise.
    val needed = steps.filter { it.first != GestureLog.MUTE || settings.doublePressKey != 0 }

    PageScaffold(stringResource(R.string.gest_title), onBack) { padding ->
        PageColumn(padding) {
            if (!serviceEnabled) {
                Section(stringResource(R.string.service_off)) {
                    Text(stringResource(R.string.gest_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.gest_open)) }
                }
            }

            Section(stringResource(R.string.gest_try)) {
                for ((kind, title, summary) in steps) {
                    val done = kind in seen
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (done) "\u2705" else "\u25CB", fontSize = 22.sp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (needed.all { it.first in seen }) {
                    Text(stringResource(R.string.gest_all_done), color = ICON_GREEN, style = MaterialTheme.typography.titleSmall)
                }
            }

            Section(stringResource(R.string.gest_seen)) {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.gest_seen_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (e in entries) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label(e.kind)), modifier = Modifier.weight(1f))
                        Text(
                            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(e.at)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedButton(onClick = { GestureLog.clear() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gest_reset))
            }
        }
    }
}
