package com.rigen.volumeui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Kanade Keyboard: what is drawn. The keys always sit left to right, exactly as laid out in
 * KeyboardLayouts.kt, even when the phone language is Arabic.
 *
 * Layers, from the back: background, light behind the keys, the keys, then the bubble above a
 * pressed key, the extra letters and the pictures.
 */

private const val BACKSPACE_PATH =
    "M22,3H7c-0.69,0 -1.23,0.35 -1.59,0.88L0,12l5.41,8.11c0.36,0.53 0.9,0.89 1.59,0.89h15c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM19,15.59L17.59,17 14,13.41 10.41,17 9,15.59 12.59,12 9,8.41 10.41,7 14,10.59 17.59,7 19,8.41 15.41,12 19,15.59z"
private const val SHIFT_PATH = "M12,3 L3,13 H8 V20 H16 V13 H21 Z"
private const val CAPS_PATH = "M12,2 L3,12 H8 V17 H16 V12 H21 Z M8,19 H16 V22 H8 Z"
private const val ENTER_PATH = "M19,7v4H5.83l3.58,-3.59L8,6l-6,6 6,6 1.41,-1.41L5.83,13H21V7z"

/** The shape of the bubble that appears above a pressed key. */
internal fun kbBubbleShape(id: Int): Shape = when (id) {
    KB_SHAPE_CIRCLE -> CircleShape
    KB_SHAPE_DIAMOND -> GenericShape { size, _ ->
        moveTo(size.width / 2f, 0f)
        lineTo(size.width, size.height / 2f)
        lineTo(size.width / 2f, size.height)
        lineTo(0f, size.height / 2f)
        close()
    }
    KB_SHAPE_HEXAGON -> GenericShape { size, _ ->
        moveTo(size.width * 0.25f, 0f)
        lineTo(size.width * 0.75f, 0f)
        lineTo(size.width, size.height / 2f)
        lineTo(size.width * 0.75f, size.height)
        lineTo(size.width * 0.25f, size.height)
        lineTo(0f, size.height / 2f)
        close()
    }
    KB_SHAPE_STAR -> GenericShape { size, _ ->
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = minOf(size.width, size.height) / 2f
        val inner = outer * 0.5f
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.toRadians((-90 + i * 36).toDouble())
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    KB_SHAPE_HEART -> GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        moveTo(w / 2f, h * 0.92f)
        cubicTo(-0.12f * w, h * 0.5f, 0.12f * w, -0.08f * h, w / 2f, h * 0.28f)
        cubicTo(0.88f * w, -0.08f * h, 1.12f * w, h * 0.5f, w / 2f, h * 0.92f)
        close()
    }
    else -> RoundedCornerShape(16.dp)
}

private class RectHolder {
    var rect: Rect = Rect.Zero
}

/** Everything one key needs to look and move the way the person chose. */
private class KeyStyle(
    val base: Color,
    val pressed: Color,
    val radius: Dp,
    val gapH: Dp,
    val gapV: Dp,
    val borderW: Dp,
    val borderC: Color,
    val fxStyle: Int,
    val fxColor: Color,
    val motion: Int,
)

private fun plainStyle(base: Color, pressed: Color, cfg: KbConfig) = KeyStyle(
    base, pressed, cfg.keyRadius.dp, cfg.keyGap.dp, (cfg.keyGap * 1.5f).dp,
    0.dp, Color.Transparent, KB_KEYFX_DEFAULT, Color.Transparent, KB_MOTION_NONE,
)

@Composable
internal fun KeyboardRoot(h: KbHost, withInsets: Boolean = true) {
    val cfg = h.cfg
    val fx = h.fx
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rowH = (48f * cfg.heightPct / 100f * (if (landscape) 0.8f else 1f)).dp
    fx.density = LocalDensity.current.density
    val bgBrush = Brush.verticalGradient(listOf(pal.bg, pal.bg2 ?: pal.bg))

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().onSizeChanged { fx.rootW = it.width }) {
            Box(Modifier.matchParentSize().background(bgBrush))
            // The light behind the keys.
            fx.bursts.forEach { b ->
                key(b.id) { BurstItem(b, pal.fx, cfg.fxSize) { fx.bursts.remove(b) } }
            }
            val oh = cfg.oneHanded.coerceIn(0, 2)
            val frac = cfg.oneHandedWidth / 100f
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (withInsets) Modifier.navigationBarsPadding() else Modifier)
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            ) {
                // One-handed: the keyboard sits on one side, and a thin panel fills the other.
                if (oh == 2) OneHandedSide(h, pal, Modifier.weight(1f - frac))
                Column(Modifier.weight(if (oh == 0) 1f else frac)) {
                    SuggestionBar(h, pal)
                    when (h.page) {
                        PG_EMOJI -> EmojiPanel(h, pal, rowH)
                        PG_CLIP -> ClipboardPanel(h, pal, rowH)
                        else -> KeysPanel(h, pal, rowH)
                    }
                }
                if (oh == 1) OneHandedSide(h, pal, Modifier.weight(1f - frac))
            }
            // Drawn above the keys; only the extra letters take touches.
            val p = fx.press
            if (p != null && cfg.previewShape != KB_SHAPE_NONE) PressBubble(p, pal, cfg.previewShape, fx.rootW)
            val ab = fx.bubble
            if (ab != null) AltBubbleView(h, ab, pal)
            fx.taps.forEach { e ->
                key(e.id) { TapItem(e, cfg, pal.picture) { fx.taps.remove(e) } }
            }
        }
    }
}

