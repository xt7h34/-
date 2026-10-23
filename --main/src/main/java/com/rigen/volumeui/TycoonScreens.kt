package com.rigen.volumeui

import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Kanade Tycoon: the screens. The whole game runs in the bot; this file only shows what the bot says
 * (it asks for the state every 1.5 seconds) and sends the player's commands.
 */

private val CAT_ORDER = listOf("phone", "earbuds")

private fun JSONArray?.objs(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONObject.list(key: String): List<JSONObject> = optJSONArray(key).objs()

internal fun tyMoney(v: Double): String {
    val a = abs(v)
    val sign = if (v < 0) "-" else ""
    return when {
        a >= 1_000_000 -> sign + String.format(java.util.Locale.US, "%.2fM", a / 1_000_000)
        a >= 10_000 -> sign + String.format(java.util.Locale.US, "%.1fK", a / 1_000)
        else -> sign + a.roundToInt().toString()
    }
}

private val MONTH_DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

private fun tyDate(day: Int): String {
    val year = 2020 + day / 365
    var d = day % 365
    var m = 0
    while (m < 11 && d >= MONTH_DAYS[m]) { d -= MONTH_DAYS[m]; m++ }
    return String.format(java.util.Locale.US, "%04d-%02d-%02d", year, m + 1, d + 1)
}

@Composable
private fun catName(cat: String): String = when (cat) {
    "phone" -> stringResource(R.string.ty_cat_phone)
    "earbuds" -> stringResource(R.string.ty_cat_earbuds)
    else -> cat
}

@Composable
private fun slotName(slot: String): String = when (slot) {
    "chip" -> stringResource(R.string.ty_slot_chip)
    "ram" -> stringResource(R.string.ty_slot_ram)
    "display" -> stringResource(R.string.ty_slot_display)
    "camera" -> stringResource(R.string.ty_slot_camera)
    "battery" -> stringResource(R.string.ty_slot_battery)
    "driver" -> stringResource(R.string.ty_slot_driver)
    "anc" -> stringResource(R.string.ty_slot_anc)
    else -> slot
}

@Composable
private fun eventName(type: String): String = when (type) {
    "ram_surge" -> stringResource(R.string.ty_ev_ram_surge)
    "ram_glut" -> stringResource(R.string.ty_ev_ram_glut)
    "chip_short" -> stringResource(R.string.ty_ev_chip_short)
    "display_short" -> stringResource(R.string.ty_ev_display_short)
    "boom" -> stringResource(R.string.ty_ev_boom)
    "slump" -> stringResource(R.string.ty_ev_slump)
    "rival_launch" -> stringResource(R.string.ty_ev_rival_launch)
    "strike" -> stringResource(R.string.ty_ev_strike)
    "viral" -> stringResource(R.string.ty_ev_viral)
    else -> type
}

@Composable
private fun logText(e: JSONObject): String = when (e.optString("type")) {
    "start" -> stringResource(R.string.ty_log_start)
    "event_start" -> stringResource(R.string.ty_log_event_start, eventName(e.optString("event")))
    "event_end" -> stringResource(R.string.ty_log_event_end, eventName(e.optString("event")))
    "research_done" -> stringResource(R.string.ty_log_research, catName(e.optString("cat")), slotName(e.optString("slot")), e.optInt("tier").toString())
    "order_done" -> stringResource(R.string.ty_log_order, e.optString("model"), e.optInt("units").toString())
    "bankrupt" -> stringResource(R.string.ty_log_bankrupt)
    else -> e.optString("type")
}

@Composable
private fun TyCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun TycoonPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val token = remember { AccountStore.load(ctx)?.token }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var expired by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var panel by remember { mutableIntStateOf(-1) }
    var builder by remember { mutableStateOf<String?>(null) }
    val popups = remember { mutableStateListOf<TyPopup>() }
    val now = rememberTyClock()
    val seen = remember { object { var sold = HashMap<Int, Int>(); var logKey = ""; var first = true; var id = 0L } }

    // The game is drawn wide, like a console game: landscape while this page is open.
    DisposableEffect(Unit) {
        val activity = ctx as? android.app.Activity
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (old != null) activity.requestedOrientation = old }
    }

    LaunchedEffect(token) {
        if (token == null) return@LaunchedEffect
        while (true) {
            val r = KanadeApi.call("/tycoon/state", token = token)
            when {
                r.ok && r.body != null -> { state = r.body; offline = false }
                r.status == 401 -> { expired = true }
                else -> offline = true
            }
            delay(1500)
        }
    }
    LaunchedEffect(message) {
        if (message.isNotEmpty()) {
            delay(3500)
            message = ""
        }
    }
    // Numbers that float up from the office when something happens: a sale, a finished research, an event.
    LaunchedEffect(state) {
        val view = state?.optJSONObject("company") ?: return@LaunchedEffect
        val t = now.value
        view.list("models").forEach { m ->
            val id = m.optInt("id")
            val sold = m.optInt("sold30")
            val before = seen.sold[id]
            seen.sold[id] = sold
            if (!seen.first && before != null && sold > before) {
                seen.id++
                popups.add(TyPopup(seen.id, "+" + tyMoney((sold - before) * m.optDouble("price")), TyColors.green, t, 0.35f + (seen.id % 5) * 0.06f, 0.35f))
            }
        }
        val last = view.list("log").lastOrNull()
        val key = last?.toString().orEmpty()
        if (!seen.first && key != seen.logKey && last != null) {
            when (last.optString("type")) {
                "research_done" -> { seen.id++; popups.add(TyPopup(seen.id, "+1 🎓", TyColors.cyan, t, 0.5f, 0.25f)) }
                "order_done" -> { seen.id++; popups.add(TyPopup(seen.id, "📦 ✓", TyColors.orange, t, 0.55f, 0.45f)) }
                "event_start" -> message = ctx.getString(R.string.ty_log_event_start, "") + last.optString("event").replace('_', ' ')
            }
        }
        seen.logKey = key
        seen.first = false
        popups.removeAll { now.value - it.born > 2000 }
    }

    fun act(body: JSONObject, onOk: (JSONObject) -> Unit) {
        val t = token ?: return
        scope.launch {
            val r = KanadeApi.call("/tycoon/act", body, t)
            r.body?.optJSONObject("state")?.let { state = it }
            if (r.ok) r.body?.let(onOk) else message = r.message(ctx)
        }
    }

    val s = state
    val view = s?.optJSONObject("company")
    val bankrupt = view?.optJSONObject("company")?.optBoolean("bankrupt") == true
    val inGame = s != null && view != null && !bankrupt && token != null && !expired

    Box(Modifier.fillMaxSize()) {
        when {
            token == null || expired -> TyBackdrop(TyColors.homeTop, TyColors.homeBottom) { Notice(stringResource(R.string.ty_need_signin)) }
            s == null -> TyBackdrop(TyColors.homeTop, TyColors.homeBottom) { Notice(stringResource(if (offline) R.string.ty_offline else R.string.ty_loading)) }
            s.isNull("company") -> TyBackdrop(TyColors.homeTop, TyColors.homeBottom) { CreateCompany(s, ::act) }
            bankrupt -> TyBackdrop(TyColors.homeTop, TyColors.homeBottom) { Bankrupt(s, ::act) }
            builder != null -> Builder(view!!, builder!!, now, ::act) { builder = null }
            else -> Game(s, view!!, now, popups, panel, { panel = it }, { builder = it }, ::act)
        }
        if (builder == null) {
            Text(
                "←", color = Color.White, fontSize = 26.sp,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(44.dp)
                    .clickable { if (inGame && panel >= 0) panel = -1 else onBack() }
                    .padding(top = 4.dp, start = 8.dp),
            )
        }
        if (message.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    message,
                    color = Color.White,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = 56.dp)
                        .background(Color(0xE61B2038), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}


@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
    }
}

