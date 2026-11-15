package com.rigen.volumeui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

/** The colour of each kind of line, used for the dots in the month and the bar in the day. */
private fun calColor(type: Int): Color = when (type) {
    CT_EVENT -> Color(0xFF0A84FF)
    CT_OCCASION -> Color(0xFFFF9F0A)
    CT_REMINDER -> Color(0xFFBF5AF2)
    CT_BILL -> Color(0xFFFF453A)
    CT_ALARM -> Color(0xFF8E8E93)
    CT_PRAYER -> Color(0xFF30D158)
    else -> Color(0xFF64D2FF)
}

@Composable
private fun calTypeName(type: Int): String = stringResource(
    when (type) {
        CT_EVENT -> R.string.cal_kind_event
        CT_OCCASION -> R.string.cal_kind_occasion
        CT_REMINDER -> R.string.cal_kind_reminder
        CT_BILL -> R.string.cal_kind_bill
        CT_ALARM -> R.string.cal_alarm
        CT_PRAYER -> R.string.cal_prayer
        else -> R.string.cal_goal_due
    },
)

@Composable
fun CalendarPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = CalendarStore.tick
    val snap = remember(tick) { CalendarEngine.snapshot(ctx) }
    val locale = ctx.resources.configuration.locales[0]
    val today = LocalDate.now()
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var dayKey by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthKey)
    val selected = LocalDate.parse(dayKey)
    var editor by remember { mutableStateOf<CalEvent?>(null) }
    var editorIsNew by remember { mutableStateOf(false) }
    var wheel by rememberSaveable { mutableStateOf(false) }
    val pick = { d: LocalDate ->
        dayKey = d.toString()
        monthKey = YearMonth.from(d).toString()
    }

    PageScaffold(stringResource(R.string.cal_title), onBack) { pad ->
        PageColumn(pad) {
            MonthHeader(
                month = if (wheel) YearMonth.from(selected) else month,
                locale = locale,
                wheel = wheel,
                onToggleView = { wheel = !wheel },
                onPrev = {
                    if (wheel) {
                        pick(selected.minusMonths(1))
                    } else {
                        monthKey = month.minusMonths(1).toString()
                    }
                },
                onNext = {
                    if (wheel) {
                        pick(selected.plusMonths(1))
                    } else {
                        monthKey = month.plusMonths(1).toString()
                    }
                },
                onToday = { pick(today) },
            )
            if (wheel) {
                ArcDateWheel(selected, locale) { pick(it) }
            } else {
                MonthGrid(month, selected, today, locale, snap) { pick(it) }
            }
            DayPanel(
                day = selected,
                locale = locale,
                snap = snap,
                onAdd = {
                    editor = newEvent(selected)
                    editorIsNew = true
                },
                onEdit = { id ->
                    snap.events.firstOrNull { it.id == id }?.let {
                        editor = it
                        editorIsNew = false
                    }
                },
            )
        }
    }
    editor?.let { EventEditor(it, editorIsNew) { editor = null } }
}

