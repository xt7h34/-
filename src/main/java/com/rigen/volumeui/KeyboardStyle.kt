package com.rigen.volumeui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * Settings > Keyboard > the parts that make the keyboard look and behave the way the person
 * wants: every color, the bubble and picture on press, the letter positions and extra symbols.
 */

// ───────────────────────── Colors ─────────────────────────

private class ColorDef(
    val label: Int,
    val get: (KbConfig) -> Int,
    val set: (KbConfig, Int) -> KbConfig,
    val fallback: (KbPalette) -> Color,
)

private val COLOR_DEFS = listOf(
    ColorDef(R.string.kb_c_bg, { it.colBg }, { c, v -> c.copy(colBg = v) }, { it.bg }),
    ColorDef(R.string.kb_c_bg2, { it.colBg2 }, { c, v -> c.copy(colBg2 = v) }, { it.bg2 ?: it.bg }),
    ColorDef(R.string.kb_c_key, { it.colKey }, { c, v -> c.copy(colKey = v) }, { it.key }),
    ColorDef(R.string.kb_c_num, { it.colNumKey }, { c, v -> c.copy(colNumKey = v) }, { it.numKey }),
    ColorDef(R.string.kb_c_special, { it.colSpecial }, { c, v -> c.copy(colSpecial = v) }, { it.special }),
    ColorDef(R.string.kb_c_text, { it.colText }, { c, v -> c.copy(colText = v) }, { it.text }),
    ColorDef(R.string.kb_c_icon, { it.colIcon }, { c, v -> c.copy(colIcon = v) }, { it.icon }),
    ColorDef(R.string.kb_c_press, { it.colPress }, { c, v -> c.copy(colPress = v) }, { it.keyDown }),
    ColorDef(R.string.kb_c_accent, { it.colAccent }, { c, v -> c.copy(colAccent = v) }, { it.accent }),
    ColorDef(R.string.kb_c_preview, { it.colPreview }, { c, v -> c.copy(colPreview = v) }, { it.preview }),
    ColorDef(R.string.kb_c_hint, { it.colHint }, { c, v -> c.copy(colHint = v) }, { it.sub }),
)

/** All the sections below, one after the other. Call this inside the page's column. */
@Composable
internal fun KbStyleSections(cfg: KbConfig, update: (KbConfig) -> Unit) {
    Section(stringResource(R.string.kb_preview_title)) {
        KbPreview(cfg)
    }
    ColorsSection(cfg, update)
    PressSection(cfg, update)
    LayoutEditor(cfg, update)
    SymbolsSection(cfg, update)
}

