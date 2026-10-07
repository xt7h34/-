package com.rigen.volumeui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun mod(a: Int, n: Int): Int = ((a % n) + n) % n

/** Returns a function that asks for the notification permission when Android requires it. */
@Composable
internal fun rememberNotifPermission(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

internal fun formatClock(ctx: Context, ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
    val f = android.text.format.DateFormat.getTimeFormat(ctx)
    f.timeZone = tz
    return f.format(Date(ms))
}

internal fun formatHm(ctx: Context, hour: Int, minute: Int): String {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, hour)
    c.set(Calendar.MINUTE, minute)
    return formatClock(ctx, c.timeInMillis)
}

internal fun formatDuration(ctx: Context, minutes: Int): String {
    val m = abs(minutes)
    return when {
        m % 60 == 0 -> ctx.getString(R.string.dur_h, m / 60)
        m < 60 -> ctx.getString(R.string.dur_m, m)
        else -> ctx.getString(R.string.dur_hm, m / 60, m % 60)
    }
}

private fun prettyZone(id: String): String = id.substringAfterLast('/').replace('_', ' ')

// ───────────────────────────── The Clock hub ─────────────────────────────

@Composable
fun ClockHubPage(onOpen: (String) -> Unit, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.clock_title), onBack) { padding ->
        PageColumn(padding) {
            SettingsGroup {
                SettingsItem(
                    Glyph.CLOCK, ICON_BLUE,
                    stringResource(R.string.clock_tab_clock), stringResource(R.string.clock_tab_clock_sum),
                ) { onOpen(PAGE_CLOCK_TIME) }
                ItemDivider()
                SettingsItem(
                    Glyph.ALARM, ICON_ORANGE,
                    stringResource(R.string.clock_tab_alarms), stringResource(R.string.clock_tab_alarms_sum),
                ) { onOpen(PAGE_ALARMS) }
                ItemDivider()
                SettingsItem(
                    Glyph.TIMER, ICON_PINK,
                    stringResource(R.string.clock_tab_timer), stringResource(R.string.clock_tab_timer_sum),
                ) { onOpen(PAGE_TIMER) }
                ItemDivider()
                SettingsItem(
                    Glyph.SLEEP, ICON_PURPLE,
                    stringResource(R.string.clock_tab_sleep), stringResource(R.string.clock_tab_sleep_sum),
                ) { onOpen(PAGE_SLEEP) }
            }
        }
    }
}

// ───────────────────────────── World clock ─────────────────────────────

@Composable
private fun AnalogClock(now: Long, tz: TimeZone, modifier: Modifier = Modifier) {
    val cal = Calendar.getInstance(tz)
    cal.timeInMillis = now
    val hour = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
    val minute = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
    val second = cal.get(Calendar.SECOND).toFloat()
    val face = MaterialTheme.colorScheme.background
    val ring = MaterialTheme.colorScheme.outlineVariant
    val handColor = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(face, r, c)
        drawCircle(ring, r, c, style = Stroke(width = 2.dp.toPx()))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90.0)
            val cs = cos(a).toFloat()
            val sn = sin(a).toFloat()
            drawLine(
                tick.copy(alpha = 0.45f),
                Offset(c.x + cs * r * 0.86f, c.y + sn * r * 0.86f),
                Offset(c.x + cs * r * 0.93f, c.y + sn * r * 0.93f),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        fun hand(deg: Float, len: Float, w: Float, col: Color) {
            val a = Math.toRadians(deg - 90.0)
            drawLine(
                col, c,
                Offset(c.x + cos(a).toFloat() * r * len, c.y + sin(a).toFloat() * r * len),
                strokeWidth = w, cap = StrokeCap.Round,
            )
        }
        hand(hour * 30f, 0.5f, 5.dp.toPx(), handColor)
        hand(minute * 6f, 0.75f, 5.dp.toPx(), handColor)
        hand(second * 6f, 0.82f, 1.5.dp.toPx(), tick)
        drawCircle(handColor, 5.dp.toPx(), c)
    }
}

@SuppressLint("MissingPermission")
private fun lookupPlace(ctx: Context, done: (String?) -> Unit) {
    try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            } ?: continue
            val b = best
            if (b == null || l.time > b.time) best = l
        }
        val loc = best
        if (loc == null) {
            done(null)
            return
        }
        val main = Handler(Looper.getMainLooper())
        val geocoder = Geocoder(ctx, Locale.getDefault())
        fun name(a: Address?): String? {
            if (a == null) return null
            val city = a.locality ?: a.subAdminArea ?: a.adminArea
            return listOfNotNull(city, a.countryName).joinToString(", ").ifBlank { null }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(
                loc.latitude, loc.longitude, 1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        main.post { done(name(addresses.firstOrNull())) }
                    }

                    override fun onError(errorMessage: String?) {
                        main.post { done(null) }
                    }
                },
            )
        } else {
            Thread {
                val a = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                } catch (e: Exception) {
                    null
                }
                main.post { done(name(a)) }
            }.start()
        }
    } catch (e: Throwable) {
        done(null)
    }
}

