package com.rigen.volumeui

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val HIGH_VOLUME_RED = Color(0xFFE53935)

/** Everything the panel needs to draw. */
data class PanelState(
    /** The volume the panel is mainly about (media, or call during a call). */
    val stream: Int,
    val level: Int,
    val max: Int,
    val dnd: Boolean,
    /** True when the Volume Station part is open. */
    val expanded: Boolean = false,
    val levels: Map<Int, Int> = emptyMap(),
    val maxes: Map<Int, Int> = emptyMap(),
    val mediaPlaying: Boolean = false,
)

/** What the panel can ask for. A null [PanelActions] makes the panel display-only. */
class PanelActions(
    /** A bar was touched: stream, position 0..1. */
    val onSeek: (Int, Float) -> Unit,
    /** A finger went down (true) or up (false) on the panel. */
    val onTouch: (Boolean) -> Unit,
    val onToggleStation: () -> Unit,
    /** A media key code (previous / play-pause / next). */
    val onMedia: (Int) -> Unit,
)

private class PanelColors(val fg: Color, val bar: Color)

private class PanelCtx(
    val settings: PanelSettings,
    val state: PanelState,
    val colors: PanelColors,
    val actions: PanelActions?,
) {
    val dots: Boolean get() = settings.stationEnabled && actions != null
}

@Composable
fun PathIcon(pathData: String, color: Color, iconSize: Dp, modifier: Modifier = Modifier) {
    val path = remember(pathData) {
        runCatching { PathParser().parsePathString(pathData).toPath() }.getOrNull()
    }
    Canvas(modifier.size(iconSize)) {
        val p = path ?: return@Canvas
        scale(scale = this.size.minDimension / 24f, pivot = Offset.Zero) {
            drawPath(p, color)
        }
    }
}

/**
 * The volume panel. Horizontal or vertical. With the Volume Station on, three dots sit inside
 * it; tapping them opens the other volumes and the media controls inside the same frame.
 */
