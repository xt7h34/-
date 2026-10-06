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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val HIGH_VOLUME_RED = Color(0xFFE53935)
private val ACTIVE_BLUE = Color(0xFF7FA6FF)

/** Everything the panel and the Station card need to draw. */
data class PanelState(
    /** The volume the panel is mainly about (media, or call during a call). */
    val stream: Int,
    val level: Int,
    val max: Int,
    /** Do Not Disturb is on. */
    val dnd: Boolean,
    /** True when the Volume Station card is open instead of the panel. */
    val expanded: Boolean = false,
    val levels: Map<Int, Int> = emptyMap(),
    val maxes: Map<Int, Int> = emptyMap(),
    val mediaPlaying: Boolean = false,
)

/** What the panel can ask for. A null [PanelActions] makes it display-only. */
class PanelActions(
    /** A bar was touched: stream, position 0..1. */
    val onSeek: (Int, Float) -> Unit,
    /** A finger went down (true) or up (false) on a bar. */
    val onTouch: (Boolean) -> Unit,
    /** The three dots were tapped. */
    val onToggleStation: () -> Unit,
    /** A media key code (previous / play-pause / next). */
    val onMedia: (Int) -> Unit,
    val onMuteAll: () -> Unit,
    val onToggleDnd: () -> Unit,
    val onOpenSettings: () -> Unit,
    /** The speaker button of the capsule style: mute or unmute the main volume. */
    val onToggleMute: () -> Unit,
)

private class PanelColors(val fg: Color, val bar: Color, val frame: Color)

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
    androidx.compose.foundation.Canvas(modifier.size(iconSize)) {
        val p = path ?: return@Canvas
        scale(scale = this.size.minDimension / 24f, pivot = Offset.Zero) {
            drawPath(p, color)
        }
    }
}

/** Shows the Volume Station card when it is open, otherwise the panel. */
@Composable
fun PanelOrStation(
    settings: PanelSettings,
    state: PanelState,
    modifier: Modifier = Modifier,
    actions: PanelActions? = null,
) {
    if (state.expanded && settings.stationEnabled && actions != null) {
        StationCard(settings, state, modifier, actions)
    } else {
        VolumePanel(settings, state, modifier, actions)
    }
}

