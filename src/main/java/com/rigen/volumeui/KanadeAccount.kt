package com.rigen.volumeui

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.DateFormat
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ───────────────────────── Talking to the Kanade bot on this phone ─────────────────────────

class ApiResult(val status: Int, val body: JSONObject?) {
    val ok: Boolean get() = status in 200..299

    /** The bot's own message when it sent one, otherwise a generic one. */
    fun message(c: Context): String {
        val fromBot = body?.optString("error").orEmpty()
        if (fromBot.isNotBlank()) return fromBot
        return c.getString(if (status == 0) R.string.acc_offline else R.string.acc_error)
    }
}

object KanadeApi {
    // The bot (in Termux) listens here, and only here. The app may speak plain http to this address only.
    private const val BASE = "http://127.0.0.1:8787"

    suspend fun call(path: String, body: JSONObject? = null, token: String? = null): ApiResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(BASE + path).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 4000
                c.readTimeout = 20000
                c.requestMethod = if (body == null) "GET" else "POST"
                c.setRequestProperty("Accept", "application/json")
                if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                ApiResult(code, runCatching { JSONObject(text) }.getOrNull())
            } catch (e: Exception) {
                ApiResult(0, null)
            } finally {
                conn?.disconnect()
            }
        }
}

// ───────────────────────── The signed-in session, kept encrypted ─────────────────────────

class Session(val token: String, val profile: JSONObject)

/** The token and the profile are encrypted with a key that never leaves the Android Keystore. */
object AccountStore {
    private const val ALIAS = "kanade_account"
    private const val PREF = "kanade_account"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun save(c: Context, s: Session) {
        try {
            val plain = JSONObject().put("token", s.token).put("profile", s.profile).toString()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("s", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            Diag.error(c, "AccountStore.save", e)
        }
    }

    fun load(c: Context): Session? {
        return try {
            val raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("s", null) ?: return null
            val all = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            val o = JSONObject(String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8))
            Session(o.getString("token"), o.getJSONObject("profile"))
        } catch (e: Exception) {
            null
        }
    }

    fun clear(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("s").apply()
    }
}

// ───────────────────────── The screen ─────────────────────────

private enum class Step { Start, Login, Register, Code, Done }

