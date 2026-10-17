package com.rigen.volumeui

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection

/*
 * Kanade Keyboard: what is drawn. The keys always sit left to right, exactly as laid out in
 * KeyboardLayouts.kt, even when the phone language is Arabic.
 */

private const val BACKSPACE_PATH =
    "M22,3H7c-0.69,0 -1.23,0.35 -1.59,0.88L0,12l5.41,8.11c0.36,0.53 0.9,0.89 1.59,0.89h15c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM19,15.59L17.59,17 14,13.41 10.41,17 9,15.59 12.59,12 9,8.41 10.41,7 14,10.59 17.59,7 19,8.41 15.41,12 19,15.59z"
private const val SHIFT_PATH = "M12,3 L3,13 H8 V20 H16 V13 H21 Z"
private const val CAPS_PATH = "M12,2 L3,12 H8 V17 H16 V12 H21 Z M8,19 H16 V22 H8 Z"
private const val ENTER_PATH = "M19,7v4H5.83l3.58,-3.59L8,6l-6,6 6,6 1.41,-1.41L5.83,13H21V7z"

@Composable
internal fun KeyboardRoot(s: KanadeKeyboardService) {
    val cfg = s.cfg
    val pal = kbPalette(cfg.theme, isSystemInDarkTheme())
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rowH = (48f * cfg.heightPct / 100f * (if (landscape) 0.8f else 1f)).dp

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(pal.bg)
                .navigationBarsPadding()
                .padding(horizontal = 3.dp, vertical = 2.dp),
        ) {
            SuggestionBar(s, pal)
            if (s.page == PG_EMOJI) EmojiPanel(s, pal, rowH) else KeysPanel(s, pal, rowH)
        }
    }
}

// ───────────────────────── Suggestions bar ─────────────────────────

@Composable
private fun SuggestionBar(s: KanadeKeyboardService, pal: KbPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp),
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
    val rows = remember(s.lang, s.page) {
        if (symbols) kbSymbolRows(if (s.page == PG_SYM2) 2 else 1) else kbLetterRows(s.lang)
    }
    val bottom = remember(s.lang, symbols) { kbBottomRow(s.lang, symbols) }
    val numbers = remember { kbNumberRow() }
    Column {
        if (s.cfg.numberRow) KeyRow(s, pal, numbers, rowH * 0.85f)
        rows.forEach { KeyRow(s, pal, it, rowH) }
        KeyRow(s, pal, bottom, rowH)
    }
}

@Composable
private fun KeyRow(s: KanadeKeyboardService, pal: KbPalette, keys: List<KbKey>, height: Dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        keys.forEach { k ->
            if (k.kind == KeyKind.GAP) {
                Spacer(Modifier.weight(k.weight))
            } else {
                KeyView(s, pal, k, Modifier.weight(k.weight).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun KeyView(s: KanadeKeyboardService, pal: KbPalette, k: KbKey, modifier: Modifier) {
    val isEnter = k.kind == KeyKind.ENTER
    val special = k.kind != KeyKind.CHAR && k.kind != KeyKind.SPACE
    val base = when {
        isEnter -> pal.accent
        special -> pal.special
        else -> pal.key
    }
    val down = when {
        isEnter -> pal.accent.copy(alpha = 0.75f)
        special -> pal.specialDown
        else -> pal.keyDown
    }
    val upper = s.page == PG_LETTERS && s.lang == "en" && s.shift != 0
    val shown = if (k.kind == KeyKind.CHAR && upper) k.label.uppercase() else k.label
    val hasAlts = k.kind == KeyKind.CHAR && k.alts.isNotEmpty()

    KeyCap(
        modifier = modifier,
        base = base,
        down = down,
        repeat = k.kind == KeyKind.BACKSPACE,
        hasLong = hasAlts || k.kind == KeyKind.SPACE,
        onDown = { s.feedback(k.kind) },
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
            KeyKind.BACKSPACE -> PathIcon(BACKSPACE_PATH, pal.text, 24.dp)
            KeyKind.SHIFT -> PathIcon(
                if (s.shift == 2) CAPS_PATH else SHIFT_PATH,
                if (s.shift != 0) pal.accent else pal.text,
                24.dp,
            )
            KeyKind.ENTER ->
                if (s.enterLabel.isEmpty()) {
                    PathIcon(ENTER_PATH, pal.onAccent, 24.dp)
                } else {
                    Text(s.enterLabel, color = pal.onAccent, fontSize = 14.sp, maxLines = 1)
                }
            KeyKind.EMOJI -> Text("\uD83D\uDE42", fontSize = 20.sp)
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
                    color = pal.text,
                    fontSize = if (shown.length > 1 && special) 14.sp else 22.sp,
                    maxLines = 1,
                )
                if (hasAlts) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(pal.sub),
                    )
                }
            }
        }
    }
}

/**
 * One key. A tap types on release. [repeat] keys (backspace) type on press and keep going while
 * held. [hasLong] keys show their long-press action after a short hold.
 */
@Composable
private fun KeyCap(
    modifier: Modifier,
    base: Color,
    down: Color,
    repeat: Boolean,
    hasLong: Boolean,
    onDown: () -> Unit,
    onTap: () -> Unit,
    onLong: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val press by rememberUpdatedState(onDown)

    Box(
        modifier = modifier
            .pointerInput(repeat, hasLong) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    try {
                        press()
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
                    }
                }
            }
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) down else base),
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
                base = pal.special, down = pal.specialDown, repeat = false, hasLong = false,
                onDown = { s.feedback(KeyKind.LETTERS) },
                onTap = { s.closeEmoji() },
                onLong = {},
            ) {
                Text(if (s.lang == "ar") "أب" else "ABC", color = pal.text, fontSize = 14.sp, maxLines = 1)
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
                base = pal.special, down = pal.specialDown, repeat = true, hasLong = false,
                onDown = { s.feedback(KeyKind.BACKSPACE) },
                onTap = { s.onBackspace() },
                onLong = {},
            ) {
                PathIcon(BACKSPACE_PATH, pal.text, 24.dp)
            }
        }
    }
}