/**
 * The volume panel. Horizontal or vertical. With the Volume Station on, three dots sit inside
 * it; tapping them opens the Station card.
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
    val colors = PanelColors(fg, if (settings.barColorArgb == 0) fg else Color(settings.barColorArgb), frameBg)
    val ctx = PanelCtx(settings, state, colors, actions)

    when (settings.panelStyle) {
        STYLE_CAPSULE -> CapsulePanel(ctx, modifier)
        STYLE_CAPSULE2 -> Capsule2Panel(ctx, modifier)
        STYLE_BAR -> BarPanel(ctx, modifier)
        else -> {
            var frame = modifier
            if (settings.showFrame) {
                frame = frame
                    .clip(RoundedCornerShape(settings.cornerRadiusDp.dp))
                    .background(frameBg)
            }
            Box(frame) {
                if (settings.vertical) {
                    StreamColumn(ctx)
                } else {
                    StreamRow(ctx)
                }
            }
        }
    }
}

// ───────────────────────────── The panel itself ─────────────────────────────

private fun seekCallback(ctx: PanelCtx): ((Float) -> Unit)? {
    val a = ctx.actions ?: return null
    if (!ctx.settings.touchEnabled) return null
    val stream = ctx.state.stream
    return { f -> a.onSeek(stream, f) }
}

@Composable
private fun StreamRow(ctx: PanelCtx) {
    val s = ctx.settings
    val st = ctx.state
    val fraction = if (st.max > 0) (st.level.toFloat() / st.max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val iconColor = if (pct >= s.redThresholdPct) HIGH_VOLUME_RED else ctx.colors.fg
    val glyph = if (st.level == 0) Glyph.MUTED else glyphForStream(st.stream)
    val thickness = (s.heightDp / 6).coerceIn(4, 16)
    val iconSize = (s.heightDp - 24).coerceIn(16, 32)

    Row(
        modifier = Modifier
            .width(s.widthDp.dp)
            .height(s.heightDp.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathIcon(glyph.pathData, iconColor, iconSize.dp)
        Spacer(Modifier.width(10.dp))
        VolumeBar(
            fraction = fraction,
            vertical = false,
            thickness = thickness,
            barColor = ctx.colors.bar,
            onSeek = seekCallback(ctx),
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
        if (st.dnd && s.showDndIcon) {
            Spacer(Modifier.width(8.dp))
            PathIcon(Glyph.DND.pathData, ctx.colors.fg.copy(alpha = 0.9f), 16.dp)
        }
        if (ctx.dots) {
            Spacer(Modifier.width(4.dp))
            DotsButton(ctx.colors.fg, false) { ctx.actions?.onToggleStation?.invoke() }
        }
    }
}

@Composable
private fun StreamColumn(ctx: PanelCtx) {
    val s = ctx.settings
    val st = ctx.state
    val fraction = if (st.max > 0) (st.level.toFloat() / st.max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val iconColor = if (pct >= s.redThresholdPct) HIGH_VOLUME_RED else ctx.colors.fg
    val glyph = if (st.level == 0) Glyph.MUTED else glyphForStream(st.stream)
    val thickness = (s.widthDp / 6).coerceIn(4, 16)
    val iconSize = (s.widthDp - 24).coerceIn(16, 32)

    Column(
        modifier = Modifier
            .width(s.widthDp.dp)
            .height(s.heightDp.dp)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$pct",
            color = ctx.colors.fg,
            fontSize = if (s.widthDp < 48) 11.sp else 14.sp,
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
            onSeek = seekCallback(ctx),
            onTouch = ctx.actions?.onTouch,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        PathIcon(glyph.pathData, iconColor, iconSize.dp)
        if (st.dnd && s.showDndIcon) {
            Spacer(Modifier.height(6.dp))
            PathIcon(Glyph.DND.pathData, ctx.colors.fg.copy(alpha = 0.9f), 14.dp)
        }
        if (ctx.dots) {
            Spacer(Modifier.height(2.dp))
            DotsButton(ctx.colors.fg, true) { ctx.actions?.onToggleStation?.invoke() }
        }
    }
}

// ───────────────────────────── The Volume Station card ─────────────────────────────

/**
 * The Volume Station: a card for the middle of the screen. A title with a gear (top right) and
 * the optional mute-all / Do Not Disturb buttons (top left), then one framed bar per volume, then
 * the media buttons.
 */
