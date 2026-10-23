package com.rigen.volumeui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val GLOW = Color(0xFFFF9A4D)

// ───────────────────────────── Timer ─────────────────────────────

/** Turns a touch on the dial into minutes (0 at the top, clockwise, 60 minutes all the way round). */
private fun touchToMs(o: Offset, w: Float, h: Float): Long {
    var deg = Math.toDegrees(atan2((o.x - w / 2f).toDouble(), -(o.y - h / 2f).toDouble()))
    if (deg < 0) deg += 360.0
    return ((deg / 360.0) * 60.0).roundToInt().coerceIn(0, 60) * 60_000L
}

@Composable
private fun TimerDial(ms: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    val fraction = (ms / 3_600_000f).coerceIn(0f, 1f)
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onChange(touchToMs(it, size.width.toFloat(), size.height.toFloat())) })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onChange(touchToMs(it, size.width.toFloat(), size.height.toFloat())) },
                ) { change, _ ->
                    change.consume()
                    onChange(touchToMs(change.position, size.width.toFloat(), size.height.toFloat()))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val rad = size.minDimension / 2f
            drawArc(
                brush = Brush.radialGradient(listOf(GLOW, GLOW.copy(alpha = 0f)), center = center, radius = rad),
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = true,
                topLeft = Offset.Zero,
                size = size,
            )
        }
        Text(formatMs(ms), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SmallPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TimerPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var timers by remember { mutableStateOf(ClockStore.timers(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            timers = ClockStore.timers(ctx)
            delay(100)
        }
    }
    var setMs by remember { mutableLongStateOf(5 * 60_000L) }
    var openId by remember { mutableIntStateOf(0) }
    var floating by remember { mutableStateOf(ClockStore.floatingEnabled(ctx)) }
    val askNotifications = rememberNotifPermission()

    // The big view of one timer. The x leaves it only: the timer keeps going.
    val opened = timers.firstOrNull { it.id == openId }
    if (opened != null) {
        BackHandler { openId = 0 }
        TimerRunningView(
            state = opened,
            now = now,
            onClose = { openId = 0 },
            onToggle = { ClockEngine.timerToggle(ctx, opened.id) },
            onCancel = {
                ClockEngine.timerCancel(ctx, opened.id)
                openId = 0
            },
        )
        return
    }

    PageScaffold(stringResource(R.string.clock_tab_timer), onBack) { padding ->
        PageColumn(padding) {
            if (timers.isNotEmpty()) {
                Section(stringResource(R.string.timer_running_card)) {
                    timers.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable { openId = t.id }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    formatMs(t.remaining(now)),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    formatMs(t.total),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable { ClockEngine.timerToggle(ctx, t.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                PathIcon(
                                    (if (t.running) Glyph.PAUSE else Glyph.PLAY).pathData,
                                    MaterialTheme.colorScheme.onPrimaryContainer, 20.dp,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { ClockEngine.timerCancel(ctx, t.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                PathIcon(Glyph.CLOSE.pathData, MaterialTheme.colorScheme.onSurfaceVariant, 20.dp)
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TimerDial(setMs, { setMs = it }, Modifier.size(280.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.timer_set_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(-60_000L, 30_000L, 60_000L, 300_000L).forEach { d ->
                    SmallPill((if (d < 0) "\u2212" else "+") + formatMs(abs(d))) {
                        setMs = (setMs + d).coerceIn(0L, 24 * 3_600_000L)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(1, 5, 10, 15, 30).forEach { m ->
                    SmallPill(formatDuration(ctx, m)) { setMs = m * 60_000L }
                }
            }
            Button(
                onClick = {
                    askNotifications()
                    val id = ClockEngine.timerStart(ctx, setMs)
                    if (id > 0) openId = id
                },
                enabled = setMs > 0 && timers.size < ClockEngine.MAX_TIMERS,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.timer_start)) }
            Section(stringResource(R.string.timer_floating)) {
                SwitchRow(stringResource(R.string.timer_floating), floating) {
                    floating = it
                    ClockStore.setFloatingEnabled(ctx, it)
                    if (it) ClockStore.setFloatingHidden(ctx, false)
                    VolumeAccessibilityService.instance?.refreshFloatingTimer()
                }
                Text(
                    stringResource(R.string.timer_floating_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The big view while a timer runs. The x leaves this view only: the timer keeps going. */
@Composable
private fun TimerRunningView(
    state: TimerState,
    now: Long,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
) {
    val remaining = state.remaining(now)
    val fraction = if (state.total > 0) (remaining.toFloat() / state.total).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(40.dp))
            Text(stringResource(R.string.timer_title), style = MaterialTheme.typography.titleMedium)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                PathIcon(Glyph.CLOSE.pathData, MaterialTheme.colorScheme.onBackground, 22.dp)
            }
        }
        Box(Modifier.align(Alignment.Center).size(320.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val rad = size.minDimension / 2f
                drawArc(
                    brush = Brush.radialGradient(listOf(GLOW, GLOW.copy(alpha = 0f)), center = center, radius = rad),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = size,
                )
            }
            Text(formatMs(remaining), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Medium)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(width = 112.dp, height = 56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                PathIcon(
                    (if (state.running) Glyph.PAUSE else Glyph.PLAY).pathData,
                    MaterialTheme.colorScheme.onBackground, 24.dp,
                )
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.timer_cancel)) }
        }
    }
}

// ───────────────────────────── Sleepy Baby ─────────────────────────────

/** The wake-up time of the first alarm of the morning, or null if there is no active alarm. */
private fun wakeFromAlarms(alarms: List<AlarmItem>): Int? {
    val on = alarms.filter { it.enabled }.map { it.hour * 60 + it.minute }.sorted()
    return on.firstOrNull { it in 180..719 } ?: on.firstOrNull()
}

private fun fixGoal(m: Int): Int = if (m == 0) 1440 else m

/**
 * The sleep ring. Drag the bedtime or the wake-up handle to move it (5-minute steps). A touch that
 * does not start on a handle is left alone, so the page can still scroll over the ring.
 */
@Composable
private fun SleepDial(
    bedMin: Int,
    wakeMin: Int,
    onBed: (Int) -> Unit,
    onWake: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.onPrimary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bedNow by rememberUpdatedState(bedMin)
    val wakeNow by rememberUpdatedState(wakeMin)
    val onBedNow by rememberUpdatedState(onBed)
    val onWakeNow by rememberUpdatedState(onWake)
    val onDoneNow by rememberUpdatedState(onDone)
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val r = minOf(w, h) / 2f
                val ringR = r - r * 0.2f / 2f
                val cx = w / 2f
                val cy = h / 2f
                fun handle(m: Int): Offset {
                    val a = Math.toRadians(m / 1440.0 * 360.0 - 90.0)
                    return Offset(cx + ringR * cos(a).toFloat(), cy + ringR * sin(a).toFloat())
                }
                val dBed = (down.position - handle(bedNow)).getDistance()
                val dWake = (down.position - handle(wakeNow)).getDistance()
                if (minOf(dBed, dWake) > 36.dp.toPx()) return@awaitEachGesture
                val moveBed = dBed <= dWake
                down.consume()
                drag(down.id) { change ->
                    change.consume()
                    var deg = Math.toDegrees(
                        atan2((change.position.x - cx).toDouble(), -(change.position.y - cy).toDouble()),
                    )
                    if (deg < 0) deg += 360.0
                    val m = mod((deg / 360.0 * 288.0).roundToInt() * 5, 1440)
                    if (moveBed) onBedNow(m) else onWakeNow(m)
                }
                onDoneNow()
            }
        },
    ) {
        val r = size.minDimension / 2f
        val c = center
        val stroke = r * 0.2f
        val ringR = r - stroke / 2f
        drawCircle(track, ringR, c, style = Stroke(stroke))
        val start = bedMin / 1440f * 360f - 90f
        val sweep = mod(wakeMin - bedMin, 1440) / 1440f * 360f
        drawArc(
            accent, start, sweep, false,
            topLeft = Offset(c.x - ringR, c.y - ringR),
            size = Size(ringR * 2f, ringR * 2f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        for (m in listOf(bedMin, wakeMin)) {
            val a = Math.toRadians(m / 1440.0 * 360.0 - 90.0)
            drawCircle(
                dot, stroke * 0.26f,
                Offset(c.x + ringR * cos(a).toFloat(), c.y + ringR * sin(a).toFloat()),
            )
        }
        val labelR = r - stroke - 20.dp.toPx()
        for (h in 0 until 24 step 2) {
            val a = Math.toRadians(h * 15.0 - 90.0)
            val layout = measurer.measure(h.toString(), TextStyle(fontSize = 13.sp, color = labelColor))
            val x = c.x + labelR * cos(a).toFloat()
            val y = c.y + labelR * sin(a).toFloat()
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
        }
    }
}

@Composable
private fun SleepBars(list: List<SleepEntry>, goal: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    Canvas(modifier) {
        val labelH = 22.dp.toPx()
        val chartH = size.height - labelH
        val maxM = maxOf(goal * 1.3f, (list.maxOfOrNull { it.minutes } ?: 0).toFloat(), 1f)
        val slot = size.width / 7f
        val gy = chartH - goal / maxM * chartH
        drawLine(
            line.copy(alpha = 0.6f), Offset(0f, gy), Offset(size.width, gy),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
        )
        list.forEachIndexed { i, e ->
            val h = (e.minutes / maxM * chartH).coerceAtLeast(2f)
            val x = i * slot + slot * 0.2f
            drawRoundRect(
                if (e.minutes >= goal) accent else accent.copy(alpha = 0.45f),
                Offset(x, chartH - h), Size(slot * 0.6f, h), CornerRadius(8.dp.toPx()),
            )
            val layout = measurer.measure(dayFormat.format(Date(e.wake)), TextStyle(fontSize = 11.sp, color = labelColor))
            drawText(layout, topLeft = Offset(i * slot + (slot - layout.size.width) / 2f, chartH + 4.dp.toPx()))
        }
    }
}

@Composable
private fun TimeBlock(label: String, time: String, day: String, editable: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(if (editable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(time, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(day, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModePill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SleepPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    var goal by remember { mutableIntStateOf(ClockStore.sleepGoal(ctx)) }
    var manualWake by remember { mutableIntStateOf(ClockStore.sleepManualWake(ctx)) }
    var bedOverride by remember { mutableIntStateOf(ClockStore.sleepBedOverride(ctx)) }
    var entries by remember { mutableStateOf(ClockStore.sleepEntries(ctx)) }
    var mode by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableIntStateOf(0) } // 1 bed, 2 wake, 3 log bed, 4 log wake
    var logBed by remember { mutableIntStateOf(0) }
    var dismissedDay by remember { mutableIntStateOf(ClockStore.sleepPromptDismissed(ctx)) }

    val alarmWake = remember { wakeFromAlarms(ClockStore.alarms(ctx)) }
    val wakeMin = if (manualWake >= 0) manualWake else alarmWake ?: 360
    val bedMin = if (bedOverride >= 0) bedOverride else mod(wakeMin - goal, 1440)
    val planMin = fixGoal(mod(wakeMin - bedMin, 1440))
    val hasWakeSource = alarmWake != null || manualWake >= 0

    val today = dayKey(now)
    val hasToday = entries.any { dayKey(it.wake) == today }
    val wakeTodayMs = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, wakeMin / 60)
        set(Calendar.MINUTE, wakeMin % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun saveLog(bedM: Int, wakeM: Int) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, wakeM / 60)
        cal.set(Calendar.MINUTE, wakeM % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis > System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, -1)
        val wakeMs = cal.timeInMillis
        val minutes = fixGoal(mod(wakeM - bedM, 1440))
        ClockStore.addSleep(ctx, wakeMs - minutes * 60_000L, wakeMs)
        entries = ClockStore.sleepEntries(ctx)
    }

    PageScaffold(stringResource(R.string.clock_tab_sleep), onBack) { padding ->
        PageColumn(padding) {
            // ── The app asks, or asks the person to log ──
            if (!hasToday) {
                if (hasWakeSource && now >= wakeTodayMs) {
                    if (dismissedDay != today) {
                        Section(stringResource(R.string.sleep_manual_title)) {
                            Text(
                                stringResource(
                                    R.string.sleep_prompt,
                                    formatDuration(ctx, planMin),
                                    formatHm(ctx, bedMin / 60, bedMin % 60),
                                    formatHm(ctx, wakeMin / 60, wakeMin % 60),
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { saveLog(bedMin, wakeMin) }) { Text(stringResource(R.string.sleep_yes)) }
                                OutlinedButton(onClick = {
                                    logBed = bedMin
                                    dialog = 3
                                }) { Text(stringResource(R.string.sleep_edit)) }
                                TextButton(onClick = {
                                    ClockStore.setSleepPromptDismissed(ctx, today)
                                    dismissedDay = today
                                }) { Text(stringResource(R.string.sleep_later)) }
                            }
                        }
                    }
                } else if (!hasWakeSource) {
                    Section(stringResource(R.string.sleep_manual_title)) {
                        Text(stringResource(R.string.sleep_manual_text), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = {
                            logBed = bedMin
                            dialog = 3
                        }) { Text(stringResource(R.string.sleep_manual_button)) }
                    }
                }
            }

            // ── The plan: when to sleep and wake ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val crossesMidnight = bedMin > wakeMin
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TimeBlock(
                        stringResource(R.string.sleep_bed),
                        formatHm(ctx, bedMin / 60, bedMin % 60),
                        stringResource(if (crossesMidnight) R.string.sleep_yesterday else R.string.sleep_today),
                        mode == 1,
                    ) { dialog = 1 }
                    TimeBlock(
                        stringResource(R.string.sleep_wake),
                        formatHm(ctx, wakeMin / 60, wakeMin % 60),
                        stringResource(R.string.sleep_today),
                        mode == 1,
                    ) { dialog = 2 }
                }
                Spacer(Modifier.height(12.dp))
                SleepDial(
                    bedMin, wakeMin,
                    onBed = { bedOverride = it },
                    onWake = {
                        // Like the time picker: moving the wake-up keeps the bedtime where it is.
                        if (bedOverride < 0) bedOverride = bedMin
                        manualWake = it
                    },
                    onDone = {
                        ClockStore.setSleepBedOverride(ctx, bedOverride)
                        ClockStore.setSleepManualWake(ctx, manualWake)
                    },
                    modifier = Modifier.size(250.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    formatDuration(ctx, planMin),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (planMin >= goal) stringResource(R.string.sleep_reach)
                    else stringResource(R.string.sleep_short, formatDuration(ctx, goal - planMin)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModePill(stringResource(R.string.sleep_mode_hours), mode == 0) { mode = 0 }
                    ModePill(stringResource(R.string.sleep_mode_times), mode == 1) { mode = 1 }
                }
                if (mode == 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallPill("−") {
                            goal = (goal - 30).coerceIn(240, 720)
                            ClockStore.setSleepGoal(ctx, goal)
                            bedOverride = -1
                            ClockStore.setSleepBedOverride(ctx, -1)
                        }
                        Text(
                            stringResource(R.string.sleep_goal) + ": " + formatDuration(ctx, goal),
                            Modifier.padding(horizontal = 14.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        SmallPill("+") {
                            goal = (goal + 30).coerceIn(240, 720)
                            ClockStore.setSleepGoal(ctx, goal)
                            bedOverride = -1
                            ClockStore.setSleepBedOverride(ctx, -1)
                        }
                    }
                }
                if (manualWake >= 0 && alarmWake != null) {
                    TextButton(onClick = {
                        manualWake = -1
                        ClockStore.setSleepManualWake(ctx, -1)
                    }) { Text(stringResource(R.string.sleep_use_alarm)) }
                }
            }

            // ── The report ──
            Section(stringResource(R.string.sleep_report)) {
                val week = entries.takeLast(7)
                if (week.isEmpty()) {
                    Text(stringResource(R.string.sleep_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SleepBars(week, goal, Modifier.fillMaxWidth().height(150.dp))
                    val avg = week.map { it.minutes }.average().roundToInt()
                    val onGoal = week.count { it.minutes >= goal }
                    val debt = week.sumOf { (goal - it.minutes).coerceAtLeast(0) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat(stringResource(R.string.sleep_avg), formatDuration(ctx, avg))
                        Stat(stringResource(R.string.sleep_nights_goal), "$onGoal/${week.size}")
                        Stat(stringResource(R.string.sleep_debt), formatDuration(ctx, debt))
                    }
                }
                if (!hasToday) {
                    OutlinedButton(onClick = {
                        logBed = bedMin
                        dialog = 3
                    }) { Text(stringResource(R.string.sleep_manual_button)) }
                }
            }

            // ── Suggestions ──
            Section(stringResource(R.string.sleep_tips)) {
                Tip(
                    stringResource(
                        R.string.sleep_tip_bed,
                        formatHm(ctx, wakeMin / 60, wakeMin % 60),
                        formatDuration(ctx, planMin),
                        formatHm(ctx, bedMin / 60, bedMin % 60),
                    ),
                )
                val week = entries.takeLast(7)
                if (week.size >= 2) {
                    val avg = week.map { it.minutes }.average().roundToInt()
                    if (avg < goal - 15) {
                        val earlier = (((goal - avg) / 5f).roundToInt() * 5).coerceIn(15, 120)
                        Tip(stringResource(R.string.sleep_tip_short, formatDuration(ctx, avg), formatDuration(ctx, goal - avg), earlier))
                    } else {
                        Tip(stringResource(R.string.sleep_tip_good))
                    }
                    val beds = week.map {
                        val c = Calendar.getInstance()
                        c.timeInMillis = it.bed
                        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                        if (m < 720) m + 1440 else m
                    }
                    val spread = (beds.maxOrNull() ?: 0) - (beds.minOrNull() ?: 0)
                    if (spread > 60) Tip(stringResource(R.string.sleep_tip_irregular, spread))
                }
            }
        }
    }

    when (dialog) {
        1 -> TimeDialog(
            stringResource(R.string.sleep_bed), bedMin,
            onConfirm = { v ->
                bedOverride = v
                ClockStore.setSleepBedOverride(ctx, v)
                dialog = 0
            },
            onDismiss = { dialog = 0 },
        )
        2 -> TimeDialog(
            stringResource(R.string.sleep_wake), wakeMin,
            onConfirm = { v ->
                bedOverride = bedMin
                ClockStore.setSleepBedOverride(ctx, bedMin)
                manualWake = v
                ClockStore.setSleepManualWake(ctx, v)
                dialog = 0
            },
            onDismiss = { dialog = 0 },
        )
        3 -> TimeDialog(
            stringResource(R.string.sleep_pick_bed), logBed,
            onConfirm = { v ->
                logBed = v
                dialog = 4
            },
            onDismiss = { dialog = 0 },
        )
        4 -> TimeDialog(
            stringResource(R.string.sleep_pick_wake), wakeMin,
            onConfirm = { v ->
                saveLog(logBed, v)
                dialog = 0
            },
            onDismiss = { dialog = 0 },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tip(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 8.dp, end = 12.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
