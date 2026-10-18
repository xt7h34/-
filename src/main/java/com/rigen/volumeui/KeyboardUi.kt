package com.rigen.volumeui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Kanade Keyboard: what is drawn. The keys always sit left to right, exactly as laid out in
 * KeyboardLayouts.kt, even when the phone language is Arabic.
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

@Composable
internal fun KeyboardRoot(s: KanadeKeyboardService) {
    val cfg = s.cfg
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rowH = (48f * cfg.heightPct / 100f * (if (landscape) 0.8f else 1f)).dp
    var rootW by remember { mutableIntStateOf(0) }
    val bgBrush = Brush.verticalGradient(listOf(pal.bg, pal.bg2 ?: pal.bg))

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().onSizeChanged { rootW = it.width }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(bgBrush)
                    .navigationBarsPadding()
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            ) {
                SuggestionBar(s, pal)
                if (s.page == PG_EMOJI) EmojiPanel(s, pal, rowH) else KeysPanel(s, pal, rowH)
            }
            // Drawn above the keys; they never take touches.
            val p = s.press
            if (p != null && cfg.previewShape != KB_SHAPE_NONE) PressBubble(p, pal, cfg.previewShape, rootW)
            s.effects.forEach { e ->
                key(e.id) { EffectItem(e, cfg.effectSize) { s.effects.remove(e) } }
            }
        }
    }
}

// ───────────────────────── Bubble and picture ─────────────────────────

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

@Composable
private fun EffectItem(e: TapEffect, sizeSp: Int, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 900, easing = LinearEasing))
        onDone()
    }
    val half = with(LocalDensity.current) { sizeSp.sp.toPx() } * 0.6f
    Text(
        e.symbol,
        fontSize = sizeSp.sp,
        modifier = Modifier
            .offset { IntOffset((e.x - half).roundToInt(), (e.y - half - progress.value * 14.dp.toPx()).roundToInt()) }
            .graphicsLayer {
                alpha = (1f - progress.value) * 0.9f
                val sc = 0.8f + 0.4f * progress.value
                scaleX = sc
                scaleY = sc
                rotationZ = e.rot
            },
    )
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

// ───────────────────────── Suggestions bar ─────────────────────────