/* ───────────── start a company ───────────── */

@Composable
private fun CreateCompany(s: JSONObject, act: (JSONObject, (JSONObject) -> Unit) -> Unit) {
    var owner by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var budget by remember { mutableIntStateOf(25) }
    val nero = s.optInt("nero")
    val budgets = s.optJSONArray("budgets")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: listOf(25, 50, 100)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TyCard {
            Text(stringResource(R.string.ty_create_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.ty_create_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(owner, { owner = it.take(24) }, label = { Text(stringResource(R.string.ty_your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(company, { company = it.take(24) }, label = { Text(stringResource(R.string.ty_company_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.ty_budget), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                budgets.forEach { b ->
                    FilterChip(selected = budget == b, onClick = { budget = b }, label = { Text("${b}M") })
                }
            }
            Text(stringResource(R.string.ty_budget_cost, budget.toString(), nero.toString()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    act(JSONObject().put("type", "create").put("owner", owner).put("company", company).put("budget", budget)) {}
                },
                enabled = owner.isNotBlank() && company.isNotBlank() && nero >= budget,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ty_start)) }
        }
    }
}

@Composable
private fun Bankrupt(s: JSONObject, act: (JSONObject, (JSONObject) -> Unit) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
        TyCard {
            Text(stringResource(R.string.ty_bankrupt_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.ty_bankrupt_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { act(JSONObject().put("type", "close")) {} }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ty_start_over))
            }
        }
    }
}

/* ───────────── the running company: the office, with a bar of icons below ───────────── */

private val NAV = listOf("🏠" to -1, "📱" to 1, "🔬" to 2, "🏭" to 3, "💰" to 4, "📊" to 0)

@Composable
private fun Game(
    s: JSONObject, view: JSONObject, now: State<Long>, popups: List<TyPopup>,
    panel: Int, setPanel: (Int) -> Unit, startBuilder: (String) -> Unit,
    act: (JSONObject, (JSONObject) -> Unit) -> Unit,
) {
    val co = view.optJSONObject("company") ?: return
    val cash by animateFloatAsState(co.optDouble("cash").toFloat(), tween(600), label = "cash")
    var pickCat by remember { mutableStateOf(false) }
    val cats = co.optJSONArray("cats")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("phone")
    val researching = co.optInt("lab") - co.optInt("labFree")
    val producing = view.list("orders").isNotEmpty()
    val stock = view.list("models").sumOf { it.optInt("stock") }
    val sold30 = view.list("models").sumOf { it.optInt("sold30") }

    TyBackdrop(TyColors.homeTop, TyColors.homeBottom) {
        TyOffice(
            lab = co.optInt("lab"), factory = co.optInt("factory"), researching = researching, producing = producing, nowState = now,
            modifier = Modifier.fillMaxSize().padding(top = 56.dp, bottom = 70.dp),
        )
        // top bar
        Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 56.dp, end = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            TyPill(tyMoney(cash.toDouble()), prefix = "💵")
            TyPill("$researching/${co.optInt("lab")}", prefix = "🎓")
            TyPill(tyDate(co.optInt("day")), prefix = "📅")
            TyPill(co.optString("name"), prefix = "🏢")
        }
        // round gauges on the right, like the original's progress circles
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Gauge(sold30.toString(), stringResource(R.string.ty_sold30), TyColors.green)
            Gauge(stock.toString(), stringResource(R.string.ty_stock), TyColors.orange)
            Gauge(co.optInt("buzz").toString(), stringResource(R.string.ty_buzz), TyColors.cyan)
        }
        // new device
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 18.dp).size(56.dp)
                .background(Color(0xFFFF6A4D), CircleShape)
                .clickable { if (cats.size == 1) startBuilder(cats[0]) else pickCat = true },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
        // bar of icons
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NAV.forEach { (icon, id) -> TyRoundButton(icon, { setPanel(id) }, selected = panel == id) }
        }
        TyPopups(popups, now)

        if (panel >= 0) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99060A1E)).clickable { setPanel(-1) },
            )
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.82f)
                    .fillMaxHeight(0.78f)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f), RoundedCornerShape(26.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                when (panel) {
                    0 -> HomeTab(s, view, co)
                    1 -> ProductsTab(view, co, act, startBuilder)
                    2 -> RndTab(view, co, act)
                    3 -> CompanyTab(view, co, act)
                    else -> FinanceTab(s, view, co, act)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (pickCat) {
            AlertDialog(
                onDismissRequest = { pickCat = false },
                title = { Text(stringResource(R.string.ty_tab_products)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        cats.forEach { c -> Button(onClick = { pickCat = false; startBuilder(c) }, modifier = Modifier.fillMaxWidth()) { Text(catName(c)) } }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { pickCat = false }) { Text(stringResource(R.string.ty_cancel)) } },
            )
        }
    }
}

