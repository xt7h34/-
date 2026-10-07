package com.rigen.volumeui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.provider.Settings
import android.os.Vibrator
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import kotlin.math.roundToInt

const val THEME_SYSTEM = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2

const val RULE_NORMAL = 0
const val RULE_HIDE = 1
const val RULE_ALT_POSITION = 2

const val STATION_W_DP = 300

/** The panel and the Volume Station always show this many steps, whatever the phone's real count is. */
const val PANEL_STEPS = 20

/** Converts a real stream level (0..realMax) to the 0..[PANEL_STEPS] scale. Any sound above zero shows at least 1. */
fun toPanelLevel(real: Int, realMax: Int): Int {
    if (real <= 0) return 0
    return ((real * PANEL_STEPS) / realMax.coerceAtLeast(1).toFloat()).roundToInt().coerceIn(1, PANEL_STEPS)
}

// Where the sound is going right now (shown in the Volume Station).
const val OUTPUT_SPEAKER = 0
const val OUTPUT_BLUETOOTH = 1
const val OUTPUT_WIRED = 2

// Pages of the app. The service opens PAGE_VOLUME from the Volume Station's gear icon.
const val EXTRA_PAGE = "page"
const val PAGE_HOME = "home"
const val PAGE_VOLUME = "volume"
const val PAGE_PANEL = "panel"
const val PAGE_BEHAVIOR = "behavior"
const val PAGE_STATION = "station"
const val PAGE_APPS = "apps"
const val PAGE_APP = "app"
const val PAGE_KEEPALIVE = "keepalive"

// Looks of the volume panel.
const val STYLE_CLASSIC = 0
const val STYLE_CAPSULE = 1
const val STYLE_BAR = 2
const val STYLE_CAPSULE2 = 3

/** What the panel does with the volume number. */
const val NUMBER_NONE = 0

/** The number takes the place of the icon inside the bar. */
const val NUMBER_ICON = 1

/** The number takes the place of the mute button (capsule styles). */
const val NUMBER_MUTE = 2

/** The look "Capsule II" is made for: a white pill with an indigo fill. */
val CAPSULE2_FRAME: Int = 0xFFFFFFFF.toInt()
val CAPSULE2_ACCENT: Int = 0xFF4F5BA5.toInt()

val DEFAULT_LIMITS: List<Int> = listOf(100, 100, 100, 100, 100)

