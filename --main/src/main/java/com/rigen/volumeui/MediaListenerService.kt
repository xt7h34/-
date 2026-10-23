package com.rigen.volumeui

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose. Android only lets an app ask "which media session is active?" if the app has
 * a notification listener enabled, so this class exists to carry that permission. It never reads,
 * stores or sends any notification: the app only uses the media session's app name and title.
 */
class MediaListenerService : NotificationListenerService()