@Composable
private fun Gauge(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (value.length > 4) 12.sp else 16.sp, maxLines = 1)
        }
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/* ───────────── designing a device: choose the parts, then the price ───────────── */

@Composable
private fun Builder(
    view: JSONObject, cat: String, now: State<Long>,
    act: (JSONObject, (JSONObject) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val token = remember { AccountStore.load(ctx)?.token }
    val slots = view.optJSONObject("cats")?.optJSONObject(cat)?.list("slots") ?: emptyList()
    var name by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(0) }
    var hue by remember { mutableStateOf(210f) }
    val picks = remember { mutableStateMapOf<String, Int>() }
    slots.forEach { sl -> if (picks[sl.optString("slot")] == null) picks[sl.optString("slot")] = sl.optInt("have") }
    var quote by remember { mutableStateOf<JSONObject?>(null) }
    var price by remember { mutableStateOf(0f) }
    var priceSet by remember { mutableStateOf(false) }

    val key = picks.toMap().toString()
    LaunchedEffect(key) {
        delay(120)
        val t = token ?: return@LaunchedEffect
        val comps = JSONObject()
        picks.forEach { (k, v) -> comps.put(k, v) }
        val r = KanadeApi.call("/tycoon/act", JSONObject().put("type", "quote").put("cat", cat).put("comps", comps), t)
        quote = r.body?.optJSONObject("quote")
    }
    val fair = quote?.optDouble("fair")?.toFloat() ?: 0f
    val unit = quote?.optDouble("unitCost")?.toFloat() ?: 0f
    if (!priceSet && fair > 0f) { price = fair; priceSet = true }

    if (step == 0) {
        TyBackdrop(TyColors.buildTop, TyColors.buildBottom) {
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                TyDevicePreview(cat, picks.toMap(), hue, now, Modifier.weight(0.8f).fillMaxHeight().padding(bottom = 64.dp))
                Column(Modifier.weight(1.2f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            name, { name = it.take(24) }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.ty_device_name)) },
                            modifier = Modifier.width(220.dp),
                        )
                        Text("  |  " + tyMoney(unit.toDouble()), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(210f, 0f, 130f, 40f, 280f, 330f).forEach { h ->
                            Box(
                                Modifier.size(if (hue == h) 30.dp else 24.dp).background(Color.hsv(h, 0.55f, 0.95f), CircleShape).clickable { hue = h },
                            )
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        slots.forEach { sl ->
                            val slot = sl.optString("slot")
                            val have = sl.optInt("have")
                            val cur = picks[slot] ?: have
                            Text(slotName(slot).uppercase(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            TyArrowRow(
                                stringResource(R.string.ty_gen_n, cur.toString()),
                                if (cur > 1) ({ picks[slot] = cur - 1 }) else null,
                                if (cur < have) ({ picks[slot] = cur + 1 }) else null,
                            )
                            Text(stringResource(R.string.ty_market_tier, String.format(java.util.Locale.US, "%.1f", sl.optDouble("e"))), color = Color(0xAAFFFFFF), fontSize = 11.sp)
                        }
                    }
                    quote?.let { q ->
                        Text(
                            stringResource(R.string.ty_quality, q.optDouble("q").roundToInt().toString()) + "   ·   " + stringResource(R.string.ty_design_fee, tyMoney(q.optDouble("fee"))),
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TyRoundButton("✕", onClose)
                        TyRoundButton("✓", { if (name.isNotBlank() && quote != null) step = 1 }, selected = name.isNotBlank() && quote != null)
                    }
                }
            }
        }
    } else {
        TyBackdrop(TyColors.priceTop, TyColors.priceBottom) {
            val profit = price - unit
            Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 64.dp, top = 16.dp)) {
                Text(stringResource(R.string.ty_unit_cost) + "  " + tyMoney(unit.toDouble()), color = Color(0xCCFFFFFF), fontSize = 15.sp)
                Text(stringResource(R.string.ty_price) + "  " + tyMoney(price.toDouble()), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(tyMoney(profit.toDouble()), color = if (profit >= 0) TyColors.green else TyColors.red, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.align(Alignment.Center).fillMaxWidth(0.6f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name.uppercase(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(stringResource(R.string.ty_price), color = Color(0xAAFFFFFF), fontSize = 14.sp)
                Text(tyMoney(price.toDouble()), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (fair > 0f) {
                    Slider(value = price.coerceIn(fair * 0.4f, fair * 2f), onValueChange = { price = it }, valueRange = (fair * 0.4f)..(fair * 2f))
                    val ratio = price / fair
                    val (label, color) = when {
                        ratio < 0.9f -> stringResource(R.string.ty_price_low) to TyColors.green
                        ratio <= 1.1f -> stringResource(R.string.ty_price_market) to TyColors.cyan
                        else -> stringResource(R.string.ty_price_high) to TyColors.orange
                    }
                    Text(label, color = color, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ty_fair, tyMoney(fair.toDouble())), color = Color(0xAAFFFFFF), fontSize = 12.sp)
                }
            }
            Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(24.dp)) { TyRoundButton("←", { step = 0 }) }
            Button(
                onClick = {
                    val comps = JSONObject()
                    picks.forEach { (k, v) -> comps.put(k, v) }
                    act(JSONObject().put("type", "model").put("cat", cat).put("name", name).put("comps", comps)) { r ->
                        act(JSONObject().put("type", "price").put("id", r.optInt("id")).put("price", price.toDouble().roundToInt())) { onClose() }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp),
            ) { Text(stringResource(R.string.ty_create).uppercase(), fontWeight = FontWeight.ExtraBold) }
        }
    }
}


@Composable
private fun HomeTab(s: JSONObject, view: JSONObject, co: JSONObject) {
    val stats = view.optJSONObject("stats")
    TyCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.ty_rev30), tyMoney(stats?.optDouble("rev30") ?: 0.0), Modifier.weight(1f))
            Stat(stringResource(R.string.ty_exp30), tyMoney(stats?.optDouble("exp30") ?: 0.0), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.ty_buzz), co.optInt("buzz").toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.ty_capacity), stringResource(R.string.ty_per_day, co.optInt("capacity").toString()), Modifier.weight(1f))
        }
    }
    val events = view.list("events")
    if (events.isNotEmpty()) {
        TyCard {
            SectionTitle(stringResource(R.string.ty_events))
            events.forEach { e ->
                val left = (e.optInt("until") - co.optInt("day")).coerceAtLeast(0)
                Text(eventName(e.optString("type")) + " · " + stringResource(R.string.ty_days_left, left.toString()))
            }
        }
    }
    val cats = view.optJSONObject("cats")
    CAT_ORDER.forEach { cat ->
        val share = cats?.optJSONObject(cat)?.optJSONObject("share") ?: return@forEach
        TyCard {
            SectionTitle(stringResource(R.string.ty_market, catName(cat)))
            val rows = mutableListOf(stringResource(R.string.ty_you) to share.optDouble("player"))
            share.optJSONObject("comps")?.let { c -> c.keys().forEach { k -> rows.add(k to c.optDouble(k)) } }
            rows.sortedByDescending { it.second }.forEach { (name, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.width(96.dp), fontWeight = if (name == stringResource(R.string.ty_you)) FontWeight.Bold else FontWeight.Normal)
                    LinearProgressIndicator(progress = { (v * 3).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                    Text(" ${(v * 100).roundToInt()}%", modifier = Modifier.width(48.dp))
                }
            }
        }
    }
    val log = view.list("log").reversed().take(10)
    if (log.isNotEmpty()) {
        TyCard {
            SectionTitle(stringResource(R.string.ty_news))
            log.forEach { e -> Text(tyDate(e.optInt("day")) + "  " + logText(e), fontSize = 13.sp) }
        }
    }
}

