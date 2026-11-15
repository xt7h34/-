package com.rigen.volumeui

import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.service.notification.StatusBarNotification
import android.widget.Toast

/** What a notification offered: the buttons, and who and what it was about. */
class BloomOffer(
    val key: String,
    val sender: String,
    val text: String,
    val actions: List<BloomAction>,
)

/** Kanade Bloom's settings. It is off until you turn it on, and it only looks at the apps you pick. */
object BloomPrefs {
    val ALL_KINDS: Set<Int> = setOf(BA_EVENT, BA_EXPENSE, BA_CODE, BA_LINK, BA_CALL, BA_MAP)

    /** Messaging apps that are chosen the first time, if they are installed. */
    private val KNOWN = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.facebook.orca",
    )

    private fun sp(c: Context) = c.getSharedPreferences("kanade_bloom", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("on", false)

    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("on", on).apply()

    fun kinds(c: Context): Set<Int> {
        val saved = sp(c).getStringSet("kinds", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: return ALL_KINDS
        // The map button came later: someone who already chose their kinds gets it switched on once.
        if (!sp(c).getBoolean("map_added", false)) {
            val withMap = saved + BA_MAP
            sp(c).edit().putBoolean("map_added", true)
                .putStringSet("kinds", withMap.map { it.toString() }.toSet()).apply()
            return withMap
        }
        return saved
    }

    fun setKinds(c: Context, kinds: Set<Int>) =
        sp(c).edit().putStringSet("kinds", kinds.map { it.toString() }.toSet()).apply()

    fun apps(c: Context): Set<String> =
        sp(c).getStringSet("apps", null)?.toSet() ?: KNOWN.filter { isInstalled(c, it) }.toSet()

    fun setApps(c: Context, apps: Set<String>) = sp(c).edit().putStringSet("apps", apps).apply()

    private fun isInstalled(c: Context, pkg: String): Boolean = try {
        c.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (e: Exception) {
        false
    }
}

/**
 * Reads each notification as it arrives, on the phone alone: nothing is saved, logged or sent. Only apps you
 * picked count, only notifications that would pop up, and a message is looked at once.
 */
object BloomEngine {
    private const val SEEN_MS = 10 * 60_000L
    private val seen = LinkedHashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())

    fun onPosted(c: Context, sbn: StatusBarNotification, importance: Int) {
        if (!BloomPrefs.enabled(c)) return
        if (sbn.packageName == c.packageName) return
        if (sbn.packageName !in BloomPrefs.apps(c)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        // A quiet notification does not pop up, so there is nothing for the capsule to sit under.
        if (importance != NotificationManager.IMPORTANCE_UNSPECIFIED && importance < NotificationManager.IMPORTANCE_HIGH) return

        val extras = n.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = lastMessage(extras)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return
        if (text.isBlank()) return

        // The same message is not offered twice.
        val id = sbn.packageName + "|" + text.hashCode()
        val now = System.currentTimeMillis()
        synchronized(seen) {
            seen.entries.removeAll { now - it.value > SEEN_MS }
            if (seen.containsKey(id)) return
            seen[id] = now
        }

        val actions = BloomParser.parse(sender, text, BloomPrefs.kinds(c), now).filterNot { alreadyInCalendar(c, it) }
        if (actions.isEmpty()) return
        val offer = BloomOffer(sbn.key, sender, text, actions)
        main.post { VolumeAccessibilityService.instance?.showBloom(offer) }
    }

    @Suppress("DEPRECATION")
    private fun lastMessage(extras: Bundle): String? {
        val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = arr.lastOrNull() as? Bundle ?: return null
        return last.getCharSequence("text")?.toString()
    }

    private fun alreadyInCalendar(c: Context, a: BloomAction): Boolean {
        if (a.kind != BA_EVENT) return false
        val day = CalendarEngine.ld(a.at)
        return CalendarStore.load(c).any { it.title == a.value && CalendarEngine.ld(it.start) == day }
    }

    /** What a new event would run into at the same time: an alarm, a prayer or another entry. Null if nothing. */
    fun conflict(c: Context, start: Long, allDay: Boolean): String? {
        if (allDay) return null
        val snap = CalendarEngine.snapshot(c)
        val near = snap.itemsFor(CalendarEngine.ld(start))
            .filter { !it.allDay && kotlin.math.abs(it.at - start) < 30 * 60_000L }
            .minByOrNull { kotlin.math.abs(it.at - start) } ?: return null
        val time = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(near.at))
        return c.getString(R.string.bloom_conflict, near.title, time)
    }

    // ───────────── What the buttons do ─────────────

    fun run(c: Context, offer: BloomOffer, a: BloomAction) {
        try {
            when (a.kind) {
                BA_CODE -> copyCode(c, a.value)
                BA_LINK -> {
                    val url = if (a.value.startsWith("http", ignoreCase = true)) a.value else "https://" + a.value
                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                BA_MAP -> {
                    val q = Uri.encode(a.value)
                    try {
                        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + q)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: android.content.ActivityNotFoundException) {
                        c.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + q))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                BA_CALL -> c.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.value))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                BA_EVENT, BA_EXPENSE -> c.startActivity(
                    Intent(c, BloomActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("kind", a.kind)
                        .putExtra("value", a.value)
                        .putExtra("at", a.at)
                        .putExtra("allDay", a.allDay)
                        .putExtra("endAt", a.endAt)
                        .putExtra("amount", a.amount)
                        .putExtra("sender", offer.sender)
                        .putExtra("snippet", offer.text),
                )
            }
        } catch (e: Exception) {
            Diag.error(c, "Bloom action", e)
        }
    }

    /** "Remind me in an hour" for any notification. */
    fun remindLater(c: Context, offer: BloomOffer) {
        val at = (System.currentTimeMillis() / 60_000L + 60L) * 60_000L
        val title = (offer.sender + ": " + offer.text).replace(Regex("\\s+"), " ").trim().take(80)
        CalendarStore.upsert(
            c,
            CalEvent(
                id = CalendarStore.newId(),
                title = title,
                notes = "",
                start = at,
                end = at,
                allDay = false,
                kind = CAL_REMINDER,
                repeat = REP_NONE,
                remindBefore = 0,
                source = "bloom",
            ),
        )
        Toast.makeText(c, Prefs.localized(c).getString(R.string.bloom_remind_set), Toast.LENGTH_SHORT).show()
    }

    /** Copies a code as a sensitive item, so Android hides it in the clipboard preview, and clears it after a minute. */
    private fun copyCode(c: Context, code: String) {
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("code", code)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        Toast.makeText(c, Prefs.localized(c).getString(R.string.bloom_code_copied), Toast.LENGTH_SHORT).show()
        main.postDelayed({
            try {
                // Only if it is still our code, so something copied later is never wiped. If Android does not
                // let us look (it often does not, in the background), the code is left alone.
                val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == code && Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            } catch (e: Exception) {
                // Not allowed: nothing to do.
            }
        }, 60_000L)
    }
}