@Composable
private fun SuggestionBar(s: KanadeKeyboardService, pal: KbPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clickable { s.openSettings() },
            contentAlignment = Alignment.Center,
        ) {
            PathIcon(Glyph.SETTINGS.pathData, pal.sub, 20.dp)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val alts = s.alts
            val sug = s.suggestions
            val hint = s.hint
            when {
                alts.isNotEmpty() -> Row(Modifier.fillMaxSize()) {
                    alts.forEach { a ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { s.typeAlt(a) },
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
                                .clickable { s.pickSuggestion(word) },
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

// ───────────────────────── Keys ─────────────────────────

@Composable
private fun KeysPanel(s: KanadeKeyboardService, pal: KbPalette, rowH: Dp) {
    val symbols = s.page != PG_LETTERS
    val raw = if (s.lang == "ar") s.cfg.layoutAr else s.cfg.layoutEn
    val rows = remember(s.lang, s.page, raw, s.customSymbols) {
        if (symbols) {
            val n = when (s.page) {
                PG_SYM2 -> 2
                PG_SYM3 -> 3
                else -> 1
            }
            kbSymbolRows(n, s.customSymbols)
        } else {
            kbLetterRows(s.lang, raw)
        }
    }
    val bottom = remember(s.lang, symbols) { kbBottomRow(s.lang, symbols) }
    val numbers = remember { kbNumberRow() }
    Column {
        if (s.cfg.numberRow) KeyRow(s, pal, numbers, rowH * 0.85f, true)
        rows.forEach { KeyRow(s, pal, it, rowH, false) }
        KeyRow(s, pal, bottom, rowH, false)
    }
}

@Composable
private fun KeyRow(s: KanadeKeyboardService, pal: KbPalette, keys: List<KbKey>, height: Dp, numberRow: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        keys.forEach { k ->
            if (k.kind == KeyKind.GAP) {
                Spacer(Modifier.weight(k.weight))
            } else {
                KeyView(s, pal, k, numberRow, Modifier.weight(k.weight).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun KeyView(s: KanadeKeyboardService, pal: KbPalette, k: KbKey, numberRow: Boolean, modifier: Modifier) {
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
    val upper = s.page == PG_LETTERS && s.lang == "en" && s.shift != 0
    val shown = if (k.kind == KeyKind.CHAR && upper) k.label.uppercase() else k.label
    val hintChar = if (k.kind == KeyKind.CHAR) k.alts.firstOrNull()?.removePrefix("◌") else null
    val iconColor = if (isEnter) pal.onAccent else pal.icon

    KeyCap(
        modifier = modifier,
        baseColor = base,
        pressedColor = down,
        repeat = k.kind == KeyKind.BACKSPACE,
        hasLong = (k.kind == KeyKind.CHAR && k.alts.isNotEmpty()) || k.kind == KeyKind.SPACE,
        onDown = { s.feedback(k.kind) },
        onStart = { rect, touch ->
            if (k.kind == KeyKind.CHAR && shown.isNotBlank()) s.showPress(PressInfo(shown, rect))
            s.spawnEffect(touch)
        },
        onEnd = { s.hidePress() },
        onTap = {
            when (k.kind) {
                KeyKind.CHAR -> s.typeChar(k.label)
                KeyKind.SPACE -> s.onSpace()
                KeyKind.BACKSPACE -> s.onBackspace()
                KeyKind.ENTER -> s.onEnter()
                KeyKind.SHIFT -> s.onShift()
                KeyKind.SYMBOLS -> s.showSymbols()
                KeyKind.LETTERS -> s.showLetters()
                KeyKind.SYM_PAGE -> s.togglePage()
                KeyKind.LANG -> s.toggleLang()
                KeyKind.EMOJI -> s.showEmoji()
                KeyKind.GAP -> {}
            }
        },
        onLong = {
            if (k.kind == KeyKind.SPACE) s.showPicker() else s.alts = k.alts
        },
    ) {
        when (k.kind) {
            KeyKind.BACKSPACE -> PathIcon(BACKSPACE_PATH, iconColor, 24.dp)
            KeyKind.SHIFT -> PathIcon(
                if (s.shift == 2) CAPS_PATH else SHIFT_PATH,
                if (s.shift != 0) pal.accent else iconColor,
                24.dp,
            )
            KeyKind.ENTER ->
                if (s.enterLabel.isEmpty()) {
                    PathIcon(ENTER_PATH, iconColor, 24.dp)
                } else {
                    Text(s.enterLabel, color = iconColor, fontSize = 14.sp, maxLines = 1)
                }
            KeyKind.LANG -> GlobeIcon(iconColor, 24.dp)
            KeyKind.EMOJI -> SmileyIcon(iconColor, 24.dp)
            KeyKind.SPACE -> Text(
                if (s.lang == "ar") "العربية" else "English",
                color = pal.sub,
                fontSize = 12.sp,
                maxLines = 1,
            )
            KeyKind.GAP -> {}
            else -> {
                Text(
                    shown,
                    color = if (special) pal.icon else pal.text,
                    fontSize = if (shown.length > 1 && special) 14.sp else 22.sp,
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
 * held. [hasLong] keys show their long-press action after a short hold.
 * [onStart] and [onEnd] tell the keyboard when the finger goes down and up, for the bubble.
 */
@Composable
private fun KeyCap(
    modifier: Modifier,
    baseColor: Color,
    pressedColor: Color,
    repeat: Boolean,
    hasLong: Boolean,
    onDown: () -> Unit,
    onStart: (Rect, Offset) -> Unit,
    onEnd: () -> Unit,
    onTap: () -> Unit,
    onLong: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val holder = remember { RectHolder() }
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val press by rememberUpdatedState(onDown)
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)

    Box(
        modifier = modifier
            .onGloballyPositioned { holder.rect = it.boundsInRoot() }
            .pointerInput(repeat, hasLong) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
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
                                long()
                                waitForUpOrCancellation()
                            } else if (up != null) {
                                tap()
                            }
                        } else {
                            if (waitForUpOrCancellation() != null) tap()
                        }
                    } finally {
                        pressed = false
                        end()
                    }
                }
            }
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) pressedColor else baseColor),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ───────────────────────── Emoji ─────────────────────────

@Composable
private fun EmojiPanel(s: KanadeKeyboardService, pal: KbPalette, rowH: Dp) {
    val ctx = LocalContext.current
    var cat by remember { mutableIntStateOf(if (KbPrefs.recentEmoji(ctx).isEmpty()) 1 else 0) }
    val list = remember(cat, s.recentVersion) {
        if (cat == 0) KbPrefs.recentEmoji(ctx) else kbSupportedEmoji(kbEmojiList(KB_EMOJI[cat]))
    }

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
                        .clickable { s.typeEmoji(e) },
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
                baseColor = pal.special, pressedColor = pal.specialDown, repeat = false, hasLong = false,
                onDown = { s.feedback(KeyKind.LETTERS) },
                onStart = { _, _ -> }, onEnd = {},
                onTap = { s.closeEmoji() },
                onLong = {},
            ) {
                Text(if (s.lang == "ar") "أب" else "ABC", color = pal.icon, fontSize = 14.sp, maxLines = 1)
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
                baseColor = pal.special, pressedColor = pal.specialDown, repeat = true, hasLong = false,
                onDown = { s.feedback(KeyKind.BACKSPACE) },
                onStart = { _, _ -> }, onEnd = {},
                onTap = { s.onBackspace() },
                onLong = {},
            ) {
                PathIcon(BACKSPACE_PATH, pal.icon, 24.dp)
            }
        }
    }
}
