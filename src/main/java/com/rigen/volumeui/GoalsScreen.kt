package com.rigen.volumeui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private val GOAL_EMOJIS = listOf("🎯", "🏠", "🚗", "✈️", "💻", "📱", "🎓", "💍", "🎁", "🏖️", "🛍️", "💰")
private const val HISTORY_PAGE = 30
private const val MAX_BACKUP_FILE = 25 * 1024 * 1024

/** Weekdays in Calendar's numbering (Sunday is 1), with their short names. */
private val WEEK_DAYS = listOf(
    Calendar.SUNDAY to R.string.day_sun,
    Calendar.MONDAY to R.string.day_mon,
    Calendar.TUESDAY to R.string.day_tue,
    Calendar.WEDNESDAY to R.string.day_wed,
    Calendar.THURSDAY to R.string.day_thu,
    Calendar.FRIDAY to R.string.day_fri,
    Calendar.SATURDAY to R.string.day_sat,
)

/** "50 100 200" (spaces, slashes or semicolons between) into amounts; empty is fine; null when something is not an amount. */
private fun parseQuick(text: String): List<Long>? {
    val parts = text.split(Regex("[\\s/;\u061B]+")).filter { it.isNotBlank() }
    if (parts.size > 3) return null
    val out = ArrayList<Long>()
    for (p in parts) {
        val v = parseMoney(p) ?: return null
        if (v <= 0L) return null
        out.add(v)
    }
    return out
}

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

private fun fmtDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun fmtTime(ctx: Context, minutes: Int): String {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, minutes / 60)
    c.set(Calendar.MINUTE, minutes % 60)
    return android.text.format.DateFormat.getTimeFormat(ctx).format(c.time)
}

private fun pickDate(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val host = ctx.activityOrNull() ?: return
    val cal = Calendar.getInstance()
    if (initial > 0) cal.timeInMillis = initial
    val dialog = DatePickerDialog(
        host,
        { _, y, m, d ->
            val c = Calendar.getInstance()
            c.clear()
            c.set(y, m, d, 0, 0, 0)
            onPicked(c.timeInMillis)
        },
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
    )
    dialog.datePicker.minDate = GoalsEngine.startOfDay(System.currentTimeMillis())
    dialog.show()
}

private fun pickTime(ctx: Context, minutes: Int, onPicked: (Int) -> Unit) {
    val host = ctx.activityOrNull() ?: return
    TimePickerDialog(
        host,
        { _, h, m -> onPicked(h * 60 + m) },
        minutes / 60,
        minutes % 60,
        android.text.format.DateFormat.is24HourFormat(host),
    ).show()
}

private fun openLink(ctx: Context, link: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(ctx, R.string.goals_err_link, Toast.LENGTH_SHORT).show()
    }
}

/** Reads a picked file, giving up (null) when it is bigger than [max] bytes. */
private fun readCapped(ctx: Context, uri: Uri, max: Int): ByteArray? = try {
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        var tooBig = false
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) {
                tooBig = true
                break
            }
            out.write(buf, 0, n)
        }
        if (tooBig) null else out.toByteArray()
    }
} catch (_: Exception) {
    null
}

