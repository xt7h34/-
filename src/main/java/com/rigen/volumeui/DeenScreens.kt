package com.rigen.volumeui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Prayer and dhikr: azkar with a counter, a tasbeeh, the Qibla, and a log of the prayers you prayed.
 * Everything here is kept on this phone.
 */

object DeenStore {
    private const val DAY_MS = 86_400_000L
    const val ALL_PRAYERS = 31 // five bits, one per prayer

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("kanade_deen", Context.MODE_PRIVATE)

    fun list(kind: Int): List<Dhikr> = if (kind == 0) AzkarData.MORNING else AzkarData.EVENING

    /** How many times each dhikr of [kind] was said today. Starts from zero on a new day. */
    fun azkarDone(c: Context, kind: Int): IntArray {
        val size = list(kind).size
        val out = IntArray(size)
        val raw = sp(c).getString("azk$kind", null) ?: return out
        if (raw.substringBefore(':').toIntOrNull() != dayKey(System.currentTimeMillis())) return out
        raw.substringAfter(':').split(',').forEachIndexed { i, v -> if (i < size) out[i] = v.toIntOrNull() ?: 0 }
        return out
    }

    private fun saveAzkar(c: Context, kind: Int, a: IntArray) {
        sp(c).edit().putString("azk$kind", dayKey(System.currentTimeMillis()).toString() + ":" + a.joinToString(",")).apply()
    }

    fun azkarTap(c: Context, kind: Int, idx: Int) {
        val l = list(kind)
        val a = azkarDone(c, kind)
        if (idx in l.indices && a[idx] < l[idx].count) a[idx]++
        saveAzkar(c, kind, a)
    }

    fun azkarReset(c: Context, kind: Int) {
        sp(c).edit().remove("azk$kind").apply()
    }

    /** Dhikr finished today, out of all of them. */
    fun azkarProgress(c: Context, kind: Int): Pair<Int, Int> {
        val l = list(kind)
        val a = azkarDone(c, kind)
        return l.indices.count { a[it] >= l[it].count } to l.size
    }

    // ── Tasbeeh ──

    /** [current round, today's total, target (0 = no target)]. */
    fun tasbeeh(c: Context): IntArray {
        val p = sp(c)
        val total = if (p.getInt("tb_day", 0) == dayKey(System.currentTimeMillis())) p.getInt("tb_total", 0) else 0
        return intArrayOf(p.getInt("tb_cur", 0), total, p.getInt("tb_target", 33))
    }

    fun tasbeehSetTarget(c: Context, target: Int) {
        sp(c).edit().putInt("tb_target", target).putInt("tb_cur", 0).apply()
    }

    fun tasbeehReset(c: Context) {
        sp(c).edit().putInt("tb_cur", 0).apply()
    }

    /** One count. Returns true when it completes a round of the target. */
    fun tasbeehTap(c: Context): Boolean {
        val p = sp(c)
        val today = dayKey(System.currentTimeMillis())
        val total = (if (p.getInt("tb_day", 0) == today) p.getInt("tb_total", 0) else 0) + 1
        val target = p.getInt("tb_target", 33)
        var cur = p.getInt("tb_cur", 0) + 1
        val round = target > 0 && cur >= target
        if (round) cur = 0
        p.edit().putInt("tb_day", today).putInt("tb_total", total).putInt("tb_cur", cur).apply()
        return round
    }

    // ── Prayers prayed ──