// ───────────────────────── Light behind the keys ─────────────────────────

@Composable
private fun BoxScope.BurstItem(b: FxBurst, color: Color, sizePct: Int, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = if (b.style == KB_FX_GLOW) 950 else 720, easing = LinearEasing))
        onDone()
    }
    Canvas(Modifier.matchParentSize()) {
        val t = progress.value
        val e = 1f - (1f - t) * (1f - t)
        val a = (1f - t).coerceIn(0f, 1f)
        val unit = b.rect.height.coerceAtLeast(40f) * (sizePct / 100f)
        val c = Offset(b.x, b.y)
        when (b.style) {
            KB_FX_SPLASH -> {
                val r = unit * (0.45f + 0.35f * e)
                drawCircle(color.copy(alpha = color.alpha * 0.9f * a), r, c)
                val rnd = Random(b.seed)
                for (i in 0 until 9) {
                    val ang = rnd.nextFloat() * 6.2832f
                    val dist = r * (1.0f + rnd.nextFloat() * 0.9f) * (0.6f + 0.4f * e)
                    val rr = r * (0.10f + rnd.nextFloat() * 0.18f)
                    drawCircle(
                        color.copy(alpha = color.alpha * 0.85f * a), rr,
                        Offset(c.x + cos(ang) * dist, c.y + sin(ang) * dist),
                    )
                }
            }
            KB_FX_GLOW -> {
                val radius = unit * 3.2f * (0.4f + 0.6f * e)
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = color.alpha * 0.7f * a), Color.Transparent),
                        center = c, radius = radius,
                    ),
                    radius, c,
                )
            }
            KB_FX_RIPPLE -> {
                val radius = unit * 2.2f * e
                drawCircle(
                    color.copy(alpha = color.alpha * 0.9f * a), radius, c,
                    style = Stroke(width = (4.dp.toPx() * a) + 1.dp.toPx()),
                )
                drawCircle(color.copy(alpha = color.alpha * 0.18f * a), radius, c)
            }
            KB_FX_DOT -> {
                drawCircle(color.copy(alpha = color.alpha * 0.95f * (1f - t * t)), unit * (0.5f + 0.8f * e), c)
            }
            KB_FX_CROSS -> {
                val w = b.rect.width
                val hh = b.rect.height
                val grow = 1f + 0.12f * e
                val col = color.copy(alpha = color.alpha * 0.85f * a)
                val corner = CornerRadius(12f)
                drawRoundRect(
                    col,
                    Offset(b.rect.center.x - 1.5f * w * grow, b.rect.top),
                    Size(3f * w * grow, hh),
                    corner,
                )
                drawRoundRect(
                    col,
                    Offset(b.rect.left, b.rect.center.y - 1.5f * hh * grow),
                    Size(w, 3f * hh * grow),
                    corner,
                )
            }
        }
    }
}

// ───────────────────────── Bubbles and pictures ─────────────────────────

@Composable
private fun PressBubble(info: PressInfo, pal: KbPalette, shapeId: Int, rootW: Int) {
    val density = LocalDensity.current
    val shape = remember(shapeId) { kbBubbleShape(shapeId) }
    val minW = with(density) { 46.dp.toPx() }
    val w = (info.rect.width * 1.55f).coerceAtLeast(minW)
    val h = if (shapeId == KB_SHAPE_ROUNDED) w * 1.2f else w
    val x = (info.rect.center.x - w / 2f).coerceIn(0f, (rootW - w).coerceAtLeast(0f))
    val y = (info.rect.top - h - with(density) { 4.dp.toPx() }).coerceAtLeast(0f)
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .shadow(6.dp, shape)
            .background(pal.preview, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(info.label, color = pal.text, fontSize = 30.sp, maxLines = 1)
    }
}

