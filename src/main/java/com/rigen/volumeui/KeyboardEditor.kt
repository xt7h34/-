package com.rigen.volumeui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * The key editor. Tap a key and change how wide and how tall it is, move it, delete it,
 * or add a new key. Everything can be undone, and a page can be reset to the original keys.
 */

private val KIND_CHOICES = listOf(
    KeyKind.CHAR, KeyKind.SPACE, KeyKind.BACKSPACE, KeyKind.ENTER,
    KeyKind.SHIFT, KeyKind.LANG, KeyKind.EMOJI, KeyKind.SYMBOLS,
)

private fun defaultWeight(kind: KeyKind): Float = when (kind) {
    KeyKind.SPACE -> 3f
    KeyKind.ENTER, KeyKind.BACKSPACE, KeyKind.SHIFT -> 1.5f
    KeyKind.SYMBOLS -> 1.4f
    else -> 1f
}

/** What a key shows on the editing board. */
private fun boardLabel(k: KeySpec): String = when (k.kind) {
    KeyKind.CHAR -> k.label
    KeyKind.SPACE -> "␣"
    KeyKind.BACKSPACE -> "⌫"
    KeyKind.ENTER -> "↵"
    KeyKind.SHIFT -> "⇧"
    KeyKind.SYMBOLS -> "?123"
    KeyKind.LETTERS -> "ABC"
    KeyKind.SYM_PAGE -> "=\\<"
    KeyKind.LANG -> "\uD83C\uDF10"
    KeyKind.EMOJI -> "\u263A"
    KeyKind.GAP -> "·"
}

