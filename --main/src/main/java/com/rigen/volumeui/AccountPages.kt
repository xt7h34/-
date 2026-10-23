package com.rigen.volumeui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextDirection
import org.json.JSONObject

/** Loads one read-only answer of the bot for the signed-in account. */
@Composable
private fun rememberBotData(path: String, onSignedOut: () -> Unit): Triple<JSONObject?, Boolean, String> {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(path) {
        val s = AccountStore.load(ctx)
        if (s == null) {
            loading = false
            onSignedOut()
            return@LaunchedEffect
        }
        val r = KanadeApi.call(path, token = s.token)
        loading = false
        if (r.status == 401) {
            AccountStore.clear(ctx)
            KanadeReminders.reset(ctx)
            onSignedOut()
        } else if (r.ok) {
            data = r.body
        } else {
            error = r.message(ctx)
        }
    }
    return Triple(data, loading, error)
}

/** The numbers linked to your account (like .حساباتي in the bot). The numbers are shown partly hidden. */
@Composable
fun LinkedAccountsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var signedOut by remember { mutableStateOf(AccountStore.load(ctx) == null) }
    val (data, loading, error) = rememberBotData("/me/accounts") { signedOut = true }
    val number = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)

    PageScaffold(stringResource(R.string.linked_title), onBack) { padding ->
        PageColumn(padding) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (signedOut) {
                Text(stringResource(R.string.habit_signin), style = MaterialTheme.typography.bodyMedium)
            } else {
                ErrorText(error)
                val d = data
                if (d != null) {
                    val linked = d.optJSONArray("linked")
                    val count = linked?.length() ?: 0
                    Section(stringResource(R.string.linked_title)) {
                        Text(
                            stringResource(if (d.optBoolean("isSub")) R.string.linked_sub_of else R.string.linked_master_you),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(d.optString("master"), style = number)
                        if (count == 0) {
                            Text(stringResource(R.string.linked_none))
                        } else {
                            Text(
                                stringResource(R.string.linked_count, count, d.optInt("max", 5)),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            for (i in 0 until count) {
                                Text("${i + 1}. " + linked!!.getString(i), style = number)
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.linked_shared_money),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.linked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