private fun newEvent(day: LocalDate): CalEvent {
    val now = System.currentTimeMillis()
    val start = if (day == LocalDate.now()) {
        // The next full hour.
        (now / 3_600_000L + 1) * 3_600_000L
    } else {
        CalendarEngine.startMs(day) + 9 * 3_600_000L
    }
    return CalEvent(
        id = CalendarStore.newId(),
        title = "",
        notes = "",
        start = start,
        end = start + 3_600_000L,
        allDay = false,
        kind = CAL_EVENT,
        repeat = REP_NONE,
        remindBefore = 10,
    )
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    locale: Locale,
    wheel: Boolean,
    onToggleView: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val flip = if (rtl) Modifier.scale(-1f, 1f) else Modifier
    val tint = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { onPrev() }, contentAlignment = Alignment.Center) {
            PathIcon(Glyph.PREVIOUS.pathData, tint, 24.dp, flip)
        }
        Text(
            month.month.getDisplayName(TextStyle.FULL, locale) + " " + month.year,
            Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onToggleView) {
            Text(stringResource(if (wheel) R.string.cal_view_month else R.string.cal_view_wheel))
        }
        TextButton(onClick = onToday) { Text(stringResource(R.string.cal_today)) }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { onNext() }, contentAlignment = Alignment.Center) {
            PathIcon(Glyph.NEXT.pathData, tint, 24.dp, flip)
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    locale: Locale,
    snap: CalSnapshot,
    onPick: (LocalDate) -> Unit,
) {
    val first: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val len = month.lengthOfMonth()
    val offset = (month.atDay(1).dayOfWeek.value - first.value + 7) % 7
    val rows = (offset + len + 6) / 7
    val byDay = remember(snap, month) { (1..len).associateWith { snap.itemsFor(month.atDay(it)) } }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    Text(
                        first.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val day = r * 7 + c - offset + 1
                        if (day in 1..len) {
                            val date = month.atDay(day)
                            DayCell(
                                day = day,
                                isSelected = date == selected,
                                isToday = date == today,
                                types = (byDay[day] ?: emptyList()).map { it.type }.distinct().take(4),
                            ) { onPick(date) }
                        } else {
                            Spacer(Modifier.weight(1f).height(48.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(day: Int, isSelected: Boolean, isToday: Boolean, types: List<Int>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        isSelected -> scheme.primary
        isToday -> scheme.primaryContainer
        else -> Color.Transparent
    }
    val fg = when {
        isSelected -> scheme.onPrimary
        isToday -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    Box(
        Modifier
            .weight(1f)
            .height(48.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.toString(), color = fg, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                types.forEach {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(calColor(it)))
                }
            }
        }
    }
}

@Composable
private fun DayPanel(
    day: LocalDate,
    locale: Locale,
    snap: CalSnapshot,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val items = remember(snap, day) { snap.itemsFor(day) }
    val dateText = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    val hijri = remember(day) { CalendarEngine.hijriLabel(ctx, day) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(dateText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (hijri.isNotEmpty()) {
                    Text(hijri, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.cal_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (item.eventId.isNotEmpty()) Modifier.clickable { onEdit(item.eventId) } else Modifier)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (item.allDay) stringResource(R.string.cal_all_day_short)
                        else android.text.format.DateFormat.getTimeFormat(ctx).format(Date(item.at)),
                        Modifier.width(64.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(calColor(item.type)))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                        val sub = if (item.sub.isNotBlank()) item.sub else calTypeName(item.type)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cal_add)) }
        }
    }
}

@Composable
internal fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
internal fun EventEditor(
    initial: CalEvent,
    isNew: Boolean,
    warning: String? = null,
    onSaved: (CalEvent) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var notes by remember { mutableStateOf(initial.notes) }
    var start by remember { mutableLongStateOf(initial.start) }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var kind by remember { mutableIntStateOf(initial.kind) }
    var repeat by remember { mutableIntStateOf(initial.repeat) }
    var remind by remember { mutableIntStateOf(initial.remindBefore) }
    var endDay by remember {
        mutableLongStateOf(
            if (initial.end - initial.start >= CAL_MULTIDAY_MS) CalendarEngine.startOfDay(initial.end)
            else CalendarEngine.startOfDay(initial.start),
        )
    }

    val kinds = listOf(
        CAL_EVENT to stringResource(R.string.cal_kind_event),
        CAL_OCCASION to stringResource(R.string.cal_kind_occasion),
        CAL_REMINDER to stringResource(R.string.cal_kind_reminder),
        CAL_BILL to stringResource(R.string.cal_kind_bill),
    )
    val repeats = listOf(
        REP_NONE to stringResource(R.string.cal_repeat_none),
        REP_DAILY to stringResource(R.string.cal_repeat_daily),
        REP_WEEKLY to stringResource(R.string.cal_repeat_weekly),
        REP_MONTHLY to stringResource(R.string.cal_repeat_monthly),
        REP_YEARLY to stringResource(R.string.cal_repeat_yearly),
    )
    val reminds = listOf(
        -1 to stringResource(R.string.cal_remind_none),
        0 to stringResource(R.string.cal_remind_at),
        10 to stringResource(R.string.cal_remind_10m),
        60 to stringResource(R.string.cal_remind_1h),
        1440 to stringResource(R.string.cal_remind_1d),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.cal_add else R.string.cal_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (warning != null) {
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.cal_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.cal_notes_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipRow(kinds, kind) {
                    kind = it
                    if (it == CAL_OCCASION && isNew) {
                        // Birthdays and anniversaries: the whole day, every year.
                        allDay = true
                        repeat = REP_YEARLY
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cal_all_day), Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            calPickDate(ctx, start) { midnight ->
                                start = midnight + (start - CalendarEngine.startOfDay(start))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(start))) }
                    if (!allDay) {
                        OutlinedButton(
                            onClick = {
                                val mins = ((start - CalendarEngine.startOfDay(start)) / 60_000L).toInt()
                                calPickTime(ctx, mins) { m ->
                                    start = CalendarEngine.startOfDay(start) + m * 60_000L
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(android.text.format.DateFormat.getTimeFormat(ctx).format(Date(start))) }
                    }
                }
                if (repeat == REP_NONE) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.cal_until), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(
                            onClick = { calPickDate(ctx, maxOf(endDay, CalendarEngine.startOfDay(start))) { endDay = it } },
                        ) {
                            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(maxOf(endDay, CalendarEngine.startOfDay(start)))))
                        }
                    }
                }
                Text(stringResource(R.string.cal_repeat_label), style = MaterialTheme.typography.labelMedium)
                ChipRow(repeats, repeat) { repeat = it }
                Text(stringResource(R.string.cal_remind_label), style = MaterialTheme.typography.labelMedium)
                ChipRow(reminds, remind) { remind = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val s = if (allDay) CalendarEngine.startOfDay(start) else start
                    val firstDay = CalendarEngine.startOfDay(s)
                    val lastDay = if (repeat == REP_NONE) maxOf(endDay, firstDay) else firstDay
                    val endsAt = when {
                        lastDay > firstDay && allDay -> lastDay
                        lastDay > firstDay -> lastDay + (s - firstDay) + 3_600_000L
                        allDay -> s
                        else -> s + 3_600_000L
                    }
                    val saved = initial.copy(
                        title = title.trim(),
                        notes = notes.trim(),
                        start = s,
                        end = endsAt,
                        allDay = allDay,
                        kind = kind,
                        repeat = repeat,
                        remindBefore = remind,
                    )
                    CalendarStore.upsert(ctx, saved)
                    onSaved(saved)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.cal_save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(
                        onClick = {
                            CalendarStore.delete(ctx, initial.id)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.cal_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cal_cancel)) }
            }
        },
    )
}

private tailrec fun Context.calActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.calActivity()
    else -> null
}

private fun calPickDate(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val host = ctx.calActivity() ?: return
    val d = CalendarEngine.ld(initial)
    DatePickerDialog(
        host,
        { _, y, m, day -> onPicked(CalendarEngine.startMs(LocalDate.of(y, m + 1, day))) },
        d.year, d.monthValue - 1, d.dayOfMonth,
    ).show()
}

private fun calPickTime(ctx: Context, minutes: Int, onPicked: (Int) -> Unit) {
    val host = ctx.calActivity() ?: return
    TimePickerDialog(
        host,
        { _, h, m -> onPicked(h * 60 + m) },
        minutes / 60,
        minutes % 60,
        android.text.format.DateFormat.is24HourFormat(host),
    ).show()
}