/** Small built-in icons, drawn from 24x24 vector path data (no extra library needed). */
enum class Glyph(val pathData: String) {
    MEDIA("M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z"),
    CALL("M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"),
    RING("M23.71,16.67C20.66,13.78 16.54,12 12,12 7.46,12 3.34,13.78 0.29,16.67c-0.18,0.18 -0.29,0.43 -0.29,0.71 0,0.28 0.11,0.53 0.29,0.71l2.48,2.48c0.18,0.18 0.43,0.29 0.71,0.29 0.27,0 0.52,-0.11 0.7,-0.28 0.79,-0.74 1.69,-1.36 2.66,-1.85 0.33,-0.16 0.56,-0.5 0.56,-0.9v-3.1c1.45,-0.48 3,-0.73 4.6,-0.73s3.15,0.25 4.6,0.73v3.1c0,0.39 0.23,0.74 0.56,0.9 0.98,0.49 1.87,1.12 2.66,1.87 0.18,0.17 0.43,0.28 0.7,0.28 0.28,0 0.53,-0.11 0.71,-0.29l2.48,-2.48c0.18,-0.18 0.29,-0.43 0.29,-0.71 0,-0.27 -0.11,-0.52 -0.29,-0.7zM21.16,6.26l-1.41,-1.41 -3.56,3.55 1.41,1.41s3.45,-3.52 3.56,-3.55zM13,2h-2v5h2V2zM6.4,9.81L7.81,8.4 4.26,4.84 2.84,6.26c0.11,0.03 3.56,3.55 3.56,3.55z"),
    NOTIFICATION("M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"),
    ALARM("M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8H11v6l4.75,2.85 0.75,-1.23 -4,-2.37V8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"),
    MUTED("M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z"),
    DND("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM17,13H7v-2h10v2z"),
    PLAY("M8,5v14l11,-7z"),
    PAUSE("M6,19h4V5H6v14zm8,-14v14h4V5h-4z"),
    STOP("M6,6h12v12H6z"),
    PREVIOUS("M6,6h2v12H6zm3.5,6l8.5,6V6z"),
    NEXT("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"),
    HOME("M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"),
    VOLUME("M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"),
    TUNE("M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z"),
    APPS("M4,8h4V4H4v4zm6,12h4v-4h-4v4zm-6,0h4v-4H4v4zm0,-6h4v-4H4v4zm6,0h4v-4h-4v4zm6,-10v4h4V4h-4zm-6,4h4V4h-4v4zm6,6h4v-4h-4v4zm0,6h4v-4h-4v4z"),
    BACK("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"),
    MORE("M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"),
    ACCESS("M12,2c1.1,0 2,0.9 2,2s-0.9,2 -2,2 -2,-0.9 -2,-2 0.9,-2 2,-2zM21,9h-6v13h-2v-6h-2v6H9V9H3V7h18v2z"),
    WARNING("M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z"),
    SETTINGS("M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94l-0.36,-2.54c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41l-0.36,2.54c-0.59,0.24 -1.13,0.57 -1.62,0.94l-2.39,-0.96c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87c-0.12,0.21 -0.08,0.47 0.12,0.61l2.03,1.58c-0.05,0.3 -0.09,0.63 -0.09,0.94s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61l-2.01,-1.58zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z"),
    CLOCK("M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z"),
    TIMER("M15,1H9v2h6V1zM11,14h2V8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"),
    SLEEP("M12,3a9,9 0 1,0 9,9c0,-0.46 -0.04,-0.92 -0.1,-1.36a5.389,5.389 0 0,1 -4.4,2.26 5.403,5.403 0 0,1 -3.14,-9.8c-0.44,-0.06 -0.9,-0.1 -1.36,-0.1z"),
    ADD("M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"),
    DELETE("M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"),
    SEARCH("M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"),
    CLOSE("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"),
    BATTERY("M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z"),
    VIBRATE("M0,15h2V9H0v6zm3,2h2V7H3v10zm19,-8v6h2V9h-2zm-3,8h2V7h-2v10zM16.5,3h-9C6.67,3 6,3.67 6,4.5v15c0,0.83 0.67,1.5 1.5,1.5h9c0.83,0 1.5,-0.67 1.5,-1.5v-15c0,-0.83 -0.67,-1.5 -1.5,-1.5zM16,19H8V5h8v14z"),
    BLUETOOTH("M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z"),
    HEADSET("M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z"),
    PALETTE("M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5H16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z"),
}

class StreamInfo(val stream: Int, val glyph: Glyph, val labelRes: Int)

/** Every volume the Volume Station lists. The order matches [PanelSettings.volumeLimits]. */
val STREAMS: List<StreamInfo> = listOf(
    StreamInfo(AudioManager.STREAM_MUSIC, Glyph.MEDIA, R.string.stream_media),
    StreamInfo(AudioManager.STREAM_VOICE_CALL, Glyph.CALL, R.string.stream_call),
    StreamInfo(AudioManager.STREAM_RING, Glyph.RING, R.string.stream_ring),
    StreamInfo(AudioManager.STREAM_NOTIFICATION, Glyph.NOTIFICATION, R.string.stream_notification),
    StreamInfo(AudioManager.STREAM_ALARM, Glyph.ALARM, R.string.stream_alarm),
)

fun glyphForStream(stream: Int): Glyph =
    STREAMS.firstOrNull { it.stream == stream }?.glyph ?: Glyph.MEDIA

