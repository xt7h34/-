package com.rigen.volumeui

import android.content.Context
import androidx.compose.ui.graphics.Color

/*
 * Kanade Keyboard: settings and colors.
 * Everything here is stored on the phone only. The keyboard never uses the network.
 */


internal const val KB_THEME_AUTO = 0
internal const val KB_THEME_LIGHT = 1
internal const val KB_THEME_DARK = 2
internal const val KB_THEME_KANADE = 3

internal const val KB_SHAPE_NONE = 0
internal const val KB_SHAPE_ROUNDED = 1
internal const val KB_SHAPE_CIRCLE = 2
internal const val KB_SHAPE_DIAMOND = 3
internal const val KB_SHAPE_HEXAGON = 4
internal const val KB_SHAPE_STAR = 5
internal const val KB_SHAPE_HEART = 6

/** How many pages of keys can be edited. */
internal const val KB_PAGES = 4

// Key groups, each with its own border.
internal const val KB_GROUPS = 9
internal const val KB_G_LETTERS = 0
internal const val KB_G_NUMBERS = 1
internal const val KB_G_SPACE = 2
internal const val KB_G_DELETE = 3
internal const val KB_G_SHIFT = 4
internal const val KB_G_LANG = 5
internal const val KB_G_EMOJI = 6
internal const val KB_G_ENTER = 7
internal const val KB_G_SYMBOLS = 8

// Light that appears behind the keys.
internal const val KB_FX_NONE = 0
internal const val KB_FX_SPLASH = 1
internal const val KB_FX_GLOW = 2
internal const val KB_FX_RIPPLE = 3
internal const val KB_FX_DOT = 4
internal const val KB_FX_CROSS = 5

// How the pressed key itself is colored.
internal const val KB_KEYFX_DEFAULT = 0
internal const val KB_KEYFX_SOLID = 1
internal const val KB_KEYFX_SOFT = 2
internal const val KB_KEYFX_RING = 3
internal const val KB_KEYFX_GRADIENT = 4
internal const val KB_KEYFX_GLOW = 5

// How the pressed key moves.
internal const val KB_MOTION_NONE = 0
internal const val KB_MOTION_TILT = 1
internal const val KB_MOTION_SHRINK = 2
internal const val KB_MOTION_GROW = 3
internal const val KB_MOTION_LIFT = 4
internal const val KB_MOTION_BOUNCE = 5

// How the picture on a key moves.
internal const val KB_PIC_FADE = 0
internal const val KB_PIC_RISE = 1
internal const val KB_PIC_POP = 2
internal const val KB_PIC_SPIN = 3

/** Suggestions start only after the keyboard has learned from the person for this many days. */
internal const val KB_LEARN_DAYS = 7

/** A color of 0 means "use the theme's color". Everything else is the person's own choice (ARGB). */
internal data class KbConfig(
    val theme: Int = KB_THEME_AUTO,
    val heightPct: Int = 100,
    val haptics: Boolean = true,
    val sound: Boolean = false,
    val numberRow: Boolean = true,
    val suggest: Boolean = true,
    val learn: Boolean = true,
    val showHint: Boolean = true,
    val earlySuggest: Boolean = false,
    val lang: String = "ar",
    val colBg: Int = 0,
    val colBg2: Int = 0,
    val colKey: Int = 0,
    val colNumKey: Int = 0,
    val colSpecial: Int = 0,
    val colText: Int = 0,
    val colIcon: Int = 0,
    val colPress: Int = 0,
    val colAccent: Int = 0,
    val colPreview: Int = 0,
    val colHint: Int = 0,
    val previewShape: Int = KB_SHAPE_ROUNDED,
    val effectOn: Boolean = false,
    val effectChars: String = "\uD83D\uDC3E",
    val effectSize: Int = 26,
    val layoutAr: String = "",
    val layoutEn: String = "",
    val symbols: String = "",
    // Borders: one color and one thickness (in tenths of a dp) for each key group.
    val borderColor: List<Int> = List(KB_GROUPS) { 0 },
    val borderWidth: List<Int> = List(KB_GROUPS) { 0 },
    // Key size and shape.
    val numHeightPct: Int = 85,
    val keyGap: Int = 2,
    val keyRadius: Int = 8,
    val textPct: Int = 100,
    // Hints and extra letters.
    val showHints: Boolean = true,
    val altsInBubble: Boolean = false,
    val emojiInBar: Boolean = false,
    // Effects.
    val fxStyle: Int = KB_FX_NONE,
    val colFx: Int = 0,
    val fxSize: Int = 100,
    val keyFxStyle: Int = KB_KEYFX_DEFAULT,
    val colKeyFx: Int = 0,
    val keyMotion: Int = KB_MOTION_NONE,
    val colPicture: Int = 0,
    val pictureInside: Boolean = true,
    val pictureMotion: Int = KB_PIC_FADE,
    // Each page's keys after the person edited them (size, position, added keys). "" = the default.
    // Pages: 0 Arabic letters, 1 English letters, 2 symbols 1, 3 symbols 2.
    val layouts: List<String> = List(KB_PAGES) { "" },
)

