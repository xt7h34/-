package com.rigen.volumeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private class ChTop(val name: String, val count: Int, val me: Boolean)

private class ChInfo(
    val group: String,
    val groupName: String,
    val habit: String,
    val prize: Int,
    val endsAt: Long,
    val myCount: Int,
    val checkedToday: Boolean,
    val total: Int,
    val top: List<ChTop>,
)

private class ChGroup(val id: String, val name: String)

private fun parseChallenges(b: JSONObject?): List<ChInfo> {
    val a = b?.optJSONArray("challenges") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val t = o.optJSONArray("top")
        ChInfo(
            group = o.optString("group"),
            groupName = o.optString("groupName"),
            habit = o.optString("habit"),
            prize = o.optInt("prize"),
            endsAt = o.optLong("endsAt"),
            myCount = o.optInt("myCount"),
            checkedToday = o.optBoolean("checkedToday"),
            total = o.optInt("total"),
            top = if (t == null) emptyList() else (0 until t.length()).mapNotNull { j ->
                t.optJSONObject(j)?.let { ChTop(it.optString("name"), it.optInt("count"), it.optBoolean("me")) }
            },
        )
    }
}

private fun parseGroups(b: JSONObject?): List<ChGroup> {
    val a = b?.optJSONArray("groups") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { ChGroup(it.optString("id"), it.optString("name")) } }
}

/**
 * The habit challenges that run in your WhatsApp groups. It shows the same data the bot's commands use
 * (`.تحدي_عاده` and `.تحدي_تشك`), so a check made here counts there, and the other way round.
 */
@Composable
fun ChallengesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(AccountStore.load(ctx)) }
    var challenges by remember { mutableStateOf<List<ChInfo>>(emptyList()) }
    var groups by remember { mutableStateOf<List<ChGroup>>(emptyList()) }
    var online by remember { mutableStateOf(true) }
    var prizeCap by remember { mutableStateOf(500) }
    var nero by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<ChGroup?>(null) }
    var habit by remember { mutableStateOf("") }
    var prize by remember { mutableStateOf("") }

    suspend fun load() {
        val s = session ?: return
        val r = KanadeApi.call("/me/challenges", token = s.token)
        if (r.status == 401) {
            AccountStore.clear(ctx)
            session = null
            return
        }
        if (r.ok) {
            challenges = parseChallenges(r.body)
            groups = parseGroups(r.body)
            online = r.body?.optBoolean("online", true) ?: true
            prizeCap = r.body?.optInt("prizeCap", 500) ?: 500
            nero = r.body?.optInt("nero") ?: 0
            error = ""
        } else error = r.message(ctx)
        loaded = true
    }

    // Reads again every 30 seconds, so a check made from WhatsApp shows up here too.
    LaunchedEffect(session) {
        while (session != null) {
            load()
            delay(30_000)
        }
    }

    fun post(path: String, payload: JSONObject, onOk: () -> Unit) {
        scope.launch {
            busy = true
            error = ""
            info = ""
            val r = KanadeApi.call(path, payload, session?.token)
            busy = false
            if (r.status == 401) {
                AccountStore.clear(ctx)
                session = null
            } else if (r.ok) {
                onOk()
                load()
            } else error = r.message(ctx)
        }
    }

    PageScaffold(stringResource(R.string.chl_title), onBack) { padding ->
        PageColumn(padding) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (session == null) {
                Text(stringResource(R.string.habit_signin), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.chl_info), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!online) Text(stringResource(R.string.chl_offline), color = MaterialTheme.colorScheme.error)
                if (info.isNotBlank()) Text(info, color = MaterialTheme.colorScheme.primary)
                ErrorText(error)

                if (loaded && challenges.isEmpty()) Text(stringResource(R.string.chl_none))
                challenges.forEach { c ->
                    ChallengeCard(c, busy) {
                        post("/me/challenge/checkin", JSONObject().put("group", c.group)) {
                            info = ctx.getString(R.string.chl_checked)
                        }
                    }
                }

                if (starting) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.chl_new), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            if (groups.isEmpty()) {
                                Text(stringResource(R.string.chl_no_groups))
                            } else {
                                Text(stringResource(R.string.chl_pick_group), style = MaterialTheme.typography.labelLarge)
                                groups.forEach { g ->
                                    FilterChip(
                                        selected = pick?.id == g.id,
                                        onClick = { pick = g },
                                        label = { Text(g.name.ifBlank { g.id }) },
                                    )
                                }
                                Field(stringResource(R.string.chl_habit), habit, { habit = it.take(40) })
                                Field(
                                    stringResource(R.string.chl_prize, prizeCap),
                                    prize, { prize = it.filter { c -> c.isDigit() }.take(4) },
                                    keyboard = KeyboardType.Number,
                                )
                                Text(stringResource(R.string.chl_prize_note, nero), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !busy && pick != null && habit.isNotBlank(),
                                    onClick = {
                                        val g = pick ?: return@Button
                                        post(
                                            "/me/challenge/start",
                                            JSONObject().put("group", g.id).put("habit", habit.trim()).put("prize", prize.toIntOrNull() ?: 0),
                                        ) {
                                            starting = false
                                            habit = ""
                                            prize = ""
                                            pick = null
                                            info = ctx.getString(R.string.chl_started)
                                        }
                                    },
                                ) { Text(stringResource(R.string.chl_start)) }
                                TextButton(onClick = { starting = false; error = "" }) { Text(stringResource(R.string.acc_cancel)) }
                            }
                        }
                    }
                } else {
                    OutlinedButton(onClick = { starting = true; info = ""; error = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chl_new))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChallengeCard(c: ChInfo, busy: Boolean, onCheck: () -> Unit) {
    val ctx = LocalContext.current
    val left = (c.endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
    val days = (left / 86_400_000L).toInt()
    val hours = ((left % 86_400_000L) / 3_600_000L).toInt()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.habit, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (c.groupName.isNotBlank()) {
                Text(c.groupName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                ctx.getString(R.string.chl_left, days, hours) + if (c.prize > 0) "  •  " + ctx.getString(R.string.chl_prize_line, c.prize) else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(ctx.getString(R.string.chl_my_days, c.myCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { (c.myCount / 7f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            c.top.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${i + 1}. ${t.name}",
                        modifier = Modifier.weight(1f),
                        fontWeight = if (t.me) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(ctx.getString(R.string.chl_days_n, t.count))
                }
            }
            if (c.checkedToday) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chl_done_today)) }
            } else {
                Button(onClick = onCheck, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chl_check)) }
            }
        }
    }
}
