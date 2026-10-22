package com.rigen.volumeui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Optional sync with the Kanade bot. Only the daily totals per app go out (never what was watched),
 * so the bot can rank people and the Wrapped story can show "you vs everyone". Off until turned on.
 */
object ReelsSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastAt = 0L
    private var running = false

    /** Called after every counted video; sends at most once a minute and only when sync is on. */
    fun request(c: Context) {
        if (!ReelsStore.syncOn(c)) return
        val now = System.currentTimeMillis()
        if (running || now - lastAt < 60_000L) return
        val app = c.applicationContext
        val session = AccountStore.load(app) ?: return
        running = true
        lastAt = now
        scope.launch {
            try {
                push(app, session.token)
            } finally {
                running = false
            }
        }
    }

    private fun payload(c: Context): JSONObject {
        val days = JSONObject()
        for (d in ReelsStore.lastDays(c, 7)) {
            if (d.total <= 0) continue
            days.put(
                d.key,
                JSONObject()
                    .put("a", JSONObject(d.counts.toMap()))
                    .put("o", d.opens)
                    .put("l", d.longest),
            )
        }
        return JSONObject().put("days", days).put("limit", ReelsStore.limit(c))
    }

    private suspend fun push(c: Context, token: String): ApiResult =
        KanadeApi.call("/me/reels/sync", payload(c), token)

    /** Sends right now (when sync was just turned on). */
    suspend fun pushNow(c: Context) {
        val s = AccountStore.load(c) ?: return
        lastAt = System.currentTimeMillis()
        push(c, s.token)
    }

    /** Turns sync off for good: the bot forgets everything it was sent. */
    suspend fun clearRemote(c: Context) {
        val s = AccountStore.load(c) ?: return
        KanadeApi.call("/me/reels/sync", JSONObject().put("clear", true), s.token)
    }

    /** Sends the latest numbers, then asks how this person ranks. Null when there is no answer yet. */
    suspend fun pushAndRank(c: Context, period: String): Int? {
        val s = AccountStore.load(c) ?: return null
        push(c, s.token)
        val r = KanadeApi.call("/me/reels/rank", JSONObject().put("period", period), s.token)
        val b = r.body
        if (!r.ok || b == null || b.isNull("pct")) return null
        return b.optInt("pct", -1).takeIf { it in 0..100 }
    }
}
