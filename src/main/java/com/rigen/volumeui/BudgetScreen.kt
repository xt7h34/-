package com.rigen.volumeui

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private val CAT_EMOJIS = listOf("\uD83C\uDFE0", "\uD83D\uDCDA", "\uD83D\uDC3E", "\uD83C\uDF81", "\u2615", "\u2708\uFE0F", "\uD83D\uDCF1", "\uD83D\uDC76", "\uD83D\uDE97", "\uD83C\uDFCB\uFE0F")
private const val TX_PAGE = 30

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

private fun fmtDay(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/** A date up to today; today keeps the real time, an earlier day is noon. */
private fun pickPastDate(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val host = ctx.hostActivity() ?: return
    val cal = Calendar.getInstance()
    cal.timeInMillis = initial
    val dialog = DatePickerDialog(
        host,
        { _, y, m, d ->
            val today = Calendar.getInstance()
            if (today.get(Calendar.YEAR) == y && today.get(Calendar.MONTH) == m && today.get(Calendar.DAY_OF_MONTH) == d) {
                onPicked(System.currentTimeMillis())
            } else {
                val c = Calendar.getInstance()
                c.clear()
                c.set(y, m, d, 12, 0, 0)
                onPicked(c.timeInMillis)
            }
        },
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
    )
    dialog.datePicker.maxDate = System.currentTimeMillis()
    dialog.show()
}

private fun writeFile(ctx: Context, uri: Uri, bytes: ByteArray, okMessage: Int) {
    try {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        Toast.makeText(ctx, okMessage, Toast.LENGTH_LONG).show()
    } catch (_: Exception) {
        Toast.makeText(ctx, R.string.goals_io_failed, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun catLabel(c: BudgetCat): String = if (c.nameRes != 0) stringResource(c.nameRes) else c.name

@Composable
fun BudgetPage(onBack: () -> Unit) {
    MoneyPage(onBack) { _, _ -> BudgetMain(onBack) }
}

@Composable
private fun BudgetMain(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = BudgetStore.tick
    val data = remember(tick) { BudgetStore.load(ctx) }
    val goalsTick = GoalsStore.tick
    val goals = remember(goalsTick) { GoalsStore.load(ctx).filter { !it.archived && it.completedAt == 0L } }
    val now = remember(tick) { System.currentTimeMillis() }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var addMode by remember { mutableIntStateOf(0) } // 0 closed, 1 expense, 2 income
    var showSettings by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(TX_PAGE) }

    val month = BudgetEngine.month(now, offset)
    val sum = BudgetEngine.summary(data, month)
    val cats = data.cats()
    val left = data.monthly - sum.spent
    val monthTxs = remember(tick, month.start) {
        data.txs.filter { it.at >= month.start && it.at < month.end }.sortedByDescending { it.at }
    }
    val sameCurrencyGoals = goals.filter { it.currency.trim().equals(data.currency.trim(), ignoreCase = true) }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        GoalsLock.suppress = false
        if (uri != null) writeFile(ctx, uri, BudgetStore.exportCsv(ctx), R.string.bud_csv_saved)
    }

    PageScaffold(stringResource(R.string.bud_title), onBack) { padding ->
        PageColumn(padding) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset -= 1; shown = TX_PAGE }) { Text(stringResource(R.string.bud_prev)) }
                Text(
                    BudgetEngine.monthLabel(month),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(enabled = offset < 0, onClick = { offset += 1; shown = TX_PAGE }) { Text(stringResource(R.string.bud_next)) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { addMode = 1 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bud_add_expense)) }
                OutlinedButton(onClick = { addMode = 2 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bud_add_income)) }
            }

            Section(stringResource(R.string.bud_overview)) {
                if (data.monthly > 0L) {
                    Text(
                        stringResource(
                            if (left >= 0L) R.string.bud_left else R.string.bud_over,
                            formatMoney(BudgetStore.magnitude(left), data.currency),
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (left >= 0L) ICON_GREEN else ICON_RED,
                    )
                    LinearProgressIndicator(
                        progress = { (sum.spent.toDouble() / data.monthly).coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (left >= 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.bud_spent_of, formatMoney(sum.spent, data.currency), formatMoney(data.monthly, data.currency)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val daily = BudgetEngine.dailyAllowance(left, now, month)
                    if (daily != null && daily > 0L) {
                        Text(
                            stringResource(R.string.bud_daily, formatMoney(daily, data.currency)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.bud_spent, formatMoney(sum.spent, data.currency)),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(stringResource(R.string.bud_set_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sum.income > 0L) {
                    Text(
                        stringResource(R.string.bud_income_line, formatMoney(sum.income, data.currency)),
                        color = ICON_GREEN,
                    )
                }
            }

            val catRows = cats
                .filter { (sum.byCat[it.id] ?: 0L) > 0L || it.limit > 0L }
                .sortedByDescending { sum.byCat[it.id] ?: 0L }
            if (catRows.isNotEmpty()) {
                Section(stringResource(R.string.bud_categories)) {
                    for (cat in catRows) {
                        val spent = sum.byCat[cat.id] ?: 0L
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.emoji, fontSize = 20.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(catLabel(cat), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (cat.limit > 0L) {
                                        formatMoney(spent, "") + " / " + formatMoney(cat.limit, data.currency)
                                    } else {
                                        formatMoney(spent, data.currency)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (cat.limit > 0L && spent > cat.limit) ICON_RED else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (cat.limit > 0L) {
                                LinearProgressIndicator(
                                    progress = { (spent.toDouble() / cat.limit).coerceIn(0.0, 1.0).toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (spent > cat.limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            Section(stringResource(R.string.bud_transactions)) {
                if (monthTxs.isEmpty()) {
                    Text(stringResource(R.string.bud_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (t in monthTxs.take(shown)) {
                    val cat = data.cat(t.cat)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (t.income) stringResource(R.string.bud_income_label) else cat.emoji + " " + catLabel(cat),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (t.note.isBlank()) fmtDay(t.at) else fmtDay(t.at) + " \u00B7 " + t.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            (if (t.income) "+" else "-") + formatMoney(t.amount, data.currency),
                            color = if (t.income) ICON_GREEN else ICON_RED,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(onClick = {
                            BudgetStore.deleteTx(ctx, t.id)
                            Undo.show(ctx.getString(R.string.bud_tx_deleted)) { BudgetStore.restoreTx(ctx, t) }
                        }) { Text(stringResource(R.string.goals_delete)) }
                    }
                }
                if (monthTxs.size > shown) {
                    TextButton(onClick = { shown += TX_PAGE }) { Text(stringResource(R.string.goals_show_more)) }
                }
            }

            if (offset == 0 && data.monthly > 0L && left > 0L && sameCurrencyGoals.isNotEmpty()) {
                OutlinedButton(onClick = { showSave = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bud_save_rest))
                }
            }
            OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bud_settings))
            }
            OutlinedButton(
                onClick = {
                    GoalsLock.suppress = true
                    csvPicker.launch("kanabudget.csv")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.bud_export_csv)) }
        }
    }

    if (addMode != 0) {
        AddTxDialog(
            income = addMode == 2,
            data = data,
            onDismiss = { addMode = 0 },
            onSave = { amount, cat, note, at ->
                BudgetStore.addTx(ctx, amount, addMode == 2, cat, note, at)
                addMode = 0
            },
        )
    }
    if (showSettings) {
        BudgetSettingsDialog(
            data = data,
            onDismiss = { showSettings = false },
            onSave = { currency, monthly, limits ->
                BudgetStore.setMain(ctx, currency, monthly, limits)
                showSettings = false
            },
            onAddCustom = { emoji, name -> BudgetStore.addCustom(ctx, emoji, name) },
            onDeleteCustom = { BudgetStore.deleteCustom(ctx, it) },
        )
    }
    if (showSave) {
        SaveRestDialog(
            left = left,
            currency = data.currency,
            goals = sameCurrencyGoals,
            onDismiss = { showSave = false },
            onConfirm = { goal ->
                val reached = GoalsStore.addEntry(ctx, goal.id, left, "")
                BudgetStore.addTx(ctx, left, false, "savings", goal.name, System.currentTimeMillis())
                showSave = false
                if (reached) GoalsReminders.notifyDone(ctx, goal.id)
            },
        )
    }
}

// ───────────────────────── Dialogs ─────────────────────────

@Composable
private fun AddTxDialog(income: Boolean, data: BudgetData, onDismiss: () -> Unit, onSave: (Long, String, String, Long) -> Unit) {
    val ctx = LocalContext.current
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var catId by remember { mutableStateOf("food") }
    var at by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tried by remember { mutableStateOf(false) }
    val parsed = parseMoney(amount)
    val invalid = parsed == null || parsed <= 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (income) R.string.bud_add_income else R.string.bud_add_expense)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (it.length <= 20) amount = it },
                    label = { Text(stringResource(R.string.goals_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = tried && invalid,
                    supportingText = { if (tried && invalid) Text(stringResource(R.string.goals_err_amount)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!income) {
                    Text(stringResource(R.string.bud_category), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (cat in data.cats()) {
                            FilterChip(
                                selected = catId == cat.id,
                                onClick = { catId = cat.id },
                                label = { Text(cat.emoji + " " + catLabel(cat)) },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { pickPastDate(ctx, at) { at = it } }) {
                    Text(stringResource(R.string.bud_date) + ": " + fmtDay(at))
                }
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
                if (p != null && p > 0L) onSave(p, if (income) "other" else catId, note, at)
            }) { Text(stringResource(R.string.goals_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.goals_cancel)) } },
    )
}

@Composable
private fun BudgetSettingsDialog(
    data: BudgetData,
    onDismiss: () -> Unit,
    onSave: (String, Long, Map<String, Long>) -> Unit,
    onAddCustom: (String, String) -> Unit,
    onDeleteCustom: (String) -> Unit,
) {
    var currency by remember { mutableStateOf(data.currency) }
    var monthlyText by remember { mutableStateOf(if (data.monthly > 0L) editableMoney(data.monthly) else "") }
    val limitText = remember {
        mutableStateMapOf<String, String>().also { m ->
            for (c in data.cats()) if (c.limit > 0L) m[c.id] = editableMoney(c.limit)
        }
    }
    var newName by remember { mutableStateOf("") }
    var newEmoji by remember { mutableStateOf(CAT_EMOJIS[0]) }
    var tried by remember { mutableStateOf(false) }

    val monthly = if (monthlyText.isBlank()) 0L else parseMoney(monthlyText)
    val limitsParsed = data.cats().associate { c ->
        val t = limitText[c.id].orEmpty()
        c.id to (if (t.isBlank()) 0L else parseMoney(t))
    }
    val allOk = monthly != null && limitsParsed.values.all { it != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bud_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = monthlyText,
                        onValueChange = { if (it.length <= 20) monthlyText = it },
                        label = { Text(stringResource(R.string.bud_monthly)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = tried && monthly == null,
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
                Text(stringResource(R.string.bud_limits), style = MaterialTheme.typography.titleSmall)
                for (cat in data.cats()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = limitText[cat.id].orEmpty(),
                            onValueChange = { if (it.length <= 20) limitText[cat.id] = it },
                            label = { Text(cat.emoji + " " + catLabel(cat)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = tried && limitsParsed[cat.id] == null,
                            modifier = Modifier.weight(1f),
                        )
                        if (cat.nameRes == 0) {
                            TextButton(onClick = { onDeleteCustom(cat.id) }) {
                                Text(stringResource(R.string.goals_delete), color = ICON_RED)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.bud_new_cat), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (e in CAT_EMOJIS) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (e == newEmoji) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { newEmoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { if (it.length <= 30) newName = it },
                    label = { Text(stringResource(R.string.bud_cat_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onAddCustom(newEmoji, newName)
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text(stringResource(R.string.bud_add_cat)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (allOk && monthly != null) {
                    val limits = HashMap<String, Long>()
                    for ((id, v) in limitsParsed) if (v != null && v > 0L) limits[id] = v
                    onSave(currency, monthly, limits)
                }
            }) { Text(stringResource(R.string.goals_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.goals_cancel)) } },
    )
}

@Composable
private fun SaveRestDialog(left: Long, currency: String, goals: List<Goal>, onDismiss: () -> Unit, onConfirm: (Goal) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bud_save_rest_title, formatMoney(left, currency))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                goals.forEachIndexed { i, g ->
                    Row(Modifier.fillMaxWidth().clickable { selected = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == i, onClick = { selected = i })
                        Text("${g.emoji} ${g.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { goals.getOrNull(selected)?.let(onConfirm) }) { Text(stringResource(R.string.goals_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.goals_cancel)) } },
    )
}
