package com.rigen.volumeui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.random.Random

/*
 * What the keyboard drawing needs from "whoever owns the keyboard". The real keyboard service is
 * one owner. The live preview in the settings is another, so people can try every color and
 * effect without leaving the settings.
 */

/** The key being pressed, so the keyboard can draw the bubble above it. */
internal class PressInfo(val label: String, val rect: Rect)

/** One picture (a paw, a heart...) that appears where a key was pressed and fades away. */
internal class TapEffect(
    val id: Long,
    val symbol: String,
    val x: Float,
    val y: Float,
    val rot: Float,
    val rect: Rect,
)

/** A light drawn behind the keys where a key was pressed. */
internal class FxBurst(val id: Long, val style: Int, val x: Float, val y: Float, val rect: Rect, val seed: Int)

/** The extra letters of a key, shown in a bubble above it. */
internal class AltBubble(val alts: List<String>, val rect: Rect)

internal class KbAltLayout(val x: Float, val y: Float, val cellW: Float, val cellH: Float)

internal fun kbAltLayout(b: AltBubble, rootW: Int, density: Float): KbAltLayout {
    val n = b.alts.size.coerceAtLeast(1)
    val cellW = minOf(42f * density, rootW.toFloat() / n)
    val cellH = 52f * density
    val total = cellW * n
    val x = (b.rect.center.x - total / 2f).coerceIn(0f, (rootW - total).coerceAtLeast(0f))
    val y = (b.rect.top - cellH - 6f * density).coerceAtLeast(0f)
    return KbAltLayout(x, y, cellW, cellH)
}

/** Which key group a key belongs to, for its border. */
internal fun kbGroupOf(kind: KeyKind, numberRow: Boolean): Int = when (kind) {
    KeyKind.CHAR -> if (numberRow) KB_G_NUMBERS else KB_G_LETTERS
    KeyKind.SPACE -> KB_G_SPACE
    KeyKind.BACKSPACE -> KB_G_DELETE
    KeyKind.SHIFT -> KB_G_SHIFT
    KeyKind.LANG -> KB_G_LANG
    KeyKind.EMOJI -> KB_G_EMOJI
    KeyKind.ENTER -> KB_G_ENTER
    else -> KB_G_SYMBOLS
}

/** Everything that appears briefly while keys are pressed. */
internal class KbFx {
    private val handler = Handler(Looper.getMainLooper())
    private val clearPress = Runnable { press = null }
    private var nextId = 0L
    private var symbolsText = ""
    private var symbols: List<String> = emptyList()

    var rootW by mutableIntStateOf(0)
    var density: Float = 1f
    var press by mutableStateOf<PressInfo?>(null)
    var bubble by mutableStateOf<AltBubble?>(null)
    var hover by mutableIntStateOf(-1)
    val taps = mutableStateListOf<TapEffect>()
    val bursts = mutableStateListOf<FxBurst>()

    fun showPress(info: PressInfo) {
        handler.removeCallbacks(clearPress)
        press = info
    }

    /** The bubble stays a moment after the finger lifts, so a fast tap can still be seen. */
    fun hidePress() {
        handler.removeCallbacks(clearPress)
        handler.postDelayed(clearPress, 90L)
    }

    fun hidePressNow() {
        handler.removeCallbacks(clearPress)
        press = null
    }

    /** A key was pressed: start the picture and the light behind the keys. */
    fun spawn(cfg: KbConfig, rect: Rect, touch: Offset) {
        if (cfg.effectOn) {
            if (symbolsText != cfg.effectChars) {
                symbolsText = cfg.effectChars
                symbols = kbGraphemes(cfg.effectChars).take(12)
            }
            if (symbols.isNotEmpty()) {
                while (taps.size >= 12) taps.removeAt(0)
                nextId++
                taps.add(TapEffect(nextId, symbols.random(), touch.x, touch.y, Random.nextFloat() * 40f - 20f, rect))
            }
        }
        if (cfg.fxStyle != KB_FX_NONE) {
            while (bursts.size >= 8) bursts.removeAt(0)
            nextId++
            bursts.add(FxBurst(nextId, cfg.fxStyle, touch.x, touch.y, rect, Random.nextInt()))
        }
    }

    /** Which extra letter of the open bubble the finger is above, or -1. */
    fun altIndexAt(pos: Offset): Int {
        val b = bubble ?: return -1
        val l = kbAltLayout(b, rootW, density)
        if (pos.y > b.rect.top + b.rect.height * 0.2f) return -1
        val i = ((pos.x - l.x) / l.cellW).toInt()
        return if (pos.x >= l.x && i in b.alts.indices) i else -1
    }

    fun clear() {
        handler.removeCallbacks(clearPress)
        press = null
        bubble = null
        hover = -1
        taps.clear()
        bursts.clear()
    }
}

internal abstract class KbHost {
    abstract val cfg: KbConfig
    abstract val page: Int
    abstract val lang: String
    abstract val shift: Int
    abstract val suggestions: List<String>
    abstract val alts: List<String>
    abstract val hint: String?
    abstract val enterLabel: String
    abstract val recentVersion: Int
    abstract val customSymbols: List<String>
    abstract val fx: KbFx

    abstract fun feedback(kind: KeyKind)
    abstract fun typeChar(raw: String)
    abstract fun typeAlt(s: String)
    abstract fun showAlts(list: List<String>)
    abstract fun onSpace()
    abstract fun onBackspace()
    abstract fun onEnter()
    abstract fun onShift()
    abstract fun showSymbols()
    abstract fun showLetters()
    abstract fun togglePage()
    abstract fun toggleLang()
    abstract fun showEmoji()
    abstract fun closeEmoji()
    abstract fun showPicker()
    abstract fun openSettings()
    abstract fun typeEmoji(e: String)
    abstract fun pickSuggestion(word: String)
}

/** The keyboard shown inside the settings. It types nothing, but every look and effect works. */
internal class PreviewHost(override val cfg: KbConfig) : KbHost() {
    override var page by mutableIntStateOf(PG_LETTERS)
    override var lang by mutableStateOf("ar")
    override var shift by mutableIntStateOf(0)
    override val suggestions: List<String> = listOf("مرحبا", "كيف", "الحمد")
    override var alts by mutableStateOf<List<String>>(emptyList())
    override val hint: String? = null
    override val enterLabel: String = ""
    override val recentVersion: Int = 0
    override val customSymbols: List<String> = kbGraphemes(cfg.symbols).take(27)
    override val fx = KbFx()

    override fun feedback(kind: KeyKind) {}
    override fun typeChar(raw: String) {}
    override fun typeAlt(s: String) { alts = emptyList() }
    override fun showAlts(list: List<String>) { alts = list }
    override fun onSpace() {}
    override fun onBackspace() {}
    override fun onEnter() {}
    override fun onShift() { shift = if (shift == 0) 1 else 0 }
    override fun showSymbols() { page = PG_SYM1 }
    override fun showLetters() { page = PG_LETTERS }
    override fun togglePage() {
        page = when (page) {
            PG_SYM1 -> PG_SYM2
            PG_SYM2 -> if (customSymbols.isNotEmpty()) PG_SYM3 else PG_SYM1
            else -> PG_SYM1
        }
    }
    override fun toggleLang() { lang = if (lang == "ar") "en" else "ar"; shift = 0 }
    override fun showEmoji() {}
    override fun closeEmoji() {}
    override fun showPicker() {}
    override fun openSettings() {}
    override fun typeEmoji(e: String) {}
    override fun pickSuggestion(word: String) {}
}
