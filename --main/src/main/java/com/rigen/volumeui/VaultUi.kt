package com.rigen.volumeui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Kanade Vault: the screens. Screenshots are blocked while the vault is open, and it locks as
 * soon as the person leaves the page (or the app, if that setting is on).
 */

private const val CLIP_LABEL = "kanade-vault"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Copies a secret. It is marked sensitive and is wiped from the clipboard after 30 seconds. */
private fun copySecret(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(CLIP_LABEL, text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
    Toast.makeText(ctx, R.string.vault_copied, Toast.LENGTH_SHORT).show()
    Handler(Looper.getMainLooper()).postDelayed({
        try {
            if (cm.primaryClipDescription?.label?.toString() == CLIP_LABEL) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
                else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            // Nothing more can be done.
        }
    }, 30_000L)
}

@Composable
fun VaultPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val exists = remember(tick) { Vault.exists(ctx) }

    DisposableEffect(Unit) {
        val act = ctx.findActivity()
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val lifecycle = (act as? ComponentActivity)?.lifecycle
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !Vault.suppressLock && VaultPrefs.lockOnLeave(ctx)) Vault.lock()
        }
        lifecycle?.addObserver(watcher)
        onDispose {
            lifecycle?.removeObserver(watcher)
            act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Vault.lock()
        }
    }

    PageScaffold(stringResource(R.string.vault_title), onBack) { padding ->
        PageColumn(padding) {
            when {
                !exists -> VaultSetup { tick++ }
                !Vault.unlocked -> VaultLocked()
                else -> VaultOpen { tick++ }
            }
        }
    }
}

// ───────────────────────── Fields ─────────────────────────

@Composable
internal fun SecretField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { show = !show }) {
                Text(stringResource(if (show) R.string.vault_hide else R.string.vault_show))
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun StrengthBar(pw: String) {
    if (pw.isEmpty()) return
    val level = vaultStrength(pw)
    val names = listOf(R.string.vault_s0, R.string.vault_s1, R.string.vault_s2, R.string.vault_s3)
    val colors = listOf(Color(0xFFE5484D), Color(0xFFF29900), Color(0xFF7BB33A), Color(0xFF2E9E5B))
    LinearProgressIndicator(
        progress = { (level + 1) / 4f },
        color = colors[level],
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(names[level]), style = MaterialTheme.typography.bodySmall, color = colors[level])
}

// ───────────────────────── Setup ─────────────────────────

@Composable
private fun VaultSetup(onCreated: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Section(stringResource(R.string.vault_title)) {
        Text(stringResource(R.string.vault_setup_intro))
        Text(
            stringResource(R.string.vault_setup_warn),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        SecretField(stringResource(R.string.vault_master), p1) { p1 = it }
        StrengthBar(p1)
        Text(
            stringResource(R.string.vault_min),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecretField(stringResource(R.string.vault_master_confirm), p2) { p2 = it }
        SwitchRow(stringResource(R.string.vault_understand), understood) { understood = it }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    Vault.create(ctx, p1.toCharArray())
                    p1 = ""
                    p2 = ""
                    busy = false
                    onCreated()
                }
            },
            enabled = p1.length >= 8 && p1 == p2 && understood && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.vault_create)) }
        Text(
            stringResource(R.string.vault_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ───────────────────────── Locked ─────────────────────────

@Composable
internal fun VaultLocked() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }
    var wait by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val wrapped = remember { Vault.bioWrapped(ctx) }
    val canBio = wrapped != null && VaultBio.available(ctx)
    val bioTitle = stringResource(R.string.vault_bio_title)
    val cancel = stringResource(R.string.vault_cancel)

    fun bioUnlock() {
        val w = wrapped ?: return
        VaultBio.unlock(ctx, bioTitle, cancel, w.first, w.second, { k -> Vault.unlockWithKey(ctx, k) }, {})
    }

    LaunchedEffect(Unit) { if (canBio) bioUnlock() }
    LaunchedEffect(wait) {
        if (wait > 0) {
            delay(1000)
            wait--
        }
    }

    Section(stringResource(R.string.vault_title)) {
        SecretField(stringResource(R.string.vault_master), pw) { pw = it }
        if (wrong > 0) Text(stringResource(R.string.vault_wrong), color = MaterialTheme.colorScheme.error)
        if (wait > 0) Text(stringResource(R.string.vault_wait, wait), color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    val ok = Vault.unlock(ctx, pw.toCharArray())
                    busy = false
                    pw = ""
                    if (!ok) {
                        wrong++
                        if (wrong % 5 == 0) wait = 30
                    }
                }
            },
            enabled = pw.isNotEmpty() && !busy && wait == 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.vault_unlock)) }
        if (canBio) {
            OutlinedButton(onClick = { bioUnlock() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vault_use_bio))
            }
        }
    }
}