@Composable
private fun ColorsSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    var picking by remember { mutableStateOf<ColorDef?>(null) }

    Section(stringResource(R.string.kb_colors_title)) {
        Text(
            stringResource(R.string.kb_colors_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        COLOR_DEFS.forEach { def ->
            val value = def.get(cfg)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { picking = def }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(def.label))
                    if (value == 0) {
                        Text(
                            stringResource(R.string.kb_color_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .background(if (value != 0) Color(value) else def.fallback(pal))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(10.dp)),
                )
            }
        }
        OutlinedButton(
            onClick = {
                update(
                    cfg.copy(
                        colBg = 0, colBg2 = 0, colKey = 0, colNumKey = 0, colSpecial = 0, colText = 0,
                        colIcon = 0, colPress = 0, colAccent = 0, colPreview = 0, colHint = 0,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.kb_colors_reset))
        }
    }

    val def = picking
    if (def != null) {
        val current = def.get(cfg)
        ColorPickerDialog(
            title = stringResource(def.label),
            initial = if (current != 0) Color(current) else def.fallback(pal),
            onDismiss = { picking = null },
            onPick = { argb ->
                update(def.set(cfg, argb))
                picking = null
            },
            onDefault = {
                update(def.set(cfg, 0))
                picking = null
            },
        )
    }
}

private val PRESETS = listOf(
    0xFFE5484D, 0xFFF29900, 0xFFFFE600, 0xFF34A853, 0xFF00FFFF, 0xFF3D7BF5,
    0xFF7B61FF, 0xFFE0558A, 0xFFDDB3FF, 0xFFFFFFFF, 0xFF808080, 0xFF000000,
).map { Color(it) }

@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onDefault: () -> Unit,
) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var vl by remember { mutableFloatStateOf(start[2]) }
    var alpha by remember { mutableFloatStateOf(initial.alpha) }

    fun argb(): Int = android.graphics.Color.HSVToColor((alpha * 255f).roundToInt(), floatArrayOf(hue, sat, vl))
    fun hexOf(): String = String.format("%06X", argb() and 0xFFFFFF)
    var hex by remember { mutableStateOf(hexOf()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .background(Color(argb())),
                )
                // Quick colors.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PRESETS.forEach { c ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(c)
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(6.dp))
                                .clickable {
                                    val h = FloatArray(3)
                                    android.graphics.Color.colorToHSV(c.toArgb(), h)
                                    hue = h[0]
                                    sat = h[1]
                                    vl = h[2]
                                    hex = hexOf()
                                },
                        )
                    }
                }
                Text(stringResource(R.string.kb_pick_hue), style = MaterialTheme.typography.labelMedium)
                Slider(value = hue, onValueChange = { hue = it; hex = hexOf() }, valueRange = 0f..360f)
                Text(stringResource(R.string.kb_pick_sat), style = MaterialTheme.typography.labelMedium)
                Slider(value = sat, onValueChange = { sat = it; hex = hexOf() }, valueRange = 0f..1f)
                Text(stringResource(R.string.kb_pick_val), style = MaterialTheme.typography.labelMedium)
                Slider(value = vl, onValueChange = { vl = it; hex = hexOf() }, valueRange = 0f..1f)
                Text(stringResource(R.string.kb_pick_alpha), style = MaterialTheme.typography.labelMedium)
                Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.05f..1f)
                OutlinedTextField(
                    value = hex,
                    onValueChange = { raw ->
                        val clean = raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                        hex = clean
                        if (clean.length == 6) {
                            val rgb = clean.toInt(16) or (0xFF shl 24)
                            val h = FloatArray(3)
                            android.graphics.Color.colorToHSV(rgb, h)
                            hue = h[0]
                            sat = h[1]
                            vl = h[2]
                        }
                    },
                    label = { Text(stringResource(R.string.kb_pick_hex)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(argb()) }) { Text(stringResource(R.string.kb_pick_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDefault) { Text(stringResource(R.string.kb_pick_default)) }
        },
    )
}

// ───────────────────────── Preview ─────────────────────────