private fun offsetLabel(ctx: Context, tz: TimeZone, now: Long): String {
    val diff = (tz.getOffset(now) - TimeZone.getDefault().getOffset(now)) / 60_000
    if (diff == 0) return ctx.getString(R.string.zone_same)
    return (if (diff > 0) "+" else "−") + formatDuration(ctx, diff)
}

@Composable
fun WorldClockPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    var place by remember { mutableStateOf(prettyZone(TimeZone.getDefault().id)) }
    var hasLocation by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasLocation = it }
    LaunchedEffect(hasLocation) {
        if (hasLocation) lookupPlace(ctx) { name -> if (name != null) place = name }
    }
    var zones by remember { mutableStateOf(ClockStore.zones(ctx)) }
    var picking by remember { mutableStateOf(false) }

    PageScaffold(stringResource(R.string.clock_tab_clock), onBack) { padding ->
        PageColumn(padding) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnalogClock(now, TimeZone.getDefault(), Modifier.size(230.dp))
                Spacer(Modifier.height(18.dp))
                Text(
                    formatClock(ctx, now),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(place, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!hasLocation) {
                Section(stringResource(R.string.clock_tab_clock)) {
                    Text(stringResource(R.string.loc_perm_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                        Text(stringResource(R.string.loc_perm_button))
                    }
                }
            }
            Section(stringResource(R.string.zones_title)) {
                if (zones.isEmpty()) {
                    Text(stringResource(R.string.zones_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                zones.forEach { id ->
                    val tz = TimeZone.getTimeZone(id)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(prettyZone(id), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                offsetLabel(ctx, tz, now),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(formatClock(ctx, now, tz), style = MaterialTheme.typography.titleLarge)
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    zones = zones - id
                                    ClockStore.saveZones(ctx, zones)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            PathIcon(Glyph.CLOSE.pathData, MaterialTheme.colorScheme.onSurfaceVariant, 18.dp)
                        }
                    }
                }
                OutlinedButton(onClick = { picking = true }) {
                    PathIcon(Glyph.ADD.pathData, MaterialTheme.colorScheme.primary, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.zone_add))
                }
            }
        }
    }
    if (picking) {
        ZonePickerDialog(
            onPick = { id ->
                if (id !in zones) {
                    zones = zones + id
                    ClockStore.saveZones(ctx, zones)
                }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ZonePickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember {
        TimeZone.getAvailableIDs().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
    }
    val shown = remember(query) {
        val k = query.trim().lowercase().replace(' ', '_')
        (if (k.isEmpty()) all else all.filter { it.lowercase().contains(k) }).take(60)
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.zone_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { id ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(id) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(prettyZone(id), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                id.substringBefore('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── The wheel (alarm and sleep times) ─────────────────────────────

/** Numbers on a big arc. Drag up or down to turn it. The number in the middle band is selected. */
@Composable
private fun ArcWheel(count: Int, value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val latest by rememberUpdatedState(onValue)
    var pos by remember { mutableFloatStateOf(value.toFloat()) }
    val onBg = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.primary
    val disc = MaterialTheme.colorScheme.surfaceContainer
    Canvas(
        modifier.pointerInput(count) {
            detectDragGestures(
                onDragEnd = {
                    pos = pos.roundToInt().toFloat()
                    latest(mod(pos.roundToInt(), count))
                },
                onDragCancel = { pos = pos.roundToInt().toFloat() },
            ) { change, drag ->
                change.consume()
                pos -= drag.y / (size.height / 10f)
                latest(mod(pos.roundToInt(), count))
            }
        },
    ) {
        val cy = size.height / 2f
        val radius = size.height * 0.5f
        val cx = size.width * 0.12f + radius
        drawCircle(disc, radius + 40.dp.toPx(), Offset(cx, cy))
        drawRoundRect(
            accent.copy(alpha = 0.12f),
            Offset(0f, cy - 28.dp.toPx()),
            Size(size.width, 56.dp.toPx()),
            CornerRadius(28.dp.toPx()),
        )
        val base = pos.roundToInt()
        for (k in -5..5) {
            val idx = base + k
            val rel = idx - pos
            val ang = rel * 11f
            val rad = Math.toRadians(ang.toDouble())
            val x = cx - radius * cos(rad).toFloat()
            val y = cy + radius * sin(rad).toFloat()
            val selected = abs(rel) < 0.5f
            val alpha = (1f - abs(rel) / 6f).coerceIn(0.15f, 1f)
            val layout = measurer.measure(
                String.format("%02d", mod(idx, count)),
                TextStyle(
                    fontSize = if (selected) 30.sp else 20.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = (if (selected) accent else onBg).copy(alpha = alpha),
                ),
            )
            rotate(-ang, Offset(x, y)) {
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
            }
        }
    }
}

@Composable
private fun WheelFieldRow(label: String, value: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            String.format("%02d", value),
            style = if (active) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Hour and minute: tap which one to turn, then drag the wheel. */
@Composable
internal fun TimeWheelPicker(hour: Int, minute: Int, onChange: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    var field by remember { mutableIntStateOf(0) }
    Box(modifier) {
        key(field) {
            ArcWheel(
                count = if (field == 0) 24 else 60,
                value = if (field == 0) hour else minute,
                onValue = { v -> if (field == 0) onChange(v, minute) else onChange(hour, v) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.6f),
            )
        }
        Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WheelFieldRow(stringResource(R.string.alarm_hour), hour, field == 0) { field = 0 }
            WheelFieldRow(stringResource(R.string.alarm_min), minute, field == 1) { field = 1 }
        }
    }
}

/** A small window to pick a time, used by the Sleepy Baby page. */
@Composable
internal fun TimeDialog(
    title: String,
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var h by remember { mutableIntStateOf(initialMinutes / 60) }
    var m by remember { mutableIntStateOf(initialMinutes % 60) }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                TimeWheelPicker(h, m, { nh, nm -> h = nh; m = nm }, Modifier.fillMaxWidth().height(300.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.alarm_cancel)) }
                    Button(onClick = { onConfirm(h * 60 + m) }) { Text(stringResource(R.string.alarm_save)) }
                }
            }
        }
    }
}

// ───────────────────────────── Alarms ─────────────────────────────

private val DAY_NAMES = listOf(
    R.string.day_0, R.string.day_1, R.string.day_2, R.string.day_3, R.string.day_4, R.string.day_5, R.string.day_6,
)

@Composable
fun AlarmsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var alarms by remember { mutableStateOf(ClockStore.alarms(ctx)) }
    var editing by remember { mutableStateOf<AlarmItem?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    val askNotifications = rememberNotifPermission()

    fun store(list: List<AlarmItem>) {
        alarms = list.sortedBy { it.hour * 60 + it.minute }
        ClockStore.saveAlarms(ctx, alarms)
        ClockEngine.rescheduleAll(ctx)
    }

    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        AlarmEditor(
            initial = current,
            isNew = editingNew,
            onSave = { a ->
                askNotifications()
                store(alarms.filter { it.id != a.id } + a)
                editing = null
            },
            onDelete = {
                ClockEngine.cancelAlarm(ctx, current.id)
                store(alarms.filter { it.id != current.id })
                editing = null
            },
            onClose = { editing = null },
        )
        return
    }

    if (showStyle) {
        BackHandler { showStyle = false }
        AlarmRingScreen(
            editing = true,
            title = stringResource(R.string.alarm_title),
            showSnooze = true,
            onDismiss = {},
            onSnooze = {},
            onDone = { showStyle = false },
        )
        return
    }
    if (showMenu) {
        AlarmSettingsDialog(
            onClose = { showMenu = false },
            onCustomize = {
                showMenu = false
                showStyle = true
            },
        )
    }

    PageScaffold(stringResource(R.string.clock_tab_alarms), onBack) { padding ->
        PageColumn(padding) {
            FullScreenNotice()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        editingNew = true
                        editing = AlarmItem(ClockStore.nextAlarmId(ctx), 8, 0, "", true, 0)
                    },
                ) {
                    PathIcon(Glyph.ADD.pathData, MaterialTheme.colorScheme.onPrimary, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.alarm_add))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { showMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    PathIcon(Glyph.MORE.pathData, MaterialTheme.colorScheme.onBackground, 22.dp)
                }
            }
            if (alarms.isEmpty()) {
                Text(stringResource(R.string.alarm_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (alarms.isNotEmpty()) {
                SettingsGroup {
                    alarms.forEachIndexed { i, a ->
                        if (i > 0) ItemDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingNew = false
                                    editing = a
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    formatHm(ctx, a.hour, a.minute),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (a.enabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val days = if (a.days == 0) ctx.getString(R.string.alarm_once)
                                else (0..6).filter { ((a.days shr it) and 1) == 1 }
                                    .joinToString(" ") { ctx.getString(DAY_NAMES[it]) }
                                Text(
                                    listOf(a.label, days).filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            KanadeSwitch(a.enabled) { on ->
                                askNotifications()
                                store(alarms.map { if (it.id == a.id) it.copy(enabled = on) else it })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun AlarmEditor(
    initial: AlarmItem,
    isNew: Boolean,
    onSave: (AlarmItem) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var h by remember { mutableIntStateOf(initial.hour) }
    var m by remember { mutableIntStateOf(initial.minute) }
    var label by remember { mutableStateOf(initial.label) }
    var days by remember { mutableIntStateOf(initial.days) }
    var tone by remember { mutableStateOf(initial.ringtone) }
    val ctx = LocalContext.current
    val pickTone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        if (uri != null) tone = uri.toString()
    }
    val toneName = remember(tone) {
        if (tone.isBlank()) "" else runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(tone)).getTitle(ctx) }.getOrDefault("")
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.alarm_title), style = MaterialTheme.typography.headlineMedium)
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                PathIcon(Glyph.CLOSE.pathData, MaterialTheme.colorScheme.onBackground, 22.dp)
            }
        }
        TimeWheelPicker(h, m, { nh, nm -> h = nh; m = nm }, Modifier.fillMaxWidth().weight(1f))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            singleLine = true,
            label = { Text(stringResource(R.string.alarm_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.alarm_sound), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = tone.isBlank(),
                onClick = { tone = "" },
                label = { Text(stringResource(R.string.alarm_sound_kanade)) },
            )
            FilterChip(
                selected = tone.isNotBlank(),
                onClick = {
                    val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    if (tone.isNotBlank()) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(tone))
                    pickTone.launch(i)
                },
                label = { Text(if (toneName.isNotBlank()) toneName else stringResource(R.string.alarm_sound_device)) },
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.alarm_repeat), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (d in 0..6) {
                val on = ((days shr d) and 1) == 1
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { days = days xor (1 shl d) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(DAY_NAMES[d]),
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isNew) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.alarm_delete))
                }
            }
            Button(
                onClick = {
                    onSave(initial.copy(hour = h, minute = m, label = label.trim(), enabled = true, days = days, ringtone = tone))
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.alarm_save)) }
        }
    }
}