data class PanelSettings(
    // Look
    val cornerRadiusDp: Int = 24,
    val colorArgb: Int = 0xFF1E1E1E.toInt(),
    /** Bar color. 0 means "automatic" (picked for contrast with the panel color). */
    val barColorArgb: Int = 0,
    /** Real on-screen size of the panel: width = horizontal extent, height = vertical extent. */
    val widthDp: Int = 260,
    val heightDp: Int = 56,
    val vertical: Boolean = false,
    /** STYLE_CLASSIC, STYLE_CAPSULE or STYLE_BAR. */
    val panelStyle: Int = STYLE_CLASSIC,
    val showFrame: Boolean = true,
    val showDndIcon: Boolean = true,
    /** Off = the panel is never drawn, but the keys still work (limits, double press...). */
    val panelEnabled: Boolean = true,
    /** NUMBER_NONE, NUMBER_ICON or NUMBER_MUTE. */
    val numberMode: Int = NUMBER_ICON,
    val redThresholdPct: Int = 85,
    // Behavior
    val hideDelayMs: Int = 1500,
    /** Center of the panel as a fraction of the screen (0..1). */
    val posX: Float = 0.5f,
    val posY: Float = 0.08f,
    /** Lets the user drag on the bar to change the volume. */
    val touchEnabled: Boolean = true,
    /** Dragging moves the bar from where it is, instead of jumping to the finger. */
    val relativeDrag: Boolean = false,
    /** 0 = off, 1 = double-press volume up to mute, 2 = double-press volume down to mute. */
    val doublePressKey: Int = 0,
    val haptics: Boolean = true,
    /** Highest allowed level (percent) per stream, in [STREAMS] order. */
    val volumeLimits: List<Int> = DEFAULT_LIMITS,
    // Volume Station (three dots in the panel open a card in the middle of the screen)
    val stationEnabled: Boolean = false,
    /** Buttons in the top-left corner of the Station card. */
    val stationMuteAll: Boolean = true,
    val stationDnd: Boolean = false,
    /** Ringer mode button (sound / vibrate / silent). */
    val stationRinger: Boolean = false,
    /** Sound output button (speaker / Bluetooth / headphones). */
    val stationOutput: Boolean = false,
    /** The playing app's name and title above the media buttons. Needs media access. */
    val stationNowPlaying: Boolean = false,
    // Per-app rules: package name -> RULE_*
    val appRules: Map<String, Int> = emptyMap(),
    val altPosX: Float = 0.5f,
    val altPosY: Float = 0.92f,
    // The app itself
    val themeMode: Int = THEME_SYSTEM,
    val dynamicColor: Boolean = false,
    /** "" = follow the system, otherwise a language code such as "ar" or "en". */
    val language: String = "",
)

/** Single source of truth for saved settings. Used by both the app screens and the service. */
object Prefs {
    private const val FILE = "kanade_system"
    private const val K_RADIUS = "corner_radius_dp"
    private const val K_COLOR = "color_argb"
    private const val K_BAR_COLOR = "bar_color_argb"
    private const val K_WIDTH = "width_dp"
    private const val K_HEIGHT = "height_dp"
    private const val K_VERTICAL = "vertical"
    private const val K_STYLE = "panel_style"
    private const val K_FRAME = "show_frame"
    private const val K_DELAY = "hide_delay_ms"
    private const val K_RED = "red_threshold_pct"
    private const val K_DND = "show_dnd_icon"
    private const val K_PANEL_ON = "panel_enabled"
    private const val K_NUMBER = "number_mode"
    private const val K_POS_X = "pos_x"
    private const val K_POS_Y = "pos_y"
    private const val K_TOUCH = "touch_enabled"
    private const val K_DOUBLE = "double_press_key"
    private const val K_HAPTICS = "haptics"
    private const val K_LIMITS = "volume_limits"
    private const val K_STATION = "station_enabled"
    private const val K_ST_MUTE = "station_mute_all"
    private const val K_ST_DND = "station_dnd_button"
    private const val K_RELATIVE = "relative_drag"
    private const val K_ST_RINGER = "station_ringer"
    private const val K_ST_OUTPUT = "station_output"
    private const val K_ST_NOW = "station_now_playing"
    private const val K_RULES = "app_rules"
    private const val K_ALT_X = "alt_pos_x"
    private const val K_ALT_Y = "alt_pos_y"
    private const val K_THEME = "theme_mode"
    private const val K_DYNAMIC = "dynamic_color_v2"
    private const val K_LANG = "language"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun decodeLimits(s: String?): List<Int> {
        val parts = s?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        return List(DEFAULT_LIMITS.size) { i -> (parts.getOrNull(i) ?: 100).coerceIn(10, 100) }
    }

