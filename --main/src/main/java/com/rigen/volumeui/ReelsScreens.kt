package com.rigen.volumeui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// ═════════════════════════════ The Reels page ═════════════════════════════

@Composable
fun ReelsPage(serviceEnabled: Boolean, onOpen: (String) -> Unit, goBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = ReelsStore.tick
    val today = remember(t) { ReelsStore.today(ctx) }
    val enabled = remember(t) { ReelsStore.enabled(ctx) }
    val overlay = remember(t) { ReelsStore.overlay(ctx) }
    val appsOn = remember(t) { ReelsStore.apps(ctx) }
    val limit = remember(t) { ReelsStore.limit(ctx) }
    val sec = remember(t) { ReelsStore.secPerReel(ctx) }
    val week = remember(t) { ReelsStore.lastDays(ctx, 7) }
    var wrapped by remember { mutableIntStateOf(0) } // 0 = closed, 1 = today, 2 = this week

    if (wrapped != 0) {
        BackHandler { wrapped = 0 }
        val data = if (wrapped == 1) {
            ReelsWrappedData(listOf(today), ReelsStore.lastDays(ctx, 1, 1), sec, limit)
        } else {
            ReelsWrappedData(week, ReelsStore.lastDays(ctx, 7, 7), sec, limit)
        }
        ReelsWrapped(data) { wrapped = 0 }
        return
    }

    val level = reelsLevel(today.total, limit)
    val levelColor = reelsLevelColor(level)
    val mood = when (level) {
        0 -> R.string.reels_mood_0
        1 -> R.string.reels_mood_1
        2 -> R.string.reels_mood_2
        else -> R.string.reels_mood_3
    }

    PageScaffold(stringResource(R.string.reels_title), goBack) { padding ->
        PageColumn(padding) {
            if (!serviceEnabled) {
                SettingsGroup {
                    SettingsItem(
                        glyph = Glyph.ACCESS,
                        iconColor = ICON_ORANGE,
                        title = stringResource(R.string.service_off),
                        summary = stringResource(R.string.reels_service_needed),
                    ) {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            }

            // Today at a glance.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ReelsBrainFace(level, Modifier.size(120.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        today.total.toString(),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Black,
                        color = levelColor,
                    )
                    Text(
                        stringResource(R.string.reels_of_limit, limit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(mood), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val frac = (today.total.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(frac)
                                .background(levelColor),
                        )
                    }
                }
            }

            Section(stringResource(R.string.reels_title)) {
                SwitchRow(stringResource(R.string.reels_enable), enabled) { ReelsStore.setEnabled(ctx, it) }
                SwitchRow(stringResource(R.string.reels_overlay), overlay) { ReelsStore.setOverlay(ctx, it) }
            }

            Section(stringResource(R.string.reels_apps)) {
                REELS_APPS.forEach { app ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ReelsAppIcon(app, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label)
                            Text(
                                stringResource(R.string.reels_today_count, today.counts[app.key] ?: 0),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        KanadeSwitch(appsOn.contains(app.key)) { ReelsStore.setApp(ctx, app.key, it) }
                    }
                }
            }

            Section(stringResource(R.string.reels_week)) {
                val maxV = (week.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(112.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    week.forEach { d ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Text(d.total.toString(), fontSize = 10.sp)
                            Box(
                                Modifier
                                    .width(22.dp)
                                    .height((d.total.toFloat() / maxV.toFloat() * 64f + 3f).dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(reelsLevelColor(reelsLevel(d.total, limit))),
                            )
                            Text(reelsDayLabel(d.key), fontSize = 10.sp)
                        }
                    }
                }
            }

            Section(stringResource(R.string.reels_settings)) {
                ReelsStepper(
                    stringResource(R.string.reels_limit), limit.toString(),
                    onMinus = { ReelsStore.setLimit(ctx, limit - 25) },
                    onPlus = { ReelsStore.setLimit(ctx, limit + 25) },
                )
                ReelsStepper(
                    stringResource(R.string.reels_sec), sec.toString(),
                    onMinus = { ReelsStore.setSecPerReel(ctx, sec - 5) },
                    onPlus = { ReelsStore.setSecPerReel(ctx, sec + 5) },
                )
            }

            SettingsGroup {
                SettingsItem(
                    Glyph.BRAIN, ICON_PINK,
                    stringResource(R.string.reels_wrapped_day),
                ) { wrapped = 1 }
                ItemDivider()
                SettingsItem(
                    Glyph.BRAIN, ICON_PURPLE,
                    stringResource(R.string.reels_wrapped_week),
                ) { wrapped = 2 }
                ItemDivider()
                SettingsItem(
                    Glyph.TUNE, ICON_BLUE,
                    stringResource(R.string.reels_diag_title),
                ) { onOpen(PAGE_REELS_DIAG) }
            }
        }
    }
}

@Composable
private fun ReelsStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        ReelsStepButton("−", onMinus)
        Text(value, Modifier.width(56.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        ReelsStepButton("+", onPlus)
    }
}

@Composable
private fun ReelsStepButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 20.sp)
    }
}