    private fun log(c: Context): JSONObject = try {
        JSONObject(sp(c).getString("plog", "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    fun prayerMask(c: Context, day: Int): Int = log(c).optInt(day.toString(), 0)

    fun prayerToggle(c: Context, day: Int, bit: Int) {
        val o = log(c)
        val m = o.optInt(day.toString(), 0) xor (1 shl bit)
        if (m == 0) o.remove(day.toString()) else o.put(day.toString(), m)
        // Keep the last 120 days.
        val keys = o.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().toList()
        if (keys.size > 120) keys.take(keys.size - 120).forEach { o.remove(it.toString()) }
        sp(c).edit().putString("plog", o.toString()).apply()
    }

    /** Days in a row with all five prayed. Today counts once it is complete. */
    fun prayerStreak(c: Context, now: Long): Int {
        var t = now
        if (prayerMask(c, dayKey(t)) != ALL_PRAYERS) t -= DAY_MS
        var n = 0
        while (n < 400 && prayerMask(c, dayKey(t)) == ALL_PRAYERS) {
            n++
            t -= DAY_MS
        }
        return n
    }

    fun prayerCountToday(c: Context): Int = Integer.bitCount(prayerMask(c, dayKey(System.currentTimeMillis())))
}

private val PRAYER_NAMES = listOf(R.string.deen_p_fajr, R.string.deen_p_dhuhr, R.string.deen_p_asr, R.string.deen_p_maghrib, R.string.deen_p_isha)

// ───────────────────────── Hub ─────────────────────────

@Composable
fun DeenHubPage(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val morning = DeenStore.azkarProgress(ctx, 0)
    val evening = DeenStore.azkarProgress(ctx, 1)
    PageScaffold(stringResource(R.string.deen_title), onBack) { padding ->
        PageColumn(padding) {
            SettingsGroup {
                SettingsItem(
                    Glyph.DEEN, ICON_GREEN,
                    stringResource(R.string.azkar_title),
                    stringResource(R.string.azkar_summary, morning.first, morning.second, evening.first, evening.second),
                ) { onOpen(PAGE_AZKAR) }
                ItemDivider()
                SettingsItem(
                    Glyph.GOALS, ICON_ORANGE,
                    stringResource(R.string.tasbeeh_title), stringResource(R.string.tasbeeh_summary),
                ) { onOpen(PAGE_TASBEEH) }
                ItemDivider()
                SettingsItem(
                    Glyph.ACCESS, ICON_BLUE,
                    stringResource(R.string.qibla_title), stringResource(R.string.qibla_summary),
                ) { onOpen(PAGE_QIBLA) }
                ItemDivider()
                SettingsItem(
                    Glyph.CLOCK, ICON_PURPLE,
                    stringResource(R.string.tracker_title),
                    stringResource(R.string.tracker_summary, DeenStore.prayerCountToday(ctx)),
                ) { onOpen(PAGE_TRACKER) }
            }
        }
    }
}

// ───────────────────────── Azkar ─────────────────────────

@Composable
fun AzkarPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var kind by rememberSaveable { mutableIntStateOf(if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 15) 1 else 0) }
    var tick by remember { mutableIntStateOf(0) }
    val items = DeenStore.list(kind)
    val done = remember(tick, kind) { DeenStore.azkarDone(ctx, kind) }
    val finished = items.indices.count { done[it] >= items[it].count }

    PageScaffold(stringResource(R.string.azkar_title), onBack) { padding ->
        PageColumn(padding) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = kind == 0, onClick = { kind = 0 }, label = { Text(stringResource(R.string.azkar_morning)) })
                FilterChip(selected = kind == 1, onClick = { kind = 1 }, label = { Text(stringResource(R.string.azkar_evening)) })
            }
            LinearProgressIndicator(progress = { finished.toFloat() / items.size }, modifier = Modifier.fillMaxWidth())
            Text(
                if (finished == items.size) stringResource(R.string.azkar_done) else stringResource(R.string.azkar_tap) + "  ($finished/${items.size})",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.forEachIndexed { i, d ->
                val left = d.count - done[i]
                val complete = left <= 0
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !complete) {
                        DeenStore.azkarTap(ctx, kind, i)
                        haptic.performHapticFeedback(if (left == 1) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                        tick++
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (complete) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(d.text, fontSize = 19.sp, lineHeight = 34.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        Text(
                            if (complete) "\u2713" else "$left / ${d.count}",
                            fontWeight = FontWeight.Bold,
                            color = if (complete) ICON_GREEN else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            OutlinedButton(onClick = { DeenStore.azkarReset(ctx, kind); tick++ }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.azkar_reset))
            }
        }
    }
}

// ───────────────────────── Tasbeeh ─────────────────────────

@Composable
fun TasbeehPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var state by remember { mutableStateOf(DeenStore.tasbeeh(ctx)) }
    var rounds by remember { mutableIntStateOf(0) }
    PageScaffold(stringResource(R.string.tasbeeh_title), onBack) { padding ->
        PageColumn(padding) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in listOf(33, 100, 0)) {
                    FilterChip(
                        selected = state[2] == t,
                        onClick = { DeenStore.tasbeehSetTarget(ctx, t); state = DeenStore.tasbeeh(ctx) },
                        label = { Text(if (t == 0) stringResource(R.string.tasbeeh_free) else t.toString()) },
                    )
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable {
                            val round = DeenStore.tasbeehTap(ctx)
                            if (round) rounds++
                            haptic.performHapticFeedback(if (round) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                            state = DeenStore.tasbeeh(ctx)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state[0].toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold)
                        if (state[2] > 0) Text("/ ${state[2]}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                stringResource(R.string.tasbeeh_today, state[1]) + if (rounds > 0) "  \u00B7  \u2713 $rounds" else "",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = { DeenStore.tasbeehReset(ctx); state = DeenStore.tasbeeh(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tasbeeh_reset))
            }
        }
    }
}

// ───────────────────────── Qibla ─────────────────────────

private const val KAABA_LAT = 21.4225
private const val KAABA_LON = 39.8262