/** The three dots next to "Add alarm": how alarms ring, and the way to stop them. */
@Composable
private fun AlarmSettingsDialog(onClose: () -> Unit, onCustomize: () -> Unit) {
    val ctx = LocalContext.current
    var inDnd by remember { mutableStateOf(ClockStore.ringInDnd(ctx)) }
    var inSilent by remember { mutableStateOf(ClockStore.ringInSilent(ctx)) }
    var inVibrate by remember { mutableStateOf(ClockStore.ringInVibrate(ctx)) }
    var dismissMode by remember { mutableIntStateOf(ClockStore.ringDismiss(ctx)) }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.alarm_menu_title), style = MaterialTheme.typography.titleLarge)
            FullScreenNotice()
            Text(stringResource(R.string.alarm_ring_in), style = MaterialTheme.typography.labelLarge)
            SwitchRow(stringResource(R.string.alarm_in_dnd), inDnd) {
                inDnd = it
                ClockStore.setRingInDnd(ctx, it)
            }
            SwitchRow(stringResource(R.string.alarm_in_silent), inSilent) {
                inSilent = it
                ClockStore.setRingInSilent(ctx, it)
            }
            SwitchRow(stringResource(R.string.alarm_in_vibrate), inVibrate) {
                inVibrate = it
                ClockStore.setRingInVibrate(ctx, it)
            }
            Text(
                stringResource(R.string.alarm_ring_in_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.alarm_dismiss_method), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DISMISS_DRAG to R.string.dismiss_drag,
                    DISMISS_TAP to R.string.dismiss_tap,
                    DISMISS_HOLD to R.string.dismiss_hold,
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = dismissMode == mode,
                        onClick = {
                            dismissMode = mode
                            ClockStore.setRingDismiss(ctx, mode)
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            Button(onClick = onCustomize, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.alarm_customize))
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.ring_done))
            }
        }
    }
}

/** Android 14+ keeps full-screen alarms off until the user allows them. Tells them how. */
@Composable
private fun FullScreenNotice() {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(ClockEngine.canFullScreen(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            ok = ClockEngine.canFullScreen(ctx)
            delay(1000)
        }
    }
    if (!ok) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.fsi_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(R.string.fsi_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(onClick = { ClockEngine.openFullScreenSettings(ctx) }) {
                    Text(stringResource(R.string.fsi_allow))
                }
            }
        }
    }
}