@Composable
internal fun KbKeyEditor(cfg: KbConfig, update: (KbConfig) -> Unit) {
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    var pageIdx by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var textTick by remember { mutableIntStateOf(0) }
    val undo = remember { mutableStateListOf<String>() }
    val redo = remember { mutableStateListOf<String>() }
    val pending = remember { arrayOfNulls<String>(1) }

    val custom = kbGraphemes(cfg.symbols).take(27)
    val rows = kbPageRows(cfg, pageIdx, custom)

    fun apply(enc: String) {
        update(cfg.copy(layouts = cfg.layouts.toMutableList().also { it[pageIdx] = enc }))
    }

    /** One step that can be undone. */
    fun commit(newRows: List<RowSpec>) {
        undo.add(kbEncodeRows(rows))
        redo.clear()
        apply(kbEncodeRows(newRows))
    }

    /** Many small changes in a row (dragging a slider, typing) count as one step. */
    fun live(newRows: List<RowSpec>) {
        if (pending[0] == null) pending[0] = kbEncodeRows(rows)
        apply(kbEncodeRows(newRows))
    }

    fun endLive() {
        val before = pending[0]
        if (before != null) {
            undo.add(before)
            redo.clear()
        }
        pending[0] = null
    }

    // Typing in the text boxes ends one step after a short pause.
    LaunchedEffect(textTick) {
        if (textTick > 0) {
            delay(900)
            endLive()
        }
    }

    fun mapKey(r: Int, c: Int, f: (KeySpec) -> KeySpec): List<RowSpec> =
        rows.mapIndexed { ri, row ->
            if (ri != r) row else RowSpec(row.number, row.keys.mapIndexed { ci, k -> if (ci == c) f(k) else k })
        }

    fun deleteKey(r: Int, c: Int) {
        val left = rows.mapIndexed { ri, row ->
            if (ri != r) row else RowSpec(row.number, row.keys.filterIndexed { ci, _ -> ci != c })
        }.filter { it.keys.isNotEmpty() }
        if (left.isEmpty()) return
        commit(left)
        sel = null
    }

    fun moveSideways(r: Int, c: Int, d: Int) {
        val keys = rows[r].keys
        val to = c + d
        if (to !in keys.indices) return
        val m = keys.toMutableList()
        val t = m[c]
        m[c] = m[to]
        m[to] = t
        commit(rows.mapIndexed { ri, row -> if (ri == r) RowSpec(row.number, m) else row })
        sel = Pair(r, to)
    }

    fun moveRow(r: Int, c: Int, d: Int) {
        val to = r + d
        if (to !in rows.indices) return
        val key = rows[r].keys[c]
        val newRows = rows.mapIndexed { ri, row ->
            when (ri) {
                r -> RowSpec(row.number, row.keys.filterIndexed { ci, _ -> ci != c })
                to -> {
                    val m = row.keys.toMutableList()
                    m.add(minOf(c, m.size), key)
                    RowSpec(row.number, m)
                }
                else -> row
            }
        }
        // A row that lost its last key disappears, so the target row number may shift.
        val keep = newRows.filter { it.keys.isNotEmpty() }
        commit(keep)
        sel = null
    }

    Section(stringResource(R.string.kb_ke_title)) {
        Text(
            stringResource(R.string.kb_ke_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            listOf(
                stringResource(R.string.kb_ke_p_ar),
                stringResource(R.string.kb_ke_p_en),
                stringResource(R.string.kb_ke_p_s1),
                stringResource(R.string.kb_ke_p_s2),
            ),
            pageIdx,
        ) {
            pageIdx = it
            sel = null
            undo.clear()
            redo.clear()
            pending[0] = null
        }

        // The keys, left to right as on the keyboard.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(pal.bg)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEachIndexed { r, row ->
                    val maxH = row.keys.filter { it.kind != KeyKind.GAP }.maxOfOrNull { it.hScale } ?: 1f
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height((40f * maxH).dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.keys.forEachIndexed { c, k ->
                            val on = sel == Pair(r, c)
                            val dim = row.number && !cfg.numberRow
                            val special = k.kind != KeyKind.CHAR && k.kind != KeyKind.SPACE
                            Box(
                                Modifier
                                    .weight(k.weight)
                                    .fillMaxHeight(k.hScale / maxH)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            k.kind == KeyKind.GAP -> pal.bg
                                            special -> pal.special
                                            row.number -> pal.numKey
                                            else -> pal.key
                                        }.copy(alpha = if (dim) 0.35f else 1f),
                                    )
                                    .then(
                                        if (on) Modifier.border(2.dp, pal.accent, RoundedCornerShape(6.dp))
                                        else if (k.kind == KeyKind.GAP) Modifier.border(1.dp, pal.sub.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                        else Modifier,
                                    )
                                    .clickable { sel = Pair(r, c) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(boardLabel(k), color = pal.text, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.kb_ke_numrow_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Undo, redo, add, reset.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = {
                    if (undo.isNotEmpty()) {
                        redo.add(kbEncodeRows(rows))
                        apply(undo.removeAt(undo.lastIndex))
                        sel = null
                    }
                },
                enabled = undo.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.kb_ke_undo), maxLines = 1) }
            OutlinedButton(
                onClick = {
                    if (redo.isNotEmpty()) {
                        undo.add(kbEncodeRows(rows))
                        apply(redo.removeAt(redo.lastIndex))
                        sel = null
                    }
                },
                enabled = redo.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.kb_ke_redo), maxLines = 1) }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.kb_ke_add), maxLines = 1)
            }
        }

        // The selected key.
        val chosen = sel
        val key = if (chosen != null) rows.getOrNull(chosen.first)?.keys?.getOrNull(chosen.second) else null
        if (chosen == null || key == null) {
            Text(
                stringResource(R.string.kb_ke_select_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val r = chosen.first
            val c = chosen.second
            ChoiceRow(
                listOf(stringResource(R.string.kb_ke_tab_w), stringResource(R.string.kb_ke_tab_h)),
                tab,
            ) { tab = it }
            val isWidth = tab == 0
            val value = if (isWidth) key.weight else key.hScale
            val low = if (isWidth) 0.4f else 0.6f
            val high = if (isWidth) 4f else 1.6f
            fun withValue(v: Float): List<RowSpec> =
                mapKey(r, c) { if (isWidth) it.copy(weight = v) else it.copy(hScale = v) }

            Text(
                stringResource(
                    if (isWidth) R.string.kb_ke_width else R.string.kb_ke_height,
                    String.format("%.2f", value),
                ),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { commit(withValue((value - 0.1f).coerceIn(low, high))) }) { Text("−", fontSize = 22.sp) }
                Slider(
                    value = value.coerceIn(low, high),
                    onValueChange = { live(withValue((it * 20f).roundToInt() / 20f)) },
                    onValueChangeFinished = { endLive() },
                    valueRange = low..high,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { commit(withValue((value + 0.1f).coerceIn(low, high))) }) { Text("+", fontSize = 22.sp) }
            }

            // Move it.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { moveSideways(r, c, -1) }, modifier = Modifier.weight(1f)) { Text("◀") }
                OutlinedButton(onClick = { moveSideways(r, c, 1) }, modifier = Modifier.weight(1f)) { Text("▶") }
                OutlinedButton(onClick = { moveRow(r, c, -1) }, modifier = Modifier.weight(1f)) { Text("▲") }
                OutlinedButton(onClick = { moveRow(r, c, 1) }, modifier = Modifier.weight(1f)) { Text("▼") }
            }

            // A text key can type anything, and have extra letters.
            if (key.kind == KeyKind.CHAR) {
                var text by remember(chosen, pageIdx) { mutableStateOf(key.label) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v.take(12)
                        if (text.isNotEmpty()) {
                            live(mapKey(r, c) { it.copy(label = text) })
                            textTick++
                        }
                    },
                    label = { Text(stringResource(R.string.kb_ke_text)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                var extra by remember(chosen, pageIdx) { mutableStateOf(key.alts.joinToString("")) }
                OutlinedTextField(
                    value = extra,
                    onValueChange = { v ->
                        extra = v.take(24)
                        live(mapKey(r, c) { it.copy(alts = kbGraphemes(extra)) })
                        textTick++
                    },
                    label = { Text(stringResource(R.string.kb_ke_alts)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedButton(onClick = { deleteKey(r, c) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.kb_ke_delete))
            }
        }

        OutlinedButton(
            onClick = {
                update(
                    cfg.copy(
                        layouts = cfg.layouts.toMutableList().also { it[pageIdx] = "" },
                        layoutAr = if (pageIdx == 0) "" else cfg.layoutAr,
                        layoutEn = if (pageIdx == 1) "" else cfg.layoutEn,
                    ),
                )
                sel = null
                undo.clear()
                redo.clear()
                pending[0] = null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.kb_ke_reset))
        }
    }

    if (adding) {
        AddKeyDialog(
            rowCount = rows.size,
            startRow = (sel?.first ?: (rows.size - 2)).coerceIn(0, rows.size - 1),
            onDismiss = { adding = false },
            onAdd = { kind, text, extra, rowIdx ->
                val spec = KeySpec(
                    kind = kind,
                    label = if (kind == KeyKind.CHAR) text else "",
                    weight = defaultWeight(kind),
                    hScale = 1f,
                    alts = if (kind == KeyKind.CHAR) kbGraphemes(extra) else emptyList(),
                )
                val after = if (sel?.first == rowIdx) sel?.second ?: -1 else -1
                val newRows = rows.mapIndexed { ri, row ->
                    if (ri != rowIdx) {
                        row
                    } else {
                        val m = row.keys.toMutableList()
                        m.add(if (after >= 0) after + 1 else m.size, spec)
                        RowSpec(row.number, m)
                    }
                }
                commit(newRows)
                sel = Pair(rowIdx, if (after >= 0) after + 1 else rows[rowIdx].keys.size)
                adding = false
            },
        )
    }
}

@Composable
private fun AddKeyDialog(
    rowCount: Int,
    startRow: Int,
    onDismiss: () -> Unit,
    onAdd: (KeyKind, String, String, Int) -> Unit,
) {
    var kindIdx by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var rowIdx by remember { mutableIntStateOf(startRow) }
    val kind = KIND_CHOICES[kindIdx]
    val kindNames = listOf(
        stringResource(R.string.kb_kind_text), stringResource(R.string.kb_kind_space),
        stringResource(R.string.kb_kind_delete), stringResource(R.string.kb_kind_enter),
        stringResource(R.string.kb_kind_shift), stringResource(R.string.kb_kind_lang),
        stringResource(R.string.kb_kind_emoji), stringResource(R.string.kb_kind_symbols),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kb_ke_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.kb_ke_kind), style = MaterialTheme.typography.labelMedium)
                kindNames.chunked(4).forEachIndexed { r, chunk ->
                    ChoiceRow(chunk, kindIdx - r * 4) { kindIdx = r * 4 + it }
                }
                if (kind == KeyKind.CHAR) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(12) },
                        label = { Text(stringResource(R.string.kb_ke_text)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = extra,
                        onValueChange = { extra = it.take(24) },
                        label = { Text(stringResource(R.string.kb_ke_alts)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.kb_ke_row), style = MaterialTheme.typography.labelMedium)
                (0 until rowCount).map { (it + 1).toString() }.chunked(6).forEachIndexed { r, chunk ->
                    ChoiceRow(chunk, rowIdx - r * 6) { rowIdx = r * 6 + it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(kind, text, extra, rowIdx) },
                enabled = kind != KeyKind.CHAR || text.isNotEmpty(),
            ) { Text(stringResource(R.string.kb_ke_add_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kb_ke_cancel)) }
        },
    )
}