/* ───────────── products ───────────── */

@Composable
private fun ProductsTab(view: JSONObject, co: JSONObject, act: (JSONObject, (JSONObject) -> Unit) -> Unit, startBuilder: (String) -> Unit) {
    var pricing by remember { mutableStateOf<JSONObject?>(null) }
    var producing by remember { mutableStateOf<JSONObject?>(null) }

    val cats = co.optJSONArray("cats")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("phone")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cats.forEach { cat ->
            Button(onClick = { startBuilder(cat) }) { Text(stringResource(R.string.ty_new_device, catName(cat))) }
        }
    }
    val models = view.list("models")
    if (models.isEmpty()) Text(stringResource(R.string.ty_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
    models.filter { !it.optBoolean("discontinued") }.forEach { m ->
        TyCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.optString("name"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(catName(m.optString("cat")) + " · " + stringResource(R.string.ty_quality, m.optDouble("q").roundToInt().toString()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(tyMoney(m.optDouble("price")), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            val ratio = m.optDouble("ratio")
            val (label, color) = when {
                ratio < 0.9 -> stringResource(R.string.ty_price_low) to Color(0xFF5DE2B1)
                ratio <= 1.1 -> stringResource(R.string.ty_price_market) to Color(0xFF64D8FF)
                else -> stringResource(R.string.ty_price_high) to Color(0xFFFFB45E)
            }
            Text(label + " · " + stringResource(R.string.ty_fair, tyMoney(m.optDouble("fair"))), color = color, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.ty_stock), m.optInt("stock").toString(), Modifier.weight(1f))
                Stat(stringResource(R.string.ty_sold30), m.optInt("sold30").toString(), Modifier.weight(1f))
                Stat(stringResource(R.string.ty_unit_cost), tyMoney(m.optDouble("unitCost")), Modifier.weight(1f))
            }
            val queued = view.list("orders").filter { it.optInt("model") == m.optInt("id") }.sumOf { it.optInt("units") }
            if (queued > 0) Text(stringResource(R.string.ty_in_production, queued.toString()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { producing = m }) { Text(stringResource(R.string.ty_produce)) }
                OutlinedButton(onClick = { pricing = m }) { Text(stringResource(R.string.ty_set_price)) }
                TextButton(onClick = { act(JSONObject().put("type", "discontinue").put("id", m.optInt("id"))) {} }) { Text(stringResource(R.string.ty_discontinue)) }
            }
        }
    }
    pricing?.let { m -> PriceDialog(m, act) { pricing = null } }
    producing?.let { m -> ProduceDialog(m, act) { producing = null } }
}

@Composable
private fun PriceDialog(m: JSONObject, act: (JSONObject, (JSONObject) -> Unit) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(m.optDouble("price").roundToInt().toString()) }
    val fair = m.optDouble("fair")
    val value = text.toDoubleOrNull() ?: 0.0
    val ratio = if (fair > 0) value / fair else 0.0
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(m.optString("name")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() }.take(7) }, label = { Text(stringResource(R.string.ty_price)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                val (label, color) = when {
                    value <= 0 -> "" to Color.Unspecified
                    ratio < 0.9 -> stringResource(R.string.ty_price_low) to Color(0xFF5DE2B1)
                    ratio <= 1.1 -> stringResource(R.string.ty_price_market) to Color(0xFF64D8FF)
                    else -> stringResource(R.string.ty_price_high) to Color(0xFFFFB45E)
                }
                Text(label, color = color, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.ty_fair, tyMoney(fair)))
                TextButton(onClick = { text = fair.roundToInt().toString() }) { Text(stringResource(R.string.ty_use_market)) }
            }
        },
        confirmButton = {
            TextButton(enabled = value >= 1, onClick = { act(JSONObject().put("type", "price").put("id", m.optInt("id")).put("price", value)) { onClose() } }) {
                Text(stringResource(R.string.ty_save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ty_cancel)) } },
    )
}