private fun writeTo(ctx: Context, uri: Uri, bytes: ByteArray, okMessage: Int) {
    try {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        Toast.makeText(ctx, okMessage, Toast.LENGTH_LONG).show()
    } catch (_: Exception) {
        Toast.makeText(ctx, R.string.goals_io_failed, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun statusText(p: GoalPlan): String = when (p.status) {
    GoalStatus.NEW -> stringResource(R.string.goals_st_new)
    GoalStatus.DONE -> stringResource(R.string.goals_st_done)
    GoalStatus.AHEAD -> stringResource(R.string.goals_st_ahead)
    GoalStatus.ON_TRACK -> stringResource(R.string.goals_st_on)
    GoalStatus.BEHIND -> if ((p.daysLeft ?: 0) < 0) stringResource(R.string.goals_st_overdue) else stringResource(R.string.goals_st_behind)
    GoalStatus.NONE -> stringResource(R.string.goals_st_none)
}

@Composable
private fun statusColor(s: GoalStatus): Color = when (s) {
    GoalStatus.DONE, GoalStatus.AHEAD -> ICON_GREEN
    GoalStatus.ON_TRACK -> MaterialTheme.colorScheme.primary
    GoalStatus.BEHIND -> ICON_ORANGE
    GoalStatus.NEW, GoalStatus.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun reminderLine(ctx: Context, g: Goal): String? {
    if (g.remind !in 1..3) return null
    val time = fmtTime(ctx, g.remindMinutes)
    return when (g.remind) {
        1 -> stringResource(R.string.goals_rem_line_daily, time)
        2 -> {
            val dayName = stringResource(WEEK_DAYS.firstOrNull { it.first == g.remindDay }?.second ?: R.string.day_sun)
            stringResource(R.string.goals_rem_line_weekly, dayName, time)
        }
        else -> stringResource(R.string.goals_rem_line_monthly, g.remindDay.coerceIn(1, 28), time)
    }
}

/** The goal's photo in a circle, or its emoji when it has none. */
@Composable
private fun GoalThumb(g: Goal, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, g.id, g.photoAt) {
        value = if (g.photoAt > 0) withContext(Dispatchers.IO) { GoalsPhotos.load(ctx, g.id, false) } else null
    }
    val b = bmp
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (b != null) {
            Image(bitmap = b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(g.emoji, fontSize = (size.value * 0.55f).sp)
        }
    }
}

// ───────────────────────── Page and lock ─────────────────────────

@Composable
fun GoalsPage(onBack: () -> Unit) {
    MoneyPage(onBack) { lockOn, setLock -> GoalsMain(onBack, lockOn, setLock) }
}

/**
 * The lock (if the person turned it on) in front of a page that shows money. The goals page and the
 * budget page both stand behind it, and one unlock opens both.
 */
@Composable
internal fun MoneyPage(onBack: () -> Unit, content: @Composable (lockOn: Boolean, setLock: (Boolean) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var locked by remember { mutableStateOf(GoalsLock.enabled(ctx) && !GoalsLock.unlocked) }
    var lockOn by remember { mutableStateOf(GoalsLock.enabled(ctx)) }

    // The phone lost its fingerprint and screen lock: nothing can check the person any more.
    LaunchedEffect(Unit) {
        if (GoalsLock.enabled(ctx) && !GoalsLock.available(ctx)) {
            GoalsLock.setEnabled(ctx, false)
            lockOn = false
            locked = false
        }
    }

    // Leaving the app locks the page again; leaving the page does too.
    DisposableEffect(Unit) {
        val lifecycle = (ctx.activityOrNull() as? ComponentActivity)?.lifecycle
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !GoalsLock.suppress && GoalsLock.enabled(ctx)) {
                GoalsLock.unlocked = false
                locked = true
            }
        }
        lifecycle?.addObserver(watcher)
        onDispose {
            lifecycle?.removeObserver(watcher)
            GoalsLock.unlocked = false
        }
    }

    // With the lock on, the app switcher and screenshots do not show the amounts.
    DisposableEffect(lockOn) {
        val window = ctx.activityOrNull()?.window
        if (lockOn) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (lockOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    if (locked) {
        GoalsGate(onBack) { locked = false }
    } else {
        content(lockOn) { lockOn = it }
    }
}

@Composable
private fun GoalsGate(onBack: () -> Unit, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val title = stringResource(R.string.goals_lock_prompt)
    val cancel = stringResource(R.string.goals_cancel)

    fun ask() {
        failed = false
        GoalsLock.authenticate(
            ctx, title, cancel,
            ok = {
                GoalsLock.unlocked = true
                onUnlocked()
            },
            failed = { failed = true },
        )
    }

    LaunchedEffect(Unit) { ask() }

    PageScaffold(stringResource(R.string.goals_title), onBack) { padding ->
        PageColumn(padding) {
            Section(stringResource(R.string.goals_locked)) {
                Text(
                    stringResource(if (failed) R.string.goals_lock_failed else R.string.goals_lock_msg),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { ask() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.goals_unlock))
                }
            }
        }
    }
}

@Composable
private fun GoalsMain(onBack: () -> Unit, lockOn: Boolean, onLockChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val tick = GoalsStore.tick
    val goals = remember(tick) { GoalsStore.load(ctx) }
    val now = remember(tick) { System.currentTimeMillis() }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorGoal by remember { mutableStateOf<Goal?>(null) }
    var celebrate by remember { mutableStateOf<Goal?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    val active = goals.filter { !it.archived }
    val archived = goals.filter { it.archived }
    val open = goals.firstOrNull { it.id == openId }

    BackHandler(enabled = open != null) { openId = null }

    // Saving plans that add money by themselves catch up with whatever came due while the app was closed.
    LaunchedEffect(Unit) { GoalsAuto.catchUp(ctx) }

    PageScaffold(
        title = open?.name ?: stringResource(R.string.goals_title),
        onBack = if (open != null) ({ openId = null }) else onBack,
    ) { padding ->
        PageColumn(padding) {
            if (open == null) {
                Button(
                    onClick = { editorGoal = null; showEditor = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.goals_add)) }

                if (goals.isEmpty()) {
                    Section(stringResource(R.string.goals_title)) {
                        Text(stringResource(R.string.goals_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (active.size >= 2) {
                    Section(stringResource(R.string.goals_total)) {
                        for ((_, list) in active.groupBy { it.currency.trim().uppercase() }) {
                            val cur = list.first().currency
                            Text(
                                stringResource(
                                    R.string.goals_saved_of,
                                    formatMoney(list.sumOf { it.saved }, cur),
                                    formatMoney(list.sumOf { it.target }, cur),
                                ),
                            )
                        }
                    }
                }

                for (g in active) {
                    GoalCard(g, GoalsEngine.plan(g, now)) { openId = g.id }
                }

                if (archived.isNotEmpty()) {
                    Section(stringResource(R.string.goals_archived, archived.size)) {
                        if (!showArchived) {
                            TextButton(onClick = { showArchived = true }) { Text(stringResource(R.string.goals_show_more)) }
                        } else {
                            for (g in archived) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { openId = g.id },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GoalThumb(g, 36.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(g.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        formatMoney(g.saved, g.currency),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                GoalsSettings(lockOn, onLockChange)
            } else {
                GoalDetail(
                    g = open,
                    plan = GoalsEngine.plan(open, now),
                    active = active,
                    onEdit = { editorGoal = open; showEditor = true },
                    onClose = { openId = null },
                    onCelebrate = { celebrate = it },
                )
            }
        }
    }

    if (showEditor) {
        GoalEditDialog(
            initial = editorGoal,
            onDismiss = { showEditor = false },
            onSave = {
                GoalsStore.upsert(ctx, it)
                showEditor = false
            },
        )
    }

    celebrate?.let { g ->
        AlertDialog(
            onDismissRequest = { celebrate = null },
            title = { Text(stringResource(R.string.goals_celebrate_title)) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(g.emoji, fontSize = 56.sp)
                    Text(stringResource(R.string.goals_celebrate_msg, g.name))
                }
            },
            confirmButton = { TextButton(onClick = { celebrate = null }) { Text(stringResource(R.string.goals_ok)) } },
        )
    }
}

@Composable
private fun GoalCard(g: Goal, plan: GoalPlan, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalThumb(g, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusText(plan), style = MaterialTheme.typography.bodySmall, color = statusColor(plan.status))
                }
                Text("${(plan.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(progress = { plan.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.goals_saved_of, formatMoney(plan.saved, g.currency), formatMoney(g.target, g.currency)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ───────────────────────── Backup, export and lock settings ─────────────────────────

@Composable
private fun GoalsSettings(lockOn: Boolean, onLockChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var askExport by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var working by remember { mutableStateOf(false) }
    val lockAvailable = remember { GoalsLock.available(ctx) }
    val lockTitle = stringResource(R.string.goals_lock_prompt)
    val cancel = stringResource(R.string.goals_cancel)

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        GoalsLock.suppress = false
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) writeTo(ctx, uri, bytes, R.string.goals_exported)
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        GoalsLock.suppress = false
        if (uri != null) writeTo(ctx, uri, GoalsStore.exportCsv(ctx), R.string.goals_csv_exported)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        GoalsLock.suppress = false
        if (uri != null) {
            val bytes = readCapped(ctx, uri, MAX_BACKUP_FILE)
            if (bytes == null) Toast.makeText(ctx, R.string.goals_io_failed, Toast.LENGTH_LONG).show() else importBytes = bytes
        }
    }

    Section(stringResource(R.string.goals_backup)) {
        if (lockAvailable || lockOn) {
            SwitchRow(stringResource(R.string.goals_lock_switch), lockOn) { want ->
                if (want) {
                    GoalsLock.authenticate(
                        ctx, lockTitle, cancel,
                        ok = {
                            GoalsLock.setEnabled(ctx, true)
                            onLockChange(true)
                        },
                        failed = { },
                    )
                } else {
                    GoalsLock.setEnabled(ctx, false)
                    onLockChange(false)
                }
            }
            Text(
                stringResource(R.string.goals_lock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { askExport = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.goals_export))
        }
        OutlinedButton(
            onClick = {
                GoalsLock.suppress = true
                importPicker.launch(arrayOf("*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.goals_import)) }
        OutlinedButton(
            onClick = {
                GoalsLock.suppress = true
                csvPicker.launch("kanagoals.csv")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.goals_export_csv)) }
    }

    if (askExport) {
        BackupPasswordDialog(
            confirm = true,
            working = working,
            onDismiss = { askExport = false },
            onOk = { pw ->
                working = true
                scope.launch {
                    val bytes = withContext(Dispatchers.Default) {
                        try {
                            GoalsBackup.encrypt(pw, GoalsStore.exportBytes(ctx))
                        } finally {
                            pw.fill('\u0000')
                        }
                    }
                    working = false
                    askExport = false
                    pendingExport = bytes
                    GoalsLock.suppress = true
                    exportPicker.launch("kanagoals-backup.kgoals")
                }
            },
        )
    }

    val toImport = importBytes
    if (toImport != null) {
        BackupPasswordDialog(
            confirm = false,
            working = working,
            onDismiss = { importBytes = null },
            onOk = { pw ->
                working = true
                scope.launch {
                    val plain = withContext(Dispatchers.Default) {
                        try {
                            GoalsBackup.decrypt(pw, toImport)
                        } finally {
                            pw.fill('\u0000')
                        }
                    }
                    val result = if (plain == null) -1 else GoalsStore.importBytes(ctx, plain)
                    working = false
                    importBytes = null
                    Toast.makeText(
                        ctx,
                        when {
                            result < 0 -> ctx.getString(R.string.goals_import_wrong)
                            result == 0 -> ctx.getString(R.string.goals_import_nothing)
                            else -> ctx.getString(R.string.goals_imported, result)
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
}

@Composable
private fun BackupPasswordDialog(confirm: Boolean, working: Boolean, onDismiss: () -> Unit, onOk: (CharArray) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val tooShort = confirm && pw.length < 8
    val mismatch = confirm && pw != pw2

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(if (confirm) R.string.goals_export else R.string.goals_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecretField(stringResource(R.string.goals_bk_password), pw) { pw = it }
                if (confirm) {
                    SecretField(stringResource(R.string.goals_bk_confirm), pw2) { pw2 = it }
                    Text(
                        stringResource(R.string.goals_bk_min),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tried && tooShort) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tried && mismatch) {
                        Text(stringResource(R.string.goals_bk_mismatch), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (working) Text(stringResource(R.string.goals_bk_working))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    tried = true
                    if (pw.isNotEmpty() && !tooShort && !mismatch) onOk(pw.toCharArray())
                },
            ) { Text(stringResource(R.string.goals_continue)) }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(R.string.goals_cancel)) }
        },
    )
}

// ───────────────────────── Detail ─────────────────────────

@Composable
private fun GoalDetail(
    g: Goal,
    plan: GoalPlan,
    active: List<Goal>,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onCelebrate: (Goal) -> Unit,
) {
    val ctx = LocalContext.current
    var amountMode by remember { mutableStateOf(0) } // 0 closed, 1 deposit, 2 withdraw
    var showTransfer by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(HISTORY_PAGE) }
    val targets = active.filter { it.id != g.id && GoalsStore.sameCurrency(it, g) }
    val photo by produceState<Bitmap?>(null, g.id, g.photoAt) {
        value = if (g.photoAt > 0) withContext(Dispatchers.IO) { GoalsPhotos.load(ctx, g.id, false) } else null
    }
    val pb = photo

    if (pb != null) {
        Image(
            bitmap = pb.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(28.dp)),
            contentScale = ContentScale.Crop,
        )
    }

    Section("${g.emoji}  ${g.name}") {
        Text(statusText(plan), color = statusColor(plan.status), style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(progress = { plan.progress }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.goals_saved_of, formatMoney(plan.saved, g.currency), formatMoney(g.target, g.currency)) +
                "  (${(plan.progress * 100).toInt()}%)",
        )
        if (plan.remaining > 0) {
            Text(
                stringResource(R.string.goals_remaining, formatMoney(plan.remaining, g.currency)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!g.archived) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { amountMode = 1 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.goals_deposit)) }
                OutlinedButton(onClick = { amountMode = 2 }, enabled = plan.saved > 0, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.goals_withdraw))
                }
            }
            if (plan.saved > 0 && targets.isNotEmpty()) {
                OutlinedButton(onClick = { showTransfer = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.goals_transfer))
                }
            }
        }
    }

    if (plan.status != GoalStatus.DONE && !g.archived) {
        Section(stringResource(R.string.goals_plan)) {
            val daysLeft = plan.daysLeft
            if (g.deadline > 0 && daysLeft != null) {
                val rel = when {
                    daysLeft > 0 -> stringResource(R.string.goals_days_left, daysLeft)
                    daysLeft == 0 -> stringResource(R.string.goals_due_today)
                    else -> stringResource(R.string.goals_days_ago, -daysLeft)
                }
                Text(stringResource(R.string.goals_deadline_line, fmtDate(g.deadline), rel))
            }
            val need = plan.need
            if (need != null) {
                val m = formatMoney(need, g.currency)
                Text(
                    when (plan.needUnit) {
                        NeedUnit.DAY -> stringResource(R.string.goals_need_day, m)
                        NeedUnit.WEEK -> stringResource(R.string.goals_need_week, m)
                        NeedUnit.MONTH -> stringResource(R.string.goals_need_month, m)
                    },
                )
            }
            val eta = plan.projectedFinish
            Text(
                if (eta != null) stringResource(R.string.goals_eta, fmtDate(eta)) else stringResource(R.string.goals_eta_unknown),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rem = reminderLine(ctx, g)
            if (rem != null) Text(rem, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (g.plan > 0L) {
                Text(
                    stringResource(if (g.autoAdd) R.string.goals_plan_line_auto else R.string.goals_plan_line, formatMoney(g.plan, g.currency)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (!g.archived && active.size > 1) {
        val pos = active.indexOfFirst { it.id == g.id }
        Section(stringResource(R.string.goals_priority, pos + 1, active.size)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { GoalsStore.move(ctx, g.id, -1) }, enabled = pos > 0, modifier = Modifier.weight(1f)) {
                    Text("▲ " + stringResource(R.string.goals_prio_up))
                }
                OutlinedButton(onClick = { GoalsStore.move(ctx, g.id, 1) }, enabled = pos in 0 until active.size - 1, modifier = Modifier.weight(1f)) {
                    Text("▼ " + stringResource(R.string.goals_prio_down))
                }
            }
        }
    }

    Section(stringResource(R.string.goals_history)) {
        val sorted = remember(g.entries) { g.entries.sortedByDescending { it.at } }
        if (sorted.isEmpty()) {
            Text(stringResource(R.string.goals_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (e in sorted.take(shown)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (e.amount > 0) "+" else "") + formatMoney(e.amount, g.currency),
                        color = if (e.amount > 0) ICON_GREEN else ICON_RED,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (e.note.isBlank()) fmtDate(e.at) else "${fmtDate(e.at)} · ${e.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    GoalsStore.deleteEntry(ctx, g.id, e.id)
                    Undo.show(ctx.getString(R.string.goals_entry_deleted)) { GoalsStore.restoreEntry(ctx, g.id, e) }
                }) { Text(stringResource(R.string.goals_delete)) }
            }
        }
        if (sorted.size > shown) {
            TextButton(onClick = { shown += HISTORY_PAGE }) { Text(stringResource(R.string.goals_show_more)) }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (!g.archived) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.goals_edit)) }
        }
        if (g.link.isNotBlank()) {
            OutlinedButton(onClick = { openLink(ctx, g.link) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.goals_open_link))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                val archive = !g.archived
                GoalsStore.setArchived(ctx, g.id, archive)
                if (archive) onClose()
            },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(if (g.archived) R.string.goals_restore else R.string.goals_archive)) }
        TextButton(onClick = {
            val removed = GoalsStore.remove(ctx, g.id)
            onClose()
            if (removed != null) {
                // The photo goes only once the time to undo is over.
                Undo.show(ctx.getString(R.string.goals_deleted), onExpire = { GoalsPhotos.delete(ctx, removed.first.id) }) {
                    GoalsStore.restore(ctx, removed.first, removed.second)
                }
            }
        }) {
            Text(stringResource(R.string.goals_delete), color = ICON_RED)
        }
    }

    if (amountMode != 0) {
        val deposit = amountMode == 1
        AmountDialog(
            title = stringResource(if (deposit) R.string.goals_deposit else R.string.goals_withdraw),
            max = if (deposit) null else plan.saved,
            onDismiss = { amountMode = 0 },
            onConfirm = { amount, note ->
                val reached = GoalsStore.addEntry(ctx, g.id, if (deposit) amount else -amount, note)
                amountMode = 0
                if (reached) onCelebrate(g)
            },
        )
    }
    if (showTransfer) {
        TransferDialog(
            from = g,
            targets = targets,
            onDismiss = { showTransfer = false },
            onConfirm = { to, amount ->
                val reached = GoalsStore.transfer(ctx, g.id, to.id, amount)
                showTransfer = false
                if (reached) onCelebrate(to)
            },
        )
    }
}

// ───────────────────────── Dialogs ─────────────────────────

@Composable
private fun AmountDialog(title: String, max: Long?, onDismiss: () -> Unit, onConfirm: (Long, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val parsed = parseMoney(amount)
    val invalid = parsed == null || parsed <= 0L
    val tooBig = max != null && parsed != null && parsed > max

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (it.length <= 20) amount = it },
                    label = { Text(stringResource(R.string.goals_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = tooBig || (tried && invalid),
                    supportingText = {
                        if (tooBig) Text(stringResource(R.string.goals_err_max))
                        else if (tried && invalid) Text(stringResource(R.string.goals_err_amount))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 120) note = it },
                    label = { Text(stringResource(R.string.goals_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                val p = parsed
                if (p != null && p > 0L && !tooBig) onConfirm(p, note)
            }) { Text(stringResource(R.string.goals_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.goals_cancel)) } },
    )
}

@Composable
private fun TransferDialog(from: Goal, targets: List<Goal>, onDismiss: () -> Unit, onConfirm: (Goal, Long) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    var amount by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val parsed = parseMoney(amount)
    val invalid = parsed == null || parsed <= 0L
    val tooBig = parsed != null && parsed > from.saved

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goals_transfer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.goals_transfer_to), style = MaterialTheme.typography.titleSmall)
                targets.forEachIndexed { i, t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == i, onClick = { selected = i })
                        Text("${t.emoji} ${t.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (it.length <= 20) amount = it },
                    label = { Text(stringResource(R.string.goals_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = tooBig || (tried && invalid),
                    supportingText = {
                        if (tooBig) Text(stringResource(R.string.goals_err_max))
                        else if (tried && invalid) Text(stringResource(R.string.goals_err_amount))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                val p = parsed
                val to = targets.getOrNull(selected)
                if (p != null && p > 0L && !tooBig && to != null) onConfirm(to, p)
            }) { Text(stringResource(R.string.goals_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.goals_cancel)) } },
    )
}

@Composable
private fun GoalEditDialog(initial: Goal?, onDismiss: () -> Unit, onSave: (Goal) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultCurrency = stringResource(R.string.goals_currency_default)
    val goalId = remember { initial?.id ?: GoalsStore.newId() }
    var emoji by remember { mutableStateOf(initial?.emoji ?: GOAL_EMOJIS[0]) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var target by remember { mutableStateOf(initial?.let { editableMoney(it.target) } ?: "") }
    var currency by remember { mutableStateOf(initial?.currency ?: defaultCurrency) }
    var deadline by remember { mutableLongStateOf(initial?.deadline ?: 0L) }
    var link by remember { mutableStateOf(initial?.link ?: "") }
    var remind by remember { mutableIntStateOf(initial?.remind ?: 0) }
    var remindMinutes by remember { mutableIntStateOf(initial?.remindMinutes ?: (20 * 60)) }
    var weekDay by remember {
        mutableIntStateOf(if (initial != null && initial.remind == 2) initial.remindDay.coerceIn(1, 7) else Calendar.getInstance().get(Calendar.DAY_OF_WEEK))
    }
    var domText by remember {
        mutableStateOf(if (initial != null && initial.remind == 3 && initial.remindDay in 1..28) initial.remindDay.toString() else "1")
    }
    var planText by remember {
        mutableStateOf(if (initial != null && initial.plan > 0L) editableMoney(initial.plan) else "")
    }
    var autoAdd by remember { mutableStateOf(initial?.autoAdd ?: false) }
    var quickText by remember {
        mutableStateOf(initial?.quick?.joinToString(" ") { editableMoney(it) } ?: "")
    }
    var pendingPhoto by remember { mutableStateOf(false) }
    var removedPhoto by remember { mutableStateOf(false) }
    var photoTick by remember { mutableIntStateOf(0) }
    var tried by remember { mutableStateOf(false) }

    // A picked photo waits in a temporary file; leaving the dialog any way clears what is left of it.
    DisposableEffect(Unit) { onDispose { GoalsPhotos.discardTemp(ctx, goalId) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        GoalsLock.suppress = false
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { GoalsPhotos.stage(ctx, goalId, uri) }
                if (ok) {
                    pendingPhoto = true
                    removedPhoto = false
                    photoTick++
                } else {
                    Toast.makeText(ctx, R.string.goals_photo_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val preview by produceState<Bitmap?>(null, photoTick, pendingPhoto, removedPhoto) {
        value = withContext(Dispatchers.IO) {
            when {
                pendingPhoto -> GoalsPhotos.load(ctx, goalId, true)
                removedPhoto -> null
                initial != null && initial.photoAt > 0 -> GoalsPhotos.load(ctx, goalId, false)
                else -> null
            }
        }
    }
    val pv = preview

    val targetMinor = parseMoney(target)
    val nameOk = name.trim().isNotEmpty()
    val targetOk = targetMinor != null && targetMinor > 0L
    val cleanedLink = cleanLink(link)
    val dom = domText.toIntOrNull()
    val domOk = remind != 3 || (dom != null && dom in 1..28)
    val planMinor = if (planText.isBlank()) 0L else parseMoney(planText)
    val planOk = remind == 0 || planMinor != null
    val quickParsed = parseQuick(quickText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.goals_new_title else R.string.goals_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pv != null) {
                    Image(
                        bitmap = pv.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        GoalsLock.suppress = true
                        picker.launch("image/*")
                    }) { Text(stringResource(if (pv != null) R.string.goals_photo_change else R.string.goals_photo_add)) }
                    if (pv != null) {
                        TextButton(onClick = {
                            removedPhoto = true
                            pendingPhoto = false
                            GoalsPhotos.discardTemp(ctx, goalId)
                            photoTick++
                        }) { Text(stringResource(R.string.goals_photo_remove)) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (e in GOAL_EMOJIS) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (e == emoji) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 60) name = it },
                    label = { Text(stringResource(R.string.goals_name)) },
                    singleLine = true,
                    isError = tried && !nameOk,
                    supportingText = { if (tried && !nameOk) Text(stringResource(R.string.goals_err_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { if (it.length <= 20) target = it },
                        label = { Text(stringResource(R.string.goals_target)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = tried && !targetOk,
                        supportingText = { if (tried && !targetOk) Text(stringResource(R.string.goals_err_amount)) },
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { if (it.length <= 8) currency = it },
                        label = { Text(stringResource(R.string.goals_currency)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(ctx, deadline) { deadline = it } }) {
                        Text(if (deadline > 0) fmtDate(deadline) else stringResource(R.string.goals_pick_date))
                    }
                    if (deadline > 0) {
                        TextButton(onClick = { deadline = 0L }) { Text(stringResource(R.string.goals_clear_date)) }
                    }
                }
                OutlinedTextField(
                    value = link,
                    onValueChange = { if (it.length <= 500) link = it },
                    label = { Text(stringResource(R.string.goals_link)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = cleanedLink == null,
                    supportingText = { if (cleanedLink == null) Text(stringResource(R.string.goals_err_link)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.goals_rem_title), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to R.string.goals_rem_off,
                        1 to R.string.goals_rem_daily,
                        2 to R.string.goals_rem_weekly,
                        3 to R.string.goals_rem_monthly,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = remind == value,
                            onClick = {
                                remind = value
                                if (value != 0 && Build.VERSION.SDK_INT >= 33 &&
                                    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                if (remind != 0) {
                    OutlinedButton(onClick = { pickTime(ctx, remindMinutes) { remindMinutes = it } }) {
                        Text(fmtTime(ctx, remindMinutes))
                    }
                }
                if (remind == 2) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((day, res) in WEEK_DAYS) {
                            FilterChip(selected = weekDay == day, onClick = { weekDay = day }, label = { Text(stringResource(res)) })
                        }
                    }
                }
                if (remind != 0) {
                    OutlinedTextField(
                        value = planText,
                        onValueChange = { if (it.length <= 20) planText = it },
                        label = { Text(stringResource(R.string.goals_plan_amount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = tried && !planOk,
                        supportingText = { if (tried && !planOk) Text(stringResource(R.string.goals_err_amount)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (planText.isNotBlank()) {
                        SwitchRow(stringResource(R.string.goals_plan_auto), autoAdd) { autoAdd = it }
                    }
                }
                if (remind == 3) {
                    OutlinedTextField(
                        value = domText,
                        onValueChange = { if (it.length <= 2) domText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.goals_rem_day_of_month)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = tried && !domOk,
                        supportingText = { if (tried && !domOk) Text(stringResource(R.string.goals_err_day)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = quickText,
                    onValueChange = { if (it.length <= 40) quickText = it },
                    label = { Text(stringResource(R.string.goals_quick_label)) },
                    singleLine = true,
                    isError = quickParsed == null,
                    supportingText = { if (quickParsed == null) Text(stringResource(R.string.goals_err_amount)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (nameOk && targetOk && targetMinor != null && cleanedLink != null && domOk && planOk && quickParsed != null) {
                    var photoAt = initial?.photoAt ?: 0L
                    if (pendingPhoto) {
                        GoalsPhotos.commit(ctx, goalId)
                        photoAt = System.currentTimeMillis()
                    } else if (removedPhoto) {
                        GoalsPhotos.deleteFinal(ctx, goalId)
                        photoAt = 0L
                    }
                    val plan = if (remind != 0 && (planMinor ?: 0L) > 0L) (planMinor ?: 0L) else 0L
                    val day = when (remind) {
                        2 -> weekDay
                        3 -> dom ?: 1
                        else -> 0
                    }
                    val auto = autoAdd && plan > 0L
                    // A changed plan or schedule starts from now, so it never back-fills the past.
                    val sameSchedule = initial != null && initial.plan == plan && initial.autoAdd == auto &&
                        initial.remind == remind && initial.remindMinutes == remindMinutes && initial.remindDay == day
                    val planSince = when {
                        plan <= 0L -> 0L
                        sameSchedule -> initial?.planSince ?: 0L
                        else -> System.currentTimeMillis()
                    }
                    onSave(
                        Goal(
                            id = goalId,
                            name = name.trim(),
                            emoji = emoji,
                            target = targetMinor,
                            currency = currency.trim(),
                            deadline = deadline,
                            link = cleanedLink,
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                            entries = initial?.entries ?: emptyList(),
                            archived = initial?.archived ?: false,
                            completedAt = initial?.completedAt ?: 0L,
                            photoAt = photoAt,
                            remind = remind,
                            remindMinutes = remindMinutes,
                            remindDay = day,
                            plan = plan,
                            autoAdd = auto,
                            planSince = planSince,
                            quick = quickParsed,
                        ),
                    )
                }
            }) { Text(stringResource(R.string.goals_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.goals_cancel)) } },
    )
}