@Composable
fun StationCard(
    settings: PanelSettings,
    state: PanelState,
    modifier: Modifier = Modifier,
    actions: PanelActions? = null,
) {
    val frameBg = Color(settings.colorArgb)
    val fg = if (frameBg.luminance() > 0.5f) Color.Black else Color.White
    val barColor = if (settings.barColorArgb == 0) fg else Color(settings.barColorArgb)
    val shape = RoundedCornerShape(settings.cornerRadiusDp.coerceAtLeast(24).dp)
    val inner = fg.copy(alpha = 0.09f)
    val allMuted = STREAMS.all { (state.levels[it.stream] ?: 0) == 0 }

    Column(
        modifier = modifier
            .width(STATION_W_DP.dp)
            .clip(shape)
            .background(frameBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left and right here mean the real screen sides, whatever the language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (settings.stationMuteAll) {
                        TapIcon((if (allMuted) Glyph.MUTED else Glyph.VOLUME).pathData, fg, 44.dp, 24.dp) {
                            actions?.onMuteAll?.invoke()
                        }
                    }
                    if (settings.stationDnd) {
                        TapIcon(Glyph.DND.pathData, if (state.dnd) ACTIVE_BLUE else fg, 44.dp, 24.dp) {
                            actions?.onToggleDnd?.invoke()
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.section_station),
                    color = fg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TapIcon(Glyph.SETTINGS.pathData, fg, 44.dp, 24.dp) {
                        actions?.onOpenSettings?.invoke()
                    }
                }
            }
        }

        STREAMS.forEach { info ->
            val level = state.levels[info.stream] ?: 0
            val max = state.maxes[info.stream] ?: 1
            val seek: ((Float) -> Unit)? = if (actions != null) {
                { f -> actions.onSeek(info.stream, f) }
            } else {
                null
            }
            StationVolumeFrame(
                info = info,
                level = level,
                max = max,
                fg = fg,
                barColor = barColor,
                inner = inner,
                threshold = settings.redThresholdPct,
                onSeek = seek,
                onTouch = actions?.onTouch,
            )
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(inner),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapIcon(Glyph.PREVIOUS.pathData, fg, 44.dp, 24.dp) {
                    actions?.onMedia?.invoke(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
                TapIcon(
                    (if (state.mediaPlaying) Glyph.PAUSE else Glyph.PLAY).pathData,
                    fg, 44.dp, 28.dp,
                ) {
                    actions?.onMedia?.invoke(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
                TapIcon(Glyph.NEXT.pathData, fg, 44.dp, 24.dp) {
                    actions?.onMedia?.invoke(KeyEvent.KEYCODE_MEDIA_NEXT)
                }
            }
        }
    }
}

/** One volume in its own rounded frame: icon, name, bar and percent. */
@Composable
private fun StationVolumeFrame(
    info: StreamInfo,
    level: Int,
    max: Int,
    fg: Color,
    barColor: Color,
    inner: Color,
    threshold: Int,
    onSeek: ((Float) -> Unit)?,
    onTouch: ((Boolean) -> Unit)?,
) {
    val fraction = if (max > 0) (level.toFloat() / max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val iconColor = if (pct >= threshold) HIGH_VOLUME_RED else fg
    val glyph = if (level == 0) Glyph.MUTED else info.glyph

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(inner)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathIcon(glyph.pathData, iconColor, 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(info.labelRes),
                color = fg.copy(alpha = 0.75f),
                fontSize = 12.sp,
            )
            VolumeBar(
                fraction = fraction,
                vertical = false,
                thickness = 8,
                barColor = barColor,
                onSeek = onSeek,
                onTouch = onTouch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$pct",
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
    }
}

// ───────────────────────────── Parts ─────────────────────────────

/**
 * Touch handling for a bar: pressing or dragging reports the position (0..1). Horizontal bars
 * grow from the left and vertical bars from the bottom.
 */
@Composable
private fun Modifier.seekGestures(
    vertical: Boolean,
    onSeek: ((Float) -> Unit)?,
    onTouch: ((Boolean) -> Unit)?,
): Modifier {
    val seekCb by rememberUpdatedState(onSeek)
    val touchCb by rememberUpdatedState(onTouch)
    return this.pointerInput(vertical) {
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
    }
}

/** The thin bar. The whole cell around it is the touch target. It always fills from the left or the bottom. */
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
    val track = barColor.copy(alpha = 0.25f)
    val round = RoundedCornerShape((thickness / 2).dp)

    Box(
        modifier = modifier.seekGestures(vertical, onSeek, onTouch),
        contentAlignment = Alignment.Center,
    ) {
        // Keep the bar left-to-right in every language, so touch and drawing agree.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
}

/** A thick bar: the whole cell is the track and the fill grows inside it. */
@Composable
private fun FatBar(
    fraction: Float,
    vertical: Boolean,
    track: Color,
    accent: Color,
    cornerDp: Int,
    onSeek: ((Float) -> Unit)?,
    onTouch: ((Boolean) -> Unit)?,
    modifier: Modifier,
    insideFill: (@Composable () -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val animated by animateFloatAsState(fraction, tween(120), label = "fat")
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerDp.dp))
            .background(track)
            .seekGestures(vertical, onSeek, onTouch),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (vertical) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(animated)
                        .background(accent),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (insideFill != null) {
                        Box(Modifier.padding(top = 12.dp)) { insideFill() }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(animated)
                        .background(accent),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (insideFill != null) {
                        Box(Modifier.padding(end = 12.dp)) { insideFill() }
                    }
                }
            }
        }
        if (overlay != null) overlay()
    }
}

/** A round button with a filled background and an icon. */
@Composable
private fun CircleButton(
    bg: Color,
    boxSize: Dp,
    pathData: String,
    iconColor: Color,
    iconSize: Dp,
    onTap: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center,
    ) {
        PathIcon(pathData, iconColor, iconSize)
    }
}

// ───────────────────────────── Other panel styles ─────────────────────────────

/**
 * The capsule: a pill with a speaker button, a thick slider with the volume's icon in the fill,
 * and the three dots. Vertical or horizontal.
 */
