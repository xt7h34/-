package com.rigen.volumeui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private class BloomApp(val pkg: String, val label: String)

private fun loadBloomApps(context: Context): List<BloomApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { BloomApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.pkg }
        .filter { it.pkg != context.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun BloomPage(notificationAccess: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(BloomPrefs.enabled(ctx)) }
    var kinds by remember { mutableStateOf(BloomPrefs.kinds(ctx)) }
    var chosen by remember { mutableStateOf(BloomPrefs.apps(ctx)) }
    var apps by remember { mutableStateOf<List<BloomApp>?>(null) }
    var query by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        // The list of apps can take a moment, so it is loaded off the main thread.
        Thread { apps = loadBloomApps(ctx) }.start()
        onDispose { }
    }

    val kindRows = listOf(
        BA_EVENT to R.string.bloom_kind_event,
        BA_EXPENSE to R.string.bloom_kind_expense,
        BA_CODE to R.string.bloom_kind_code,
        BA_LINK to R.string.bloom_kind_link,
        BA_CALL to R.string.bloom_kind_call,
    )
    val loaded = apps
    val shown = loaded?.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) } ?: emptyList()

    PageScaffold(stringResource(R.string.bloom_title), onBack, lazy = true) { _ ->
        LazyColumn(
            state = LocalPageList.current,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeaderSpacer() }
            item { Text(stringResource(R.string.bloom_desc), style = MaterialTheme.typography.bodyMedium) }
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.bloom_title),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Switch(checked = enabled, onCheckedChange = {
                            enabled = it
                            BloomPrefs.setEnabled(ctx, it)
                        })
                    }
                }
            }
            if (enabled && !notificationAccess) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.bloom_access_needed), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }) { Text(stringResource(R.string.bloom_open_access)) }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.bloom_privacy), style = MaterialTheme.typography.bodySmall) }
            item {
                Text(
                    stringResource(R.string.bloom_kinds),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(kindRows, key = { it.first }) { (kind, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), Modifier.weight(1f))
                    Switch(checked = kind in kinds, onCheckedChange = { on ->
                        kinds = if (on) kinds + kind else kinds - kind
                        BloomPrefs.setKinds(ctx, kinds)
                    })
                }
            }
            item {
                Text(
                    stringResource(R.string.bloom_apps),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_apps)) },
                )
            }
            if (loaded == null) {
                item { Text(stringResource(R.string.loading_apps)) }
            } else if (shown.isEmpty()) {
                item { Text(stringResource(R.string.no_apps)) }
            } else {
                items(shown, key = { it.pkg }) { app ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(app.label, Modifier.weight(1f))
                        Switch(checked = app.pkg in chosen, onCheckedChange = { on ->
                            chosen = if (on) chosen + app.pkg else chosen - app.pkg
                            BloomPrefs.setApps(ctx, chosen)
                        })
                    }
                }
            }
        }
    }
}
