package com.rigen.volumeui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/*
 * Kanade Keyboard. A real system keyboard that lives inside the Kanade app.
 *
 * - It only suggests: nothing is ever corrected or replaced by itself.
 * - It learns the person's words on the phone, and only starts suggesting after a full week.
 * - It never uses the network and never learns from password fields.
 */

internal const val PG_LETTERS = 0
internal const val PG_SYM1 = 1
internal const val PG_SYM2 = 2
internal const val PG_EMOJI = 3
internal const val PG_SYM3 = 4 // the person's own symbols
internal const val PG_CLIP = 5 // the clipboard history
internal const val PG_TRANS = 6 // translation

class KanadeKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    // A ComposeView outside an Activity needs these owners, otherwise it never draws.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var learner: KbLearner
    private lateinit var clipStore: KbClipStore
    private var seenClipGen = 0
    private var pageBeforeClip = PG_LETTERS
    private var pageBeforeTr = PG_LETTERS

    // What the translate screen can do in the text field.
    private val trIo = object : KbTr.Io {
        override fun fieldText(): String {
            val c = ic ?: return ""
            val selected = c.getSelectedText(0)
            if (!selected.isNullOrEmpty()) return selected.toString()
            val before = c.getTextBeforeCursor(2000, 0)?.toString() ?: ""
            val after = c.getTextAfterCursor(2000, 0)?.toString() ?: ""
            return before + after
        }

        override fun replaceWith(text: String) {
            val c = ic ?: return
            c.beginBatchEdit()
            val selected = c.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                c.commitText(text, 1)
            } else {
                val before = c.getTextBeforeCursor(2000, 0)?.length ?: 0
                val after = c.getTextAfterCursor(2000, 0)?.length ?: 0
                c.deleteSurroundingText(before, after)
                c.commitText(text, 1)
            }
            c.endBatchEdit()
            closeTranslate()
        }

        override fun insert(text: String) {
            ic?.commitText(text, 1)
            closeTranslate()
        }
    }
    internal val tr = KbTr(this, trIo)
    private val dropClipSuggestion = Runnable { clipSuggestion = null }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { readClipboard() }
    private var uiContext: Context? = null
    private var seenWipe = 0

    // What the keyboard shows.
    internal var cfg by mutableStateOf(KbConfig())
    internal var page by mutableIntStateOf(PG_LETTERS)
    internal var lang by mutableStateOf("ar")
    internal var shift by mutableIntStateOf(0) // 0 off, 1 next letter, 2 caps lock
    internal var suggestions by mutableStateOf<List<String>>(emptyList())
    internal var alts by mutableStateOf<List<String>>(emptyList())
    internal var hint by mutableStateOf<String?>(null)
    internal var enterLabel by mutableStateOf("")
    internal var recentVersion by mutableIntStateOf(0)
    internal var customSymbols by mutableStateOf<List<String>>(emptyList())
    internal var toolbarOpen by mutableStateOf(false)
    internal var clips by mutableStateOf<List<ClipItem>>(emptyList())

    /** What was just copied, offered in the top bar for a few seconds. */
    internal var clipSuggestion by mutableStateOf<ClipItem?>(null)

    /** Everything that appears briefly while keys are pressed (bubbles, pictures, lights). */
    internal val fx = KbFx()

    // What the current text field asked for.
    private var secure = false
    private var noLearn = false
    private var rawInputType = 0
    private var imeAction = EditorInfo.IME_ACTION_NONE
    private var noEnterAction = false
    private var lastShiftTap = 0L
    private var pageBeforeEmoji = PG_LETTERS

    private val refreshRunnable = Runnable { refreshNow() }
    private val saveRunnable = Runnable { learner.save() }

    /** What the drawing code sees. It only forwards to this service. */
    internal val host: KbHost = object : KbHost() {
        private val svc: KanadeKeyboardService get() = this@KanadeKeyboardService
        override val cfg: KbConfig get() = svc.cfg
        override val page: Int get() = svc.page
        override val lang: String get() = svc.lang
        override val shift: Int get() = svc.shift
        override val suggestions: List<String> get() = svc.suggestions
        override val alts: List<String> get() = svc.alts
        override val hint: String? get() = svc.hint
        override val enterLabel: String get() = svc.enterLabel
        override val recentVersion: Int get() = svc.recentVersion
        override val customSymbols: List<String> get() = svc.customSymbols
        override val fx: KbFx get() = svc.fx
        override val toolbarOpen: Boolean get() = svc.toolbarOpen
        override val clips: List<ClipItem> get() = svc.clips
        override val clipSuggestion: ClipItem? get() = svc.clipSuggestion
        override val tr: KbTr get() = svc.tr

        override fun feedback(kind: KeyKind) = svc.feedback(kind)
        override fun typeChar(raw: String) = svc.typeChar(raw)
        override fun typeAlt(s: String) = svc.typeAlt(s)
        override fun showAlts(list: List<String>) = svc.showAlts(list)
        override fun onSpace() = svc.onSpace()
        override fun onBackspace() = svc.onBackspace()
        override fun onEnter() = svc.onEnter()
        override fun onShift() = svc.onShift()
        override fun showSymbols() = svc.showSymbols()
        override fun showLetters() = svc.showLetters()
        override fun togglePage() = svc.togglePage()
        override fun toggleLang() = svc.toggleLang()
        override fun showEmoji() = svc.showEmoji()
        override fun closeEmoji() = svc.closeEmoji()
        override fun showPicker() = svc.showPicker()
        override fun openSettings() = svc.openSettings()
        override fun typeEmoji(e: String) = svc.typeEmoji(e)
        override fun pickSuggestion(word: String) = svc.pickSuggestion(word)
        override fun setToolbar(open: Boolean) = svc.setToolbar(open)
        override fun showClipboard() = svc.showClipboard()
        override fun closeClipboard() = svc.closeClipboard()
        override fun pasteClip(c: ClipItem) = svc.pasteClip(c)
        override fun pinClip(c: ClipItem) = svc.pinClip(c)
        override fun deleteClip(c: ClipItem) = svc.deleteClip(c)
        override fun clearClips() = svc.clearClips()
        override fun setOneHanded(mode: Int) = svc.setOneHanded(mode)
        override fun useClipSuggestion() = svc.useClipSuggestion()
        override fun showTranslate() = svc.showTranslate()
        override fun closeTranslate() = svc.closeTranslate()
        override fun text(id: Int): String = svc.str(id)
    }

    // ───────────────────────── Lifecycle ─────────────────────────

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        learner = KbLearner(this).also { it.load() }
        clipStore = KbClipStore(this).also { it.load() }
        seenClipGen = KbPrefs.clipGen(this)
        seenWipe = KbPrefs.wipeGen(this)
        cfg = KbPrefs.load(this)
        applyConfig()
        lang = cfg.lang
        uiContext = Prefs.localized(this)
        clips = clipStore.list(cfg)
        try {
            getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Diag.error(this, "KeyboardService.clipListener", e)
        }
    }

    override fun onCreateInputView(): View {
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@KanadeKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@KanadeKeyboardService)
            setContent { KeyboardRoot(host) }
        }
        val decor = window?.window?.decorView
        decor?.setViewTreeLifecycleOwner(this)
        decor?.setViewTreeSavedStateRegistryOwner(this)
        return compose
    }

    // No full-screen "extract" editor in landscape: the keyboard stays a keyboard.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cfg = KbPrefs.load(this)
        applyConfig()
        fx.clear()
        uiContext = Prefs.localized(this)
        if (KbPrefs.wipeGen(this) != seenWipe) {
            learner.clearAll()
            seenWipe = KbPrefs.wipeGen(this)
        }
        if (KbPrefs.clipGen(this) != seenClipGen) {
            clipStore.clearAll()
            seenClipGen = KbPrefs.clipGen(this)
        }
        toolbarOpen = false
        readClipboard()
        clips = clipStore.list(cfg)

        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val textPassword = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        val numberPassword = cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        secure = textPassword || numberPassword
        val flagged = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val addressLike = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
        noLearn = secure || flagged || addressLike
        rawInputType = type

        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (info?.actionLabel != null) {
            imeAction = info.actionId
            enterLabel = info.actionLabel.toString()
        } else {
            imeAction = action
            enterLabel = enterText(action)
        }

        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        if (!restarting) {
            page = if (numeric) PG_SYM1 else PG_LETTERS
            alts = emptyList()
        }
        shift = 0
        updateAutoCap()
        refreshNow()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        scheduleRefresh()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        fx.clear()
        handler.removeCallbacks(saveRunnable)
        learner.save()
        alts = emptyList()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            // The system is shutting the keyboard down anyway.
        }
        learner.save(async = false)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    // ───────────────────────── Typing ─────────────────────────

    private val ic: InputConnection? get() = currentInputConnection

    internal fun typeChar(raw: String) {
        val c = ic ?: return
        var out = raw
        if (page == PG_LETTERS && lang == "en" && shift != 0) out = raw.uppercase()
        val last = out.lastOrNull()
        if (last != null && !kbIsWordChar(last)) learnBefore(c)
        c.commitText(out, 1)
        if (shift == 1) shift = 0
        alts = emptyList()
        toolbarOpen = false
        clipSuggestion = null
        updateAutoCap()
        scheduleRefresh()
    }

    /** A letter picked from the long-press strip. */
    internal fun typeAlt(s: String) {
        fx.bubble = null
        typeChar(s.removePrefix("◌"))
    }

    internal fun showAlts(list: List<String>) {
        alts = list
    }

    internal fun onSpace() {
        val c = ic ?: return
        // A text shortcut: the word typed before this space is one the person made up, so it is replaced
        // by its text. Never in a password field.
        if (!secure) {
            val token = (c.getTextBeforeCursor(40, 0)?.toString() ?: "").takeLastWhile { !it.isWhitespace() }
            val expansion = if (token.isNotEmpty()) SnippetStore.expansionFor(this, token) else null
            if (expansion != null) {
                c.beginBatchEdit()
                c.deleteSurroundingText(token.length, 0)
                c.commitText("$expansion ", 1)
                c.endBatchEdit()
                if (shift == 1) shift = 0
                alts = emptyList()
                toolbarOpen = false
                clipSuggestion = null
                updateAutoCap()
                scheduleRefresh()
                return
            }
        }
        learnBefore(c)
        c.commitText(" ", 1)
        if (shift == 1) shift = 0
        alts = emptyList()
        toolbarOpen = false
        clipSuggestion = null
        updateAutoCap()
        scheduleRefresh()
    }

    internal fun onBackspace() {
        val c = ic ?: return
        val selected = c.getSelectedText(0)
        if (!selected.isNullOrEmpty()) c.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        alts = emptyList()
        updateAutoCap()
        scheduleRefresh()
    }

    internal fun onEnter() {
        val c = ic ?: return
        learnBefore(c)
        if (!noEnterAction && imeAction != EditorInfo.IME_ACTION_NONE &&
            imeAction != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            c.performEditorAction(imeAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        alts = emptyList()
        updateAutoCap()
        scheduleRefresh()
    }

    internal fun typeEmoji(e: String) {
        val c = ic ?: return
        c.commitText(e, 1)
        KbPrefs.pushEmoji(this, e)
        recentVersion++
        scheduleRefresh()
    }

    /** The person tapped a suggestion: it replaces the word being typed. Nothing happens by itself. */
    internal fun pickSuggestion(word: String) {
        val c = ic ?: return
        val before = c.getTextBeforeCursor(80, 0)?.toString() ?: ""
        val prefix = kbTrailingWord(before)
        val prev = prevWordOf(before, prefix)
        c.beginBatchEdit()
        if (prefix.isNotEmpty()) c.deleteSurroundingText(prefix.length, 0)
        c.commitText("$word ", 1)
        c.endBatchEdit()
        learnWord(prev, word)
        alts = emptyList()
        scheduleRefresh()
    }

    // ───────────────────────── Keys that change the layout ─────────────────────────

    internal fun onShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == 2 -> 0
            shift == 1 && now - lastShiftTap < 400 -> 2
            shift == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
    }

    internal fun showSymbols() {
        page = PG_SYM1
        alts = emptyList()
    }

    internal fun showLetters() {
        page = PG_LETTERS
        alts = emptyList()
        updateAutoCap()
        scheduleRefresh()
    }

    internal fun togglePage() {
        page = when (page) {
            PG_SYM1 -> PG_SYM2
            PG_SYM2 -> if (customSymbols.isNotEmpty()) PG_SYM3 else PG_SYM1
            else -> PG_SYM1
        }
    }

    /** Things the keyboard works out from the settings once, instead of on every key press. */
    private fun applyConfig() {
        customSymbols = kbGraphemes(cfg.symbols).take(27)
    }

    // ───────────────────────── Toolbar, clipboard and one-handed mode ─────────────────────────

    internal fun setToolbar(open: Boolean) {
        toolbarOpen = open
        alts = emptyList()
    }

    internal fun showClipboard() {
        toolbarOpen = false
        clipSuggestion = null
        pageBeforeClip = if (page == PG_EMOJI || page == PG_CLIP) PG_LETTERS else page
        page = PG_CLIP
        alts = emptyList()
        clips = clipStore.list(cfg)
    }

    internal fun showTranslate() {
        toolbarOpen = false
        clipSuggestion = null
        pageBeforeTr = if (page == PG_EMOJI || page == PG_CLIP || page == PG_TRANS) PG_LETTERS else page
        page = PG_TRANS
        alts = emptyList()
        tr.start(cfg)
    }

    internal fun closeTranslate() {
        page = pageBeforeTr
        updateAutoCap()
        scheduleRefresh()
    }

    internal fun closeClipboard() {
        page = pageBeforeClip
        updateAutoCap()
    }

    internal fun pasteClip(c: ClipItem) {
        val conn = ic ?: return
        conn.commitText(c.text, 1)
        closeClipboard()
        scheduleRefresh()
    }

    internal fun useClipSuggestion() {
        val c = clipSuggestion ?: return
        clipSuggestion = null
        val conn = ic ?: return
        conn.commitText(c.text, 1)
        scheduleRefresh()
    }

    internal fun pinClip(c: ClipItem) {
        clipStore.setPinned(c.id, !c.pinned)
        clips = clipStore.list(cfg)
    }

    internal fun deleteClip(c: ClipItem) {
        clipStore.delete(c.id)
        clips = clipStore.list(cfg)
    }

    /** Clears what is not pinned. Pinned items are only removed one by one. */
    internal fun clearClips() {
        for (c in clipStore.list(cfg)) if (!c.pinned) clipStore.delete(c.id)
        clips = clipStore.list(cfg)
    }

    /** Looks at what is on the clipboard and keeps it, unless it is marked as sensitive. */
    private fun readClipboard() {
        if (!cfg.clipOn) return
        try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val desc = clip.description
            if (!desc.hasMimeType("text/*")) return
            // Password managers mark what they copy as sensitive. It is never saved.
            if (desc.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (clipStore.add(text, cfg)) {
                clips = clipStore.list(cfg)
                if (!secure) {
                    clipSuggestion = clips.firstOrNull { it.text == text.trim() }
                    handler.removeCallbacks(dropClipSuggestion)
                    handler.postDelayed(dropClipSuggestion, 30_000L)
                }
            }
        } catch (e: Exception) {
            // Reading the clipboard can be refused. Then nothing is saved.
        }
    }

    internal fun setOneHanded(mode: Int) {
        // Start from what is saved, so a settings change made meanwhile is not lost.
        val fresh = KbPrefs.load(this).copy(oneHanded = mode)
        KbPrefs.save(this, fresh)
        cfg = fresh
        toolbarOpen = false
    }

    internal fun toggleLang() {
        lang = if (lang == "ar") "en" else "ar"
        shift = 0
        alts = emptyList()
        KbPrefs.saveLang(this, lang)
        updateAutoCap()
        scheduleRefresh()
    }

    internal fun showEmoji() {
        toolbarOpen = false
        pageBeforeEmoji = if (page == PG_EMOJI) PG_LETTERS else page
        page = PG_EMOJI
        alts = emptyList()
    }

    internal fun closeEmoji() {
        page = pageBeforeEmoji
        updateAutoCap()
    }

    internal fun showPicker() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    internal fun openSettings() {
        val i = Intent(this, MainActivity::class.java)
        i.putExtra(EXTRA_PAGE, PAGE_KEYBOARD)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(i)
            requestHideSelf(0)
        } catch (e: Exception) {
            Diag.error(this, "KeyboardService.openSettings", e)
        }
    }

    internal fun feedback(kind: KeyKind) {
        if (cfg.haptics) Haptics.tick(this)
        if (cfg.sound) {
            val fx = when (kind) {
                KeyKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyKind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyKind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            getSystemService(AudioManager::class.java)?.playSoundEffect(fx, -1f)
        }
    }

    /** Texts for the keyboard, in the language chosen inside the app. */
    internal fun str(id: Int): String = (uiContext ?: this).getString(id)

    // ───────────────────────── Capital letters ─────────────────────────

    private fun updateAutoCap() {
        if (page != PG_LETTERS || lang != "en" || shift == 2) return
        val c = ic ?: return
        shift = if (c.getCursorCapsMode(rawInputType) != 0) 1 else 0
    }

    // ───────────────────────── Learning and suggestions ─────────────────────────

    private fun canLearn() = cfg.learn && !noLearn

    /** The word before the one being typed, or null after a sentence mark or a line break. */
    private fun prevWordOf(before: String, prefix: String): String? {
        val head = before.substring(0, before.length - prefix.length)
        if (head.isEmpty() || !head.last().isWhitespace() || head.last() == '\n') return null
        val word = kbTrailingWord(head.trimEnd(' ', '\u00A0'))
        return word.ifEmpty { null }
    }

    private fun learnBefore(c: InputConnection) {
        if (!canLearn()) return
        val before = c.getTextBeforeCursor(80, 0)?.toString() ?: return
        val word = kbTrailingWord(before)
        if (word.isEmpty()) return
        learnWord(prevWordOf(before, word), word)
    }

    private fun learnWord(prev: String?, word: String) {
        if (!canLearn()) return
        KbPrefs.markLearnStartIfNeeded(this)
        learner.record(prev, word)
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 20_000L)
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    private fun refreshNow() {
        if (!cfg.suggest || secure) {
            suggestions = emptyList()
            hint = null
            return
        }
        if (!KbPrefs.suggestionsReady(this, cfg)) {
            suggestions = emptyList()
            hint = if (cfg.showHint && cfg.learn && !noLearn) hintText() else null
            return
        }
        hint = null
        val c = ic
        if (c == null) {
            suggestions = emptyList()
            return
        }
        val before = c.getTextBeforeCursor(80, 0)?.toString() ?: ""
        val prefix = kbTrailingWord(before)
        suggestions = learner.suggest(prefix, prevWordOf(before, prefix), lang == "ar", 3)
    }

    private fun hintText(): String {
        val ctx = uiContext ?: this
        val left = KbPrefs.daysLeft(this)
        return if (left == null) ctx.getString(R.string.kb_hint_start) else ctx.getString(R.string.kb_hint_days, left)
    }

    private fun enterText(action: Int): String {
        val id = when (action) {
            EditorInfo.IME_ACTION_GO -> R.string.kb_enter_go
            EditorInfo.IME_ACTION_SEARCH -> R.string.kb_enter_search
            EditorInfo.IME_ACTION_SEND -> R.string.kb_enter_send
            EditorInfo.IME_ACTION_NEXT -> R.string.kb_enter_next
            EditorInfo.IME_ACTION_DONE -> R.string.kb_enter_done
            else -> return ""
        }
        return if (noEnterAction) "" else str(id)
    }
}
