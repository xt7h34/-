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

/** Suggestions start only after the keyboard has learned from the person for this many days. */
internal const val KB_LEARN_DAYS = 7

internal class KbConfig(
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
) {
    fun copy(
        theme: Int = this.theme,
        heightPct: Int = this.heightPct,
        haptics: Boolean = this.haptics,
        sound: Boolean = this.sound,
        numberRow: Boolean = this.numberRow,
        suggest: Boolean = this.suggest,
        learn: Boolean = this.learn,
        showHint: Boolean = this.showHint,
        earlySuggest: Boolean = this.earlySuggest,
        lang: String = this.lang,
    ) = KbConfig(theme, heightPct, haptics, sound, numberRow, suggest, learn, showHint, earlySuggest, lang)
}

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
    val key: Color,
    val keyDown: Color,
    val special: Color,
    val specialDown: Color,
    val text: Color,
    val sub: Color,
    val accent: Color,
    val onAccent: Color,
)

internal fun kbPalette(theme: Int, systemDark: Boolean): KbPalette {
    val mode = if (theme == KB_THEME_AUTO) (if (systemDark) KB_THEME_DARK else KB_THEME_LIGHT) else theme
    return when (mode) {
        KB_THEME_LIGHT -> KbPalette(
            bg = Color(0xFFE4E5EC), key = Color(0xFFFFFFFF), keyDown = Color(0xFFD2D4DF),
            special = Color(0xFFC8CAD6), specialDown = Color(0xFFB4B7C6),
            text = Color(0xFF1B1B1F), sub = Color(0xFF6B6D7A),
            accent = Color(0xFF5B47E0), onAccent = Color(0xFFFFFFFF),
        )
        KB_THEME_KANADE -> KbPalette(
            bg = Color(0xFF1C1432), key = Color(0xFF332759), keyDown = Color(0xFF4A3B7C),
            special = Color(0xFF281D47), specialDown = Color(0xFF3D3068),
            text = Color(0xFFF1ECFF), sub = Color(0xFFA99CD0),
            accent = Color(0xFF7B61FF), onAccent = Color(0xFFFFFFFF),
        )
        else -> KbPalette(
            bg = Color(0xFF1B1B20), key = Color(0xFF34353D), keyDown = Color(0xFF4A4B55),
            special = Color(0xFF26272D), specialDown = Color(0xFF3B3C45),
            text = Color(0xFFEDEDF2), sub = Color(0xFF9A9BA8),
            accent = Color(0xFF8E7BFF), onAccent = Color(0xFF14102B),
        )
    }
}