private fun reelsDayLabel(key: String): String = try {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)
    SimpleDateFormat("EEE", Locale.getDefault()).format(d!!)
} catch (e: Exception) {
    key.takeLast(2)
}

/** The real icon of the app when it is installed, otherwise a colored circle with its first letter. */
@Composable
private fun ReelsAppIcon(app: ReelsApp, size: Dp) {
    val ctx = LocalContext.current
    val bmp: ImageBitmap? = remember(app.key) {
        var found: ImageBitmap? = null
        for (p in app.packages) {
            try {
                found = ctx.packageManager.getApplicationIcon(p).toBitmap(96, 96).asImageBitmap()
                break
            } catch (e: Exception) {
                // Not installed (or not visible): try the next package name.
            }
        }
        found
    }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(app.color)),
            contentAlignment = Alignment.Center,
        ) {
            Text(app.label.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

// ═════════════════════════════ Calibration page ═════════════════════════════

@Composable
fun ReelsDiagPage(goBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = ReelsStore.tick
    val lt = ReelsEngine.logTick
    val on = remember(t) { ReelsStore.diagOn(ctx) }
    val lines = remember(lt) { ReelsEngine.log.take(60).toList() }

    PageScaffold(stringResource(R.string.reels_diag_title), goBack) { padding ->
        PageColumn(padding) {
            Section(stringResource(R.string.reels_diag_title)) {
                SwitchRow(stringResource(R.string.reels_diag_record), on) { ReelsStore.setDiagOn(ctx, it) }
                Text(
                    stringResource(R.string.reels_diag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = { ReelsEngine.clearLog() }) {
                        Text(stringResource(R.string.reels_diag_clear))
                    }
                    TextButton(onClick = { ReelsStore.clearAll(ctx) }) {
                        Text(stringResource(R.string.reels_reset))
                    }
                }
            }
            Section(stringResource(R.string.reels_diag_log)) {
                if (lines.isEmpty()) {
                    Text(
                        stringResource(R.string.reels_diag_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    lines.forEach {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ═════════════════════════════ Reels Wrapped (the story) ═════════════════════════════

/** [days] are the days being shown; [prev] is the same number of days right before them. */
class ReelsWrappedData(
    val days: List<ReelsDay>,
    val prev: List<ReelsDay>,
    val secPerReel: Int,
    val limit: Int,
)

private class RwStats(d: ReelsWrappedData) {
    val total: Int = d.days.sumOf { it.total }
    val prevTotal: Int = d.prev.sumOf { it.total }
    val byApp: List<Pair<ReelsApp, Int>> = REELS_APPS
        .map { a -> a to d.days.sumOf { it.counts[a.key] ?: 0 } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
    val hours: IntArray = IntArray(24).also { arr ->
        d.days.forEach { day -> for (i in 0 until 24) arr[i] = arr[i] + day.hours[i] }
    }
    val opens: Int = d.days.sumOf { it.opens }
    val longest: Int = d.days.maxOfOrNull { it.longest } ?: 0
    val minutes: Int = (total.toLong() * d.secPerReel / 60L).toInt()
    val peak: Int = hours.indices.maxByOrNull { hours[it] } ?: 0
    val limit: Int = d.limit
    val level: Int = reelsLevel(if (d.days.size > 1) total / d.days.size else total, d.limit)
}

private const val RW_EMPTY = 0
private const val RW_TOTAL = 1
private const val RW_WHERE = 2
private const val RW_PULLED = 3
private const val RW_TIME = 4
private const val RW_PEAK = 5
private const val RW_LONGEST = 6
private const val RW_VS = 7
private const val RW_VERDICT = 8

private val RW_CREAM = Color(0xFFFAE8BD)
private val RW_RED = Color(0xFFE85555)

private fun rwKinds(s: RwStats): List<Int> =
    if (s.total == 0) {
        listOf(RW_EMPTY)
    } else {
        listOf(RW_TOTAL, RW_WHERE, RW_PULLED, RW_TIME, RW_PEAK, RW_LONGEST, RW_VS, RW_VERDICT)
    }

private fun rwBrush(kind: Int): Brush {
    val colors = when (kind) {
        RW_TOTAL -> 0xFF4A2C30 to 0xFF1E1114
        RW_WHERE -> 0xFF4338B8 to 0xFF17124A
        RW_PULLED -> 0xFF3A7D6E to 0xFF10261F
        RW_TIME -> 0xFF8A4B1F to 0xFF2B1608
        RW_PEAK -> 0xFF1E5E8C to 0xFF0B2236
        RW_LONGEST -> 0xFF7A2E6B to 0xFF260F22
        RW_VS -> 0xFF2F5D3A to 0xFF0F2214
        RW_VERDICT -> 0xFF5A1F24 to 0xFF1D0B0D
        else -> 0xFF303040 to 0xFF14141C
    }
    return Brush.verticalGradient(listOf(Color(colors.first), Color(colors.second)))
}

private fun rwHourLabel(h: Int): String {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, h)
    cal.set(Calendar.MINUTE, 0)
    return SimpleDateFormat("h a", Locale.getDefault()).format(cal.time)
}

@Composable
fun ReelsWrapped(data: ReelsWrappedData, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val stats = remember(data) { RwStats(data) }
    val kinds = remember(stats) { rwKinds(stats) }
    var index by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    val last = kinds.size - 1
    val dateText = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date()) }
    val shareText = stringResource(R.string.rw_share_text, stats.total, stats.minutes)

    // Each slide plays for a few seconds, then the story moves on by itself.
    LaunchedEffect(index) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(5000, easing = LinearEasing))
        if (index < last) index += 1
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(kinds) {
                detectTapGestures { pos ->
                    if (pos.x < size.width / 3f) {
                        if (index > 0) index -= 1
                    } else if (index < last) {
                        index += 1
                    }
                }
            },
    ) {
        Crossfade(targetState = index, animationSpec = tween(300)) { i ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(rwBrush(kinds[i])),
            ) {
                RwSlide(kinds[i], stats)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                kinds.indices.forEach { i ->
                    val f = if (i < index) 1f else if (i == index) progress.value else 0f
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x33FFFFFF)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(f)
                                .background(Color.White),
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.rw_title),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(dateText, color = Color(0xB3FFFFFF), fontSize = 14.sp)
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    PathIcon(Glyph.CLOSE.pathData, Color.White, 22.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(50))
                        .clickable {
                            try {
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, shareText)
                                ctx.startActivity(Intent.createChooser(send, null))
                            } catch (e: Exception) {
                                // No app can share text: nothing to do.
                            }
                        }
                        .padding(horizontal = 32.dp, vertical = 14.dp),
                ) {
                    Text(
                        stringResource(R.string.rw_share),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RwLayout(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            color = Color(0xB3FFFFFF),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun RwBig(text: String, color: Color = RW_CREAM, size: Int = 104) {
    Text(
        text,
        color = color,
        fontSize = size.sp,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RwCaption(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RwSlide(kind: Int, s: RwStats) {
    when (kind) {
        RW_EMPTY -> RwLayout(stringResource(R.string.rw_title)) {
            ReelsBrainFace(0, Modifier.size(180.dp))
            Spacer(Modifier.height(16.dp))
            RwCaption(stringResource(R.string.rw_empty))
        }

        RW_TOTAL -> RwLayout(stringResource(R.string.rw_label_total)) {
            ReelsBrainFace(s.level, Modifier.size(200.dp))
            Spacer(Modifier.height(12.dp))
            RwBig(s.total.toString(), RW_RED, 96)
            RwCaption(stringResource(R.string.rw_videos))
        }

        RW_WHERE -> RwLayout(stringResource(R.string.rw_label_where)) {
            val top = s.byApp.first()
            ReelsAppIcon(top.first, 96.dp)
            Spacer(Modifier.height(8.dp))
            RwBig("${top.second * 100 / s.total}%", RW_CREAM, 88)
            RwCaption(top.first.label)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                s.byApp.drop(1).take(3).forEach { (app, n) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ReelsAppIcon(app, 56.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${n * 100 / s.total}%",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(app.label, color = Color(0xB3FFFFFF), fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                s.byApp.forEach { (app, n) ->
                    Box(
                        Modifier
                            .weight(n.toFloat())
                            .fillMaxHeight()
                            .background(Color(app.color)),
                    )
                }
            }
        }

        RW_PULLED -> RwLayout(stringResource(R.string.rw_label_pulled)) {
            RwBig(s.opens.toString())
            RwCaption(stringResource(R.string.rw_opened))
            Spacer(Modifier.height(28.dp))
            val maxH = (s.hours.maxOrNull() ?: 0).coerceAtLeast(1)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x33FFFFFF)),
            ) {
                for (h in 0 until 24) {
                    val a = if (s.hours[h] == 0) 0f else 0.3f + 0.7f * (s.hours[h].toFloat() / maxH.toFloat())
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(RW_CREAM.copy(alpha = a)),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, 6, 12, 18, 0).forEach {
                    Text(rwHourLabel(it), color = Color(0xB3FFFFFF), fontSize = 12.sp)
                }
            }
        }

        RW_TIME -> RwLayout(stringResource(R.string.rw_label_time)) {
            val m = s.minutes
            val text = if (m >= 60) {
                stringResource(R.string.rw_hm, m / 60, m % 60)
            } else {
                stringResource(R.string.rw_min, m)
            }
            RwBig(text, RW_CREAM, 56)
            if (m >= 20) {
                Spacer(Modifier.height(12.dp))
                RwCaption(stringResource(R.string.rw_time_eq, (m / 45).coerceAtLeast(1)))
            }
        }

        RW_PEAK -> RwLayout(stringResource(R.string.rw_label_peak)) {
            RwBig(rwHourLabel(s.peak), RW_CREAM, 80)
            Spacer(Modifier.height(12.dp))
            RwCaption(stringResource(R.string.rw_peak_hint))
        }

        RW_LONGEST -> RwLayout(stringResource(R.string.rw_label_longest)) {
            RwBig(s.longest.toString())
            RwCaption(stringResource(R.string.rw_longest_unit))
        }

        RW_VS -> RwLayout(stringResource(R.string.rw_label_vs)) {
            if (s.prevTotal == 0) {
                RwCaption(stringResource(R.string.rw_vs_none))
            } else {
                val pct = (s.total - s.prevTotal) * 100 / s.prevTotal
                val up = pct >= 0
                Text(
                    if (up) "▲" else "▼",
                    color = if (up) RW_RED else Color(0xFF6FE08A),
                    fontSize = 40.sp,
                )
                RwBig("${abs(pct)}%", if (up) RW_RED else Color(0xFF6FE08A), 96)
                RwCaption(stringResource(if (up) R.string.rw_vs_more else R.string.rw_vs_less))
            }
        }

        else -> RwLayout(stringResource(R.string.rw_label_verdict)) {
            ReelsBrainFace(s.level, Modifier.size(220.dp))
            Spacer(Modifier.height(16.dp))
            RwCaption(
                stringResource(
                    when (s.level) {
                        0 -> R.string.reels_mood_0
                        1 -> R.string.reels_mood_1
                        2 -> R.string.reels_mood_2
                        else -> R.string.reels_mood_3
                    },
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.rw_limit_line, s.limit),
                color = Color(0xB3FFFFFF),
                fontSize = 16.sp,
            )
        }
    }
}