internal object KbPrefs {
    private const val FILE = "kanade_keyboard"
    private const val DAY = 24L * 60 * 60 * 1000

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): KbConfig {
        val p = sp(c)
        val d = KbConfig()
        return KbConfig(
            theme = p.getInt("theme", d.theme),
            heightPct = p.getInt("height", d.heightPct).coerceIn(80, 130),
            haptics = p.getBoolean("haptics", d.haptics),
            sound = p.getBoolean("sound", d.sound),
            numberRow = p.getBoolean("number_row", d.numberRow),
            suggest = p.getBoolean("suggest", d.suggest),
            learn = p.getBoolean("learn", d.learn),
            showHint = p.getBoolean("hint", d.showHint),
            earlySuggest = p.getBoolean("early", d.earlySuggest),
            lang = p.getString("lang", d.lang) ?: d.lang,
            colBg = p.getInt("c_bg", 0),
            colBg2 = p.getInt("c_bg2", 0),
            colKey = p.getInt("c_key", 0),
            colNumKey = p.getInt("c_num", 0),
            colSpecial = p.getInt("c_special", 0),
            colText = p.getInt("c_text", 0),
            colIcon = p.getInt("c_icon", 0),
            colPress = p.getInt("c_press", 0),
            colAccent = p.getInt("c_accent", 0),
            colPreview = p.getInt("c_preview", 0),
            colHint = p.getInt("c_hint", 0),
            previewShape = p.getInt("shape", d.previewShape).coerceIn(0, 6),
            effectOn = p.getBoolean("effect_on", d.effectOn),
            effectChars = p.getString("effect_chars", d.effectChars) ?: d.effectChars,
            effectSize = p.getInt("effect_size", d.effectSize).coerceIn(14, 48),
            layoutAr = p.getString("layout_ar", "") ?: "",
            layoutEn = p.getString("layout_en", "") ?: "",
            symbols = p.getString("symbols", "") ?: "",
            borderColor = readList(p.getString("border_c", ""), 0),
            borderWidth = readList(p.getString("border_w", ""), 0).map { it.coerceIn(0, 60) },
            numHeightPct = p.getInt("num_height", d.numHeightPct).coerceIn(50, 120),
            keyGap = p.getInt("key_gap", d.keyGap).coerceIn(0, 8),
            keyRadius = p.getInt("key_radius", d.keyRadius).coerceIn(0, 24),
            textPct = p.getInt("text_pct", d.textPct).coerceIn(70, 140),
            showHints = p.getBoolean("show_hints", d.showHints),
            altsInBubble = p.getBoolean("alts_bubble", d.altsInBubble),
            emojiInBar = p.getBoolean("emoji_bar", d.emojiInBar),
            fxStyle = p.getInt("fx_style", d.fxStyle).coerceIn(0, 5),
            colFx = p.getInt("c_fx", 0),
            fxSize = p.getInt("fx_size", d.fxSize).coerceIn(50, 160),
            keyFxStyle = p.getInt("keyfx_style", d.keyFxStyle).coerceIn(0, 5),
            colKeyFx = p.getInt("c_keyfx", 0),
            keyMotion = p.getInt("key_motion", d.keyMotion).coerceIn(0, 5),
            colPicture = p.getInt("c_picture", 0),
            pictureInside = p.getBoolean("pic_inside", d.pictureInside),
            pictureMotion = p.getInt("pic_motion", d.pictureMotion).coerceIn(0, 3),
            layouts = List(KB_PAGES) { p.getString("layout2_$it", "") ?: "" },
        )
    }

    fun save(c: Context, k: KbConfig) {
        sp(c).edit()
            .putInt("theme", k.theme)
            .putInt("height", k.heightPct)
            .putBoolean("haptics", k.haptics)
            .putBoolean("sound", k.sound)
            .putBoolean("number_row", k.numberRow)
            .putBoolean("suggest", k.suggest)
            .putBoolean("learn", k.learn)
            .putBoolean("hint", k.showHint)
            .putBoolean("early", k.earlySuggest)
            .putString("lang", k.lang)
            .putInt("c_bg", k.colBg)
            .putInt("c_bg2", k.colBg2)
            .putInt("c_key", k.colKey)
            .putInt("c_num", k.colNumKey)
            .putInt("c_special", k.colSpecial)
            .putInt("c_text", k.colText)
            .putInt("c_icon", k.colIcon)
            .putInt("c_press", k.colPress)
            .putInt("c_accent", k.colAccent)
            .putInt("c_preview", k.colPreview)
            .putInt("c_hint", k.colHint)
            .putInt("shape", k.previewShape)
            .putBoolean("effect_on", k.effectOn)
            .putString("effect_chars", k.effectChars)
            .putInt("effect_size", k.effectSize)
            .putString("layout_ar", k.layoutAr)
            .putString("layout_en", k.layoutEn)
            .putString("symbols", k.symbols)
            .putString("border_c", k.borderColor.joinToString(","))
            .putString("border_w", k.borderWidth.joinToString(","))
            .putInt("num_height", k.numHeightPct)
            .putInt("key_gap", k.keyGap)
            .putInt("key_radius", k.keyRadius)
            .putInt("text_pct", k.textPct)
            .putBoolean("show_hints", k.showHints)
            .putBoolean("alts_bubble", k.altsInBubble)
            .putBoolean("emoji_bar", k.emojiInBar)
            .putInt("fx_style", k.fxStyle)
            .putInt("c_fx", k.colFx)
            .putInt("fx_size", k.fxSize)
            .putInt("keyfx_style", k.keyFxStyle)
            .putInt("c_keyfx", k.colKeyFx)
            .putInt("key_motion", k.keyMotion)
            .putInt("c_picture", k.colPicture)
            .putBoolean("pic_inside", k.pictureInside)
            .putInt("pic_motion", k.pictureMotion)
            .apply {
                for (i in 0 until KB_PAGES) putString("layout2_$i", k.layouts.getOrElse(i) { "" })
            }
            .apply()
    }

    /** A saved list of numbers, always [KB_GROUPS] long. */
    private fun readList(raw: String?, fill: Int): List<Int> {
        val parts = (raw ?: "").split(",").map { it.trim().toIntOrNull() ?: fill }
        return List(KB_GROUPS) { parts.getOrNull(it) ?: fill }
    }

    fun saveLang(c: Context, lang: String) {
        sp(c).edit().putString("lang", lang).apply()
    }

    // ── The learning week ──

    /** When the first word was learned, or 0 when the keyboard has not learned anything yet. */
    fun learnStart(c: Context): Long = sp(c).getLong("learn_start", 0L)

    fun markLearnStartIfNeeded(c: Context) {
        if (learnStart(c) == 0L) sp(c).edit().putLong("learn_start", System.currentTimeMillis()).apply()
    }

    fun resetLearning(c: Context) {
        sp(c).edit().remove("learn_start").apply()
    }

    /** Days left before suggestions start. Null when learning has not started yet. */
    fun daysLeft(c: Context): Int? {
        val start = learnStart(c)
        if (start == 0L) return null
        val remain = KB_LEARN_DAYS * DAY - (System.currentTimeMillis() - start)
        return if (remain <= 0) 0 else ((remain + DAY - 1) / DAY).toInt()
    }

    fun suggestionsReady(c: Context, k: KbConfig): Boolean {
        if (!k.suggest) return false
        if (k.earlySuggest) return true
        return daysLeft(c) == 0
    }

    /** Goes up each time the learned data is wiped from the settings, so the running keyboard drops its copy too. */
    fun wipeGen(c: Context): Int = sp(c).getInt("wipe_gen", 0)

    fun bumpWipe(c: Context) {
        sp(c).edit().putInt("wipe_gen", wipeGen(c) + 1).apply()
    }

    // ── Recent emoji ──

    fun recentEmoji(c: Context): List<String> =
        (sp(c).getString("recent_emoji", "") ?: "").split(' ').filter { it.isNotEmpty() }

    fun pushEmoji(c: Context, e: String) {
        val list = (listOf(e) + recentEmoji(c).filter { it != e }).take(30)
        sp(c).edit().putString("recent_emoji", list.joinToString(" ")).apply()
    }
}

