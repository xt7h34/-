package com.rigen.volumeui

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.graphics.Matrix
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Base64
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.max

/*
 * KanaGoals, the parts around the goals themselves: reminders, the home screen widget, the lock,
 * the encrypted backup and the photos. Like the goals, all of it stays on this phone.
 */

object GoalsExtras {
    /** Called after every change: the reminders and the widget follow the goals. */
    fun afterSave(c: Context, goals: List<Goal>) {
        try {
            GoalsReminders.sync(c, goals)
        } catch (e: Throwable) {
            Diag.error(c, "goals reminders", e)
        }
        try {
            GoalsWidgets.updateAll(c)
        } catch (e: Throwable) {
            Diag.error(c, "goals widget", e)
        }
    }
}

// ───────────────────────── Reminders ─────────────────────────

/**
 * One local alarm per goal that has a reminder. Each alarm rings once; when it does, the next one
 * is set. A reboot or an app update clears alarms, so the receiver sets them again.
 */
object GoalsReminders {
    const val ACTION = "com.kanade.goals.REMIND"
    private const val CHANNEL = "kanagoals_reminders"
    private const val PREF = "kanade_goals_alarms"
    private const val KEY_IDS = "ids"

    /** The next time this goal's reminder should ring after [now], or null when it has none. */
    fun nextTrigger(g: Goal, now: Long): Long? {
        if (g.remind !in 1..3) return null
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, g.remindMinutes / 60)
        c.set(Calendar.MINUTE, g.remindMinutes % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        when (g.remind) {
            1 -> if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
            2 -> {
                val want = g.remindDay.coerceIn(1, 7)
                var guard = 0
                while ((c.get(Calendar.DAY_OF_WEEK) != want || c.timeInMillis <= now) && guard < 8) {
                    c.add(Calendar.DAY_OF_YEAR, 1)
                    guard++
                }
            }
            else -> {
                val dom = g.remindDay.coerceIn(1, 28)
                c.set(Calendar.DAY_OF_MONTH, dom)
                if (c.timeInMillis <= now) {
                    c.add(Calendar.MONTH, 1)
                    c.set(Calendar.DAY_OF_MONTH, dom)
                }
            }
        }
        return c.timeInMillis
    }

    private fun pending(c: Context, id: String, create: Boolean): PendingIntent? {
        val intent = Intent(c, GoalsReceiver::class.java)
            .setAction(ACTION)
            .setData(Uri.parse("kanagoal://$id"))
            .putExtra("id", id)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(c, 0, intent, flags)
    }

