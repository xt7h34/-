package com.rigen.volumeui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Remembers that the first-run steps have been seen (or skipped), so they only show once. */
object OnboardingPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("kanade_onboarding", Context.MODE_PRIVATE)

    fun done(c: Context): Boolean = sp(c).getBoolean("done", false)

    fun setDone(c: Context) = sp(c).edit().putBoolean("done", true).apply()
}

/** Lets Settings ask for the first-run steps to show again. */
object IntroSignal {
    var replay by mutableStateOf(false)
}

private const val STEPS = 6
private val STEP_GREEN = Color(0xFF9BB36B)

/**
 * The first-run steps: a bar of six segments, a big question, a short line, and a place for the answer. The
 * last two steps ask for the two features that are off until you want them. Skip is always there.
 */
@Composable
fun OnboardingFlow(serviceEnabled: Boolean, dndAccess: Boolean, mediaAccess: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val locale = ctx.resources.configuration.locales[0]
    var step by rememberSaveable { mutableIntStateOf(0) }
    var budgetIndex by rememberSaveable { mutableIntStateOf(0) }
    var bloomOn by remember { mutableStateOf(BloomPrefs.enabled(ctx)) }
    var edgeOn by remember { mutableStateOf(EdgePrefs.enabled(ctx)) }
    val currency = remember {
        BudgetStore.load(ctx).currency.ifBlank {
            try {
                java.util.Currency.getInstance(locale).currencyCode
            } catch (e: Exception) {
                ""
            }
        }
    }
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val none = stringResource(R.string.ob_budget_none)
    val budgetLabels = remember(numbers, none) { (0..60).map { if (it == 0) none else numbers.format(it * 500L) } }

    fun saveBudget() {
        val minor = budgetIndex * 50_000L
        if (minor > 0L) {
            val b = BudgetStore.load(ctx)
            BudgetStore.setMain(ctx, currency, minor, b.limits)
        }
    }

    fun finish() {
        if (edgeOn != EdgePrefs.enabled(ctx)) {
            EdgePrefs.setEnabled(ctx, edgeOn)
            VolumeAccessibilityService.instance?.refreshEdgePanel()
        }
        OnboardingPrefs.setDone(ctx)
        onDone()
    }

    BackHandler(enabled = step > 0) { step -= 1 }

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until STEPS) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (i <= step) STEP_GREEN else scheme.outlineVariant),
                    )
                }
            }
            Text(
                (step + 1).toString() + "/" + STEPS,
                Modifier.padding(start = 14.dp),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (step < STEPS - 1) {
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.ob_skip)) }
            }
        }

        Crossfade(step, Modifier.weight(1f), label = "ob_step") { s ->
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(
                        when (s) {
                            0 -> R.string.ob_welcome_title
                            1 -> R.string.ob_service_title
                            2 -> R.string.ob_dnd_title
                            3 -> R.string.ob_budget_title
                            4 -> R.string.bloom_title
                            else -> R.string.ob_done_title
                        },
                    ),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(
                        when (s) {
                            0 -> R.string.ob_welcome_sub
                            1 -> R.string.ob_service_sub
                            2 -> R.string.ob_dnd_sub
                            3 -> R.string.ob_budget_sub
                            4 -> R.string.ob_bloom_sub
                            else -> R.string.ob_done_sub
                        },
                    ),
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp)) {
                    when (s) {
                        0 -> WelcomeStep()
                        1 -> StatusStep(
                            ok = serviceEnabled,
                            onText = stringResource(R.string.ob_service_on),
                            offText = stringResource(R.string.ob_service_off),
                        ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        2 -> StatusStep(
                            ok = dndAccess,
                            onText = stringResource(R.string.ob_dnd_on),
                            offText = stringResource(R.string.ob_dnd_off),
                        ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                        3 -> BudgetStep(budgetLabels, budgetIndex, currency) { budgetIndex = it }
                        4 -> BloomStep(bloomOn, mediaAccess) {
                            bloomOn = it
                            BloomPrefs.setEnabled(ctx, it)
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.edge_panel), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Switch(checked = edgeOn, onCheckedChange = { edgeOn = it })
                            }
                            Text(
                                stringResource(R.string.edge_panel_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (step > 0) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainer)
                        .clickable { step -= 1 },
                    contentAlignment = Alignment.Center,
                ) {
                    PathIcon(Glyph.PREVIOUS.pathData, scheme.onSurface, 24.dp, if (rtl) Modifier.scale(-1f, 1f) else Modifier)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(scheme.onSurface)
                    .clickable {
                        if (step == 3) saveBudget()
                        if (step < STEPS - 1) step += 1 else finish()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(if (step < STEPS - 1) R.string.ob_next else R.string.ob_start),
                        color = scheme.surface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (step < STEPS - 1) {
                        PathIcon(Glyph.NEXT.pathData, scheme.surface, 22.dp, if (rtl) Modifier.scale(-1f, 1f) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        FeatureRow("\uD83C\uDF9A\uFE0F", R.string.ob_feat_panel_t, R.string.ob_feat_panel_d)
        FeatureRow("\uD83D\uDCC5", R.string.ob_feat_cal_t, R.string.ob_feat_cal_d)
        FeatureRow("\uD83C\uDF38", R.string.ob_feat_bloom_t, R.string.ob_feat_bloom_d)
    }
}

@Composable
private fun FeatureRow(emoji: String, title: Int, detail: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(emoji, fontSize = 30.sp)
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusStep(ok: Boolean, onText: String, offText: String, onOpen: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (ok) "\u2705" else "\u26AA", fontSize = 24.sp)
                Text(if (ok) onText else offText, style = MaterialTheme.typography.titleMedium)
            }
            if (!ok) {
                Button(onClick = onOpen) { Text(stringResource(R.string.ob_open_settings)) }
            }
        }
    }
}

@Composable
private fun BudgetStep(labels: List<String>, index: Int, currency: String, onIndex: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ob_budget_label), style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            Text(
                if (index == 0) "\u2014" else labels[index],
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (index > 0 && currency.isNotBlank()) {
                Text(currency, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        VerticalWheel(labels, index, onIndex, Modifier.width(150.dp).height(280.dp))
    }
}

@Composable
private fun BloomStep(on: Boolean, mediaAccess: Boolean, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ob_bloom_switch), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = on, onCheckedChange = onToggle)
        }
        Text(stringResource(R.string.bloom_privacy), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        if (on && !mediaAccess) {
            Text(stringResource(R.string.bloom_access_needed), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text(stringResource(R.string.bloom_open_access))
            }
        }
    }
}