// ───────────────────────── Open ─────────────────────────

@Composable
private fun VaultOpen(onDestroyed: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<VaultEntry?>(null) }
    val list = Vault.entries.filter {
        query.isBlank() || it.title.contains(query, true) || it.user.contains(query, true) || it.url.contains(query, true)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { editing = VaultEntry.blank() }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.vault_add))
        }
        OutlinedButton(onClick = { Vault.lock() }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.vault_lock))
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.vault_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (Vault.entries.isEmpty()) {
        Text(
            stringResource(R.string.vault_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Section(stringResource(R.string.vault_title)) {
        for (e in list) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editing = e }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleSmall)
                    if (e.user.isNotEmpty()) {
                        Text(
                            e.user,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { copySecret(ctx, e.password) }) { Text(stringResource(R.string.vault_copy)) }
            }
        }
    }
    VaultSettings(onDestroyed)

    val target = editing
    if (target != null) {
        EntryDialog(target, isNew = Vault.entries.none { it.id == target.id }) { editing = null }
    }
}

@Composable
private fun EntryDialog(initial: VaultEntry, isNew: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var user by remember { mutableStateOf(initial.user) }
    var pw by remember { mutableStateOf(initial.password) }
    var url by remember { mutableStateOf(initial.url) }
    var app by remember { mutableStateOf(initial.app) }
    var notes by remember { mutableStateOf(initial.notes) }
    var generating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(if (isNew) R.string.vault_entry_add else R.string.vault_entry_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it.take(100) },
                    label = { Text(stringResource(R.string.vault_f_title)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it.take(200) },
                    label = { Text(stringResource(R.string.vault_f_user)) }, singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { copySecret(ctx, user) }, enabled = user.isNotEmpty()) {
                            Text(stringResource(R.string.vault_copy))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField(stringResource(R.string.vault_f_pass), pw) { pw = it.take(500) }
                StrengthBar(pw)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { generating = true }) { Text(stringResource(R.string.vault_generate)) }
                    OutlinedButton(onClick = { copySecret(ctx, pw) }, enabled = pw.isNotEmpty()) {
                        Text(stringResource(R.string.vault_copy))
                    }
                }
                OutlinedTextField(
                    value = url, onValueChange = { url = it.take(300) },
                    label = { Text(stringResource(R.string.vault_f_url)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = app, onValueChange = { app = it.trim().take(200) },
                    label = { Text(stringResource(R.string.vault_f_app)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it.take(2000) },
                    label = { Text(stringResource(R.string.vault_f_notes)) }, minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    Vault.upsert(ctx, initial.copy(title = title.trim(), user = user.trim(), password = pw, url = url.trim(), notes = notes, app = app))
                    onClose()
                },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.vault_save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            Vault.delete(ctx, initial.id)
                            onClose()
                        }
                    }) {
                        Text(
                            stringResource(if (confirmDelete) R.string.vault_delete_confirm else R.string.vault_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.vault_cancel)) }
            }
        },
    )

    if (generating) {
        GeneratorDialog(onDismiss = { generating = false }) {
            pw = it
            generating = false
        }
    }
}

@Composable
private fun GeneratorDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var length by remember { mutableIntStateOf(20) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var again by remember { mutableIntStateOf(0) }
    val generated = remember(length, upper, lower, digits, symbols, again) {
        vaultGenerate(length, upper, lower, digits, symbols)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.vault_gen_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(generated, style = MaterialTheme.typography.titleMedium)
                StrengthBar(generated)
                Text(stringResource(R.string.vault_gen_len, length))
                Slider(value = length.toFloat(), onValueChange = { length = it.toInt() }, valueRange = 8f..64f)
                SwitchRow(stringResource(R.string.vault_gen_upper), upper) { upper = it }
                SwitchRow(stringResource(R.string.vault_gen_lower), lower) { lower = it }
                SwitchRow(stringResource(R.string.vault_gen_digits), digits) { digits = it }
                SwitchRow(stringResource(R.string.vault_gen_symbols), symbols) { symbols = it }
            }
        },
        confirmButton = { TextButton(onClick = { onUse(generated) }) { Text(stringResource(R.string.vault_gen_use)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { again++ }) { Text(stringResource(R.string.vault_gen_again)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_cancel)) }
            }
        },
    )
}

// ───────────────────────── Settings, backup, delete ─────────────────────────