@Composable
fun AccountPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(AccountStore.load(ctx)) }
    var step by remember { mutableStateOf(if (session != null) Step.Done else Step.Start) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    var ident by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("+20") }
    var phone by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    fun finish(r: ApiResult) {
        val token = r.body?.optString("token").orEmpty()
        val profile = r.body?.optJSONObject("profile")
        if (r.ok && token.isNotEmpty() && profile != null) {
            val s = Session(token, profile)
            AccountStore.save(ctx, s)
            session = s
            pass = ""
            newPass = ""
            code = ""
            error = ""
            step = Step.Done
        } else {
            error = r.message(ctx)
        }
    }

    suspend fun refresh() {
        val s = session ?: return
        val r = KanadeApi.call("/auth/me", token = s.token)
        if (r.status == 401) {
            AccountStore.clear(ctx)
            session = null
            step = Step.Start
        } else if (r.ok) {
            val p = r.body?.optJSONObject("profile")
            if (p != null) {
                val ns = Session(s.token, p)
                AccountStore.save(ctx, ns)
                session = ns
            }
        }
    }

    LaunchedEffect(step) {
        if (step == Step.Done) refresh()
    }

    BackHandler(enabled = step == Step.Login || step == Step.Register || step == Step.Code) {
        error = ""
        step = if (step == Step.Code) Step.Register else Step.Start
    }

    PageScaffold(stringResource(R.string.account_title), onBack) { padding ->
        PageColumn(padding) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (step) {
                Step.Start -> {
                    Text(stringResource(R.string.acc_intro), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            error = ""
                            step = Step.Login
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.acc_have)) }
                    OutlinedButton(
                        onClick = {
                            error = ""
                            step = Step.Register
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.acc_new)) }
                }

                Step.Login -> {
                    Field(stringResource(R.string.acc_ident), ident, { ident = it })
                    Field(stringResource(R.string.acc_password), pass, { pass = it }, secret = true)
                    ErrorText(error)
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = ""
                                val r = KanadeApi.call(
                                    "/auth/login",
                                    JSONObject().put("identifier", ident.trim()).put("password", pass)
                                        .put("device", Build.MODEL),
                                )
                                busy = false
                                finish(r)
                            }
                        },
                        enabled = !busy && ident.isNotBlank() && pass.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.acc_login)) }
                    TextButton(
                        onClick = {
                            error = ""
                            step = Step.Start
                        },
                    ) { Text(stringResource(R.string.ring_back)) }
                }

                Step.Register -> {
                    Field(stringResource(R.string.acc_name), name, { name = it })
                    Field(stringResource(R.string.acc_birth), birth, { birth = it }, KeyboardType.Number)
                    Field(stringResource(R.string.acc_hash), hash, { hash = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(
                            stringResource(R.string.acc_cc), cc,
                            { cc = it.filter { c -> c.isDigit() || c == '+' }.take(5) },
                            KeyboardType.Phone, modifier = Modifier.weight(0.35f),
                        )
                        Field(
                            stringResource(R.string.acc_phone), phone, { phone = it },
                            KeyboardType.Phone, modifier = Modifier.weight(0.65f),
                        )
                    }
                    Text(
                        stringResource(R.string.acc_phone_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Field(stringResource(R.string.acc_pass_new), newPass, { newPass = it }, secret = true)
                    ErrorText(error)
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = ""
                                // A number that starts with + already has its country code.
                                val digits = if (phone.trim().startsWith("+")) {
                                    phone.filter { it.isDigit() }
                                } else {
                                    cc.filter { it.isDigit() } + phone.filter { it.isDigit() }.trimStart('0')
                                }
                                val r = KanadeApi.call(
                                    "/auth/register/start",
                                    JSONObject().put("name", name.trim()).put("birthdate", birth.trim())
                                        .put("hash", hash.trim()).put("phone", digits)
                                        .put("password", newPass).put("device", Build.MODEL),
                                )
                                busy = false
                                val id = r.body?.optString("requestId").orEmpty()
                                if (r.ok && id.isNotEmpty()) {
                                    requestId = id
                                    code = ""
                                    step = Step.Code
                                } else {
                                    error = r.message(ctx)
                                }
                            }
                        },
                        enabled = !busy && name.isNotBlank() && birth.isNotBlank() && hash.isNotBlank() &&
                            phone.isNotBlank() && newPass.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.acc_send_code)) }
                    TextButton(
                        onClick = {
                            error = ""
                            step = Step.Start
                        },
                    ) { Text(stringResource(R.string.ring_back)) }
                }

                Step.Code -> {
                    Text(stringResource(R.string.acc_code_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.acc_code_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Field(
                        stringResource(R.string.acc_code_title), code,
                        { code = it.filter { c -> c.isDigit() }.take(6) }, KeyboardType.Number,
                    )
                    ErrorText(error)
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = ""
                                val r = KanadeApi.call(
                                    "/auth/register/confirm",
                                    JSONObject().put("requestId", requestId).put("code", code),
                                )
                                busy = false
                                finish(r)
                            }
                        },
                        enabled = !busy && code.length == 6,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.acc_confirm)) }
                    TextButton(
                        onClick = {
                            error = ""
                            step = Step.Register
                        },
                    ) { Text(stringResource(R.string.ring_back)) }
                }

                Step.Done -> {
                    val p = session?.profile
                    if (p != null) {
                        val hashText = p.optString("hash")
                        val shown = p.optString("name").ifBlank { hashText }
                        Text(stringResource(R.string.acc_signed_as, shown), style = MaterialTheme.typography.titleLarge)
                        if (hashText.isNotBlank()) {
                            Text(
                                hashText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val referral = p.optString("referralCode")
                        if (referral.isNotBlank()) {
                            Text(stringResource(R.string.acc_referral, referral), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(stringResource(R.string.acc_pass_title), style = MaterialTheme.typography.labelLarge)
                        val passInfo = p.optJSONObject("pass")
                        Text(
                            if (passInfo != null) {
                                stringResource(
                                    R.string.acc_pass_until,
                                    passInfo.optString("tier").replaceFirstChar { it.uppercase() },
                                    DateFormat.getDateInstance().format(Date(passInfo.optLong("expiresAt"))),
                                )
                            } else {
                                stringResource(R.string.acc_pass_none)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    ErrorText(error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    refresh()
                                    busy = false
                                }
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.acc_refresh)) }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    session?.let { KanadeApi.call("/auth/logout", JSONObject(), it.token) }
                                    AccountStore.clear(ctx)
                                    session = null
                                    step = Step.Start
                                }
                            },
                        ) { Text(stringResource(R.string.acc_logout)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = modifier,
    )
}

@Composable
private fun ErrorText(error: String) {
    if (error.isNotBlank()) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}
