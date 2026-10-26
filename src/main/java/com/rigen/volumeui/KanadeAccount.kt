package com.rigen.volumeui

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.DateFormat
import java.text.NumberFormat
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

class BytesResult(val status: Int, val bytes: ByteArray?, val etag: String?)

object KanadeApi {
    /**
     * Public address of the bot (the ngrok static domain), e.g. "https://name.ngrok-free.dev".
     * It comes from api-url.txt in the repo root at build time. Empty means the bot on this same phone (Termux).
     */
    private val PUBLIC_BASE: String get() = BuildConfig.PUBLIC_BASE

    // Plain http is allowed to this address only (see network_security_config.xml).
    private const val LOCAL_BASE = "http://127.0.0.1:8787"

    private val remote: String?
        get() = PUBLIC_BASE.trim().trimEnd('/').takeIf { it.startsWith("https://") }

    private val BASE: String get() = remote ?: LOCAL_BASE

    suspend fun call(path: String, body: JSONObject? = null, token: String? = null): ApiResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(BASE + path).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = if (remote != null) 10000 else 4000
                c.readTimeout = 20000
                c.requestMethod = if (body == null) "GET" else "POST"
                c.setRequestProperty("Accept", "application/json")
                // ngrok shows a warning page to browsers; this tells it the caller is an app.
                c.setRequestProperty("ngrok-skip-browser-warning", "1")
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

    /** Downloads a file (the account photo) with the same sign-in token. Pass the last etag to skip an unchanged file. */
    suspend fun fetchBytes(path: String, token: String, etag: String? = null): BytesResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(BASE + path).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = if (remote != null) 10000 else 4000
                c.readTimeout = 20000
                c.requestMethod = "GET"
                c.setRequestProperty("ngrok-skip-browser-warning", "1")
                c.setRequestProperty("Authorization", "Bearer $token")
                if (etag != null) c.setRequestProperty("If-None-Match", etag)
                val code = c.responseCode
                val tooBig = c.contentLength > 3_000_000
                val bytes = if (code in 200..299 && !tooBig) c.inputStream.use { it.readBytes() } else null
                BytesResult(code, bytes, c.getHeaderField("ETag"))
            } catch (e: Exception) {
                BytesResult(0, null, null)
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
        AvatarStore.clearCache(c)
    }
}

// ───────────────────────── The screen ─────────────────────────

private enum class Step { Start, Login, Register, Code, Done }

private class DeviceItem(val id: String, val device: String, val created: Long, val last: Long, val current: Boolean)

private fun parseDevices(b: JSONObject?): List<DeviceItem> {
    val a = b?.optJSONArray("sessions") ?: return emptyList()
    return (0 until a.length()).map {
        val o = a.getJSONObject(it)
        DeviceItem(
            o.optString("id"), o.optString("device"), o.optLong("created"),
            o.optLong("last", o.optLong("created")), o.optBoolean("current"),
        )
    }
}