@Composable
private fun VaultSettings(onDestroyed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lockLeave by remember { mutableStateOf(VaultPrefs.lockOnLeave(ctx)) }
    var bioOn by remember { mutableStateOf(Vault.bioWrapped(ctx) != null) }
    var changing by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var confirmDestroy by remember { mutableStateOf(false) }
    val bioTitle = stringResource(R.string.vault_bio_title)
    val cancel = stringResource(R.string.vault_cancel)

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        Vault.suppressLock = false
        val bytes = Vault.backupBytes(ctx)
        if (uri != null && bytes != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Toast.makeText(ctx, R.string.vault_exported, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.vault_io_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        Vault.suppressLock = false
        if (uri != null) {
            try {
                importBytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.vault_io_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    val autofill = remember { ctx.getSystemService(android.view.autofill.AutofillManager::class.java) }
    var autofillOn by remember { mutableStateOf(autofill?.hasEnabledAutofillServices() == true) }
    LaunchedEffect(Unit) {
        while (true) {
            autofillOn = autofill?.hasEnabledAutofillServices() == true
            delay(1500)
        }
    }

    Section(stringResource(R.string.vault_settings)) {
        Text(stringResource(R.string.vault_af), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (autofillOn) R.string.vault_af_on else R.string.vault_af_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!autofillOn) {
            Button(
                onClick = {
                    try {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                                android.net.Uri.parse("package:${ctx.packageName}"),
                            ),
                        )
                    } catch (e: Exception) {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.vault_af_enable)) }
        }
        SwitchRow(stringResource(R.string.vault_lock_leave), lockLeave) {
            lockLeave = it
            VaultPrefs.setLockOnLeave(ctx, it)
        }
        if (VaultBio.available(ctx)) {
            SwitchRow(stringResource(R.string.vault_bio), bioOn) { on ->
                if (!on) {
                    Vault.setBio(ctx, null, null)
                    VaultBio.forget()
                    bioOn = false
                } else {
                    val k = Vault.currentKey()
                    if (k != null) {
                        VaultBio.enroll(
                            ctx, bioTitle, cancel, k,
                            { iv, ct ->
                                Vault.setBio(ctx, iv, ct)
                                bioOn = true
                            },
                            {},
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = { changing = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vault_change))
        }
        OutlinedButton(
            onClick = {
                Vault.suppressLock = true
                exportPicker.launch("kanade-vault-backup.kvault")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.vault_export)) }
        OutlinedButton(
            onClick = {
                Vault.suppressLock = true
                importPicker.launch(arrayOf("*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.vault_import)) }
        OutlinedButton(
            onClick = {
                if (!confirmDestroy) {
                    confirmDestroy = true
                } else {
                    Vault.destroy(ctx)
                    VaultBio.forget()
                    onDestroyed()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(if (confirmDestroy) R.string.vault_destroy_confirm else R.string.vault_destroy),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.vault_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (changing) {
        PasswordsDialog(
            title = stringResource(R.string.vault_change),
            onDismiss = { changing = false },
            confirmText = stringResource(R.string.vault_save),
            fields = listOf(R.string.vault_old, R.string.vault_new, R.string.vault_master_confirm),
            valid = { v -> v[0].isNotEmpty() && v[1].length >= 8 && v[1] == v[2] },
        ) { v ->
            scope.launch {
                val ok = Vault.changePassword(ctx, v[0].toCharArray(), v[1].toCharArray())
                if (ok) {
                    VaultBio.forget()
                    bioOn = false
                }
                Toast.makeText(ctx, if (ok) R.string.vault_changed else R.string.vault_wrong, Toast.LENGTH_LONG).show()
            }
            changing = false
        }
    }
    val bytes = importBytes
    if (bytes != null) {
        PasswordsDialog(
            title = stringResource(R.string.vault_import),
            onDismiss = { importBytes = null },
            confirmText = stringResource(R.string.vault_import_go),
            fields = listOf(R.string.vault_import_pass),
            valid = { v -> v[0].isNotEmpty() },
        ) { v ->
            scope.launch {
                val added = Vault.importBackup(ctx, bytes, v[0].toCharArray())
                val msg = if (added < 0) ctx.getString(R.string.vault_import_wrong) else ctx.getString(R.string.vault_imported, added)
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
            importBytes = null
        }
    }
}

/** A small dialog with several secret fields. */
@Composable
private fun PasswordsDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    fields: List<Int>,
    valid: (List<String>) -> Boolean,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember { mutableStateOf(List(fields.size) { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { i, label ->
                    SecretField(stringResource(label), values.value[i]) { v ->
                        values.value = values.value.toMutableList().also { it[i] = v }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(values.value) }, enabled = valid(values.value)) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_cancel)) } },
    )
}
