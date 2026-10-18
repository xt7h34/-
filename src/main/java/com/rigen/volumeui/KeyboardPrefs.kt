package com.rigen.volumeui

import android.content.Context
import androidx.compose.ui.graphics.Color

/*
 * Kanade Keyboard: settings and colors.
 * Everything here is stored on the phone only. The keyboard never uses the network.
 */

const val PAGE_KEYBOARD = "keyboard"

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
            .apply()
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
)

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
    )
}