internal class KbPalette(
    val bg: Color,
    val bg2: Color?,
    val key: Color,
    val numKey: Color,
    val keyDown: Color,
    val special: Color,
    val specialDown: Color,
    val text: Color,
    val icon: Color,
    val sub: Color,
    val accent: Color,
    val onAccent: Color,
    val preview: Color,
    val fx: Color,
    val keyFx: Color,
    val picture: Color?,
)

private val KB_YELLOW = Color(0xFFFFE600)

private class KbBase(
    val bg: Color, val key: Color, val keyDown: Color, val special: Color, val specialDown: Color,
    val text: Color, val sub: Color, val accent: Color, val onAccent: Color,
)

private fun kbBase(theme: Int, systemDark: Boolean): KbBase {
    val mode = if (theme == KB_THEME_AUTO) (if (systemDark) KB_THEME_DARK else KB_THEME_LIGHT) else theme
    return when (mode) {
        KB_THEME_LIGHT -> KbBase(
            bg = Color(0xFFE4E5EC), key = Color(0xFFFFFFFF), keyDown = Color(0xFFD2D4DF),
            special = Color(0xFFC8CAD6), specialDown = Color(0xFFB4B7C6),
            text = Color(0xFF1B1B1F), sub = Color(0xFF6B6D7A),
            accent = Color(0xFF5B47E0), onAccent = Color(0xFFFFFFFF),
        )
        KB_THEME_KANADE -> KbBase(
            bg = Color(0xFF1C1432), key = Color(0xFF332759), keyDown = Color(0xFF4A3B7C),
            special = Color(0xFF281D47), specialDown = Color(0xFF3D3068),
            text = Color(0xFFF1ECFF), sub = Color(0xFFA99CD0),
            accent = Color(0xFF7B61FF), onAccent = Color(0xFFFFFFFF),
        )
        else -> KbBase(
            bg = Color(0xFF1B1B20), key = Color(0xFF34353D), keyDown = Color(0xFF4A4B55),
            special = Color(0xFF26272D), specialDown = Color(0xFF3B3C45),
            text = Color(0xFFEDEDF2), sub = Color(0xFF9A9BA8),
            accent = Color(0xFF8E7BFF), onAccent = Color(0xFF14102B),
        )
    }
}

