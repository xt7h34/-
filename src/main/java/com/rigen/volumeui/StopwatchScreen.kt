package com.rigen.volumeui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** mm:ss.cc, or h:mm:ss.cc from one hour on. */
fun formatStopwatch(ms: Long): String {
    val cs = (ms / 10L) % 100L
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) String.format("%d:%02d:%02d.%02d", h, m, s, cs) else String.format("%02d:%02d.%02d", m, s, cs)
}

@Composable
fun StopwatchPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var sw by remember { mutableStateOf(ClockStore.stopwatch(ctx)) }
    var nowE by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowE = SystemClock.elapsedRealtime()
            delay(30)
        }
    }
    val elapsed = sw.elapsed(nowE)

    // Keep the screen on while it runs, so the numbers can be watched.
    val view = LocalView.current
    DisposableEffect(sw.running) {
        view.keepScreenOn = sw.running
        onDispose { view.keepScreenOn = false }
    }

    fun save(s: StopwatchState) {
        sw = s
        ClockStore.saveStopwatch(ctx, s)
    }

    PageScaffold(stringResource(R.string.clock_tab_stopwatch), onBack) { padding ->
        PageColumn(padding) {
            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                Text(
                    formatStopwatch(elapsed),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        if (sw.running) {
                            save(StopwatchState(true, sw.startAt, sw.acc, sw.laps + sw.elapsed(SystemClock.elapsedRealtime())))
                        } else {
                            save(StopwatchState(false, 0L, 0L, emptyList()))
                        }
                    },
                    enabled = elapsed > 0L,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (sw.running) R.string.sw_lap else R.string.sw_reset))
                }
                Button(
                    onClick = {
                        val e = SystemClock.elapsedRealtime()
                        if (sw.running) {
                            save(StopwatchState(false, 0L, sw.elapsed(e), sw.laps))
                        } else {
                            save(StopwatchState(true, e, sw.acc, sw.laps))
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            when {
                                sw.running -> R.string.sw_pause
                                elapsed > 0L -> R.string.sw_resume
                                else -> R.string.sw_start
                            },
                        ),
                    )
                }
            }
            if (sw.laps.isNotEmpty()) {
                val times = sw.laps.mapIndexed { i, t -> t - (if (i > 0) sw.laps[i - 1] else 0L) }
                val best = if (times.size >= 3) times.min() else -1L
                val worst = if (times.size >= 3) times.max() else -1L
                Section(stringResource(R.string.sw_laps)) {
                    for (i in sw.laps.indices.reversed()) {
                        val color = when (times[i]) {
                            best -> MaterialTheme.colorScheme.tertiary
                            worst -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(stringResource(R.string.sw_lap_n, i + 1), modifier = Modifier.weight(1f), color = color)
                            Text(formatStopwatch(times[i]), modifier = Modifier.weight(1f), color = color, textAlign = TextAlign.Center)
                            Text(
                                formatStopwatch(sw.laps[i]),
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}