@Composable
private fun KbPreview(cfg: KbConfig) {
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    val brush = Brush.verticalGradient(listOf(pal.bg, pal.bg2 ?: pal.bg))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(brush)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The bubble that appears above the pressed key.
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (cfg.previewShape != KB_SHAPE_NONE) {
                        val shape = kbBubbleShape(cfg.previewShape)
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(shape)
                                .background(pal.preview),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("ا", color = pal.text, fontSize = 24.sp)
                        }
                    }
                }
                Spacer(Modifier.weight(2f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("١", "٢", "٣", "٤", "٥").forEach { n ->
                    PreviewKey(n, pal.numKey, pal.text, Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PreviewKey("ب", pal.key, pal.text, Modifier.weight(1f))
                PreviewKey(
                    "ا", pal.keyDown, pal.text, Modifier.weight(1f),
                    if (cfg.effectOn) kbGraphemes(cfg.effectChars).firstOrNull() else null,
                )
                PreviewKey("ت", pal.key, pal.text, Modifier.weight(1f))
                PreviewKey("⌫", pal.special, pal.icon, Modifier.weight(1f))
                PreviewKey("↵", pal.accent, pal.onAccent, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PreviewKey(label: String, bg: Color, fg: Color, modifier: Modifier, stamp: String? = null) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 18.sp)
        if (stamp != null) Text(stamp, fontSize = 18.sp, modifier = Modifier.padding(start = 14.dp, top = 12.dp))
    }
}

// ───────────────────────── Bubble and picture ─────────────────────────

@Composable
private fun PressSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    Section(stringResource(R.string.kb_press_title)) {
        Text(stringResource(R.string.kb_shape))
        ChoiceRow(
            listOf(
                stringResource(R.string.kb_shape_none),
                stringResource(R.string.kb_shape_rounded),
                stringResource(R.string.kb_shape_circle),
                stringResource(R.string.kb_shape_diamond),
            ),
            cfg.previewShape,
        ) { update(cfg.copy(previewShape = it)) }
        ChoiceRow(
            listOf(
                stringResource(R.string.kb_shape_hex),
                stringResource(R.string.kb_shape_star),
                stringResource(R.string.kb_shape_heart),
            ),
            cfg.previewShape - KB_SHAPE_HEXAGON,
        ) { update(cfg.copy(previewShape = KB_SHAPE_HEXAGON + it)) }

        SwitchRow(stringResource(R.string.kb_effect_on), cfg.effectOn) { update(cfg.copy(effectOn = it)) }
        if (cfg.effectOn) {
            OutlinedTextField(
                value = cfg.effectChars,
                onValueChange = { update(cfg.copy(effectChars = it.take(24))) },
                label = { Text(stringResource(R.string.kb_effect_chars)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Text(stringResource(R.string.kb_effect_size, cfg.effectSize))
                Slider(
                    value = cfg.effectSize.toFloat(),
                    onValueChange = { update(cfg.copy(effectSize = it.roundToInt())) },
                    valueRange = 14f..48f,
                )
            }
        }
    }
}

// ───────────────────────── Letter positions ─────────────────────────

@Composable
private fun LayoutEditor(cfg: KbConfig, update: (KbConfig) -> Unit) {
    var lang by remember { mutableStateOf("ar") }
    var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val rows = kbParseLayout(if (lang == "ar") cfg.layoutAr else cfg.layoutEn, lang)

    fun save(raw: String) {
        update(if (lang == "ar") cfg.copy(layoutAr = raw) else cfg.copy(layoutEn = raw))
    }

    Section(stringResource(R.string.kb_layout_title)) {
        Text(
            stringResource(R.string.kb_layout_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            listOf(stringResource(R.string.kb_layout_ar), stringResource(R.string.kb_layout_en)),
            if (lang == "ar") 0 else 1,
        ) {
            lang = if (it == 0) "ar" else "en"
            sel = null
        }
        // The keys are drawn left to right, the same as on the keyboard.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEachIndexed { c, tok ->
                            val on = sel == Pair(r, c)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    )
                                    .clickable {
                                        val first = sel
                                        if (first == null) {
                                            sel = Pair(r, c)
                                        } else if (first == Pair(r, c)) {
                                            sel = null
                                        } else {
                                            val m = rows.map { it.toMutableList() }
                                            val t = m[first.first][first.second]
                                            m[first.first][first.second] = m[r][c]
                                            m[r][c] = t
                                            save(kbEncodeLayout(m))
                                            sel = null
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    tok,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        val chosen = sel
        if (chosen != null) {
            OutlinedTextField(
                value = "",
                onValueChange = { v ->
                    val g = kbGraphemes(v).lastOrNull()
                    if (g != null && g != "|") {
                        val m = rows.map { it.toMutableList() }
                        m[chosen.first][chosen.second] = if (lang == "en") g.lowercase() else g
                        save(kbEncodeLayout(m))
                        sel = null
                    }
                },
                label = { Text(stringResource(R.string.kb_layout_replace)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(
            onClick = {
                save("")
                sel = null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.kb_layout_reset))
        }
    }
}

// ───────────────────────── Extra symbols ─────────────────────────

@Composable
private fun SymbolsSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    Section(stringResource(R.string.kb_symbols_title)) {
        Text(
            stringResource(R.string.kb_symbols_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = cfg.symbols,
            onValueChange = { update(cfg.copy(symbols = it.take(200))) },
            label = { Text(stringResource(R.string.kb_symbols_field)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.kb_symbols_count, minOf(27, kbGraphemes(cfg.symbols).size)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
