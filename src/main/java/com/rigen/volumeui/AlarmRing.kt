package com.rigen.volumeui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

const val DISMISS_DRAG = 0
const val DISMISS_TAP = 1
const val DISMISS_HOLD = 2

private const val CH_RING = "kanade_ring"
private const val CH_RING_DND = "kanade_ring_dnd"

// ───────────────────────────── The ringing service ─────────────────────────────

/**
 * Plays the alarm (or the "time is up" sound) and keeps the ring screen's notification. It runs
 * as a foreground service so it keeps ringing with the screen off, and it plays on the alarm
 * volume, so silent and vibrate modes do not mute it.
 */
class AlarmRingService : Service() {

    companion object {
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_NOTE = "note_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TONE = "tone"
        private const val TIMEOUT_MS = 5 * 60_000L

        /** The notification id that is ringing right now, or 0. The ring screen watches it. */
        @Volatile
        var active: Int = 0

        fun start(c: Context, noteId: Int, title: String, alarmId: Int, tone: String) {
            val i = Intent(c, AlarmRingService::class.java)
                .putExtra(EXTRA_ID, alarmId)
                .putExtra(EXTRA_NOTE, noteId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TONE, tone)
            c.startForegroundService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var savedVolume = -1
    private var savedFilter = -1
    private var noteId = 0

    private val timeout = Runnable { ClockEngine.dismiss(this, noteId) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val alarmId = intent.getIntExtra(EXTRA_ID, 0)
        noteId = intent.getIntExtra(EXTRA_NOTE, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.alarm_title)
        val tone = intent.getStringExtra(EXTRA_TONE) ?: ""
        val inDnd = ClockStore.ringInDnd(this)
        val inSilent = ClockStore.ringInSilent(this)
        val inVibrate = ClockStore.ringInVibrate(this)

        val nm = getSystemService(NotificationManager::class.java)
        val channelId = if (inDnd) CH_RING_DND else CH_RING
        val ch = NotificationChannel(channelId, getString(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        if (inDnd) ch.setBypassDnd(true)
        nm.createNotificationChannel(ch)

        val screen = PendingIntent.getActivity(
            this, 9000 + noteId,
            Intent(this, AlarmRingActivity::class.java)
                .putExtra(EXTRA_ID, alarmId).putExtra(EXTRA_NOTE, noteId).putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(getString(R.string.alarm_ringing))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(0, getString(R.string.alarm_dismiss), ClockEngine.broadcast(this, ClockEngine.ACTION_DISMISS, noteId, 600000 + noteId))
        if (alarmId > 0) {
            b.addAction(0, getString(R.string.alarm_snooze), ClockEngine.broadcast(this, ClockEngine.ACTION_SNOOZE, alarmId, 700000 + alarmId))
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(noteId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(noteId, n)
        }
        active = noteId

        try {
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kanade:ring").also { it.acquire(TIMEOUT_MS) }
        } catch (e: Throwable) {
            Diag.error(this, "ring wakelock", e)
        }
        // Each switch decides what happens when the phone is in that mode. The stricter one wins.
        val am = getSystemService(AudioManager::class.java)
        val filter = nm.currentInterruptionFilter
        val dndActive = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val ringer = am.ringerMode
        var sound = true
        var vibrate = true
        var force = false
        if (dndActive) {
            if (inDnd) {
                force = true
            } else {
                sound = false
                vibrate = false
            }
        }
        if (ringer == AudioManager.RINGER_MODE_SILENT) {
            if (inSilent) {
                force = true
            } else {
                sound = false
                vibrate = false
            }
        }
        if (ringer == AudioManager.RINGER_MODE_VIBRATE) {
            if (inVibrate) force = true else sound = false
        }
        if (force && sound) relaxSilence(dndActive && inDnd)
        if (sound) startSound(tone)
        if (vibrate) startVibration()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
        return START_NOT_STICKY
    }

    /** Makes sure a quiet alarm volume or "total silence" cannot swallow the alarm. */
    private fun relaxSilence(relaxDnd: Boolean) {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val now = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (now < (max * 0.5f).roundToInt()) {
                savedVolume = now
                am.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.7f).roundToInt().coerceAtLeast(1), 0)
            }
        } catch (e: Throwable) {
            Diag.error(this, "ring volume", e)
        }
        if (!relaxDnd) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
            ) {
                savedFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            }
        } catch (e: Throwable) {
            Diag.error(this, "ring dnd", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun startSound(tone: String) {
        stopSound()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        try {
            val am = getSystemService(AudioManager::class.java)
            am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        } catch (e: Throwable) {
            Diag.error(this, "ring focus", e)
        }
        fun build(custom: Boolean): MediaPlayer {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            if (custom) {
                mp.setDataSource(this, Uri.parse(tone))
            } else {
                resources.openRawResourceFd(R.raw.kanade_alarm).use {
                    mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            mp.isLooping = true
            mp.prepare()
            return mp
        }
        val mp = try {
            build(tone.isNotBlank())
        } catch (e: Throwable) {
            Diag.error(this, "ring tone, using the default one", e)
            try {
                build(false)
            } catch (e2: Throwable) {
                Diag.error(this, "ring default tone", e2)
                null
            }
        }
        player = mp
        mp?.start()
    }

    private fun stopSound() {
        try {
            player?.stop()
        } catch (e: Throwable) {
            // Already stopped.
        }
        player?.release()
        player = null
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v = getSystemService(Vibrator::class.java)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600), 0), attrs)
        } catch (e: Throwable) {
            Diag.error(this, "ring vibration", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopSound()
        try {
            getSystemService(Vibrator::class.java).cancel()
        } catch (e: Throwable) {
            // Nothing to cancel.
        }
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Throwable) {
            // Already released.
        }
        try {
            if (savedVolume >= 0) {
                getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            }
            if (savedFilter >= 0) {
                getSystemService(NotificationManager::class.java).setInterruptionFilter(savedFilter)
            }
        } catch (e: Throwable) {
            Diag.error(this, "ring restore", e)
        }
        active = 0
        super.onDestroy()
    }
}

// ───────────────────────────── The ring screen ─────────────────────────────

/** Opens over the lock screen when an alarm rings. */
class AlarmRingActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Prefs.localized(newBase))
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        enableEdgeToEdge()
        val alarmId = intent.getIntExtra(AlarmRingService.EXTRA_ID, 0)
        val noteId = intent.getIntExtra(AlarmRingService.EXTRA_NOTE, 0)
        val title = intent.getStringExtra(AlarmRingService.EXTRA_TITLE) ?: getString(R.string.alarm_title)
        setContent {
            KanadeTheme(THEME_DARK, false) {
                // If the alarm was stopped some other way (the notification), close this screen.
                LaunchedEffect(Unit) {
                    delay(1500)
                    while (true) {
                        if (AlarmRingService.active == 0) finish()
                        delay(500)
                    }
                }
                AlarmRingScreen(
                    editing = false,
                    title = title,
                    showSnooze = alarmId > 0,
                    onDismiss = {
                        ClockEngine.dismiss(this, noteId)
                        finish()
                    },
                    onSnooze = {
                        ClockEngine.snooze(this, alarmId)
                        finish()
                    },
                    onDone = {},
                )
            }
        }
    }
}