/**
 * A wheel of values turned by a vertical drag: the picked one sits in the pill in the middle, the others fade
 * away above and below it, and it settles on the nearest value when you let go.
 */
@Composable
private fun VerticalWheel(labels: List<String>, index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val currentIndex by rememberUpdatedState(index)
    val currentOnIndex by rememberUpdatedState(onIndex)
    var pos by remember { mutableFloatStateOf(index.toFloat()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val last = (labels.size - 1).coerceAtLeast(0)

    LaunchedEffect(index) {
        if (pos.roundToInt() != index) pos = index.toFloat()
    }

    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val pill = scheme.surfaceVariant
    val strong = scheme.onSurface
    val soft = scheme.onSurfaceVariant

    Canvas(
        modifier.pointerInput(labels.size) {
            val rowPx = 56.dp.toPx()
            val settle: () -> Unit = {
                job?.cancel()
                job = scope.launch {
                    animate(pos, pos.roundToInt().toFloat(), animationSpec = tween(160)) { v, _ -> pos = v }
                }
            }
            detectVerticalDragGestures(
                onDragStart = { job?.cancel() },
                onDragEnd = { settle() },
                onDragCancel = { settle() },
            ) { change, dy ->
                change.consume()
                pos = (pos - dy / rowPx).coerceIn(0f, last.toFloat())
                val i = pos.roundToInt()
                if (i != currentIndex) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnIndex(i)
                }
            }
        },
    ) {
        val rowPx = 56.dp.toPx()
        val cy = size.height / 2f
        drawRoundRect(
            color = pill.copy(alpha = 0.7f),
            topLeft = Offset(0f, cy - rowPx / 2f + 4.dp.toPx()),
            size = Size(size.width, rowPx - 8.dp.toPx()),
            cornerRadius = CornerRadius(20.dp.toPx()),
        )
        val base = pos.roundToInt()
        for (k in -3..3) {
            val i = base + k
            if (i !in labels.indices) continue
            val offset = i - pos
            val y = cy + offset * rowPx
            val fade = (1f - abs(offset) / 3f).coerceIn(0.1f, 1f)
            val picked = abs(offset) < 0.5f
            drawIntoCanvas { canvas ->
                paint.textSize = (30f - 8f * min(abs(offset), 1.5f)).dp.toPx()
                paint.isFakeBoldText = picked
                paint.color = (if (picked) strong else soft).copy(alpha = fade).toArgb()
                canvas.nativeCanvas.drawText(labels[i], size.width / 2f, y + paint.textSize / 3f, paint)
            }
        }
    }
}
