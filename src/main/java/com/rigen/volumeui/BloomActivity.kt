package com.rigen.volumeui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Not a Bloom button: the Edge Panel shortcut that adds money to your first active goal. */
const val BA_GOAL_QUICK = 10

/**
 * Where a Bloom button that needs a look first lands: a small sheet over whatever you were doing, filled in
 * from the notification, so you can fix what Bloom guessed before anything is saved. After saving you can undo.
 */
class BloomActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Prefs.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getIntExtra("kind", -1)
        if (kind != BA_EVENT && kind != BA_EXPENSE && kind != BA_GOAL_QUICK) {
            finish()
            return
        }
        val value = intent.getStringExtra("value").orEmpty()
        val at = intent.getLongExtra("at", 0L)
        val allDay = intent.getBooleanExtra("allDay", false)
        val endAt = intent.getLongExtra("endAt", 0L)
        val amount = intent.getLongExtra("amount", 0L)
        val sender = intent.getStringExtra("sender").orEmpty()
        val settings = Prefs.load(this)
        setContent {
            KanadeTheme(settings.themeMode, settings.dynamicColor, settings.accent, settings.amoled) {
                when (kind) {
                    BA_EVENT -> BloomEventSheet(value, sender, at, allDay, endAt) { finish() }
                    BA_EXPENSE -> BloomExpenseSheet(value, sender, amount) { finish() }
                    else -> QuickGoalSheet { finish() }
                }
            }
        }
    }
}

@Composable
private fun BloomEventSheet(title: String, sender: String, at: Long, allDay: Boolean, endAt: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val initial = remember {
        CalEvent(
            id = CalendarStore.newId(),
            title = title,
            notes = sender,
            start = at,
            end = if (endAt > at) endAt else if (allDay) at else at + 3_600_000L,
            allDay = allDay,
            kind = CAL_EVENT,
            repeat = REP_NONE,
            remindBefore = if (allDay) 0 else 10,
            source = "bloom",
        )
    }
    val warning = remember { if (endAt > at) null else BloomEngine.conflict(ctx, at, allDay) }
    var saved by remember { mutableStateOf<CalEvent?>(null) }
    val done = saved
    if (done == null) {
        EventEditor(
            initial = initial,
            isNew = true,
            warning = warning,
            onSaved = { saved = it },
            onDismiss = { if (saved == null) onClose() },
        )
    } else {
        SavedDialog(
            onUndo = {
                CalendarStore.delete(ctx, done.id)
                onClose()
            },
            onClose = onClose,
        )
    }
}

@Composable
private fun BloomExpenseSheet(currency: String, sender: String, amount: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val data = remember { BudgetStore.load(ctx) }
    val cats = remember { data.cats() }
    var amountText by remember { mutableStateOf(if (amount > 0L) editableMoney(amount) else "") }
    var note by remember { mutableStateOf(sender) }
    var cat by remember { mutableStateOf("other") }
    var savedAt by remember { mutableStateOf(0L) }
    var savedAmount by remember { mutableStateOf(0L) }

    if (savedAt == 0L) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.bloom_expense_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(stringResource(R.string.bloom_amount) + if (currency.isNotBlank()) " ($currency)" else "") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.cal_notes_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cats.forEach { c ->
                            val name = if (c.nameRes != 0) stringResource(c.nameRes) else c.name
                            FilterChip(
                                selected = cat == c.id,
                                onClick = { cat = c.id },
                                label = { Text(c.emoji + " " + name) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                val minor = parseMoney(amountText)
                TextButton(
                    enabled = minor != null && minor > 0L,
                    onClick = {
                        val m = minor ?: return@TextButton
                        val now = System.currentTimeMillis()
                        BudgetStore.addTx(ctx, m, false, cat, note, now)
                        savedAmount = m
                        savedAt = now
                    },
                ) { Text(stringResource(R.string.cal_save)) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cal_cancel)) } },
        )
    } else {
        SavedDialog(
            onUndo = {
                BudgetStore.load(ctx).txs.firstOrNull { it.at == savedAt && it.amount == savedAmount }
                    ?.let { BudgetStore.deleteTx(ctx, it.id) }
                onClose()
            },
            onClose = onClose,
        )
    }
}

/** "Saved", with Undo for a few seconds, then it closes by itself. */
@Composable
private fun SavedDialog(onUndo: () -> Unit, onClose: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(4000)
        onClose()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.bloom_saved)) },
        text = { Text(stringResource(R.string.bloom_saved_hint), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.bloom_done)) } },
        dismissButton = { TextButton(onClick = onUndo) { Text(stringResource(R.string.bloom_undo)) } },
    )
}

@Composable
private fun QuickGoalSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val goal = remember { GoalsStore.load(ctx).firstOrNull { !it.archived && it.completedAt == 0L } }
    if (goal == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var savedEntry by remember { mutableStateOf<String?>(null) }

    if (savedEntry == null) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text((goal.emoji + " " + goal.name).trim()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(stringResource(R.string.bloom_amount)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.cal_notes_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                val minor = parseMoney(amountText)
                TextButton(
                    enabled = minor != null && minor > 0L,
                    onClick = {
                        val m = minor ?: return@TextButton
                        GoalsStore.addEntry(ctx, goal.id, m, note)
                        savedEntry = GoalsStore.load(ctx).firstOrNull { it.id == goal.id }
                            ?.entries?.lastOrNull { it.amount == m }?.id.orEmpty()
                    },
                ) { Text(stringResource(R.string.cal_save)) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cal_cancel)) } },
        )
    } else {
        SavedDialog(
            onUndo = {
                val id = savedEntry.orEmpty()
                if (id.isNotEmpty()) GoalsStore.deleteEntry(ctx, goal.id, id)
                onClose()
            },
            onClose = onClose,
        )
    }
}