@Composable
fun AccountPage(onOpen: (String) -> Unit, onBack: () -> Unit) {
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
    var email by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var emailRequired by remember { mutableStateOf(false) }

    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }

    // Changing the email of an account that is already signed in
    var mailStep by remember { mutableStateOf(0) } // 0 closed, 1 new email + password, 2 code, 3 remove
    var mailError by remember { mutableStateOf("") }
    var chEmail by remember { mutableStateOf("") }
    var chPass by remember { mutableStateOf("") }
    var chAnswer by remember { mutableStateOf("") }
    var askQuestion by remember { mutableStateOf("") } // set when the bot asks for the security answer
    var chCode by remember { mutableStateOf("") }

    // What the bot says about the rewards and the prayer times, and which reminders are on.
    var rewards by remember { mutableStateOf<List<RewardState>>(emptyList()) }
    var prayers by remember { mutableStateOf<List<PrayerTime>>(emptyList()) }
    var prayerState by remember { mutableStateOf("") } // "" = loading, "none" = no location, "ok", "off"
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var enabledSet by remember { mutableStateOf(KanadeReminders.enabled(ctx)) }
    val askNotifications = rememberNotifPermission()

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
            emailCode = ""
            error = ""
            mailStep = 0
            chEmail = ""
            chPass = ""
            chAnswer = ""
            chCode = ""
            ClockWidgets.updateAll(ctx)
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
            KanadeReminders.reset(ctx)
            session = null
            step = Step.Start
        } else if (r.ok) {
            val p = r.body?.optJSONObject("profile")
            if (p != null) {
                val ns = Session(s.token, p)
                AccountStore.save(ctx, ns)
                session = ns
                askQuestion = ""
            }
        }
    }

    suspend fun loadTimers() {
        val s = session ?: return
        val t = KanadeApi.call("/me/timers", token = s.token)
        if (t.ok) {
            rewards = KanadeReminders.parseRewards(t.body)
            KanadeReminders.cacheRewards(ctx, rewards)
            ClockWidgets.updateAll(ctx)
            if (enabledSet.isNotEmpty()) KanadeReminders.scheduleRewards(ctx, rewards)
        }
        val p = KanadeApi.call("/me/prayer", token = s.token)
        if (p.ok) {
            prayers = KanadeReminders.parsePrayers(p.body)
            prayerState = KanadeReminders.prayerState(p.body, prayers)
            KanadeReminders.cachePrayers(ctx, prayerState, prayers)
            if ("prayer" in enabledSet) KanadeReminders.schedulePrayers(ctx, prayers)
        }
        val h = KanadeApi.call("/me/habits", token = s.token)
        if (h.ok) KanadeReminders.cacheHabits(ctx, KanadeReminders.parseHabits(h.body))
        ClockWidgets.updateAll(ctx)
        if (enabledSet.isNotEmpty()) KanadeReminders.scheduleSync(ctx)
    }

    fun toggle(id: String, on: Boolean) {
        KanadeReminders.setEnabled(ctx, id, on)
        enabledSet = KanadeReminders.enabled(ctx)
        if (on) {
            askNotifications()
            scope.launch { loadTimers() }
        } else {
            KanadeReminders.cancelItem(ctx, id)
            if (enabledSet.isEmpty()) KanadeReminders.cancelAll(ctx)
        }
    }

    fun applyProfile(r: ApiResult): Boolean {
        val pr = r.body?.optJSONObject("profile")
        val s = session
        if (r.ok && pr != null && s != null) {
            val ns = Session(s.token, pr)
            AccountStore.save(ctx, ns)
            session = ns
            askQuestion = ""
            return true
        }
        return false
    }

    // 401 = the bot no longer knows this session: back to the start.
    fun sessionExpired(r: ApiResult): Boolean {
        if (r.status != 401) return false
        AccountStore.clear(ctx)
        KanadeReminders.reset(ctx)
        session = null
        step = Step.Start
        return true
    }

    suspend fun loadDevices() {
        val r = KanadeApi.call("/me/sessions", token = session?.token)
        if (!sessionExpired(r) && r.ok) devices = parseDevices(r.body)
    }

    LaunchedEffect(step) {
        if (step == Step.Done) {
            refresh()
            loadTimers()
            loadDevices()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
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
                    Field(stringResource(R.string.acc_email), email, { email = it }, KeyboardType.Email)
                    Text(
                        stringResource(R.string.acc_email_hint),
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
                                val payload = JSONObject().put("name", name.trim()).put("birthdate", birth.trim())
                                    .put("hash", hash.trim()).put("phone", digits)
                                    .put("password", newPass).put("device", Build.MODEL)
                                if (email.isNotBlank()) payload.put("email", email.trim())
                                val r = KanadeApi.call("/auth/register/start", payload)
                                busy = false
                                val id = r.body?.optString("requestId").orEmpty()
                                if (r.ok && id.isNotEmpty()) {
                                    requestId = id
                                    emailRequired = r.body?.optBoolean("emailRequired") == true
                                    code = ""
                                    emailCode = ""
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
                        stringResource(if (emailRequired) R.string.acc_code_hint_both else R.string.acc_code_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Field(
                        stringResource(R.string.acc_code_title), code,
                        { code = it.filter { c -> c.isDigit() }.take(6) }, KeyboardType.Number,
                    )
                    if (emailRequired) {
                        Field(
                            stringResource(R.string.acc_email_code), emailCode,
                            { emailCode = it.filter { c -> c.isDigit() }.take(6) }, KeyboardType.Number,
                        )
                    }
                    ErrorText(error)
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = ""
                                val payload = JSONObject().put("requestId", requestId).put("code", code)
                                if (emailRequired) payload.put("emailCode", emailCode)
                                val r = KanadeApi.call("/auth/register/confirm", payload)
                                busy = false
                                finish(r)
                            }
                        },
                        enabled = !busy && code.length == 6 && (!emailRequired || emailCode.length == 6),
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
                        session?.let { s ->
                            AccountAvatarEditor(token = s.token, avatar = p.optJSONObject("avatar")) { av ->
                                val ns = Session(s.token, JSONObject(p.toString()).put("avatar", av))
                                AccountStore.save(ctx, ns)
                                session = ns
                            }
                        }
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
                        val rank = p.optString("rank")
                        Text(
                            stringResource(R.string.acc_level, p.optInt("level", 1)) +
                                if (rank.isNotBlank()) " \u2022 $rank" else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LinearProgressIndicator(
                            progress = { p.optInt("levelPercent") / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val nf = NumberFormat.getIntegerInstance()
                        Text(
                            stringResource(
                                R.string.acc_wallet,
                                nf.format(p.optLong("money")),
                                nf.format(p.optLong("diamond")),
                                nf.format(p.optLong("limit")),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                    OutlinedButton(
                        onClick = { onOpen(PAGE_HABITS) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.habits_open)) }
                    OutlinedButton(
                        onClick = { onOpen(PAGE_SUBSCRIPTION) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.sub_title)) }
                    OutlinedButton(
                        onClick = { onOpen(PAGE_LINKED) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.linked_open)) }
                    Section(stringResource(R.string.rem_section)) {
                        val labels = mapOf(
                            "daily" to R.string.rem_daily,
                            "treasure" to R.string.rem_treasure,
                            "weekly" to R.string.rem_weekly,
                            "monthly" to R.string.rem_monthly,
                        )
                        KanadeReminders.REWARD_IDS.forEach { id ->
                            val r = rewards.firstOrNull { it.id == id }
                            val status = when {
                                r == null -> "\u2026"
                                r.readyAt <= now -> stringResource(R.string.rem_ready)
                                else -> stringResource(R.string.rem_in, leftText(ctx, r.readyAt - now))
                            }
                            ReminderRow(stringResource(labels.getValue(id)), status, id in enabledSet) { toggle(id, it) }
                        }
                        val next = prayers.firstOrNull { it.at > now }
                        val prayerStatus = when {
                            prayerState == "" -> "\u2026"
                            prayerState == "none" -> stringResource(R.string.rem_prayer_setup)
                            next == null -> stringResource(R.string.rem_prayer_unavailable)
                            else -> next.name + " \u2022 " +
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(next.at)) + " \u2022 " +
                                stringResource(R.string.rem_in, leftText(ctx, next.at - now))
                        }
                        ReminderRow(stringResource(R.string.rem_prayer), prayerStatus, "prayer" in enabledSet) {
                            toggle("prayer", it)
                        }
                    }
                    Section(stringResource(R.string.acc_email_section)) {
                        val masked = session?.profile?.optString("email").orEmpty()
                        val question = askQuestion.ifBlank { session?.profile?.optString("question").orEmpty() }
                        Text(
                            if (masked.isNotBlank()) masked else stringResource(R.string.acc_email_none),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        when (mailStep) {
                            0 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        mailError = ""
                                        mailStep = 1
                                    },
                                ) {
                                    Text(stringResource(if (masked.isBlank()) R.string.acc_email_add else R.string.acc_email_change))
                                }
                                if (masked.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            mailError = ""
                                            mailStep = 3
                                        },
                                    ) { Text(stringResource(R.string.acc_email_remove)) }
                                }
                            }

                            1 -> {
                                Field(stringResource(R.string.acc_email_new), chEmail, { chEmail = it }, KeyboardType.Email)
                                Field(stringResource(R.string.acc_pass_confirm), chPass, { chPass = it }, secret = true)
                                if (question.isNotBlank()) {
                                    Text(
                                        stringResource(R.string.acc_question_label, question),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Field(stringResource(R.string.acc_answer), chAnswer, { chAnswer = it })
                                }
                                Text(
                                    stringResource(R.string.acc_email_change_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ErrorText(mailError)
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            mailError = ""
                                            val r = KanadeApi.call(
                                                "/me/email/start",
                                                JSONObject().put("email", chEmail.trim()).put("password", chPass).put("answer", chAnswer),
                                                session?.token,
                                            )
                                            busy = false
                                            if (sessionExpired(r)) return@launch
                                            if (r.ok) {
                                                chCode = ""
                                                mailStep = 2
                                            } else {
                                                if (r.body?.optBoolean("needAnswer") == true) {
                                                    askQuestion = r.body?.optString("question").orEmpty()
                                                }
                                                mailError = r.message(ctx)
                                            }
                                        }
                                    },
                                    enabled = !busy && chEmail.isNotBlank() && chPass.isNotEmpty() && (question.isBlank() || chAnswer.isNotBlank()),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.acc_email_send)) }
                                TextButton(
                                    onClick = {
                                        mailStep = 0
                                        chPass = ""
                                        chAnswer = ""
                                    },
                                ) { Text(stringResource(R.string.acc_cancel)) }
                            }

                            2 -> {
                                Text(
                                    stringResource(R.string.acc_email_code_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Field(
                                    stringResource(R.string.acc_email_code), chCode,
                                    { chCode = it.filter { c -> c.isDigit() }.take(6) }, KeyboardType.Number,
                                )
                                ErrorText(mailError)
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            mailError = ""
                                            val r = KanadeApi.call(
                                                "/me/email/confirm",
                                                JSONObject().put("code", chCode),
                                                session?.token,
                                            )
                                            busy = false
                                            if (sessionExpired(r)) return@launch
                                            if (applyProfile(r)) {
                                                mailStep = 0
                                                chEmail = ""
                                                chPass = ""
                                                chAnswer = ""
                                                chCode = ""
                                            } else {
                                                mailError = r.message(ctx)
                                            }
                                        }
                                    },
                                    enabled = !busy && chCode.length == 6,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.acc_confirm)) }
                                TextButton(
                                    onClick = {
                                        mailStep = 0
                                        chPass = ""
                                        chAnswer = ""
                                    },
                                ) { Text(stringResource(R.string.acc_cancel)) }
                            }

                            else -> {
                                Text(
                                    stringResource(R.string.acc_email_remove_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Field(stringResource(R.string.acc_pass_confirm), chPass, { chPass = it }, secret = true)
                                if (question.isNotBlank()) {
                                    Text(
                                        stringResource(R.string.acc_question_label, question),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Field(stringResource(R.string.acc_answer), chAnswer, { chAnswer = it })
                                }
                                ErrorText(mailError)
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            mailError = ""
                                            val r = KanadeApi.call(
                                                "/me/email/remove",
                                                JSONObject().put("password", chPass).put("answer", chAnswer),
                                                session?.token,
                                            )
                                            busy = false
                                            if (sessionExpired(r)) return@launch
                                            if (applyProfile(r)) {
                                                mailStep = 0
                                                chPass = ""
                                                chAnswer = ""
                                            } else {
                                                if (r.body?.optBoolean("needAnswer") == true) {
                                                    askQuestion = r.body?.optString("question").orEmpty()
                                                }
                                                mailError = r.message(ctx)
                                            }
                                        }
                                    },
                                    enabled = !busy && chPass.isNotEmpty() && (question.isBlank() || chAnswer.isNotBlank()),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.acc_email_remove)) }
                                TextButton(
                                    onClick = {
                                        mailStep = 0
                                        chPass = ""
                                        chAnswer = ""
                                    },
                                ) { Text(stringResource(R.string.acc_cancel)) }
                            }
                        }
                    }
                    Section(stringResource(R.string.acc_devices_section)) {
                        devices.forEach { d ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        d.device.ifBlank { stringResource(R.string.acc_device_unknown) } +
                                            if (d.current) " \u2022 " + stringResource(R.string.acc_device_this) else "",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.acc_device_last,
                                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.last)),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!d.current) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                val r = KanadeApi.call(
                                                    "/me/sessions/revoke",
                                                    JSONObject().put("id", d.id),
                                                    session?.token,
                                                )
                                                if (sessionExpired(r)) return@launch
                                                if (r.ok) devices = parseDevices(r.body) else error = r.message(ctx)
                                            }
                                        },
                                    ) { Text(stringResource(R.string.acc_device_disconnect)) }
                                }
                            }
                        }
                        if (devices.size > 1) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val r = KanadeApi.call("/me/sessions/revoke-others", JSONObject(), session?.token)
                                        if (sessionExpired(r)) return@launch
                                        if (r.ok) devices = parseDevices(r.body) else error = r.message(ctx)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.acc_devices_disconnect_all)) }
                        }
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
                                    KanadeReminders.reset(ctx)
                                    enabledSet = emptySet()
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
internal fun Field(
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
internal fun ErrorText(error: String) {
    if (error.isNotBlank()) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "2d 5h" or "5h 20m" for what is left. */
private fun leftText(c: Context, ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    val d = (minutes / 1440L).toInt()
    val h = ((minutes % 1440L) / 60L).toInt()
    val m = (minutes % 60L).toInt()
    return if (d > 0) c.getString(R.string.rem_dh, d, h) else c.getString(R.string.widget_hm, h, m)
}

@Composable
private fun ReminderRow(title: String, status: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