    /** Sets the alarm of every goal that needs one and cancels the ones that no longer do. */
    fun sync(c: Context, goals: List<Goal>) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val sp = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = sp.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val active = HashSet<String>()
        for (g in goals) {
            val at = if (g.archived || g.completedAt != 0L) null else nextTrigger(g, now)
            val pi = if (at != null) pending(c, g.id, true) else null
            if (at != null && pi != null) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                active.add(g.id)
            }
        }
        for (id in old) {
            if (id !in active) {
                pending(c, id, false)?.let {
                    am.cancel(it)
                    it.cancel()
                }
            }
        }
        sp.edit().putStringSet(KEY_IDS, active).apply()
    }

    /** The scheduled moments of the goal's reminder after [from], up to and including [to]. */
    fun occurrences(g: Goal, from: Long, to: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = from
        while (out.size < 120) {
            val n = nextTrigger(g, t) ?: break
            if (n > to) break
            out.add(n)
            t = n
        }
        return out
    }

    /** An alarm rang: do what the plans owe, tell the person, then set the next alarm. */
    fun fire(c: Context, id: String) {
        GoalsAuto.catchUp(c)
        val goals = GoalsStore.load(c)
        val g = goals.firstOrNull { it.id == id }
        if (g != null && !g.archived && g.completedAt == 0L && g.remind in 1..3) notify(c, g, g.plan > 0L && g.autoAdd)
        sync(c, goals)
    }

    private fun channel(c: Context, nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.goals_notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openGoals(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 950900,
        Intent(c, MainActivity::class.java).putExtra(EXTRA_PAGE, PAGE_GOALS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The reminder. Unless the plan adds money by itself, it carries buttons: the plan's amount (or the
     * goal's quick amounts) and a box to type any other amount, so saving never needs the app opened.
     */
    private fun notify(c: Context, g: Goal, auto: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        channel(c, nm)
        // With the lock on, nothing about the goal shows on the lock screen, and no amount is offered.
        val locked = GoalsLock.enabled(c)
        val remaining = max(0L, g.target - g.saved)
        val name = "${g.emoji} ${g.name}"
        val text = when {
            locked -> c.getString(R.string.goals_notif_locked)
            auto && g.plan > 0L -> c.getString(R.string.goals_notif_auto, formatMoney(g.plan, g.currency), name)
            g.plan > 0L -> c.getString(R.string.goals_notif_plan, formatMoney(g.plan, g.currency), name)
            else -> c.getString(R.string.goals_notif_text, name, formatMoney(remaining, g.currency))
        }
        val nid = 5000 + (g.id.hashCode() and 0xFFFF)
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(c.getString(R.string.goals_notif_title))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(if (locked) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openGoals(c))
        if (!auto) {
            val icon = Icon.createWithResource(c, R.drawable.ic_stat_alarm)
            if (!locked) {
                val amounts = if (g.plan > 0L) listOf(g.plan) else GoalsEngine.quickFor(g, remaining).take(2)
                for (a in amounts) {
                    b.addAction(
                        Notification.Action.Builder(icon, "+" + formatMoney(a, g.currency), GoalsQuick.pending(c, g.id, a, nid)).build(),
                    )
                }
            }
            val box = RemoteInput.Builder(GoalsQuick.KEY_AMOUNT).setLabel(c.getString(R.string.goals_notif_other_hint)).build()
            b.addAction(
                Notification.Action.Builder(icon, c.getString(R.string.goals_notif_other), GoalsQuick.pending(c, g.id, 0L, nid, mutable = true))
                    .addRemoteInput(box)
                    .build(),
            )
        }
        nm.notify(nid, b.build())
    }

    /** The goal reached its price by itself or from a button: say so even if the app is closed. */
    fun notifyDone(c: Context, goalId: String) {
        val g = GoalsStore.load(c).firstOrNull { it.id == goalId } ?: return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        channel(c, nm)
        val text = if (GoalsLock.enabled(c)) c.getString(R.string.goals_st_done) else c.getString(R.string.goals_celebrate_msg, "${g.emoji} ${g.name}")
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(c.getString(R.string.goals_celebrate_title))
            .setContentText(text)
            .setVisibility(if (GoalsLock.enabled(c)) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openGoals(c))
            .build()
        nm.notify(6000 + (g.id.hashCode() and 0xFFFF), n)
    }
}

/** Saving plans that add money by themselves: whatever came due while the app was closed is added when it next runs. */
object GoalsAuto {
    fun catchUp(c: Context, now: Long = System.currentTimeMillis()) {
        for (g in GoalsStore.load(c)) {
            if (g.plan <= 0L || !g.autoAdd || g.remind !in 1..3 || g.archived || g.completedAt != 0L || g.planSince <= 0L) continue
            val times = GoalsReminders.occurrences(g, g.planSince, now)
            if (times.isEmpty()) continue
            if (GoalsStore.applyPlan(c, g.id, times)) GoalsReminders.notifyDone(c, g.id)
        }
    }
}

/** Adding money to a goal from a widget button or a notification button, without opening the app. */
object GoalsQuick {
    const val ACTION = "com.kanade.goals.QUICK"
    const val KEY_AMOUNT = "amount"

    /**
     * A button's action. With [mutable] it is the "other amount" box of a notification, which needs the
     * system to be able to put the typed text into it.
     */
    fun pending(c: Context, goalId: String, amount: Long, nid: Int = 0, mutable: Boolean = false): PendingIntent {
        val i = Intent(c, GoalsReceiver::class.java)
            .setAction(ACTION)
            .setData(Uri.parse("kanagoal://quick/$goalId/$amount/$nid/${if (mutable) "box" else "fixed"}"))
            .putExtra("id", goalId)
            .putExtra("amount", amount)
            .putExtra("nid", nid)
        val kind = if (!mutable) {
            PendingIntent.FLAG_IMMUTABLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or kind)
    }

    fun handle(c: Context, intent: Intent) {
        val id = intent.getStringExtra("id").orEmpty()
        val nid = intent.getIntExtra("nid", 0)
        var amount = intent.getLongExtra("amount", 0L)
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_AMOUNT)?.toString()
        if (typed != null) amount = parseMoney(typed) ?: 0L
        if (nid != 0) c.getSystemService(NotificationManager::class.java)?.cancel(nid)
        val g = GoalsStore.load(c).firstOrNull { it.id == id }
        if (g == null || g.archived || amount <= 0L) {
            if (typed != null) Toast.makeText(c, R.string.goals_err_amount, Toast.LENGTH_SHORT).show()
            return
        }
        val reached = GoalsStore.addEntry(c, id, amount, "")
        val msg = if (GoalsLock.enabled(c)) {
            c.getString(R.string.goals_quick_done_locked)
        } else {
            c.getString(R.string.goals_quick_done, formatMoney(amount, g.currency), "${g.emoji} ${g.name}")
        }
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
        if (reached) GoalsReminders.notifyDone(c, id)
    }
}

class GoalsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                GoalsReminders.ACTION -> GoalsReminders.fire(context, intent.getStringExtra("id").orEmpty())
                GoalsQuick.ACTION -> GoalsQuick.handle(context, intent)
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    GoalsAuto.catchUp(context)
                    GoalsReminders.sync(context, GoalsStore.load(context))
                    GoalsWidgets.updateAll(context)
                }
            }
        } catch (e: Throwable) {
            Diag.error(context, "GoalsReceiver", e)
        }
    }
}