    private fun decodeRules(s: String?): Map<String, Int> {
        if (s.isNullOrBlank()) return emptyMap()
        return s.split(";").mapNotNull { item ->
            val i = item.lastIndexOf('=')
            val mode = if (i > 0) item.substring(i + 1).toIntOrNull() else null
            if (mode == null) null else item.substring(0, i) to mode
        }.toMap()
    }

    fun load(context: Context): PanelSettings {
        val d = PanelSettings()
        val p = sp(context)
        return PanelSettings(
            cornerRadiusDp = p.getInt(K_RADIUS, d.cornerRadiusDp),
            colorArgb = p.getInt(K_COLOR, d.colorArgb),
            barColorArgb = p.getInt(K_BAR_COLOR, d.barColorArgb),
            widthDp = p.getInt(K_WIDTH, d.widthDp),
            heightDp = p.getInt(K_HEIGHT, d.heightDp),
            vertical = p.getBoolean(K_VERTICAL, d.vertical),
            panelStyle = p.getInt(K_STYLE, d.panelStyle),
            showFrame = p.getBoolean(K_FRAME, d.showFrame),
            showDndIcon = p.getBoolean(K_DND, d.showDndIcon),
            panelEnabled = p.getBoolean(K_PANEL_ON, d.panelEnabled),
            numberMode = p.getInt(K_NUMBER, d.numberMode),
            redThresholdPct = p.getInt(K_RED, d.redThresholdPct),
            hideDelayMs = p.getInt(K_DELAY, d.hideDelayMs),
            posX = p.getFloat(K_POS_X, d.posX),
            posY = p.getFloat(K_POS_Y, d.posY),
            touchEnabled = p.getBoolean(K_TOUCH, d.touchEnabled),
            relativeDrag = p.getBoolean(K_RELATIVE, d.relativeDrag),
            doublePressKey = p.getInt(K_DOUBLE, d.doublePressKey),
            haptics = p.getBoolean(K_HAPTICS, d.haptics),
            volumeLimits = decodeLimits(p.getString(K_LIMITS, null)),
            stationEnabled = p.getBoolean(K_STATION, d.stationEnabled),
            stationMuteAll = p.getBoolean(K_ST_MUTE, d.stationMuteAll),
            stationDnd = p.getBoolean(K_ST_DND, d.stationDnd),
            stationRinger = p.getBoolean(K_ST_RINGER, d.stationRinger),
            stationOutput = p.getBoolean(K_ST_OUTPUT, d.stationOutput),
            stationNowPlaying = p.getBoolean(K_ST_NOW, d.stationNowPlaying),
            appRules = decodeRules(p.getString(K_RULES, null)),
            altPosX = p.getFloat(K_ALT_X, d.altPosX),
            altPosY = p.getFloat(K_ALT_Y, d.altPosY),
            themeMode = p.getInt(K_THEME, d.themeMode),
            dynamicColor = p.getBoolean(K_DYNAMIC, d.dynamicColor),
            language = p.getString(K_LANG, "") ?: "",
        )
    }

