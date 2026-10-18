package com.rigen.volumeui

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** "2d 5h" or "5h 20m" for what is left of a plan. */
private fun planLeft(c: Context, ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    val d = (minutes / 1440L).toInt()
    val h = ((minutes % 1440L) / 60L).toInt()
    val m = (minutes % 60L).toInt()
    return if (d > 0) c.getString(R.string.rem_dh, d, h) else c.getString(R.string.widget_hm, h, m)
}

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

/** Your Kanade Pass: which plan, how long is left, today's missions, and what each plan gives. */
@Composable
fun PassPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var signedOut by remember { mutableStateOf(AccountStore.load(ctx) == null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val (data, loading, error) = rememberBotData("/me/pass") { signedOut = true }

    PageScaffold(stringResource(R.string.pass_title), onBack) { padding ->
        PageColumn(padding) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (signedOut) {
                Text(stringResource(R.string.habit_signin), style = MaterialTheme.typography.bodyMedium)
            } else {
                ErrorText(error)
                val d = data
                if (d != null) {
                    Section(stringResource(R.string.pass_your_plan)) {
                        if (d.optBoolean("active")) {
                            val expires = d.optLong("expiresAt")
                            val missions = d.optJSONObject("missions")
                            Text(
                                d.optString("emoji") + " " + d.optString("name"),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(stringResource(R.string.pass_left, planLeft(ctx, expires - now)))
                            Text(
                                stringResource(
                                    R.string.pass_until,
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(expires)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.pass_missions, missions?.optInt("claimed") ?: 0, missions?.optInt("available") ?: 0))
                            Text(stringResource(R.string.pass_xp, d.optInt("xpBonusPct")))
                            Text(stringResource(R.string.pass_discount, d.optInt("storeDiscountPct")))
                        } else {
                            Text(stringResource(R.string.pass_none))
                            Text(
                                stringResource(R.string.pass_buy_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val tiers = d.optJSONArray("tiers")
                    if (tiers != null && tiers.length() > 0) {
                        Section(stringResource(R.string.pass_plans)) {
                            for (i in 0 until tiers.length()) {
                                val t = tiers.getJSONObject(i)
                                Text(
                                    t.optString("emoji") + " " + t.optString("name"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    stringResource(
                                        R.string.pass_tier_perks,
                                        t.optInt("xpBonusPct"), t.optInt("dailyMissions"), t.optInt("storeDiscountPct"),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
