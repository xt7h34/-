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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * Settings > Keyboard: the parts that make the keyboard look and behave the way the person wants.
 * They are split in tabs like the keyboard design apps: Design, Effects and Layout.
 */

// ───────────────────────── Live preview ─────────────────────────

/** The real keyboard, inside the settings. Press its keys to see the colors, effects and motion. */
@Composable
private fun LivePreview(cfg: KbConfig) {
    val ctx = LocalContext.current
    val host = remember(cfg) { PreviewHost(cfg, ctx) }
    Section(stringResource(R.string.kb_preview_title)) {
        Text(
            stringResource(R.string.kb_preview_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
            KeyboardRoot(host, withInsets = false)
        }
    }
}

// ───────────────────────── Tabs ─────────────────────────

@Composable
internal fun KbDesignTab(cfg: KbConfig, update: (KbConfig) -> Unit) {
    LivePreview(cfg)
    Section(stringResource(R.string.kb_theme)) {
        ChoiceRow(
            listOf(
                stringResource(R.string.kb_theme_auto),
                stringResource(R.string.kb_theme_light),
                stringResource(R.string.kb_theme_dark),
                stringResource(R.string.kb_theme_kanade),
            ),
            cfg.theme,
        ) { update(cfg.copy(theme = it)) }
    }
    ColorsSection(cfg, update)
    BordersSection(cfg, update)
    SizesSection(cfg, update)
}

@Composable
internal fun KbEffectsTab(cfg: KbConfig, update: (KbConfig) -> Unit) {
    LivePreview(cfg)
    KeyboardEffectSection(cfg, update)
    KeyColorSection(cfg, update)
    MotionSection(cfg, update)
    PressSection(cfg, update)
}

@Composable
internal fun KbLayoutTab(cfg: KbConfig, update: (KbConfig) -> Unit) {
    KbKeyEditor(cfg, update)
    SymbolsSection(cfg, update)
    HintsSection(cfg, update)
    OneHandedSection(cfg, update)
}

// ───────────────────────── Choosing one of several ─────────────────────────

/** Chips, [perRow] to a line. [selected] is the index in the whole list. */
@Composable
private fun ChoiceGrid(options: List<String>, selected: Int, perRow: Int = 4, onPick: (Int) -> Unit) {
    options.chunked(perRow).forEachIndexed { r, chunk ->
        ChoiceRow(chunk, selected - r * perRow) { onPick(r * perRow + it) }
    }
}

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

/** A row with a name and a color swatch. Tap it to pick a new color. 0 means the theme's color. */
@Composable
private fun ColorField(label: String, value: Int, fallback: Color, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (value == 0) {
                Text(
                    stringResource(R.string.kb_color_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Swatch(if (value != 0) Color(value) else fallback)
    }
    if (open) {
        ColorPickerDialog(
            title = label,
            initial = if (value != 0) Color(value) else fallback,
            onDismiss = { open = false },
            onPick = {
                onChange(it)
                open = false
            },
            onDefault = {
                onChange(0)
                open = false
            },
        )
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .background(color)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun ColorsSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    Section(stringResource(R.string.kb_colors_title)) {
        Text(
            stringResource(R.string.kb_colors_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        COLOR_DEFS.forEach { def ->
            ColorField(stringResource(def.label), def.get(cfg), def.fallback(pal)) { update(def.set(cfg, it)) }
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
}

private val PRESETS = listOf(
    0xFFE5484D, 0xFFF29900, 0xFFFFE600, 0xFF34A853, 0xFF00FFFF, 0xFF3D7BF5,
    0xFF7B61FF, 0xFFE0558A, 0xFFDDB3FF, 0xFFFFFFFF, 0xFF808080, 0xFF000000,
).map { Color(it) }

/** Pick any color: quick colors, sliders or a hex code, and how see-through it is. */
@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onDefault: () -> Unit,
    width: Int? = null,
    onWidth: ((Int) -> Unit)? = null,
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
                if (width != null && onWidth != null) {
                    Text(
                        stringResource(R.string.kb_border_width, String.format("%.1f", width / 10f)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = width.toFloat(),
                        onValueChange = { onWidth(it.roundToInt()) },
                        valueRange = 0f..60f,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .background(Color(argb())),
                )
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

// ───────────────────────── Borders ─────────────────────────

private val GROUP_LABELS = listOf(
    R.string.kb_g_letters, R.string.kb_g_numbers, R.string.kb_g_space, R.string.kb_g_delete,
    R.string.kb_g_shift, R.string.kb_g_lang, R.string.kb_g_emoji, R.string.kb_g_enter, R.string.kb_g_symbols,
)

private fun <T> List<T>.setAt(index: Int, value: T): List<T> =
    toMutableList().also { if (index in it.indices) it[index] = value }

@Composable
private fun BordersSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    Section(stringResource(R.string.kb_borders_title)) {
        Text(
            stringResource(R.string.kb_borders_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GROUP_LABELS.forEachIndexed { g, label ->
            var open by remember { mutableStateOf(false) }
            val color = cfg.borderColor.getOrElse(g) { 0 }
            val w = cfg.borderWidth.getOrElse(g) { 0 }
            val shown = if (color != 0) Color(color) else pal.text.copy(alpha = 0.5f)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(label))
                    Text(
                        stringResource(R.string.kb_border_width, String.format("%.1f", w / 10f)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(pal.key)
                        .border(BorderStroke((if (w > 0) w / 10f else 1f).dp, if (w > 0) shown else Color.Gray), RoundedCornerShape(10.dp)),
                )
            }
            if (open) {
                ColorPickerDialog(
                    title = stringResource(label),
                    initial = shown,
                    onDismiss = { open = false },
                    onPick = {
                        update(cfg.copy(borderColor = cfg.borderColor.setAt(g, it)))
                        open = false
                    },
                    onDefault = {
                        update(cfg.copy(borderColor = cfg.borderColor.setAt(g, 0)))
                        open = false
                    },
                    width = w,
                    onWidth = { update(cfg.copy(borderWidth = cfg.borderWidth.setAt(g, it))) },
                )
            }
        }
        OutlinedButton(
            onClick = {
                update(cfg.copy(borderColor = List(KB_GROUPS) { 0 }, borderWidth = List(KB_GROUPS) { 0 }))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.kb_borders_reset))
        }
    }
}

// ───────────────────────── Size and shape ─────────────────────────

@Composable
private fun SizesSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    Section(stringResource(R.string.kb_sizes_title)) {
        Column {
            Text(stringResource(R.string.kb_height, cfg.heightPct))
            Slider(
                value = cfg.heightPct.toFloat(),
                onValueChange = { update(cfg.copy(heightPct = it.roundToInt())) },
                valueRange = 80f..130f,
            )
        }
        Column {
            Text(stringResource(R.string.kb_numheight, cfg.numHeightPct))
            Slider(
                value = cfg.numHeightPct.toFloat(),
                onValueChange = { update(cfg.copy(numHeightPct = it.roundToInt())) },
                valueRange = 50f..120f,
            )
        }
        Column {
            Text(stringResource(R.string.kb_gap, cfg.keyGap))
            Slider(
                value = cfg.keyGap.toFloat(),
                onValueChange = { update(cfg.copy(keyGap = it.roundToInt())) },
                valueRange = 0f..8f,
                steps = 7,
            )
        }
        Column {
            Text(stringResource(R.string.kb_radius, cfg.keyRadius))
            Slider(
                value = cfg.keyRadius.toFloat(),
                onValueChange = { update(cfg.copy(keyRadius = it.roundToInt())) },
                valueRange = 0f..24f,
            )
        }
        Column {
            Text(stringResource(R.string.kb_textsize, cfg.textPct))
            Slider(
                value = cfg.textPct.toFloat(),
                onValueChange = { update(cfg.copy(textPct = it.roundToInt())) },
                valueRange = 70f..140f,
            )
        }
    }
}

// ───────────────────────── Effects ─────────────────────────

@Composable
private fun KeyboardEffectSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    Section(stringResource(R.string.kb_fx_title)) {
        Text(
            stringResource(R.string.kb_fx_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceGrid(
            listOf(
                stringResource(R.string.kb_fx_none),
                stringResource(R.string.kb_fx_splash),
                stringResource(R.string.kb_fx_glow),
                stringResource(R.string.kb_fx_ripple),
                stringResource(R.string.kb_fx_dot),
                stringResource(R.string.kb_fx_cross),
            ),
            cfg.fxStyle,
        ) { update(cfg.copy(fxStyle = it)) }
        if (cfg.fxStyle != KB_FX_NONE) {
            ColorField(stringResource(R.string.kb_fx_color), cfg.colFx, pal.fx) { update(cfg.copy(colFx = it)) }
            Column {
                Text(stringResource(R.string.kb_fx_size, cfg.fxSize))
                Slider(
                    value = cfg.fxSize.toFloat(),
                    onValueChange = { update(cfg.copy(fxSize = it.roundToInt())) },
                    valueRange = 50f..160f,
                )
            }
        }
    }
}

@Composable
private fun KeyColorSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    Section(stringResource(R.string.kb_keyfx_title)) {
        ChoiceGrid(
            listOf(
                stringResource(R.string.kb_keyfx_default),
                stringResource(R.string.kb_keyfx_solid),
                stringResource(R.string.kb_keyfx_soft),
                stringResource(R.string.kb_keyfx_ring),
                stringResource(R.string.kb_keyfx_gradient),
                stringResource(R.string.kb_keyfx_glow),
            ),
            cfg.keyFxStyle,
        ) { update(cfg.copy(keyFxStyle = it)) }
        if (cfg.keyFxStyle != KB_KEYFX_DEFAULT) {
            ColorField(stringResource(R.string.kb_keyfx_color), cfg.colKeyFx, pal.keyFx) { update(cfg.copy(colKeyFx = it)) }
        }
    }
}

@Composable
private fun MotionSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    Section(stringResource(R.string.kb_motion_title)) {
        ChoiceGrid(
            listOf(
                stringResource(R.string.kb_motion_none),
                stringResource(R.string.kb_motion_tilt),
                stringResource(R.string.kb_motion_shrink),
                stringResource(R.string.kb_motion_grow),
                stringResource(R.string.kb_motion_lift),
                stringResource(R.string.kb_motion_bounce),
            ),
            cfg.keyMotion,
        ) { update(cfg.copy(keyMotion = it)) }
    }
}

/** The bubble above a pressed key, and the picture that appears on it. */
@Composable
private fun PressSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    val pal = kbPalette(cfg, isSystemInDarkTheme())
    Section(stringResource(R.string.kb_press_title)) {
        Text(stringResource(R.string.kb_shape))
        ChoiceGrid(
            listOf(
                stringResource(R.string.kb_shape_none),
                stringResource(R.string.kb_shape_rounded),
                stringResource(R.string.kb_shape_circle),
                stringResource(R.string.kb_shape_diamond),
                stringResource(R.string.kb_shape_hex),
                stringResource(R.string.kb_shape_star),
                stringResource(R.string.kb_shape_heart),
            ),
            cfg.previewShape,
        ) { update(cfg.copy(previewShape = it)) }

        SwitchRow(stringResource(R.string.kb_effect_on), cfg.effectOn) { update(cfg.copy(effectOn = it)) }
        if (cfg.effectOn) {
            OutlinedTextField(
                value = cfg.effectChars,
                onValueChange = { update(cfg.copy(effectChars = it.take(24))) },
                label = { Text(stringResource(R.string.kb_effect_chars)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ColorField(
                stringResource(R.string.kb_pic_color),
                cfg.colPicture,
                pal.picture ?: Color(0xFFFFE600),
            ) { update(cfg.copy(colPicture = it)) }
            Column {
                Text(stringResource(R.string.kb_effect_size, cfg.effectSize))
                Slider(
                    value = cfg.effectSize.toFloat(),
                    onValueChange = { update(cfg.copy(effectSize = it.roundToInt())) },
                    valueRange = 14f..48f,
                )
            }
            SwitchRow(stringResource(R.string.kb_pic_inside), cfg.pictureInside) { update(cfg.copy(pictureInside = it)) }
            Text(stringResource(R.string.kb_pic_motion))
            ChoiceGrid(
                listOf(
                    stringResource(R.string.kb_pic_fade),
                    stringResource(R.string.kb_pic_rise),
                    stringResource(R.string.kb_pic_pop),
                    stringResource(R.string.kb_pic_spin),
                ),
                cfg.pictureMotion,
            ) { update(cfg.copy(pictureMotion = it)) }
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

// ───────────────────────── Hints, extra letters and the emoji button ─────────────────────────

@Composable
private fun HintsSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    Section(stringResource(R.string.kb_hints_title)) {
        SwitchRow(stringResource(R.string.kb_number_row), cfg.numberRow) { update(cfg.copy(numberRow = it)) }
        SwitchRow(stringResource(R.string.kb_hints_show), cfg.showHints) { update(cfg.copy(showHints = it)) }
        SwitchRow(stringResource(R.string.kb_alts_bubble), cfg.altsInBubble) { update(cfg.copy(altsInBubble = it)) }
        Text(
            stringResource(R.string.kb_alts_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(stringResource(R.string.kb_emoji_bar), cfg.emojiInBar) { update(cfg.copy(emojiInBar = it)) }
        Text(
            stringResource(R.string.kb_emoji_bar_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ───────────────────────── One-handed mode ─────────────────────────

@Composable
private fun OneHandedSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    Section(stringResource(R.string.kb_oh_title)) {
        Text(
            stringResource(R.string.kb_oh_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            listOf(
                stringResource(R.string.kb_oh_off),
                stringResource(R.string.kb_oh_left),
                stringResource(R.string.kb_oh_right),
            ),
            cfg.oneHanded,
        ) { update(cfg.copy(oneHanded = it)) }
        if (cfg.oneHanded != 0) {
            Column {
                Text(stringResource(R.string.kb_oh_width, cfg.oneHandedWidth))
                Slider(
                    value = cfg.oneHandedWidth.toFloat(),
                    onValueChange = { update(cfg.copy(oneHandedWidth = it.roundToInt())) },
                    valueRange = 60f..92f,
                )
            }
        }
    }
}