/** The theme's colors, with every color the person chose put on top. */
internal fun kbPalette(cfg: KbConfig, systemDark: Boolean): KbPalette {
    val b = kbBase(cfg.theme, systemDark)
    fun pick(v: Int, d: Color) = if (v != 0) Color(v) else d
    val key = pick(cfg.colKey, b.key)
    val text = pick(cfg.colText, b.text)
    return KbPalette(
        bg = pick(cfg.colBg, b.bg),
        bg2 = if (cfg.colBg2 != 0) Color(cfg.colBg2) else null,
        key = key,
        numKey = pick(cfg.colNumKey, key),
        keyDown = pick(cfg.colPress, b.keyDown),
        special = pick(cfg.colSpecial, b.special),
        specialDown = pick(cfg.colPress, b.specialDown),
        text = text,
        icon = pick(cfg.colIcon, text),
        sub = pick(cfg.colHint, b.sub),
        accent = pick(cfg.colAccent, b.accent),
        onAccent = pick(cfg.colIcon, b.onAccent),
        preview = pick(cfg.colPreview, key),
        fx = pick(cfg.colFx, KB_YELLOW),
        keyFx = pick(cfg.colKeyFx, KB_YELLOW),
        picture = if (cfg.colPicture != 0) Color(cfg.colPicture) else null,
    )
}