@Composable
fun VolumePanel(
    settings: PanelSettings,
    state: PanelState,
    modifier: Modifier = Modifier,
    actions: PanelActions? = null,
) {
    val frameBg = Color(settings.colorArgb)
    val fg = when {
        !settings.showFrame -> Color.White
        frameBg.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    val colors = PanelColors(fg, if (settings.barColorArgb == 0) fg else Color(settings.barColorArgb))
    val ctx = PanelCtx(settings, state, colors, actions)
    val expanded = state.expanded && ctx.dots
    // The panel grows toward the side of the screen that has more room.
    val reverse = if (settings.vertical) settings.posX > 0.5f else settings.posY > 0.5f

    var frame = modifier
    if (settings.showFrame) {
        frame = frame
            .clip(RoundedCornerShape(settings.cornerRadiusDp.dp))
            .background(frameBg)
    }

    if (settings.vertical) {
        Row(frame) {
            if (expanded && reverse) ExtraColumns(ctx, true)
            StreamColumn(
                ctx, state.stream, glyphForStream(state.stream), state.level, state.max,
                settings.widthDp, true,
            )
            if (expanded && !reverse) ExtraColumns(ctx, false)
        }
    } else {
        Column(frame) {
            if (expanded && reverse) ExtraRows(ctx, true)
            StreamRow(
                ctx, state.stream, glyphForStream(state.stream), state.level, state.max,
                settings.heightDp, true,
            )
            if (expanded && !reverse) ExtraRows(ctx, false)
        }
    }
}

// ───────────────────────────── Rows and columns ─────────────────────────────

private fun seekCallback(ctx: PanelCtx, stream: Int, isMain: Boolean): ((Float) -> Unit)? {
    val a = ctx.actions ?: return null
    if (isMain && !ctx.settings.touchEnabled) return null
    return { f -> a.onSeek(stream, f) }
}

@Composable
private fun StreamRow(
    ctx: PanelCtx,
    stream: Int,
    glyph: Glyph,
    level: Int,
    max: Int,
    heightDp: Int,
    isMain: Boolean,
) {
    val s = ctx.settings
    val fraction = if (max > 0) (level.toFloat() / max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val iconColor = if (pct >= s.redThresholdPct) HIGH_VOLUME_RED else ctx.colors.fg
    val iconGlyph = if (level == 0) Glyph.MUTED else glyph
    val thickness = (heightDp / 6).coerceIn(4, 16)
    val iconSize = (heightDp - 24).coerceIn(16, 32)

    Row(
        modifier = Modifier
            .width(s.widthDp.dp)
            .height(heightDp.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathIcon(iconGlyph.pathData, iconColor, iconSize.dp)
        Spacer(Modifier.width(10.dp))
        VolumeBar(
            fraction = fraction,
            vertical = false,
            thickness = thickness,
            barColor = ctx.colors.bar,
            onSeek = seekCallback(ctx, stream, isMain),
            onTouch = ctx.actions?.onTouch,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$pct",
            color = ctx.colors.fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
        if (isMain && ctx.state.dnd && s.showDndIcon) {
            Spacer(Modifier.width(8.dp))
            PathIcon(Glyph.DND.pathData, ctx.colors.fg.copy(alpha = 0.9f), 16.dp)
        }
        if (isMain && ctx.dots) {
            Spacer(Modifier.width(4.dp))
            DotsButton(ctx.colors.fg, false) { ctx.actions?.onToggleStation?.invoke() }
        }
    }
}

@Composable
private fun StreamColumn(
    ctx: PanelCtx,
    stream: Int,
    glyph: Glyph,
    level: Int,
    max: Int,
    widthDp: Int,
    isMain: Boolean,
) {
    val s = ctx.settings
    val fraction = if (max > 0) (level.toFloat() / max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val iconColor = if (pct >= s.redThresholdPct) HIGH_VOLUME_RED else ctx.colors.fg
    val iconGlyph = if (level == 0) Glyph.MUTED else glyph
    val thickness = (widthDp / 6).coerceIn(4, 16)
    val iconSize = (widthDp - 24).coerceIn(16, 32)

    Column(
        modifier = Modifier
            .width(widthDp.dp)
            .height(s.heightDp.dp)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$pct",
            color = ctx.colors.fg,
            fontSize = if (widthDp < 48) 11.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        VolumeBar(
            fraction = fraction,
            vertical = true,
            thickness = thickness,
            barColor = ctx.colors.bar,
            onSeek = seekCallback(ctx, stream, isMain),
            onTouch = ctx.actions?.onTouch,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        PathIcon(iconGlyph.pathData, iconColor, iconSize.dp)
        if (isMain && ctx.state.dnd && s.showDndIcon) {
            Spacer(Modifier.height(6.dp))
            PathIcon(Glyph.DND.pathData, ctx.colors.fg.copy(alpha = 0.9f), 14.dp)
        }
        if (isMain && ctx.dots) {
            Spacer(Modifier.height(2.dp))
            DotsButton(ctx.colors.fg, true) { ctx.actions?.onToggleStation?.invoke() }
        }
    }
}

/** The other volumes and the media row, stacked under (or above) the main row. */
@Composable
private fun ExtraRows(ctx: PanelCtx, reverse: Boolean) {
    val rowH = ctx.settings.heightDp.coerceAtMost(EXTRA_ROW_MAX_H_DP)
    val others = STREAMS.filter { it.stream != ctx.state.stream }
    if (reverse) {
        MediaRow(ctx)
        Spacer(Modifier.height(PANEL_GAP_DP.dp))
    }
    others.forEach { info ->
        if (!reverse) Spacer(Modifier.height(PANEL_GAP_DP.dp))
        StreamRow(
            ctx, info.stream, info.glyph,
            ctx.state.levels[info.stream] ?: 0, ctx.state.maxes[info.stream] ?: 1,
            rowH, false,
        )
        if (reverse) Spacer(Modifier.height(PANEL_GAP_DP.dp))
    }
    if (!reverse) {
        Spacer(Modifier.height(PANEL_GAP_DP.dp))
        MediaRow(ctx)
    }
}

/** Vertical panels open sideways: the other volumes become more columns. */
@Composable
private fun ExtraColumns(ctx: PanelCtx, reverse: Boolean) {
    val colW = ctx.settings.widthDp.coerceAtMost(EXTRA_COL_MAX_W_DP)
    val others = STREAMS.filter { it.stream != ctx.state.stream }
    if (reverse) MediaColumn(ctx, colW)
    others.forEach { info ->
        StreamColumn(
            ctx, info.stream, info.glyph,
            ctx.state.levels[info.stream] ?: 0, ctx.state.maxes[info.stream] ?: 1,
            colW, false,
        )
    }
    if (!reverse) MediaColumn(ctx, colW)
}

// ───────────────────────────── Parts ─────────────────────────────

/** The bar. The whole cell around it is the touch target. */
@Composable
private fun VolumeBar(
    fraction: Float,
    vertical: Boolean,
    thickness: Int,
    barColor: Color,
    onSeek: ((Float) -> Unit)?,
    onTouch: ((Boolean) -> Unit)?,
    modifier: Modifier,
) {
    val animated by animateFloatAsState(fraction, tween(120), label = "bar")
    val seekCb by rememberUpdatedState(onSeek)
    val touchCb by rememberUpdatedState(onTouch)
    val track = barColor.copy(alpha = 0.25f)
    val round = RoundedCornerShape((thickness / 2).dp)

    Box(
        modifier = modifier.pointerInput(vertical) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val seek = seekCb ?: return@awaitEachGesture
                val length = (if (vertical) size.height else size.width).toFloat()
                if (length <= 0f) return@awaitEachGesture
                fun fractionAt(p: Float): Float {
                    val f = (p / length).coerceIn(0f, 1f)
                    return if (vertical) 1f - f else f
                }
                touchCb?.invoke(true)
                seek(fractionAt(if (vertical) down.position.y else down.position.x))
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { c ->
                        if (c.positionChanged()) {
                            seek(fractionAt(if (vertical) c.position.y else c.position.x))
                            c.consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
                touchCb?.invoke(false)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (vertical) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(thickness.dp)
                    .clip(round)
                    .background(track),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(animated)
                        .background(barColor),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thickness.dp)
                    .clip(round)
                    .background(track),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animated)
                        .background(barColor),
                )
            }
        }
    }
}

/** A round tap target with an icon. */
@Composable
private fun TapIcon(pathData: String, color: Color, boxSize: Dp, iconSize: Dp, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        PathIcon(pathData, color, iconSize)
    }
}

/** The three dots that open the Volume Station. Vertical dots in a horizontal panel, and the opposite. */
@Composable
private fun DotsButton(color: Color, panelVertical: Boolean, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val side = this.size.minDimension
            val r = side / 11f
            for (i in 0..2) {
                val pos = side * (0.2f + 0.3f * i)
                val center = if (panelVertical) Offset(pos, side / 2f) else Offset(side / 2f, pos)
                drawCircle(color, r, center)
            }
        }
    }
}

@Composable
private fun MediaButtons(ctx: PanelCtx, button: Dp) {
    val a = ctx.actions
    val fg = ctx.colors.fg
    val playGlyph = if (ctx.state.mediaPlaying) Glyph.PAUSE else Glyph.PLAY
    TapIcon(Glyph.PREVIOUS.pathData, fg, button, 22.dp) {
        a?.onMedia?.invoke(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
    TapIcon(playGlyph.pathData, fg, button, 26.dp) {
        a?.onMedia?.invoke(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
    TapIcon(Glyph.NEXT.pathData, fg, button, 22.dp) {
        a?.onMedia?.invoke(KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}

@Composable
private fun MediaRow(ctx: PanelCtx) {
    Row(
        modifier = Modifier
            .width(ctx.settings.widthDp.dp)
            .height(PANEL_MEDIA_H_DP.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaButtons(ctx, 40.dp)
    }
}

@Composable
private fun MediaColumn(ctx: PanelCtx, widthDp: Int) {
    Column(
        modifier = Modifier
            .width(widthDp.dp)
            .height(ctx.settings.heightDp.dp)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MediaButtons(ctx, widthDp.coerceAtMost(40).dp)
    }
}
