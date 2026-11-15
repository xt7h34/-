package com.rigen.volumeui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Today at a glance, read from the calendar (so it holds your entries, alarms, prayers and goals alike):
 * how long until the next thing, and how much of today's schedule is behind you. The words are already
 * written, so Home and the Edge Panel only draw them.
 */
class DayGlance(
    val nextRing: Float,
    val nextValue: String,
    val nextLabel: String,
    val doneRing: Float,
    val doneValue: String,
    val doneLabel: String,
)

val EMPTY_GLANCE = DayGlance(0f, "", "", 0f, "", "")

private fun untilText(c: Context, ms: Long): String {
    val mins = max(1L, ms / 60_000L).toInt()
    return if (mins < 60) c.getString(R.string.dg_in_m, mins) else c.getString(R.string.dg_in_hm, mins / 60, mins % 60)
}

fun dayGlance(c: Context, now: Long): DayGlance {
    val snap = CalendarEngine.snapshot(c)
    val today = CalendarEngine.ld(now)
    val timed = snap.itemsFor(today).filter { !it.allDay }

    var next = timed.firstOrNull { it.at > now }
    var tomorrow = false
    if (next == null) {
        next = snap.itemsFor(today.plusDays(1)).firstOrNull { !it.allDay }
        tomorrow = next != null
    }

    val nextRing: Float
    val nextValue: String
    val nextLabel: String
    if (next == null) {
        nextRing = 0f
        nextValue = "\u2014"
        nextLabel = c.getString(R.string.dg_next_none)
    } else {
        val before = (if (tomorrow) timed.lastOrNull() else timed.lastOrNull { it.at <= now })?.at
            ?: CalendarEngine.startMs(today)
        nextRing = ((now - before).toFloat() / (next.at - before).coerceAtLeast(1L)).coerceIn(0f, 1f)
        nextValue = untilText(c, next.at - now)
        nextLabel = if (tomorrow) c.getString(R.string.dg_tomorrow, next.title) else next.title
    }

    val total = timed.size
    val done = timed.count { it.at <= now }
    return DayGlance(
        nextRing = nextRing,
        nextValue = nextValue,
        nextLabel = nextLabel,
        doneRing = if (total > 0) done.toFloat() / total else 0f,
        doneValue = if (total > 0) "$done/$total" else "0",
        doneLabel = c.getString(if (total > 0) R.string.dg_done_label else R.string.dg_done_none),
    )
}

/** A ring that fills to [progress] (0 to 1), starting at the top. */
@Composable
internal fun RingMark(progress: Float, color: Color, diameter: Dp, stroke: Dp = 7.dp, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "ring")
    Canvas(modifier.size(diameter)) {
        val width = stroke.toPx()
        val topLeft = Offset(width / 2f, width / 2f)
        val arc = Size(this.size.width - width, this.size.height - width)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, topLeft, arc, style = Stroke(width))
        drawArc(color, -90f, 360f * shown, false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** A dark glass capsule: a ring with a soft glow behind it, a big number and a small line under it. */
@Composable
internal fun RingPill(
    ring: Float,
    color: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(40.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E))))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(size.height / 2f + 16.dp.toPx(), size.height / 2f),
                        radius = size.height,
                    ),
                    radius = size.height,
                    center = Offset(size.height / 2f + 16.dp.toPx(), size.height / 2f),
                )
            }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RingMark(ring, color, 54.dp)
        Column(Modifier.weight(1f)) {
            Text(value, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, color = Color(0xFFAEAEB2), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The small version for the narrow Edge Panel: the ring, the number under it, and a tiny line. */
@Composable
internal fun GlassRingTile(ring: Float, color: Color, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RingMark(ring, color, 44.dp, stroke = 5.dp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        Text(
            label,
            color = Color(0xFFAEAEB2),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