/** The extra letters of a key, in a bubble above it. Slide to one and let go, or tap one. */
@Composable
private fun AltBubbleView(h: KbHost, b: AltBubble, pal: KbPalette) {
    val fx = h.fx
    val density = LocalDensity.current
    val l = kbAltLayout(b, fx.rootW, fx.density)
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .offset { IntOffset(l.x.roundToInt(), l.y.roundToInt()) }
            .shadow(6.dp, shape)
            .background(pal.preview, shape),
    ) {
        b.alts.forEachIndexed { i, a ->
            Box(
                Modifier
                    .size(with(density) { l.cellW.toDp() }, with(density) { l.cellH.toDp() })
                    .background(if (fx.hover == i) pal.accent.copy(alpha = 0.4f) else Color.Transparent)
                    .clickable {
                        h.typeAlt(a)
                        fx.bubble = null
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(a, color = pal.text, fontSize = 22.sp, maxLines = 1)
            }
        }
    }
}

/** The picture that appears on a pressed key. It can stay inside the key, change color and move. */
@Composable
private fun TapItem(e: TapEffect, cfg: KbConfig, tint: Color?, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 900, easing = LinearEasing))
        onDone()
    }
    val density = LocalDensity.current
    val inside = cfg.pictureInside
    val wanted = with(density) { cfg.effectSize.sp.toPx() }
    val fit = if (inside) minOf(wanted, e.rect.height * 0.62f) else wanted
    val half = fit * 0.6f
    val cx = if (inside) e.x.coerceIn(e.rect.left + half, maxOf(e.rect.left + half, e.rect.right - half)) else e.x
    val cy = if (inside) e.y.coerceIn(e.rect.top + half, maxOf(e.rect.top + half, e.rect.bottom - half)) else e.y
    val motion = cfg.pictureMotion

    val glyph = Modifier
        .graphicsLayer {
            val t = progress.value
            when (motion) {
                KB_PIC_RISE -> {
                    alpha = (1f - t) * 0.9f
                    translationY = -t * 14.dp.toPx()
                    val sc = 0.8f + 0.4f * t
                    scaleX = sc
                    scaleY = sc
                    rotationZ = e.rot
                }
                KB_PIC_POP -> {
                    val sc = if (t < 0.25f) 0.4f + (t / 0.25f) * 0.85f else 1.25f - (t - 0.25f) * 0.3f
                    scaleX = sc
                    scaleY = sc
                    alpha = if (t < 0.5f) 0.95f else (1f - t) * 1.9f
                    rotationZ = e.rot
                }
                KB_PIC_SPIN -> {
                    alpha = (1f - t) * 0.9f
                    val sc = 0.8f + 0.3f * t
                    scaleX = sc
                    scaleY = sc
                    rotationZ = e.rot + t * 220f
                }
                else -> {
                    alpha = (1f - t) * 0.95f
                    val sc = 0.9f + 0.1f * t
                    scaleX = sc
                    scaleY = sc
                    rotationZ = e.rot
                }
            }
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .drawWithContent {
            drawContent()
            // A chosen color paints the picture's shape, so any emoji can have the same color.
            if (tint != null) drawRect(tint, blendMode = BlendMode.SrcIn)
        }
    val fitSp = with(density) { fit.toSp() }

    if (inside) {
        Box(
            Modifier
                .offset { IntOffset(e.rect.left.roundToInt(), e.rect.top.roundToInt()) }
                .size(with(density) { e.rect.width.toDp() }, with(density) { e.rect.height.toDp() })
                .clip(RoundedCornerShape(cfg.keyRadius.dp)),
        ) {
            Text(
                e.symbol,
                fontSize = fitSp,
                modifier = Modifier
                    .offset { IntOffset((cx - half - e.rect.left).roundToInt(), (cy - half - e.rect.top).roundToInt()) }
                    .then(glyph),
            )
        }
    } else {
        Text(
            e.symbol,
            fontSize = fitSp,
            modifier = Modifier
                .offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }
                .then(glyph),
        )
    }
}

// ───────────────────────── Icons ─────────────────────────

@Composable
private fun GlobeIcon(color: Color, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = this.size.minDimension
        val stroke = w * 0.085f
        val r = w / 2f - stroke / 2f
        val c = Offset(w / 2f, w / 2f)
        drawCircle(color, r, c, style = Stroke(width = stroke))
        drawOval(color, Offset(c.x - r * 0.45f, c.y - r), Size(r * 0.9f, r * 2f), style = Stroke(width = stroke))
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = stroke)
        val dy = r * 0.5f
        val dx = sqrt(r * r - dy * dy)
        drawLine(color, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y - dy), strokeWidth = stroke * 0.8f)
        drawLine(color, Offset(c.x - dx, c.y + dy), Offset(c.x + dx, c.y + dy), strokeWidth = stroke * 0.8f)
    }
}