/** The compass direction, in degrees from north, from here to the Kaaba. */
private fun qiblaBearing(lat: Double, lon: Double): Double {
    val p1 = Math.toRadians(lat)
    val p2 = Math.toRadians(KAABA_LAT)
    val dl = Math.toRadians(KAABA_LON - lon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

@SuppressLint("MissingPermission")
private fun lastLocation(c: Context): Location? = try {
    val lm = c.getSystemService(LocationManager::class.java)
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm?.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
} catch (_: Exception) {
    null
}

@Composable
fun QiblaPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val loc = remember(granted) { if (granted) lastLocation(ctx) else null }
    var heading by remember { mutableFloatStateOf(0f) }
    val sm = remember { ctx.getSystemService(SensorManager::class.java) }
    val sensor = remember { sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

    DisposableEffect(sensor) {
        val listener = object : SensorEventListener {
            val m = FloatArray(9)
            val o = FloatArray(3)
            var sx = 0f
            var sy = 0f
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(m, e.values)
                SensorManager.getOrientation(m, o)
                // Smooth on the circle, so crossing north does not jump.
                sx += (cos(o[0]) - sx) * 0.15f
                sy += (sin(o[0]) - sy) * 0.15f
                heading = ((Math.toDegrees(atan2(sy, sx).toDouble()) + 360.0) % 360.0).toFloat()
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }

    PageScaffold(stringResource(R.string.qibla_title), onBack) { padding ->
        PageColumn(padding) {
            when {
                sensor == null -> Text(stringResource(R.string.qibla_no_sensor))
                !granted -> {
                    Text(stringResource(R.string.qibla_need_loc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.qibla_allow))
                    }
                }
                loc == null -> Text(stringResource(R.string.qibla_no_fix), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val qibla = qiblaBearing(loc.latitude, loc.longitude).toFloat()
                    val declination = try {
                        GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()).declination
                    } catch (_: Exception) {
                        0f
                    }
                    val trueHeading = heading + declination
                    val diff = ((qibla - trueHeading + 540f) % 360f) - 180f
                    val aligned = abs(diff) < 4f
                    val ring = MaterialTheme.colorScheme.outlineVariant
                    val tick = MaterialTheme.colorScheme.onSurfaceVariant
                    val accent = if (aligned) Color(0xFF34A853) else MaterialTheme.colorScheme.primary
                    Text(
                        stringResource(R.string.qibla_degrees, qibla.toInt()),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(300.dp)) {
                            val r = size.minDimension / 2f
                            val c = center
                            fun at(deg: Float, k: Float): Offset {
                                val a = Math.toRadians(deg - 90.0)
                                return Offset(c.x + cos(a).toFloat() * r * k, c.y + sin(a).toFloat() * r * k)
                            }
                            rotate(-trueHeading, pivot = c) {
                                drawCircle(ring, r * 0.95f, c, style = Stroke(width = 5.dp.toPx()))
                                for (i in 0 until 12) drawLine(tick, at(i * 30f, 0.84f), at(i * 30f, 0.93f), strokeWidth = 3.dp.toPx())
                                drawLine(Color(0xFFE5484D), at(0f, 0.6f), at(0f, 0.93f), strokeWidth = 7.dp.toPx())
                                drawCircle(accent, 20.dp.toPx(), at(qibla, 0.74f))
                                drawCircle(Color.White, 8.dp.toPx(), at(qibla, 0.74f))
                            }
                            val tri = Path().apply {
                                moveTo(c.x, c.y - r * 1.0f)
                                lineTo(c.x - 16.dp.toPx(), c.y - r * 0.86f)
                                lineTo(c.x + 16.dp.toPx(), c.y - r * 0.86f)
                                close()
                            }
                            drawPath(tri, accent)
                        }
                    }
                    Text(
                        stringResource(R.string.qibla_turn),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = if (aligned) Color(0xFF34A853) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (aligned) FontWeight.Bold else FontWeight.Normal,
                    )
                    LaunchedEffect(aligned) {
                        if (aligned) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
        }
    }
}

// ───────────────────────── Prayers prayed ─────────────────────────

@Composable
fun PrayerTrackerPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val now = remember(tick) { System.currentTimeMillis() }
    val today = dayKey(now)
    val mask = remember(tick) { DeenStore.prayerMask(ctx, today) }
    val streak = remember(tick) { DeenStore.prayerStreak(ctx, now) }
    val last14 = remember(tick) { (13 downTo 0).map { DeenStore.prayerMask(ctx, dayKey(now - it * 86_400_000L)) } }

    PageScaffold(stringResource(R.string.tracker_title), onBack) { padding ->
        PageColumn(padding) {
            Section(stringResource(R.string.tracker_today)) {
                PRAYER_NAMES.forEachIndexed { i, res ->
                    Row(
                        Modifier.fillMaxWidth().clickable { DeenStore.prayerToggle(ctx, today, i); tick++ },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(res), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Checkbox(checked = ((mask shr i) and 1) == 1, onCheckedChange = { DeenStore.prayerToggle(ctx, today, i); tick++ })
                    }
                }
            }
            Section(stringResource(R.string.tracker_streak, streak)) {
                Text(stringResource(R.string.tracker_last), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (m in last14) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        m == DeenStore.ALL_PRAYERS -> Color(0xFF34A853)
                                        m != 0 -> Color(0xFFF29900)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