@Composable
private fun CapsulePanel(ctx: PanelCtx, modifier: Modifier) {
    val s = ctx.settings
    val st = ctx.state
    val c = ctx.colors
    val fraction = if (st.max > 0) (st.level.toFloat() / st.max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val accent = c.bar
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val track = c.fg.copy(alpha = 0.12f)
    val iconColor = if (pct >= s.redThresholdPct) HIGH_VOLUME_RED else onAccent
    val glyph = if (st.level == 0) Glyph.MUTED else glyphForStream(st.stream)
    val topGlyph = if (st.level == 0) Glyph.MUTED else Glyph.VOLUME
    val toggleMute: () -> Unit = { ctx.actions?.onToggleMute?.invoke() }

    var pill = modifier
        .width(s.widthDp.dp)
        .height(s.heightDp.dp)
    if (s.showFrame) {
        pill = pill
            .clip(RoundedCornerShape(50))
            .background(c.frame)
    }

    if (s.vertical) {
        // Everything scales with the real width, so a narrow panel still draws its full shape.
        val pad = (s.widthDp / 5).coerceIn(4, 10)
        val button = (s.widthDp - 2 * pad).coerceIn(16, 56)
        val corner = (button / 2).coerceIn(8, 28)
        val fillIcon = (button / 2 + 4).coerceIn(12, 22)
        Column(
            modifier = pill.padding(pad.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircleButton(accent, button.dp, topGlyph.pathData, onAccent, (button / 2).dp, toggleMute)
            if (st.dnd && s.showDndIcon) {
                Spacer(Modifier.height(6.dp))
                PathIcon(Glyph.DND.pathData, c.fg.copy(alpha = 0.9f), 14.dp)
            }
            Spacer(Modifier.height(10.dp))
            FatBar(
                fraction = fraction,
                vertical = true,
                track = track,
                accent = accent,
                cornerDp = corner,
                onSeek = seekCallback(ctx),
                onTouch = ctx.actions?.onTouch,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                insideFill = { PathIcon(glyph.pathData, iconColor, fillIcon.dp) },
            )
            if (ctx.dots) {
                Spacer(Modifier.height(6.dp))
                DotsButton(c.fg, true) { ctx.actions?.onToggleStation?.invoke() }
            }
        }
    } else {
        val button = (s.heightDp - 16).coerceIn(24, 56)
        val corner = (button / 2).coerceIn(8, 28)
        Row(
            modifier = pill.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(accent, button.dp, topGlyph.pathData, onAccent, (button / 2).dp, toggleMute)
            if (st.dnd && s.showDndIcon) {
                Spacer(Modifier.width(6.dp))
                PathIcon(Glyph.DND.pathData, c.fg.copy(alpha = 0.9f), 14.dp)
            }
            Spacer(Modifier.width(10.dp))
            FatBar(
                fraction = fraction,
                vertical = false,
                track = track,
                accent = accent,
                cornerDp = corner,
                onSeek = seekCallback(ctx),
                onTouch = ctx.actions?.onTouch,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                insideFill = { PathIcon(glyph.pathData, iconColor, 22.dp) },
            )
            if (ctx.dots) {
                Spacer(Modifier.width(6.dp))
                DotsButton(c.fg, false) { ctx.actions?.onToggleStation?.invoke() }
            }
        }
    }
}

/**
 * Capsule II: a white pill with a round speaker button, a pale slider with a small dot, a thin
 * handle line that rides on top of a solid fill block, and the three dots. Vertical only: a
 * horizontal panel falls back to the plain capsule.
 */
@Composable
private fun Capsule2Panel(ctx: PanelCtx, modifier: Modifier) {
    val s = ctx.settings
    if (!s.vertical) {
        CapsulePanel(ctx, modifier)
        return
    }
    val st = ctx.state
    val c = ctx.colors
    val fraction = if (st.max > 0) (st.level.toFloat() / st.max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val animated by animateFloatAsState(fraction, tween(120), label = "cap2")
    val accent = c.bar
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val track = c.fg.copy(alpha = 0.10f)
    val iconColor = if (pct >= s.redThresholdPct) HIGH_VOLUME_RED else onAccent
    val glyph = if (st.level == 0) Glyph.MUTED else glyphForStream(st.stream)
    val topGlyph = if (st.level == 0) Glyph.MUTED else Glyph.VOLUME
    // Everything scales with the real width. A fixed 10dp padding plus 6dp insets used to leave
    // a zero-width fill on narrow panels, so the preview looked empty and squashed.
    val pad = (s.widthDp / 5).coerceIn(4, 10)
    val inner = (s.widthDp - 2 * pad).coerceAtLeast(12)
    val button = inner.coerceIn(16, 56)
    val inset = (inner / 6).coerceIn(2, 6)
    val bigR = (inner / 2).coerceIn(8, 22)
    val fillIcon = (inner / 2 + 4).coerceIn(12, 22)

    var pill = modifier
        .width(s.widthDp.dp)
        .height(s.heightDp.dp)
    if (s.showFrame) {
        pill = pill
            .clip(RoundedCornerShape(50))
            .background(c.frame)
    }

    Column(
        modifier = pill.padding(pad.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircleButton(
            accent, button.dp, topGlyph.pathData, onAccent, (button / 2).dp,
        ) { ctx.actions?.onToggleMute?.invoke() }
        if (st.dnd && s.showDndIcon) {
            Spacer(Modifier.height(6.dp))
            PathIcon(Glyph.DND.pathData, c.fg.copy(alpha = 0.9f), 14.dp)
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .seekGestures(true, seekCallback(ctx), ctx.actions?.onTouch),
        ) {
            val total = maxHeight
            val handleSpace = 14.dp // 4 gap + 5 handle + 5 gap
            val fillH = (total * animated - handleSpace).coerceAtLeast(0.dp)
            val trackH = (total - fillH - handleSpace).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackH)
                        .padding(horizontal = inset.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = bigR.dp, topEnd = bigR.dp, bottomEnd = 6.dp, bottomStart = 6.dp,
                            ),
                        )
                        .background(track),
                ) {
                    if (trackH > 36.dp) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 14.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent),
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(fillH)
                        .padding(horizontal = inset.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 6.dp, topEnd = 6.dp, bottomEnd = bigR.dp, bottomStart = bigR.dp,
                            ),
                        )
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    PathIcon(glyph.pathData, iconColor, fillIcon.dp)
                }
            }
        }
        if (ctx.dots) {
            Spacer(Modifier.height(6.dp))
            DotsButton(c.fg, true) { ctx.actions?.onToggleStation?.invoke() }
        }
    }
}

