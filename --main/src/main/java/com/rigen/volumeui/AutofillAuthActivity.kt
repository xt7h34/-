package com.rigen.volumeui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.core.content.IntentCompat

/**
 * The screen autofill opens: unlock Kanade Pass, then either pick the account to fill in, or
 * confirm saving the one the person just signed in with. The vault locks again as soon as it closes.
 */
class AutofillAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val MODE_FILL = "fill"
        const val MODE_SAVE = "save"
    }

    private var userId: AutofillId? = null
    private var passId: AutofillId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FILL
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        val domain = intent.getStringExtra(EXTRA_DOMAIN)
        userId = IntentCompat.getParcelableExtra(intent, EXTRA_USER, AutofillId::class.java)
        passId = IntentCompat.getParcelableExtra(intent, EXTRA_PASS, AutofillId::class.java)
        val pending = if (mode == MODE_SAVE) AfPending.take() else null

        setContent {
            KanadeTheme(THEME_DARK, false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AutofillScreen(
                        mode = mode,
                        pkg = pkg,
                        domain = domain,
                        pending = pending,
                        onPick = { entry -> fill(entry, pkg, domain) },
                        onSaved = {
                            Toast.makeText(this, R.string.af_saved, Toast.LENGTH_SHORT).show()
                            setResult(RESULT_OK)
                            finish()
                        },
                        onCancel = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** Gives the chosen account back to Android, which puts it in the fields. */
    private fun fill(entry: VaultEntry, pkg: String, domain: String?) {
        // An app that was not linked yet gets linked, so next time it is found by itself.
        if (domain == null && pkg.isNotEmpty() && entry.app != pkg) Vault.upsert(this, entry.copy(app = pkg))
        val presentation = RemoteViews(packageName, R.layout.autofill_item)
        presentation.setTextViewText(R.id.af_text, entry.title.ifBlank { entry.user })
        val builder = Dataset.Builder(presentation)
        userId?.let { builder.setValue(it, AutofillValue.forText(entry.user)) }
        passId?.let { builder.setValue(it, AutofillValue.forText(entry.password)) }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build()))
        finish()
    }

    override fun onDestroy() {
        Vault.lock()
        super.onDestroy()
    }
}

private fun appLabel(c: Context, pkg: String): String =
    try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

@Composable
private fun AutofillScreen(
    mode: String,
    pkg: String,
    domain: String?,
    pending: AfPendingSave?,
    onPick: (VaultEntry) -> Unit,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val target = domain ?: appLabel(ctx, pkg)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineSmall)

        if (!Vault.unlocked) {
            Text(
                stringResource(if (mode == AutofillAuthActivity.MODE_SAVE) R.string.af_save_title else R.string.af_fill_for, target),
                style = MaterialTheme.typography.bodyMedium,
            )
            VaultLocked()
        } else if (mode == AutofillAuthActivity.MODE_SAVE) {
            SaveForm(pkg, domain, target, pending, onSaved)
        } else {
            FillList(pkg, domain, target, onPick)
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vault_cancel))
        }
    }
}

@Composable
private fun FillList(pkg: String, domain: String?, target: String, onPick: (VaultEntry) -> Unit) {
    val matched = Vault.entries.filter { vaultMatches(it, pkg, domain) }
    val others = Vault.entries.filter { e -> matched.none { it.id == e.id } }

    // Exactly one account for this app or site: fill it straight away.
    LaunchedEffect(Unit) {
        if (matched.size == 1) onPick(matched[0])
    }

    Text(stringResource(R.string.af_fill_for, target), style = MaterialTheme.typography.bodyMedium)
    if (matched.isNotEmpty()) {
        Text(stringResource(R.string.af_matching), style = MaterialTheme.typography.labelLarge)
        for (e in matched) AccountRow(e, onPick)
    } else {
        Text(
            stringResource(R.string.af_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (others.isNotEmpty()) {
        Text(stringResource(R.string.af_others), style = MaterialTheme.typography.labelLarge)
        for (e in others) AccountRow(e, onPick)
    }
}

@Composable
private fun AccountRow(e: VaultEntry, onPick: (VaultEntry) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(e) }
            .padding(vertical = 8.dp),
    ) {
        Text(e.title.ifBlank { e.user }, style = MaterialTheme.typography.titleSmall)
        if (e.user.isNotEmpty() && e.title.isNotBlank()) {
            Text(e.user, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SaveForm(pkg: String, domain: String?, target: String, pending: AfPendingSave?, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    if (pending == null) {
        Text(stringResource(R.string.af_expired), color = MaterialTheme.colorScheme.error)
        return
    }
    val existing = Vault.entries.firstOrNull { vaultMatches(it, pkg, domain) && it.user == pending.user }
    var title by remember { mutableStateOf(existing?.title ?: target) }
    var user by remember { mutableStateOf(pending.user) }
    var pw by remember { mutableStateOf(pending.password) }

    Text(
        if (existing != null) stringResource(R.string.af_save_update, target) else stringResource(R.string.af_save_new, target),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = title, onValueChange = { title = it.take(100) },
        label = { Text(stringResource(R.string.vault_f_title)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = user, onValueChange = { user = it.take(200) },
        label = { Text(stringResource(R.string.vault_f_user)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SecretField(stringResource(R.string.vault_f_pass), pw) { pw = it.take(500) }
    Button(
        onClick = {
            val base = existing ?: VaultEntry.blank().copy(
                url = if (domain != null) "https://$domain" else "",
                app = if (domain == null) pkg else "",
            )
            Vault.upsert(ctx, base.copy(title = title.trim().ifBlank { target }, user = user.trim(), password = pw))
            onSaved()
        },
        enabled = pw.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.vault_save)) }
}