@Composable
private fun SmileyIcon(color: Color, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = this.size.minDimension
        val stroke = w * 0.085f
        val r = w / 2f - stroke / 2f
        val c = Offset(w / 2f, w / 2f)
        drawCircle(color, r, c, style = Stroke(width = stroke))
        drawCircle(color, stroke * 0.8f, Offset(c.x - r * 0.35f, c.y - r * 0.2f))
        drawCircle(color, stroke * 0.8f, Offset(c.x + r * 0.35f, c.y - r * 0.2f))
        drawArc(
            color, 20f, 140f, false,
            Offset(c.x - r * 0.55f, c.y - r * 0.45f), Size(r * 1.1f, r * 1.1f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun DotsIcon(color: Color, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = this.size.minDimension
        for (i in 0 until 3) drawCircle(color, w * 0.09f, Offset(w * (0.2f + 0.3f * i), w / 2f))
    }
}

@Composable
private fun ClipboardIcon(color: Color, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = this.size.minDimension
        val stroke = w * 0.085f
        drawRoundRect(
            color, Offset(w * 0.2f, w * 0.18f), Size(w * 0.6f, w * 0.7f),
            CornerRadius(w * 0.08f), style = Stroke(width = stroke),
        )
        drawRoundRect(color, Offset(w * 0.36f, w * 0.08f), Size(w * 0.28f, w * 0.18f), CornerRadius(w * 0.05f))
        drawLine(color, Offset(w * 0.34f, w * 0.5f), Offset(w * 0.66f, w * 0.5f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.34f, w * 0.68f), Offset(w * 0.66f, w * 0.68f), strokeWidth = stroke)
    }
}

/** Two arrows, left and right: move the keyboard to the other side. */
@Composable
private fun SwapIcon(color: Color, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = this.size.minDimension
        val stroke = w * 0.09f
        val cap = StrokeCap.Round
        drawLine(color, Offset(w * 0.15f, w * 0.35f), Offset(w * 0.85f, w * 0.35f), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(w * 0.85f, w * 0.35f), Offset(w * 0.68f, w * 0.2f), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(w * 0.85f, w * 0.35f), Offset(w * 0.68f, w * 0.5f), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(w * 0.85f, w * 0.68f), Offset(w * 0.15f, w * 0.68f), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(w * 0.15f, w * 0.68f), Offset(w * 0.32f, w * 0.53f), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(w * 0.15f, w * 0.68f), Offset(w * 0.32f, w * 0.83f), strokeWidth = stroke, cap = cap)
    }
}

/** Four corners pointing out: make the keyboard full width again. */
@Composable
private fun ExpandIcon(color: Color, iconSize: Dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = this.size.minDimension
        val stroke = w * 0.09f
        val cap = StrokeCap.Round
        val a = w * 0.15f
        val b = w * 0.85f
        val l = w * 0.28f
        drawLine(color, Offset(a, a), Offset(a + l, a), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(a, a), Offset(a, a + l), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(b, a), Offset(b - l, a), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(b, a), Offset(b, a + l), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(a, b), Offset(a + l, b), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(a, b), Offset(a, b - l), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(b, b), Offset(b - l, b), strokeWidth = stroke, cap = cap)
        drawLine(color, Offset(b, b), Offset(b, b - l), strokeWidth = stroke, cap = cap)
    }
}

/** The thin panel beside a one-handed keyboard: switch side, go back to full width, settings. */
@Composable
private fun OneHandedSide(h: KbHost, pal: KbPalette, modifier: Modifier) {
    val cfg = h.cfg
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clickable { h.setOneHanded(if (cfg.oneHanded == 1) 2 else 1) },
            contentAlignment = Alignment.Center,
        ) { SwapIcon(pal.sub, 24.dp) }
        Box(
            Modifier.size(44.dp).clickable { h.setOneHanded(0) },
            contentAlignment = Alignment.Center,
        ) { ExpandIcon(pal.sub, 24.dp) }
        Box(
            Modifier.size(44.dp).clickable { h.openSettings() },
            contentAlignment = Alignment.Center,
        ) { PathIcon(Glyph.SETTINGS.pathData, pal.sub, 22.dp) }
    }
}

// ───────────────────────── Clipboard ─────────────────────────

