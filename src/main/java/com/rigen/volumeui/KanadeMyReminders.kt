package com.rigen.volumeui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** One reminder from the bot. [channel] is "both", "app" or "wa". [repeat] is 0, 1 or 7 days. */
class MyReminder(
    val id: String,
    val reason: String,
    val dueAt: Long,
    val channel: String,
    val repeat: Int,
    val legacy: Boolean,
)

/**
 * The reminders you make with the bot's `.ذكرني` or in this app. They live in the bot, so both sides see
 * the same list. For every reminder that should reach the app there is a local alarm, so it still rings
 * if Termux is closed. A repeating reminder sets its next alarm by itself when it rings.
 */
object MyReminders {
    const val ACTION = "com.kanade.reminder.MYREM"
    private const val PREF = "kanade_myrem"
    private const val RC_BASE = 941000
    private const val DAY_MS = 86_400_000L

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun parse(b: JSONObject?): List<MyReminder> {
        val a = b?.optJSONArray("reminders") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            MyReminder(
                o.optString("id"), o.optString("reason"), o.optLong("dueAt"),
                o.optString("channel", "both"), o.optInt("repeat"), o.optBoolean("legacy"),
            )
        }
    }

    private fun save(c: Context, list: List<MyReminder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("r", it.reason).put("at", it.dueAt)
                    .put("ch", it.channel).put("rp", it.repeat).put("lg", it.legacy),
            )
        }
        sp(c).edit().putString("list", arr.toString()).apply()
    }

    fun cached(c: Context): List<MyReminder> = try {
        val a = JSONArray(sp(c).getString("list", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            MyReminder(o.optString("id"), o.optString("r"), o.optLong("at"), o.optString("ch", "both"), o.optInt("rp"), o.optBoolean("lg"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun any(c: Context): Boolean = cached(c).any { it.channel != "wa" }

    private fun usedRcs(c: Context): Set<Int> =
        (sp(c).getStringSet("rcs", emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()

    private fun pending(c: Context, rc: Int, r: MyReminder?, at: Long): PendingIntent =
        PendingIntent.getBroadcast(
            c, rc,
            Intent(c, KanadeReminderReceiver::class.java).setAction(ACTION)
                .putExtra("id", r?.id.orEmpty()).putExtra("reason", r?.reason.orEmpty())
                .putExtra("at", at).putExtra("repeat", r?.repeat ?: 0).putExtra("rc", rc),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Saves the list the bot gave and sets one alarm for each reminder that should ring on this phone. */
    fun apply(c: Context, list: List<MyReminder>) {
        save(c, list)
        schedule(c, list)
    }

    fun reschedule(c: Context) = schedule(c, cached(c))

    private fun schedule(c: Context, list: List<MyReminder>) {
        val am: AlarmManager = KanadeReminders.alarms(c)
        usedRcs(c).forEach { am.cancel(pending(c, it, null, 0)) }
        val now = System.currentTimeMillis()
        val used = mutableSetOf<Int>()
        list.filter { it.channel != "wa" && !it.legacy && it.dueAt > now }.take(40).forEachIndexed { i, r ->
            val rc = RC_BASE + i
            KanadeReminders.setAt(c, r.dueAt, pending(c, rc, r, r.dueAt))
            used.add(rc)
        }
        sp(c).edit().putStringSet("rcs", used.map { it.toString() }.toSet()).apply()
    }

    /** The alarm went off: show it, then set the next one if it repeats. */
    fun onFire(c: Context, intent: Intent) {
        val id = intent.getStringExtra("id").orEmpty()
        val reason = intent.getStringExtra("reason").orEmpty()
        val at = intent.getLongExtra("at", 0L)
        val repeat = intent.getIntExtra("repeat", 0)
        val rc = intent.getIntExtra("rc", RC_BASE)
        KanadeReminders.notify(c, "myrem", reason)
        val list = cached(c)
        if (repeat > 0 && at > 0) {
            var next = at + repeat * DAY_MS
            val now = System.currentTimeMillis()
            while (next <= now) next += repeat * DAY_MS
            val r = list.firstOrNull { it.id == id }
            val updated = MyReminder(id, reason, next, r?.channel ?: "both", repeat, false)
            KanadeReminders.setAt(c, next, pending(c, rc, updated, next))
            save(c, list.filter { it.id != id } + updated)
        } else {
            save(c, list.filter { it.id != id })
        }
    }

    suspend fun sync(c: Context, token: String): ApiResult {
        val r = KanadeApi.call("/me/reminders", token = token)
        if (r.ok) apply(c, parse(r.body))
        return r
    }

    /** Signing out: no alarms and no saved list. */
    fun reset(c: Context) {
        val am: AlarmManager = KanadeReminders.alarms(c)
        usedRcs(c).forEach { am.cancel(pending(c, it, null, 0)) }
        sp(c).edit().clear().apply()
    }
}

// ───────────────────────────── The page ─────────────────────────────

private val UNITS = listOf(1 to R.string.myrem_unit_min, 60 to R.string.myrem_unit_hour, 1440 to R.string.myrem_unit_day)

@Composable
fun MyRemindersPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(AccountStore.load(ctx)) }
    var list by remember { mutableStateOf(MyReminders.cached(ctx)) }
    var max by remember { mutableIntStateOf(20) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("30") }
    var unit by remember { mutableIntStateOf(1) }
    var repeat by remember { mutableIntStateOf(0) }
    var channel by remember { mutableStateOf("both") }

    fun take(r: ApiResult): Boolean {
        if (r.status == 401) {
            AccountStore.clear(ctx)
            KanadeReminders.reset(ctx)
            session = null
            return false
        }
        if (!r.ok) {
            error = r.message(ctx)
            return false
        }
        val l = MyReminders.parse(r.body)
        MyReminders.apply(ctx, l)
        list = l
        max = r.body?.optInt("max", 20) ?: 20
        error = ""
        KanadeReminders.scheduleSync(ctx)
        return true
    }

    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        take(KanadeApi.call("/me/reminders", token = s.token))
        loaded = true
    }

    fun post(path: String, payload: JSONObject, onOk: () -> Unit = {}) {
        scope.launch {
            busy = true
            error = ""
            val r = KanadeApi.call(path, payload, session?.token)
            busy = false
            if (take(r)) onOk()
        }
    }

    PageScaffold(stringResource(R.string.myrem_title), onBack) { padding ->
        PageColumn(padding) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (session == null) {
                Text(stringResource(R.string.habit_signin), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.myrem_info), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErrorText(error)

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.myrem_new), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Field(stringResource(R.string.myrem_text), reason, { reason = it.take(120) })
                        Text(stringResource(R.string.myrem_after), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Field(
                                stringResource(R.string.myrem_amount), amount, { amount = it.filter { c -> c.isDigit() }.take(5) },
                                keyboard = KeyboardType.Number, modifier = Modifier.width(110.dp),
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                UNITS.forEach { (mult, label) ->
                                    FilterChip(selected = unit == mult, onClick = { unit = mult }, label = { Text(stringResource(label)) })
                                }
                            }
                        }
                        Text(stringResource(R.string.myrem_repeat), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to R.string.myrem_once, 1 to R.string.myrem_daily, 7 to R.string.myrem_weekly).forEach { (v, label) ->
                                FilterChip(selected = repeat == v, onClick = { repeat = v }, label = { Text(stringResource(label)) })
                            }
                        }
                        Text(stringResource(R.string.myrem_notify), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("both" to R.string.myrem_both, "app" to R.string.myrem_app, "wa" to R.string.myrem_wa).forEach { (v, label) ->
                                FilterChip(selected = channel == v, onClick = { channel = v }, label = { Text(stringResource(label)) })
                            }
                        }
                        val minutes = (amount.toLongOrNull() ?: 0L) * unit
                        Button(
                            enabled = !busy && reason.isNotBlank() && minutes in 1..43200,
                            onClick = {
                                post(
                                    "/me/reminders/add",
                                    JSONObject().put("reason", reason.trim()).put("minutes", minutes)
                                        .put("channel", channel).put("repeat", repeat),
                                ) { reason = "" }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.myrem_add)) }
                        if (minutes > 43200) Text(stringResource(R.string.myrem_too_long), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (loaded && list.isEmpty()) Text(stringResource(R.string.myrem_none))
                val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                list.forEach { r ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(r.reason, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(fmt.format(Date(r.dueAt)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                val where = when (r.channel) {
                                    "app" -> stringResource(R.string.myrem_app)
                                    "wa" -> stringResource(R.string.myrem_wa)
                                    else -> stringResource(R.string.myrem_both)
                                }
                                val again = when (r.repeat) {
                                    1 -> " • " + stringResource(R.string.myrem_daily)
                                    7 -> " • " + stringResource(R.string.myrem_weekly)
                                    else -> ""
                                }
                                Text(where + again, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                enabled = !busy,
                                onClick = { post("/me/reminders/delete", JSONObject().put("id", r.id)) },
                            ) { Text(stringResource(R.string.myrem_delete)) }
                        }
                    }
                }
            }
        }
    }
}