/** The plain bar: one rounded bar that fills with color, with the icon inside. */
@Composable
private fun BarPanel(ctx: PanelCtx, modifier: Modifier) {
    val s = ctx.settings
    val st = ctx.state
    val c = ctx.colors
    val fraction = if (st.max > 0) (st.level.toFloat() / st.max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val accent = c.bar
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val trackColor = if (s.showFrame) c.frame else c.fg.copy(alpha = 0.25f)
    val onTrack = if (trackColor.luminance() > 0.5f) Color.Black else Color.White
    val glyph = if (st.level == 0) Glyph.MUTED else glyphForStream(st.stream)
    val corner = minOf(s.widthDp, s.heightDp) / 2

    FatBar(
        fraction = fraction,
        vertical = s.vertical,
        track = trackColor,
        accent = accent,
        cornerDp = corner,
        onSeek = seekCallback(ctx),
        onTouch = ctx.actions?.onTouch,
        modifier = modifier
            .width(s.widthDp.dp)
            .height(s.heightDp.dp),
        overlay = {
            val tint = if (pct >= s.redThresholdPct) {
                HIGH_VOLUME_RED
            } else if (fraction > 0.12f) {
                onAccent
            } else {
                onTrack
            }
            val dotsTint = if (fraction > 0.9f) onAccent else onTrack
            val showDnd = st.dnd && s.showDndIcon
            if (s.vertical) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                ) { PathIcon(glyph.pathData, tint, 26.dp) }
                if (ctx.dots || showDnd) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (ctx.dots) {
                            DotsButton(dotsTint, true) { ctx.actions?.onToggleStation?.invoke() }
                        }
                        if (showDnd) {
                            Spacer(Modifier.height(4.dp))
                            PathIcon(Glyph.DND.pathData, dotsTint.copy(alpha = 0.9f), 16.dp)
                        }
                    }
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                ) { PathIcon(glyph.pathData, tint, 26.dp) }
                if (ctx.dots || showDnd) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showDnd) {
                            PathIcon(Glyph.DND.pathData, dotsTint.copy(alpha = 0.9f), 16.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        if (ctx.dots) {
                            DotsButton(dotsTint, false) { ctx.actions?.onToggleStation?.invoke() }
                        }
                    }
                }
            }
        },
    )
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