@Composable
private fun ClipboardPanel(h: KbHost, pal: KbPalette, rowH: Dp) {
    val cfg = h.cfg
    val clips = h.clips
    val none: (Rect, Offset) -> Unit = { _, _ -> }
    val shape = RoundedCornerShape(cfg.keyRadius.coerceAtLeast(6).dp)

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(rowH * 4),
        ) {
            if (clips.isEmpty()) {
                Text(
                    h.text(R.string.kb_clip_empty),
                    color = pal.sub,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(clips, key = { it.id }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(if (c.pinned) pal.special else pal.key),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                c.text,
                                color = pal.text,
                                fontSize = 15.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { h.pasteClip(c) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                            Box(
                                Modifier.size(40.dp).clickable { h.pinClip(c) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (c.pinned) "★" else "☆", color = if (c.pinned) pal.accent else pal.sub, fontSize = 20.sp)
                            }
                            Box(
                                Modifier.size(40.dp).clickable { h.deleteClip(c) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("×", color = pal.sub, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(rowH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyCap(
                modifier = Modifier.weight(1.4f).fillMaxHeight(),
                st = plainStyle(pal.special, pal.specialDown, cfg),
                repeat = false, hasLong = false,
                onDown = { h.feedback(KeyKind.LETTERS) },
                onStart = none, onEnd = {},
                onTap = { h.closeClipboard() },
                onLong = {}, onLongMove = {}, onLongEnd = {},
            ) {
                Text(if (h.lang == "ar") "أب" else "ABC", color = pal.icon, fontSize = 14.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(3f))
            KeyCap(
                modifier = Modifier.weight(2f).fillMaxHeight(),
                st = plainStyle(pal.special, pal.specialDown, cfg),
                repeat = false, hasLong = false,
                onDown = { h.feedback(KeyKind.LETTERS) },
                onStart = none, onEnd = {},
                onTap = { h.clearClips() },
                onLong = {}, onLongMove = {}, onLongEnd = {},
            ) {
                Text(h.text(R.string.kb_clip_clear_all), color = pal.icon, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

// ───────────────────────── Suggestions bar ─────────────────────────

@Composable
private fun SuggestionBar(h: KbHost, pal: KbPalette) {
    val open = h.toolbarOpen
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clickable { h.setToolbar(!open) },
            contentAlignment = Alignment.Center,
        ) {
            if (open) {
                Text("‹", color = pal.sub, fontSize = 26.sp)
            } else {
                DotsIcon(pal.sub, 22.dp)
            }
        }
        if (open) {
            // The tools: settings, emoji (when moved here), clipboard, one-handed mode.
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(onClick = { h.openSettings() }) { PathIcon(Glyph.SETTINGS.pathData, pal.sub, 22.dp) }
                if (h.cfg.emojiInBar) {
                    ToolButton(onClick = { if (h.page == PG_EMOJI) h.closeEmoji() else h.showEmoji() }) {
                        SmileyIcon(pal.sub, 24.dp)
                    }
                }
                if (h.cfg.clipOn) {
                    ToolButton(onClick = { h.showClipboard() }) { ClipboardIcon(pal.sub, 24.dp) }
                }
                ToolButton(onClick = { h.setOneHanded(if (h.cfg.oneHanded == 0) 1 else 0) }) {
                    if (h.cfg.oneHanded == 0) SwapIcon(pal.sub, 24.dp) else ExpandIcon(pal.sub, 24.dp)
                }
            }
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                val alts = h.alts
                val sug = h.suggestions
                val hint = h.hint
                when {
                    alts.isNotEmpty() -> Row(Modifier.fillMaxSize()) {
                        alts.forEach { a ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable { h.typeAlt(a) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(a, color = pal.text, fontSize = 22.sp, maxLines = 1)
                            }
                        }
                    }
                    sug.isNotEmpty() -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        sug.forEachIndexed { i, word ->
                            if (i > 0) {
                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .fillMaxHeight(0.5f)
                                        .background(pal.sub.copy(alpha = 0.35f)),
                                )
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable { h.pickSuggestion(word) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    word,
                                    color = pal.text,
                                    fontSize = 17.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                            }
                        }
                    }
                    hint != null -> Text(
                        hint,
                        color = pal.sub,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            Spacer(Modifier.width(44.dp))
        }
    }
}

@Composable
private fun RowScope.ToolButton(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ───────────────────────── Keys ─────────────────────────

@Composable
private fun KeysPanel(h: KbHost, pal: KbPalette, rowH: Dp) {
    val cfg = h.cfg
    val idx = when (h.page) {
        PG_LETTERS -> if (h.lang == "ar") 0 else 1
        PG_SYM1 -> 2
        PG_SYM2 -> 3
        else -> 4
    }
    // The person's own arrangement when they edited this page, otherwise the default keys.
    val rows = remember(idx, cfg.layouts, cfg.layoutAr, cfg.layoutEn, h.customSymbols, cfg.emojiInBar, cfg.numberRow) {
        kbPageRows(cfg, idx, h.customSymbols)
            .filter { cfg.numberRow || !it.number }
            .map { r ->
                r.number to r.keys
                    .filter { !(cfg.emojiInBar && it.kind == KeyKind.EMOJI) }
                    .map { KbKey(it.label, it.kind, it.weight, it.alts, it.hScale) }
            }
    }
    Column {
        rows.forEach { (isNumber, keys) ->
            KeyRow(h, pal, keys, if (isNumber) rowH * (cfg.numHeightPct / 100f) else rowH, isNumber)
        }
    }
}

@Composable
private fun KeyRow(h: KbHost, pal: KbPalette, keys: List<KbKey>, height: Dp, numberRow: Boolean) {
    // The row is as tall as its tallest key. Shorter keys sit in the middle.
    val maxH = keys.filter { it.kind != KeyKind.GAP }.maxOfOrNull { it.hScale } ?: 1f
    Row(
        Modifier
            .fillMaxWidth()
            .height(height * maxH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { k ->
            if (k.kind == KeyKind.GAP) {
                Spacer(Modifier.weight(k.weight))
            } else {
                KeyView(h, pal, k, numberRow, Modifier.weight(k.weight).fillMaxHeight(k.hScale / maxH))
            }
        }
    }
}

@Composable
private fun KeyView(h: KbHost, pal: KbPalette, k: KbKey, numberRow: Boolean, modifier: Modifier) {
    val cfg = h.cfg
    val fx = h.fx
    val group = kbGroupOf(k.kind, numberRow)
    val isEnter = k.kind == KeyKind.ENTER
    val special = k.kind != KeyKind.CHAR && k.kind != KeyKind.SPACE
    val base = when {
        isEnter -> pal.accent
        special -> pal.special
        numberRow -> pal.numKey
        else -> pal.key
    }
    val down = when {
        isEnter -> pal.accent.copy(alpha = 0.75f)
        special -> pal.specialDown
        else -> pal.keyDown
    }
    val borderColor = cfg.borderColor.getOrElse(group) { 0 }
    val style = KeyStyle(
        base = base,
        pressed = down,
        radius = cfg.keyRadius.dp,
        gapH = cfg.keyGap.dp,
        gapV = (cfg.keyGap * 1.5f).dp,
        borderW = (cfg.borderWidth.getOrElse(group) { 0 } / 10f).dp,
        borderC = if (borderColor != 0) Color(borderColor) else pal.text.copy(alpha = 0.5f),
        fxStyle = cfg.keyFxStyle,
        fxColor = pal.keyFx,
        motion = cfg.keyMotion,
    )
    val upper = h.page == PG_LETTERS && h.lang == "en" && h.shift != 0
    // These keys' names depend on where the keyboard is, so they are worked out here.
    val label = when (k.kind) {
        KeyKind.LETTERS -> if (h.lang == "ar") "أب" else "ABC"
        KeyKind.SYMBOLS -> "?123"
        KeyKind.SYM_PAGE -> when (h.page) {
            PG_SYM1 -> "=\\<"
            PG_SYM2 -> if (h.customSymbols.isNotEmpty()) "★" else "?123"
            else -> "?123"
        }
        else -> k.label
    }
    val shown = if (k.kind == KeyKind.CHAR && upper) label.uppercase() else label
    val hintChar = if (cfg.showHints && k.kind == KeyKind.CHAR) k.alts.firstOrNull()?.removePrefix("◌") else null
    val iconColor = if (isEnter) pal.onAccent else pal.icon
    val letterSize = (22f * cfg.textPct / 100f).sp

    KeyCap(
        modifier = modifier,
        st = style,
        repeat = k.kind == KeyKind.BACKSPACE,
        hasLong = (k.kind == KeyKind.CHAR && k.alts.isNotEmpty()) || k.kind == KeyKind.SPACE,
        onDown = { h.feedback(k.kind) },
        onStart = { rect, touch ->
            fx.bubble = null
            if (k.kind == KeyKind.CHAR && shown.isNotBlank()) fx.showPress(PressInfo(shown, rect))
            fx.spawn(cfg, rect, touch)
        },
        onEnd = { fx.hidePress() },
        onTap = {
            when (k.kind) {
                KeyKind.CHAR -> h.typeChar(k.label)
                KeyKind.SPACE -> h.onSpace()
                KeyKind.BACKSPACE -> h.onBackspace()
                KeyKind.ENTER -> h.onEnter()
                KeyKind.SHIFT -> h.onShift()
                KeyKind.SYMBOLS -> h.showSymbols()
                KeyKind.LETTERS -> h.showLetters()
                KeyKind.SYM_PAGE -> h.togglePage()
                KeyKind.LANG -> h.toggleLang()
                KeyKind.EMOJI -> h.showEmoji()
                KeyKind.GAP -> {}
            }
        },
        onLong = { rect ->
            if (k.kind == KeyKind.SPACE) {
                h.showPicker()
            } else if (cfg.altsInBubble) {
                fx.hidePressNow()
                fx.bubble = AltBubble(k.alts, rect)
            } else {
                h.showAlts(k.alts)
            }
        },
        onLongMove = { pos -> fx.hover = fx.altIndexAt(pos) },
        onLongEnd = {
            val b = fx.bubble
            val i = fx.hover
            if (b != null && i in b.alts.indices) {
                h.typeAlt(b.alts[i])
                fx.bubble = null
            }
            fx.hover = -1
        },
    ) {
        when (k.kind) {
            KeyKind.BACKSPACE -> PathIcon(BACKSPACE_PATH, iconColor, 24.dp)
            KeyKind.SHIFT -> PathIcon(
                if (h.shift == 2) CAPS_PATH else SHIFT_PATH,
                if (h.shift != 0) pal.accent else iconColor,
                24.dp,
            )
            KeyKind.ENTER ->
                if (h.enterLabel.isEmpty()) {
                    PathIcon(ENTER_PATH, iconColor, 24.dp)
                } else {
                    Text(h.enterLabel, color = iconColor, fontSize = 14.sp, maxLines = 1)
                }
            KeyKind.LANG -> GlobeIcon(iconColor, 24.dp)
            KeyKind.EMOJI -> SmileyIcon(iconColor, 24.dp)
            KeyKind.SPACE -> Text(
                if (h.lang == "ar") "العربية" else "English",
                color = pal.sub,
                fontSize = 12.sp,
                maxLines = 1,
            )
            KeyKind.GAP -> {}
            else -> {
                Text(
                    shown,
                    color = if (special) pal.icon else pal.text,
                    fontSize = if (shown.length > 1 && special) 14.sp else letterSize,
                    maxLines = 1,
                )
                if (hintChar != null) {
                    Text(
                        hintChar,
                        color = pal.sub,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 3.dp, end = 5.dp),
                    )
                }
            }
        }
    }
}

/**
 * One key. A tap types on release. [repeat] keys (backspace) type on press and keep going while
 * held. [hasLong] keys do their long-press action after a short hold, and then follow the finger
 * so the extra letters can be picked by sliding.
 * The pressed look (color, glow, ring...) and the motion (tilt, shrink...) fade in and out.
 */
@Composable
private fun KeyCap(
    modifier: Modifier,
    st: KeyStyle,
    repeat: Boolean,
    hasLong: Boolean,
    onDown: () -> Unit,
    onStart: (Rect, Offset) -> Unit,
    onEnd: () -> Unit,
    onTap: () -> Unit,
    onLong: (Rect) -> Unit,
    onLongMove: (Offset) -> Unit,
    onLongEnd: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val holder = remember { RectHolder() }
    val scope = rememberCoroutineScope()
    val glow = remember { Animatable(0f) }
    val motion = remember { Animatable(0f) }
    val tilt = remember { floatArrayOf(1f) }
    val style by rememberUpdatedState(st)
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val longMove by rememberUpdatedState(onLongMove)
    val longEnd by rememberUpdatedState(onLongEnd)
    val press by rememberUpdatedState(onDown)
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    val shape = RoundedCornerShape(st.radius)

    Box(
        modifier = modifier
            .onGloballyPositioned { holder.rect = it.boundsInRoot() }
            .pointerInput(repeat, hasLong) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    tilt[0] = if (first.position.x < size.width / 2f) -1f else 1f
                    scope.launch { glow.snapTo(1f) }
                    scope.launch { motion.animateTo(1f, tween<Float>(70)) }
                    try {
                        press()
                        start(holder.rect, first.position + holder.rect.topLeft)
                        if (repeat) {
                            tap()
                            var timedOut = true
                            withTimeoutOrNull(380L) {
                                waitForUpOrCancellation()
                                timedOut = false
                            }
                            while (timedOut) {
                                tap()
                                timedOut = true
                                withTimeoutOrNull(55L) {
                                    waitForUpOrCancellation()
                                    timedOut = false
                                }
                            }
                        } else if (hasLong) {
                            var timedOut = true
                            var up: PointerInputChange? = null
                            withTimeoutOrNull(380L) {
                                up = waitForUpOrCancellation()
                                timedOut = false
                            }
                            if (timedOut) {
                                long(holder.rect)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull() ?: break
                                    if (ch.isConsumed) break
                                    longMove(ch.position + holder.rect.topLeft)
                                    if (!ch.pressed) {
                                        longEnd()
                                        break
                                    }
                                    ch.consume()
                                }
                            } else if (up != null) {
                                tap()
                            }
                        } else {
                            if (waitForUpOrCancellation() != null) tap()
                        }
                    } finally {
                        val s = style
                        scope.launch {
                            glow.animateTo(0f, tween<Float>(if (s.fxStyle == KB_KEYFX_DEFAULT) 140 else 320))
                        }
                        scope.launch {
                            if (s.motion == KB_MOTION_BOUNCE) {
                                motion.animateTo(0f, spring<Float>(dampingRatio = 0.35f, stiffness = 450f))
                            } else {
                                motion.animateTo(0f, tween<Float>(130))
                            }
                        }
                        end()
                    }
                }
            }
            .padding(horizontal = st.gapH, vertical = st.gapV)
            .graphicsLayer {
                val p = motion.value
                when (style.motion) {
                    KB_MOTION_TILT -> {
                        rotationZ = p * 7f * tilt[0]
                        val sc = 1f - 0.03f * p
                        scaleX = sc
                        scaleY = sc
                    }
                    KB_MOTION_SHRINK -> {
                        val sc = 1f - 0.14f * p
                        scaleX = sc
                        scaleY = sc
                    }
                    KB_MOTION_GROW -> {
                        val sc = 1f + 0.14f * p
                        scaleX = sc
                        scaleY = sc
                    }
                    KB_MOTION_LIFT -> {
                        translationY = -6.dp.toPx() * p
                        val sc = 1f + 0.04f * p
                        scaleX = sc
                        scaleY = sc
                    }
                    KB_MOTION_BOUNCE -> {
                        val sc = 1f - 0.16f * p
                        scaleX = sc
                        scaleY = sc
                    }
                }
            }
            .clip(shape)
            .background(st.base)
            .drawBehind {
                val g = glow.value
                if (g > 0.001f) {
                    val s = style
                    val corner = CornerRadius(s.radius.toPx())
                    when (s.fxStyle) {
                        KB_KEYFX_SOLID -> drawRect(s.fxColor.copy(alpha = s.fxColor.alpha * g))
                        KB_KEYFX_SOFT -> drawRect(s.fxColor.copy(alpha = s.fxColor.alpha * 0.45f * g))
                        KB_KEYFX_RING -> {
                            drawRect(s.pressed.copy(alpha = s.pressed.alpha * 0.4f * g))
                            val w = 2.5.dp.toPx()
                            drawRoundRect(
                                s.fxColor.copy(alpha = s.fxColor.alpha * g),
                                Offset(w / 2f, w / 2f), Size(size.width - w, size.height - w), corner,
                                style = Stroke(width = w),
                            )
                        }
                        KB_KEYFX_GRADIENT -> drawRect(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, s.fxColor.copy(alpha = s.fxColor.alpha * g)),
                            ),
                        )
                        KB_KEYFX_GLOW -> {
                            val w1 = 6.dp.toPx()
                            val w2 = 2.dp.toPx()
                            drawRoundRect(
                                s.fxColor.copy(alpha = s.fxColor.alpha * 0.4f * g),
                                Offset(w1 / 2f, w1 / 2f), Size(size.width - w1, size.height - w1), corner,
                                style = Stroke(width = w1),
                            )
                            drawRoundRect(
                                s.fxColor.copy(alpha = s.fxColor.alpha * g),
                                Offset(w2 / 2f, w2 / 2f), Size(size.width - w2, size.height - w2), corner,
                                style = Stroke(width = w2),
                            )
                        }
                        else -> drawRect(s.pressed.copy(alpha = s.pressed.alpha * g))
                    }
                }
            }
            .then(if (st.borderW > 0.dp) Modifier.border(st.borderW, st.borderC, shape) else Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ───────────────────────── Emoji ─────────────────────────

@Composable
private fun EmojiPanel(h: KbHost, pal: KbPalette, rowH: Dp) {
    val ctx = LocalContext.current
    val cfg = h.cfg
    var cat by remember { mutableIntStateOf(if (KbPrefs.recentEmoji(ctx).isEmpty()) 1 else 0) }
    val list = remember(cat, h.recentVersion) {
        if (cat == 0) KbPrefs.recentEmoji(ctx) else kbSupportedEmoji(kbEmojiList(KB_EMOJI[cat]))
    }
    val none: (Rect, Offset) -> Unit = { _, _ -> }

    Column {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 42.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(rowH * 4),
        ) {
            items(list) { e ->
                Box(
                    Modifier
                        .height(46.dp)
                        .clickable { h.typeEmoji(e) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(e, fontSize = 26.sp)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(rowH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyCap(
                modifier = Modifier.weight(1.4f).fillMaxHeight(),
                st = plainStyle(pal.special, pal.specialDown, cfg),
                repeat = false, hasLong = false,
                onDown = { h.feedback(KeyKind.LETTERS) },
                onStart = none, onEnd = {},
                onTap = { h.closeEmoji() },
                onLong = {}, onLongMove = {}, onLongEnd = {},
            ) {
                Text(if (h.lang == "ar") "أب" else "ABC", color = pal.icon, fontSize = 14.sp, maxLines = 1)
            }
            KB_EMOJI.forEachIndexed { i, c ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp, horizontal = 1.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (i == cat) pal.key else Color.Transparent)
                        .clickable { cat = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(c.icon, fontSize = 18.sp)
                }
            }
            KeyCap(
                modifier = Modifier.weight(1.5f).fillMaxHeight(),
                st = plainStyle(pal.special, pal.specialDown, cfg),
                repeat = true, hasLong = false,
                onDown = { h.feedback(KeyKind.BACKSPACE) },
                onStart = none, onEnd = {},
                onTap = { h.onBackspace() },
                onLong = {}, onLongMove = {}, onLongEnd = {},
            ) {
                PathIcon(BACKSPACE_PATH, pal.icon, 24.dp)
            }
        }
    }
}
