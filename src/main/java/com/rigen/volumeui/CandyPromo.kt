package com.rigen.volumeui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Candy is the social app that uses the same account as Kanade. This card, on the home screen,
 * opens Candy already signed in: Kanade asks the bot for a one-time code that works for a minute,
 * and hands it to Candy (and only to Candy) when it opens it. The password is never sent anywhere.
 */

private const val CANDY_PACKAGE = "com.candy"

internal object CandyLink {
    private const val PREF = "candy_promo"

    fun installed(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(CANDY_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hidden(c: Context): Boolean = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("hidden", false)

    fun hide(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("hidden", true).apply()
    }

    /** Opens Candy and gives it the one-time code. The intent names Candy itself, so no other app can receive it. */
    fun open(c: Context, code: String) {
        c.startActivity(
            Intent()
                .setComponent(ComponentName(CANDY_PACKAGE, "$CANDY_PACKAGE.MainActivity"))
                .putExtra("handoff_code", code)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun download(c: Context, url: String) {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun CandyPromoCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val token = remember { AccountStore.load(ctx)?.token }
    var hidden by remember { mutableStateOf(CandyLink.hidden(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val installed = CandyLink.installed(ctx)
    val url = BuildConfig.CANDY_URL.trim()
    // Nothing to offer when signed out, when hidden, or when Candy is neither installed nor downloadable.
    if (token == null || hidden || (!installed && !url.startsWith("https://"))) return

    SettingsGroup {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.candy_promo_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.candy_promo_text), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    if (!installed) {
                        CandyLink.download(ctx, url)
                    } else {
                        scope.launch {
                            busy = true
                            error = ""
                            val r = KanadeApi.call("/auth/handoff", JSONObject(), token)
                            busy = false
                            val code = r.body?.optString("code").orEmpty()
                            if (r.ok && code.isNotEmpty()) {
                                try {
                                    CandyLink.open(ctx, code)
                                } catch (e: Exception) {
                                    error = ctx.getString(R.string.candy_promo_failed)
                                }
                            } else {
                                error = r.message(ctx)
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (installed) R.string.candy_promo_open else R.string.candy_promo_get))
            }
            if (error.isNotBlank()) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(
                onClick = {
                    CandyLink.hide(ctx)
                    hidden = true
                },
            ) { Text(stringResource(R.string.candy_promo_hide)) }
        }
    }
}