// ───────────────────────── Widget ─────────────────────────

/** Home screen widget: the goal you put first. */
class GoalsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetDesigns.goals(context, it)) }
    }
}

object GoalsWidgets {
    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        m.getAppWidgetIds(ComponentName(c, GoalsWidget::class.java))
            .forEach { m.updateAppWidget(it, WidgetDesigns.goals(c, it)) }
    }
}

// ───────────────────────── Lock ─────────────────────────

/**
 * An optional lock on the KanaGoals page: fingerprint or face, and the screen lock as the way back
 * in on Android 11 and newer. While it is on, the widget and the reminders show no amounts.
 */
object GoalsLock {
    /** True from a successful unlock until the app is left. Kept in memory only. */
    @Volatile
    var unlocked = false

    /** Set while a system picker is open, so that leaving the app for it does not lock the page. */
    @Volatile
    var suppress = false

    private const val PREF = "kanade_goals_lock"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("on", false)

    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("on", on).apply()
        unlocked = on
        try {
            GoalsWidgets.updateAll(c)
        } catch (e: Throwable) {
            Diag.error(c, "goals lock widget", e)
        }
    }

    private fun authenticators(): Int =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when this phone can ask for a fingerprint, a face or the screen lock. */
    fun available(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bm = c.getSystemService(BiometricManager::class.java) ?: return false
            return bm.canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
        }
        return VaultBio.available(c)
    }

    fun authenticate(c: Context, title: String, cancel: String, ok: () -> Unit, failed: () -> Unit) {
        try {
            val b = BiometricPrompt.Builder(c).setTitle(title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setAllowedAuthenticators(authenticators())
            } else {
                b.setNegativeButton(cancel, c.mainExecutor) { _, _ -> failed() }
            }
            b.build().authenticate(
                CancellationSignal(),
                c.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        ok()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        failed()
                    }
                },
            )
        } catch (e: Exception) {
            failed()
        }
    }
}

// ───────────────────────── Encrypted backup ─────────────────────────

/**
 * The backup file is locked with a password the person picks: PBKDF2 for the key and AES-256-GCM
 * for the lock, the same way the vault does it (VaultCrypto). Without the password it is noise.
 */
