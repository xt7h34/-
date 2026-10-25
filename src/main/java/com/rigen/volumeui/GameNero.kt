package com.rigen.volumeui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Nero for a finished round of a game, the way Kanade Blocks earns it: the bot opens a number when a
 * round starts, the app sends the score when it ends, and the bot does the counting (each game has its
 * own rate there). A score that could not be sent is kept and sent again the next time the game opens.
 * Only a signed-in account earns Nero.
 */
class GameNero(
    private val c: Context,
    /** The bot's name for the game: "g2048", "stack" or "memory". */
    private val game: String,
    private val scope: CoroutineScope,
    /** Below this score a round cannot earn a whole Nero, so nothing is sent. */
    private val minScore: Int,
) {
    companion object {
        const val NONE = 0
        const val GUEST = 1
        const val LOW = 2
        const val OFFLINE = 3
        const val SENDING = 4
        const val PENDING = 5
        const val REJECTED = 6
        const val DONE = 7
    }

    var state by mutableIntStateOf(NONE)
        private set
    var amount by mutableIntStateOf(0)
        private set
    var capped by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set

    private val prefs = c.applicationContext.getSharedPreferences("kanade_nero", Context.MODE_PRIVATE)
    private val signedIn: Boolean get() = AccountStore.load(c) != null

    /** A new round: the old number is dropped and the bot opens a fresh one. */
    fun begin() {
        state = NONE
        amount = 0
        capped = false
        prefs.edit().remove("gid_$game").apply()
        open()
    }

    /** For a game that survives leaving the page: open a round only when there is none yet. */
    fun ensureOpen() {
        if (prefs.getString("gid_$game", null) == null) open()
    }

    private fun open() {
        if (!signedIn) return
        scope.launch {
            val token = AccountStore.load(c)?.token ?: return@launch
            val r = KanadeApi.call("/me/game/start", JSONObject().put("game", game), token)
            val id = r.body?.optString("gameId").orEmpty()
            if (r.ok && id.isNotEmpty()) prefs.edit().putString("gid_$game", id).apply()
        }
    }

    /** The round ended with [score]. */
    fun finish(score: Int) {
        if (!signedIn) {
            state = GUEST
            return
        }
        val id = prefs.getString("gid_$game", null)
        prefs.edit().remove("gid_$game").apply()
        when {
            score < minScore -> state = LOW
            id == null -> state = OFFLINE
            else -> {
                prefs.edit().putString("pend_gid_$game", id).putInt("pend_score_$game", score).apply()
                scope.launch { send() }
            }
        }
    }

    /** Sends a score that could not be sent before. */
    fun retry() {
        if (signedIn && prefs.getString("pend_gid_$game", null) != null) scope.launch { send() }
    }

    private suspend fun send() {
        val id = prefs.getString("pend_gid_$game", null) ?: return
        val token = AccountStore.load(c)?.token ?: return
        val score = prefs.getInt("pend_score_$game", 0)
        state = SENDING
        val r = KanadeApi.call("/me/game/finish", JSONObject().put("gameId", id).put("score", score), token)
        when {
            r.ok -> {
                prefs.edit().remove("pend_gid_$game").remove("pend_score_$game").apply()
                amount = r.body?.optInt("nero", 0) ?: 0
                capped = r.body?.optBoolean("capped", false) ?: false
                state = DONE
            }
            r.status == 0 || r.status >= 500 -> state = PENDING
            else -> {
                prefs.edit().remove("pend_gid_$game").remove("pend_score_$game").apply()
                message = r.message(c)
                state = REJECTED
            }
        }
    }
}

/** The line that says what the round earned, or null before it ends. */
@Composable
fun GameNero.line(): String? = when (state) {
    GameNero.GUEST -> stringResource(R.string.blocks_nero_guest)
    GameNero.LOW -> stringResource(R.string.game_nero_low)
    GameNero.OFFLINE -> stringResource(R.string.blocks_nero_offline)
    GameNero.SENDING -> stringResource(R.string.blocks_nero_sending)
    GameNero.PENDING -> stringResource(R.string.blocks_nero_pending)
    GameNero.REJECTED -> message
    GameNero.DONE -> when {
        amount > 0 && capped -> stringResource(R.string.blocks_nero_earned, amount.toString()) + " \u00B7 " + stringResource(R.string.blocks_nero_capped)
        amount > 0 -> stringResource(R.string.blocks_nero_earned, amount.toString())
        capped -> stringResource(R.string.blocks_nero_capped)
        else -> stringResource(R.string.game_nero_low)
    }
    else -> null
}