private val RING_BGS = listOf(
    "s1" to R.drawable.alarm_bg_1,
    "s2" to R.drawable.alarm_bg_2,
    "s3" to R.drawable.alarm_bg_3,
    "s4" to R.drawable.alarm_bg_4,
)

private val DEFAULT_RING_BRUSH = Brush.verticalGradient(
    listOf(Color(0xFF12002E), Color(0xFF2A1A5E), Color(0xFF6B4A66), Color(0xFFA38B7C)),
)

internal fun customBgFile(c: Context) = File(c.filesDir, "alarm_bg.jpg")

/** Copies a picked picture into the app (small and upright), so the alarm can always show it. */
private fun saveCustomBackground(c: Context, uri: Uri): Boolean = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    val turn = try {
        c.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (e: Throwable) {
        0f
    }
    if (bmp != null && turn != 0f) {
        val m = Matrix().apply { postRotate(turn) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    if (bmp == null) {
        false
    } else {
        customBgFile(c).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        ClockStore.setRingBgVersion(c, System.currentTimeMillis())
        true
    }
} catch (e: Throwable) {
    Diag.error(c, "customBackground", e)
    false
}

/**
 * The alarm screen. With [editing] on it is the editor (background, clock style, stop button,
 * snooze minutes) and everything is saved as you change it. Otherwise it is the real ring screen.
 */
@Composable
fun AlarmRingScreen(
    editing: Boolean,
    title: String,
    showSnooze: Boolean,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    var bg by remember { mutableStateOf(ClockStore.ringBg(ctx)) }
    var bgVer by remember { mutableLongStateOf(ClockStore.ringBgVersion(ctx)) }
    var clockStyle by remember { mutableIntStateOf(ClockStore.ringClock(ctx)) }
    var dismissMode by remember { mutableIntStateOf(ClockStore.ringDismiss(ctx)) }
    var snooze by remember { mutableIntStateOf(ClockStore.ringSnooze(ctx)) }
    var sheet by remember { mutableIntStateOf(0) } // 0 closed, 1 clock styles, 2 backgrounds
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && saveCustomBackground(ctx, uri)) {
            bg = "custom"
            bgVer = ClockStore.ringBgVersion(ctx)
            ClockStore.setRingBg(ctx, "custom")
        }
    }

    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val hour24 = cal.get(Calendar.HOUR_OF_DAY)
    val hourText = if (DateFormat.is24HourFormat(ctx)) {
        "%02d".format(hour24)
    } else {
        (if (hour24 % 12 == 0) 12 else hour24 % 12).toString()
    }
    val minText = "%02d".format(cal.get(Calendar.MINUTE))
    val dateText = SimpleDateFormat("EEE, MMMM d", Locale.getDefault()).format(Date(now))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RingBackground(bg, bgVer, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x66000000), Color(0x00000000), Color(0x00000000), Color(0x80000000)),
                ),
            ),
        )

        RingClock(
            style = clockStyle,
            hourText = hourText,
            minText = minText,
            dateText = dateText,
            label = title,
            editing = editing,
            onClick = { sheet = 1 },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp),
        )

        if (editing) {
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                GlassPill(stringResource(R.string.ring_background)) { sheet = 2 }
                GlassPill(stringResource(R.string.ring_done)) { onDone() }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(stringResource(R.string.dismiss_drag), dismissMode == DISMISS_DRAG) {
                        dismissMode = DISMISS_DRAG
                        ClockStore.setRingDismiss(ctx, DISMISS_DRAG)
                    }
                    GlassChip(stringResource(R.string.dismiss_tap), dismissMode == DISMISS_TAP) {
                        dismissMode = DISMISS_TAP
                        ClockStore.setRingDismiss(ctx, DISMISS_TAP)
                    }
                    GlassChip(stringResource(R.string.dismiss_hold), dismissMode == DISMISS_HOLD) {
                        dismissMode = DISMISS_HOLD
                        ClockStore.setRingDismiss(ctx, DISMISS_HOLD)
                    }
                }
            } else {
                Text(
                    stringResource(
                        when (dismissMode) {
                            DISMISS_TAP -> R.string.ring_hint_tap
                            DISMISS_HOLD -> R.string.ring_hint_hold
                            else -> R.string.ring_hint_drag
                        },
                    ),
                    color = Color(0xCCFFFFFF),
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            DismissButton(dismissMode, enabled = !editing, onDismiss = onDismiss)
            Spacer(Modifier.height(40.dp))
            if (showSnooze || editing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editing) {
                        SmallRound("\u2212") {
                            snooze = (snooze - 1).coerceAtLeast(1)
                            ClockStore.setRingSnooze(ctx, snooze)
                        }
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x66402018))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
                            .clickable(enabled = !editing) { onSnooze() }
                            .padding(horizontal = 36.dp, vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.ring_snooze_mins, snooze),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (editing) {
                        SmallRound("+") {
                            snooze = (snooze + 1).coerceAtMost(30)
                            ClockStore.setRingSnooze(ctx, snooze)
                        }
                    }
                }
            }
        }

        if (editing && sheet != 0) {
            Box(
                Modifier.fillMaxSize().clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { sheet = 0 },
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(Color(0xFF262626))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                if (sheet == 1) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (style in 0..2) {
                            ClockThumb(style, clockStyle == style, hourText, minText) {
                                clockStyle = style
                                ClockStore.setRingClock(ctx, style)
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val ids = listOf("default") + RING_BGS.map { it.first } +
                                (if (customBgFile(ctx).exists()) listOf("custom") else emptyList())
                            ids.forEach { id ->
                                BgThumb(id, bgVer, bg == id) {
                                    bg = id
                                    ClockStore.setRingBg(ctx, id)
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3A3A3A))
                                .clickable { pickImage.launch("image/*") },
                            contentAlignment = Alignment.Center,
                        ) {
                            PathIcon(Glyph.EDIT.pathData, Color.White, 22.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RingBackground(bg: String, version: Long, modifier: Modifier) {
    val ctx = LocalContext.current
    val res = RING_BGS.firstOrNull { it.first == bg }?.second
    if (res != null) {
        Image(painterResource(res), null, modifier, contentScale = ContentScale.Crop)
    } else if (bg == "custom") {
        val bmp = remember(version) {
            runCatching { BitmapFactory.decodeFile(customBgFile(ctx).path)?.asImageBitmap() }.getOrNull()
        }
        if (bmp != null) {
            Image(bmp, null, modifier, contentScale = ContentScale.Crop)
        } else {
            Box(modifier.background(DEFAULT_RING_BRUSH))
        }
    } else {
        Box(modifier.background(DEFAULT_RING_BRUSH))
    }
}

/** The big clock in one of three looks. In the editor it gets an outline and opens the style list. */
@Composable
private fun RingClock(
    style: Int,
    hourText: String,
    minText: String,
    dateText: String,
    label: String,
    editing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    var box = modifier.clip(shape)
    if (editing) {
        box = box.border(2.dp, Color(0x66FFFFFF), shape).clickable { onClick() }
    }
    val align = if (style == 1) Alignment.Start else Alignment.CenterHorizontally
    Column(
        box.padding(horizontal = 30.dp, vertical = 14.dp),
        horizontalAlignment = align,
    ) {
        when (style) {
            0 -> Text("$hourText:$minText", color = Color.White, fontSize = 76.sp, fontWeight = FontWeight.SemiBold)
            1 -> Text("$hourText:$minText", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
            else -> {
                Text(hourText, color = Color.White, fontSize = 124.sp, lineHeight = 128.sp, fontWeight = FontWeight.Medium)
                Text(minText, color = Color.White, fontSize = 124.sp, lineHeight = 128.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(dateText, color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 18.sp)
    }
}

/** The round stop button. Swipe it up, tap it, or hold it, depending on the chosen way. */
@Composable
private fun DismissButton(mode: Int, enabled: Boolean, onDismiss: () -> Unit) {
    val dismissNow by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var hold by remember { mutableFloatStateOf(0f) }
    val limit = with(LocalDensity.current) { 150.dp.toPx() }

    val gesture = if (!enabled) {
        Modifier
    } else {
        when (mode) {
            DISMISS_TAP -> Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { dismissNow() })
            }
            DISMISS_HOLD -> Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val t0 = System.currentTimeMillis()
                        val job = scope.launch {
                            while (true) {
                                val p = ((System.currentTimeMillis() - t0) / 1200f).coerceAtMost(1f)
                                hold = p
                                if (p >= 1f) {
                                    dismissNow()
                                    break
                                }
                                delay(16)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                        hold = 0f
                    },
                )
            }
            else -> Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        if (kotlin.math.hypot(dragX, dragY) > limit * 0.8f) {
                            dismissNow()
                        } else {
                            dragX = 0f
                            dragY = 0f
                        }
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    var nx = dragX + amount.x
                    var ny = dragY + amount.y
                    val d = kotlin.math.hypot(nx, ny)
                    if (d > limit) {
                        nx = nx / d * limit
                        ny = ny / d * limit
                    }
                    dragX = nx
                    dragY = ny
                }
            }
        }
    }

    Box(
        Modifier
            .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
            .size(82.dp)
            .clip(CircleShape)
            .background(Color(0x59FFFFFF))
            .border(1.dp, Color(0x99FFFFFF), CircleShape)
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        if (hold > 0f) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * hold,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        PathIcon(Glyph.CLOSE.pathData, Color.White, 34.dp)
    }
}

@Composable
private fun GlassPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x66000000))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xCCFFFFFF) else Color(0x55000000))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color(0xFF1B1B1F) else Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun SmallRound(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0x66402018)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 24.sp)
    }
}

/** One of the three clock looks, drawn small. */
@Composable
private fun ClockThumb(style: Int, selected: Boolean, hourText: String, minText: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .size(96.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1C0B3A), Color(0xFF5E3A4D))))
            .border(if (selected) 3.dp else 0.dp, if (selected) Color(0xFF8AB4F8) else Color.Transparent, shape)
            .clickable { onClick() },
        contentAlignment = if (style == 1) Alignment.CenterStart else Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = if (style == 1) 10.dp else 0.dp),
            horizontalAlignment = if (style == 1) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            when (style) {
                0 -> Text("$hourText:$minText", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                1 -> Text("$hourText:$minText", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                else -> {
                    Text(hourText, color = Color.White, fontSize = 28.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                    Text(minText, color = Color.White, fontSize = 28.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Alarm", color = Color.White, fontSize = 8.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BgThumb(id: String, version: Long, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .width(72.dp)
            .height(104.dp)
            .clip(shape)
            .border(if (selected) 3.dp else 0.dp, if (selected) Color(0xFF8AB4F8) else Color.Transparent, shape)
            .clickable { onClick() },
    ) {
        RingBackground(id, version, Modifier.fillMaxSize())
    }
}