    fun save(context: Context, s: PanelSettings) {
        sp(context).edit()
            .putInt(K_RADIUS, s.cornerRadiusDp)
            .putInt(K_COLOR, s.colorArgb)
            .putInt(K_BAR_COLOR, s.barColorArgb)
            .putInt(K_WIDTH, s.widthDp)
            .putInt(K_HEIGHT, s.heightDp)
            .putBoolean(K_VERTICAL, s.vertical)
            .putInt(K_STYLE, s.panelStyle)
            .putBoolean(K_FRAME, s.showFrame)
            .putBoolean(K_DND, s.showDndIcon)
            .putBoolean(K_PANEL_ON, s.panelEnabled)
            .putInt(K_NUMBER, s.numberMode)
            .putInt(K_RED, s.redThresholdPct)
            .putInt(K_DELAY, s.hideDelayMs)
            .putFloat(K_POS_X, s.posX)
            .putFloat(K_POS_Y, s.posY)
            .putBoolean(K_TOUCH, s.touchEnabled)
            .putBoolean(K_RELATIVE, s.relativeDrag)
            .putInt(K_DOUBLE, s.doublePressKey)
            .putBoolean(K_HAPTICS, s.haptics)
            .putString(K_LIMITS, s.volumeLimits.joinToString(","))
            .putBoolean(K_STATION, s.stationEnabled)
            .putBoolean(K_ST_MUTE, s.stationMuteAll)
            .putBoolean(K_ST_DND, s.stationDnd)
            .putBoolean(K_ST_RINGER, s.stationRinger)
            .putBoolean(K_ST_OUTPUT, s.stationOutput)
            .putBoolean(K_ST_NOW, s.stationNowPlaying)
            .putString(K_RULES, s.appRules.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putFloat(K_ALT_X, s.altPosX)
            .putFloat(K_ALT_Y, s.altPosY)
            .putInt(K_THEME, s.themeMode)
            .putBoolean(K_DYNAMIC, s.dynamicColor)
            .putString(K_LANG, s.language)
            .apply()
    }

    /** Wraps a context so its texts use the language chosen inside the app (if any). */
    fun localized(base: Context): Context {
        val lang = base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(K_LANG, "") ?: ""
        if (lang.isEmpty()) return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}

/** True when the user has given this app media access (see [MediaListenerService]). */
fun isMediaAccessGranted(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return false
    val cn = ComponentName(context, MediaListenerService::class.java)
    return enabled.split(':').any {
        it.equals(cn.flattenToString(), ignoreCase = true) ||
            it.equals(cn.flattenToShortString(), ignoreCase = true)
    }
}

/** A short, crisp vibration tick. It does not depend on the system's "touch feedback" switch. */
object Haptics {
    fun tick(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(15L, 100))
        }
    }
}

/** Small notes the app keeps about the service, so problems can be found from the phone alone. */
object Diag {
    private const val FILE = "kanade_diag"
    private const val ERRORS = "errors"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mark(context: Context, key: String) {
        sp(context).edit().putLong(key, System.currentTimeMillis()).commit()
    }

    fun time(context: Context, key: String): Long = sp(context).getLong(key, 0L)

    fun error(context: Context, where: String, t: Throwable) {
        val trace = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().take(1800)
        val entry = "[" + java.text.DateFormat.getDateTimeInstance().format(java.util.Date()) + "] " + where + "\n" + trace
        val old = sp(context).getString(ERRORS, "") ?: ""
        sp(context).edit().putString(ERRORS, (entry + "\n\n" + old).take(6000)).commit()
    }

    fun errors(context: Context): String = sp(context).getString(ERRORS, "") ?: ""

    fun clearErrors(context: Context) {
        sp(context).edit().remove(ERRORS).commit()
    }
}

/** Records any crash, so the app can show it afterwards. */
class KanadeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Diag.error(this, "CRASH on thread " + thread.name, throwable)
            } catch (e: Throwable) {
                // Nothing more we can do here.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
