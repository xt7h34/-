package com.rigen.volumeui

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Carries the notification-listener permission. Android only lets an app ask "which media session is active?"
 * if the app has a notification listener enabled, and the app uses that for the media name and title.
 *
 * Kanade Bloom uses the same permission, and only when you turn it on: it reads a notification from the apps you
 * picked, once, to find a date, an amount, a code, a link or a number. It works on the phone alone and it keeps,
 * logs and sends nothing. With Bloom off, this class does nothing with any notification.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null || !BloomPrefs.enabled(this)) return
            val rank = Ranking()
            val importance = if (currentRanking?.getRanking(sbn.key, rank) == true) {
                rank.importance
            } else {
                NotificationManager.IMPORTANCE_UNSPECIFIED
            }
            BloomEngine.onPosted(this, sbn, importance)
        } catch (e: Throwable) {
            Diag.error(this, "Bloom listener", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        VolumeAccessibilityService.instance?.dismissBloom(key)
    }
}
