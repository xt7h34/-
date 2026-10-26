package com.rigen.volumeui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri

/**
 * What the person chose for the prayer reminders on top of the ones the account page switches on:
 * an extra alert a few minutes before each prayer, and the sound the prayer alerts make. The app
 * ships no recording; the sound is whatever the person picks (a ringtone, or an adhan saved on the phone).
 */
object PrayerAlerts {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("kanade_prayer_alerts", Context.MODE_PRIVATE)

    /** Minutes before the prayer for the early alert, or 0 for none. */
    fun leadMinutes(c: Context): Int = sp(c).getInt("lead", 0)

    fun setLead(c: Context, minutes: Int) {
        sp(c).edit().putInt("lead", minutes).apply()
    }

    /** The chosen sound as a text uri, or empty for the phone's default notification sound. */
    fun sound(c: Context): String = sp(c).getString("sound", "") ?: ""

    fun setSound(c: Context, uri: String) {
        sp(c).edit().putString("sound", uri).apply()
    }

    /**
     * The notification channel for prayer alerts. A channel's sound can't be changed once it exists, so
     * every sound gets a channel of its own and the ones of earlier sounds are removed.
     */
    fun channel(c: Context, nm: NotificationManager): String {
        val uri = sound(c)
        val id = "kanade_prayer_" + uri.hashCode().toUInt().toString(16)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, c.getString(R.string.prayer_channel), NotificationManager.IMPORTANCE_HIGH)
            if (uri.isNotEmpty()) {
                ch.setSound(
                    Uri.parse(uri),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            nm.createNotificationChannel(ch)
            nm.notificationChannels
                .filter { it.id.startsWith("kanade_prayer_") && it.id != id }
                .forEach { nm.deleteNotificationChannel(it.id) }
        }
        return id
    }

    /** Sets the alarms again after the choices changed. */
    fun apply(c: Context) {
        KanadeReminders.schedulePrayers(c, KanadeReminders.cachedPrayers(c).second)
    }
}