@Composable
private fun ProduceDialog(m: JSONObject, act: (JSONObject, (JSONObject) -> Unit) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("500") }
    val units = text.toIntOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.ty_produce) + " · " + m.optString("name")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() }.take(5) }, label = { Text(stringResource(R.string.ty_units)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.ty_estimate, tyMoney(units * m.optDouble("unitCost"))))
                Text(stringResource(R.string.ty_pay_upfront), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = units >= 1, onClick = { act(JSONObject().put("type", "produce").put("id", m.optInt("id")).put("units", units)) { onClose() } }) {
                Text(stringResource(R.string.ty_produce))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ty_cancel)) } },
    )
}

/* ───────────── research ───────────── */

@Composable
private fun RndTab(view: JSONObject, co: JSONObject, act: (JSONObject, (JSONObject) -> Unit) -> Unit) {
    val day = co.optInt("day")
    Text(stringResource(R.string.ty_lab_info, (co.optInt("lab") - co.optInt("labFree")).toString(), co.optInt("lab").toString()), color = MaterialTheme.colorScheme.onSurfaceVariant)
    CAT_ORDER.forEach { cat ->
        val slots = view.optJSONObject("cats")?.optJSONObject(cat)?.list("slots") ?: return@forEach
        TyCard {
            SectionTitle(catName(cat))
            slots.forEach { sl ->
                val slot = sl.optString("slot")
                val have = sl.optInt("have")
                val e = sl.optDouble("e")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(slotName(slot), fontWeight = FontWeight.SemiBold)
                        val behind = e - have
                        val note = when {
                            have >= e + 0.5 -> stringResource(R.string.ty_ahead)
                            behind > 1.0 -> stringResource(R.string.ty_outdated)
                            else -> stringResource(R.string.ty_on_par)
                        }
                        Text(stringResource(R.string.ty_tier_line, have.toString(), String.format(java.util.Locale.US, "%.1f", e), note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val proj = sl.optJSONObject("project")
                    val next = sl.optJSONObject("next")
                    when {
                        proj != null -> {
                            val total = (proj.optInt("done") - proj.optInt("start")).coerceAtLeast(1)
                            val prog = ((day - proj.optInt("start")).toFloat() / total).coerceIn(0f, 1f)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(stringResource(R.string.ty_researching, proj.optInt("tier").toString()), fontSize = 12.sp)
                                LinearProgressIndicator(progress = { prog }, modifier = Modifier.width(96.dp))
                            }
                        }
                        next != null -> OutlinedButton(onClick = {
                            act(JSONObject().put("type", "research").put("cat", cat).put("slot", slot).put("tier", next.optInt("tier"))) {}
                        }) { Text(stringResource(R.string.ty_research_btn, next.optInt("tier").toString(), tyMoney(next.optDouble("cost")))) }
                        else -> Text(stringResource(R.string.ty_too_early), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/* ───────────── company: lab, factory, marketing, new categories ───────────── */

@Composable
private fun CompanyTab(view: JSONObject, co: JSONObject, act: (JSONObject, (JSONObject) -> Unit) -> Unit) {
    TyCard {
        SectionTitle(stringResource(R.string.ty_lab))
        Text(stringResource(R.string.ty_level, co.optInt("lab").toString()))
        if (!co.isNull("labCost")) {
            OutlinedButton(onClick = { act(JSONObject().put("type", "upgrade").put("what", "lab")) {} }) {
                Text(stringResource(R.string.ty_upgrade, tyMoney(co.optDouble("labCost"))))
            }
        } else Text(stringResource(R.string.ty_max_level))
    }
    TyCard {
        SectionTitle(stringResource(R.string.ty_factory))
        Text(stringResource(R.string.ty_level, co.optInt("factory").toString()) + " · " + stringResource(R.string.ty_per_day, co.optInt("capacity").toString()))
        if (!co.isNull("factoryCost")) {
            OutlinedButton(onClick = { act(JSONObject().put("type", "upgrade").put("what", "factory")) {} }) {
                Text(stringResource(R.string.ty_upgrade, tyMoney(co.optDouble("factoryCost"))))
            }
        } else Text(stringResource(R.string.ty_max_level))
        val orders = view.list("orders")
        if (orders.isNotEmpty()) {
            val names = view.list("models").associate { it.optInt("id") to it.optString("name") }
            orders.forEach { o ->
                val left = (o.optInt("done") - co.optInt("day")).coerceAtLeast(0)
                Text((names[o.optInt("model")] ?: "") + " · " + o.optInt("units") + " · " + stringResource(R.string.ty_days_left, left.toString()), fontSize = 13.sp)
            }
        }
    }
    TyCard {
        SectionTitle(stringResource(R.string.ty_marketing))
        Text(stringResource(R.string.ty_buzz) + ": " + co.optInt("buzz"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val camp = view.optJSONObject("campaigns")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("small" to R.string.ty_camp_small, "medium" to R.string.ty_camp_medium, "large" to R.string.ty_camp_large).forEach { (k, label) ->
                OutlinedButton(onClick = { act(JSONObject().put("type", "campaign").put("size", k)) {} }) {
                    Text(stringResource(label) + " " + tyMoney(camp?.optDouble(k) ?: 0.0))
                }
            }
        }
    }
    val unlock = co.list("unlockable")
    if (unlock.isNotEmpty()) {
        TyCard {
            SectionTitle(stringResource(R.string.ty_new_dept))
            unlock.forEach { u ->
                Button(onClick = { act(JSONObject().put("type", "unlock").put("cat", u.optString("cat"))) {} }) {
                    Text(catName(u.optString("cat")) + " · " + tyMoney(u.optDouble("cost")))
                }
            }
        }
    }
}

/* ───────────── finance: game money <-> Nero ───────────── */

@Composable
private fun FinanceTab(s: JSONObject, view: JSONObject, co: JSONObject, act: (JSONObject, (JSONObject) -> Unit) -> Unit) {
    var inText by remember { mutableStateOf("") }
    var outText by remember { mutableStateOf("") }
    val ex = view.optJSONObject("exchange")
    val fee = ((ex?.optDouble("fee") ?: 0.1) * 100).roundToInt()
    val left = ex?.optInt("left") ?: 0
    val cap = ex?.optInt("dailyCap") ?: 100
    TyCard {
        SectionTitle(stringResource(R.string.ty_exchange))
        Text(stringResource(R.string.ty_rate), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(stringResource(R.string.ty_cash), tyMoney(co.optDouble("cash")), Modifier.weight(1f))
            Stat(stringResource(R.string.ty_nero), s.optInt("nero").toString(), Modifier.weight(1f))
        }
    }
    TyCard {
        SectionTitle(stringResource(R.string.ty_cash_in))
        OutlinedTextField(inText, { inText = it.filter { c -> c.isDigit() }.take(6) }, label = { Text(stringResource(R.string.ty_nero)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Button(
            enabled = (inText.toIntOrNull() ?: 0) >= 1,
            onClick = { act(JSONObject().put("type", "cashin").put("amount", inText.toInt())) { inText = "" } },
        ) { Text(stringResource(R.string.ty_cash_in_btn)) }
    }
    TyCard {
        SectionTitle(stringResource(R.string.ty_cash_out))
        Text(stringResource(R.string.ty_cashout_info, fee.toString(), left.toString(), cap.toString()), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(outText, { outText = it.filter { c -> c.isDigit() }.take(6) }, label = { Text(stringResource(R.string.ty_amount_millions)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        val n = outText.toIntOrNull() ?: 0
        if (n >= 1) Text(stringResource(R.string.ty_you_get, ((n * (100 - fee)) / 100).toString()))
        Button(
            enabled = n >= 1,
            onClick = { act(JSONObject().put("type", "cashout").put("amount", n)) { outText = "" } },
        ) { Text(stringResource(R.string.ty_cash_out_btn)) }
    }
}