object GoalsBackup {
    private const val APP = "KanaGoals"

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Slow on purpose (it is the guess-proofing). Call it off the main thread. */
    fun encrypt(password: CharArray, plain: ByteArray): ByteArray {
        val salt = VaultCrypto.randomBytes(16)
        val key = VaultCrypto.deriveKey(password, salt, VaultCrypto.ITERATIONS)
        try {
            val (iv, ct) = VaultCrypto.encrypt(key, plain)
            return JSONObject()
                .put("app", APP).put("v", 1).put("iter", VaultCrypto.ITERATIONS)
                .put("salt", b64(salt)).put("iv", b64(iv)).put("ct", b64(ct))
                .toString().toByteArray(Charsets.UTF_8)
        } finally {
            key.fill(0)
        }
    }

    /** The plain bytes, or null when the password is wrong or this is not a KanaGoals backup. */
    fun decrypt(password: CharArray, bytes: ByteArray): ByteArray? = try {
        val h = JSONObject(String(bytes, Charsets.UTF_8))
        require(h.optString("app") == APP)
        val iter = h.getInt("iter")
        require(iter in 100_000..2_000_000) // a file must not be able to make this run for hours
        val key = VaultCrypto.deriveKey(password, unb64(h.getString("salt")), iter)
        try {
            VaultCrypto.decrypt(key, unb64(h.getString("iv")), unb64(h.getString("ct")))
        } finally {
            key.fill(0)
        }
    } catch (e: Exception) {
        null
    }
}

// ───────────────────────── Photos ─────────────────────────

/**
 * A goal's picture, kept as a small JPEG inside the app. A picked photo waits in a temporary file
 * until the goal is saved, so cancelling never changes the photo a goal already has.
 */
object GoalsPhotos {
    private const val MAX_SIDE = 720
    private const val MAX_BACKUP_CHARS = 400_000
    private const val MAX_IMPORT_BYTES = 1_500_000

    private fun dir(c: Context): File = File(c.filesDir, "goalphotos").also { it.mkdirs() }
    private fun finalFile(c: Context, id: String) = File(dir(c), "$id.jpg")
    private fun tempFile(c: Context, id: String) = File(dir(c), "$id.tmp")

    fun delete(c: Context, id: String) {
        finalFile(c, id).delete()
        tempFile(c, id).delete()
    }

    fun deleteFinal(c: Context, id: String) {
        finalFile(c, id).delete()
    }

    fun discardTemp(c: Context, id: String) {
        tempFile(c, id).delete()
    }

    /** Makes the staged photo the goal's photo. */
    fun commit(c: Context, id: String) {
        val t = tempFile(c, id)
        if (!t.exists()) return
        val f = finalFile(c, id)
        f.delete()
        t.renameTo(f)
    }

    fun load(c: Context, id: String, staged: Boolean): Bitmap? = try {
        val f = if (staged) tempFile(c, id) else finalFile(c, id)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (e: Throwable) {
        null
    }

    /** Reads the picked image, turns it upright, shrinks it and keeps it in the temporary file. */
    fun stage(c: Context, id: String, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            false
        } else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (raw == null) {
                false
            } else {
                val orientation = c.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                val m = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                }
                var bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                val scale = MAX_SIDE.toFloat() / max(bmp.width, bmp.height)
                if (scale < 1f) {
                    bmp = Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                }
                tempFile(c, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                true
            }
        }
    } catch (e: Throwable) {
        false
    }

    /** The photo as text for the backup, or null when there is none or it is too big to carry along. */
    fun readBase64(c: Context, id: String): String? = try {
        val f = finalFile(c, id)
        if (!f.exists()) {
            null
        } else {
            val s = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            if (s.length > MAX_BACKUP_CHARS) null else s
        }
    } catch (e: Throwable) {
        null
    }

    /** Stores a photo from a backup after checking that it really is a picture. */
    fun importBase64(c: Context, id: String, text: String): Boolean = try {
        if (text.length > MAX_BACKUP_CHARS) {
            false
        } else {
            val bytes = Base64.decode(text, Base64.NO_WRAP)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bytes.size > MAX_IMPORT_BYTES || bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > 4000 || bounds.outHeight > 4000
            ) {
                false
            } else {
                finalFile(c, id).writeBytes(bytes)
                true
            }
        }
    } catch (e: Throwable) {
        false
    }
}
