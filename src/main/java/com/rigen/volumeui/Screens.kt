package com.rigen.volumeui

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

// ───────────────────────────── Theme ─────────────────────────────

private val KanadeLight = lightColorScheme(
    primary = Color(0xFF5B5BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E0FF),
    onPrimaryContainer = Color(0xFF1A1A6B),
    secondary = Color(0xFF8A5CC4),
    tertiary = Color(0xFFC2638F),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE6E1EC),
)

private val KanadeDark = darkColorScheme(
    primary = Color(0xFFC0BFFF),
    onPrimary = Color(0xFF23227F),
    primaryContainer = Color(0xFF3B3AA6),
    onPrimaryContainer = Color(0xFFE3E0FF),
    secondary = Color(0xFFD6BAFF),
    tertiary = Color(0xFFFFB0D2),
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
    surfaceVariant = Color(0xFF46454F),
)

@Composable
fun KanadeTheme(themeMode: Int, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> KanadeDark
        else -> KanadeLight
    }
    // Make the status bar icons readable on top of the chosen theme.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ───────────────────────────── Small building blocks ─────────────────────────────

private val SWATCHES = listOf(
    0xFF1E1E1E, 0xFF000000, 0xFFFFFFFF, 0xFF1565C0,
    0xFF2E7D32, 0xFFC62828, 0xFF6A1B9A, 0xFFEF6C00,
).map { it.toInt() }

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit,
) {
    Column {
        Text(label)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorRow(title: String, selected: Int, showAuto: Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAuto) {
                FilterChip(
                    selected = selected == 0,
                    onClick = { onPick(0) },
                    label = { Text(stringResource(R.string.color_auto)) },
                )
            }
            SWATCHES.forEach { argb ->
                val isSelected = selected == argb
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            BorderStroke(3.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray),
                            CircleShape,
                        )
                        .clickable { onPick(argb) },
                )
            }
        }
    }
}

/**
 * A mini phone screen: tap or drag on it to place something. [posX]/[posY] are the center of
 * the marker as fractions of the screen; the marker is drawn at its real relative size.
 */
@Composable
private fun PositionPicker(
    posX: Float,
    posY: Float,
    markerWdp: Int,
    markerHdp: Int,
    cornerDp: Int,
    markerColor: Color,
    onChange: (Float, Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val config = LocalConfiguration.current
    val screenWdp = config.screenWidthDp.coerceAtLeast(1)
    val screenHdp = config.screenHeightDp.coerceAtLeast(1)
    val pickerW = 180.dp
    val pickerH = pickerW * (screenHdp.toFloat() / screenWdp)
    val scale = 180f / screenWdp
    val markerW = (markerWdp * scale).dp
    val markerH = (markerHdp * scale).dp
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .size(pickerW, pickerH)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), shape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o ->
                    currentOnChange(o.x / size.width, o.y / size.height)
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> currentOnChange(o.x / size.width, o.y / size.height) },
                    onDrag = { c, _ ->
                        c.consume()
                        currentOnChange(c.position.x / size.width, c.position.y / size.height)
                    },
                )
            },
    ) {
        val maxLeft = (pickerW - markerW).coerceAtLeast(0.dp)
        val maxTop = (pickerH - markerH).coerceAtLeast(0.dp)
        val left = (pickerW * posX - markerW / 2).coerceIn(0.dp, maxLeft)
        val top = (pickerH * posY - markerH / 2).coerceIn(0.dp, maxTop)
        Box(
            modifier = Modifier
                .offset(left, top)
                .size(markerW, markerH)
                .clip(RoundedCornerShape((cornerDp * scale).dp))
                .background(markerColor),
        )
    }
}

// ───────────────────────────── Screens ─────────────────────────────

private class TabInfo(val glyph: Glyph, val labelRes: Int)

private val TABS = listOf(
    TabInfo(Glyph.HOME, R.string.tab_home),
    TabInfo(Glyph.VOLUME, R.string.tab_panel),
    TabInfo(Glyph.TUNE, R.string.tab_behavior),
    TabInfo(Glyph.APPS, R.string.tab_apps),
    TabInfo(Glyph.PALETTE, R.string.tab_app),
)

@Composable
private fun HomeScreen(settings: PanelSettings, serviceEnabled: Boolean) {
    val context = LocalContext.current
    // The preview is interactive: tap the three dots, drag the bars.
    val levels = remember {
        mutableStateMapOf(
            AudioManager.STREAM_MUSIC to 13,
            AudioManager.STREAM_VOICE_CALL to 4,
            AudioManager.STREAM_RING to 10,
            AudioManager.STREAM_NOTIFICATION to 8,
            AudioManager.STREAM_ALARM to 11,
        )
    }
    val maxes = remember { STREAMS.associate { it.stream to 15 } }
    var expanded by remember { mutableStateOf(false) }
    var dnd by remember { mutableStateOf(false) }
    val actions = remember {
        PanelActions(
            onSeek = { stream, f -> levels[stream] = (f * 15).roundToInt().coerceIn(0, 15) },
            onTouch = { },
            onToggleStation = { expanded = !expanded },
            onMedia = { },
        )
    }

    ScreenColumn {
        Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineMedium)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            VolumePanel(
                settings = settings,
                state = PanelState(
                    stream = AudioManager.STREAM_MUSIC,
                    level = levels[AudioManager.STREAM_MUSIC] ?: 0,
                    max = 15,
                    dnd = dnd,
                    expanded = expanded,
                    levels = levels,
                    maxes = maxes,
                ),
                actions = actions,
            )
        }
        if (settings.stationEnabled) {
            Text(stringResource(R.string.preview_hint), style = MaterialTheme.typography.bodySmall)
        }
        SwitchRow(stringResource(R.string.preview_dnd), dnd) { dnd = it }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (serviceEnabled) R.string.service_on else R.string.service_off),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(stringResource(R.string.open_accessibility)) }
                Button(onClick = {
                    val s = VolumeAccessibilityService.instance
                    if (s != null) {
                        s.showPanel(ignoreRules = true)
                    } else {
                        Toast.makeText(context, R.string.enable_service_first, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.test_panel)) }
            }
        }
    }
}

@Composable
private fun PanelScreen(settings: PanelSettings, onChange: (PanelSettings) -> Unit) {
    var previewLevel by remember { mutableIntStateOf(13) }
    var previewDnd by remember { mutableStateOf(false) }

    ScreenColumn {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            VolumePanel(
                settings = settings,
                state = PanelState(
                    stream = AudioManager.STREAM_MUSIC,
                    level = previewLevel,
                    max = 15,
                    dnd = previewDnd,
                ),
            )
        }
        LabeledSlider(stringResource(R.string.preview_level, previewLevel), previewLevel, 0f..15f) {
            previewLevel = it
        }
        SwitchRow(stringResource(R.string.preview_dnd), previewDnd) { previewDnd = it }

        Section(stringResource(R.string.section_look)) {
            SwitchRow(stringResource(R.string.vertical_panel), settings.vertical) {
                // Rotate the panel: swap its width and height.
                onChange(settings.copy(vertical = it, widthDp = settings.heightDp, heightDp = settings.widthDp))
            }
            SwitchRow(stringResource(R.string.show_frame), settings.showFrame) {
                onChange(settings.copy(showFrame = it))
            }
            SwitchRow(stringResource(R.string.show_dnd_icon), settings.showDndIcon) {
                onChange(settings.copy(showDndIcon = it))
            }
            LabeledSlider(
                stringResource(R.string.corner_radius, settings.cornerRadiusDp),
                settings.cornerRadiusDp,
                0f..40f,
            ) { onChange(settings.copy(cornerRadiusDp = it)) }
            LabeledSlider(
                stringResource(R.string.panel_width, settings.widthDp),
                settings.widthDp,
                if (settings.vertical) 32f..120f else 160f..360f,
            ) { onChange(settings.copy(widthDp = it)) }
            LabeledSlider(
                stringResource(R.string.panel_height, settings.heightDp),
                settings.heightDp,
                if (settings.vertical) 160f..360f else 32f..120f,
            ) { onChange(settings.copy(heightDp = it)) }
            LabeledSlider(
                stringResource(R.string.red_threshold, settings.redThresholdPct),
                settings.redThresholdPct,
                50f..100f,
            ) { onChange(settings.copy(redThresholdPct = it)) }
            ColorRow(stringResource(R.string.panel_color), settings.colorArgb, false) {
                onChange(settings.copy(colorArgb = it))
            }
            ColorRow(stringResource(R.string.bar_color), settings.barColorArgb, true) {
                onChange(settings.copy(barColorArgb = it))
            }
        }

        Section(stringResource(R.string.section_position)) {
            Text(stringResource(R.string.position_hint), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PositionPicker(
                    posX = settings.posX,
                    posY = settings.posY,
                    markerWdp = settings.widthDp + 24,
                    markerHdp = settings.heightDp + 24,
                    cornerDp = settings.cornerRadiusDp,
                    markerColor = if (settings.showFrame) {
                        Color(settings.colorArgb)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    },
                ) { fx, fy ->
                    onChange(settings.copy(posX = fx.coerceIn(0f, 1f), posY = fy.coerceIn(0f, 1f)))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onChange(settings.copy(posX = 0.5f, posY = 0.08f)) },
                    label = { Text(stringResource(R.string.pos_top)) },
                )
                AssistChip(
                    onClick = { onChange(settings.copy(posX = 0.5f, posY = 0.5f)) },
                    label = { Text(stringResource(R.string.pos_center)) },
                )
                AssistChip(
                    onClick = { onChange(settings.copy(posX = 0.5f, posY = 0.92f)) },
                    label = { Text(stringResource(R.string.pos_bottom)) },
                )
            }
        }
    }
}

@Composable
private fun BehaviorScreen(
    settings: PanelSettings,
    dndAccess: Boolean,
    onChange: (PanelSettings) -> Unit,
) {
    val context = LocalContext.current

    ScreenColumn {
        Section(stringResource(R.string.section_controls)) {
            SwitchRow(stringResource(R.string.touch_control), settings.touchEnabled) {
                onChange(settings.copy(touchEnabled = it))
            }
            SwitchRow(stringResource(R.string.haptics), settings.haptics) {
                onChange(settings.copy(haptics = it))
            }
            LabeledSlider(
                stringResource(R.string.hide_delay, (settings.hideDelayMs / 1000f).toString()),
                settings.hideDelayMs,
                500f..5000f,
            ) { onChange(settings.copy(hideDelayMs = it / 100 * 100)) }
            Text(stringResource(R.string.double_press_title))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = settings.doublePressKey == 0,
                    onClick = { onChange(settings.copy(doublePressKey = 0)) },
                    label = { Text(stringResource(R.string.dp_none)) },
                )
                FilterChip(
                    selected = settings.doublePressKey == 1,
                    onClick = { onChange(settings.copy(doublePressKey = 1)) },
                    label = { Text(stringResource(R.string.dp_up)) },
                )
                FilterChip(
                    selected = settings.doublePressKey == 2,
                    onClick = { onChange(settings.copy(doublePressKey = 2)) },
                    label = { Text(stringResource(R.string.dp_down)) },
                )
            }
        }

        Section(stringResource(R.string.section_limits)) {
            Text(stringResource(R.string.limits_hint), style = MaterialTheme.typography.bodySmall)
            STREAMS.forEachIndexed { i, info ->
                val limit = settings.volumeLimits.getOrElse(i) { 100 }
                LabeledSlider(
                    stringResource(R.string.limit_label, stringResource(info.labelRes), limit),
                    limit,
                    10f..100f,
                ) { v ->
                    val next = settings.volumeLimits.toMutableList()
                    next[i] = v
                    onChange(settings.copy(volumeLimits = next))
                }
            }
        }

        Section(stringResource(R.string.section_station)) {
            Text(stringResource(R.string.station_desc), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.station_toggle), settings.stationEnabled) {
                onChange(settings.copy(stationEnabled = it))
            }
            if (!dndAccess) {
                Text(stringResource(R.string.dnd_perm_text), style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }) { Text(stringResource(R.string.dnd_perm_button)) }
            }
        }
    }
}

private class AppEntry(val pkg: String, val label: String)

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.pkg }
        .filter { it.pkg != context.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun AppsScreen(settings: PanelSettings, onChange: (PanelSettings) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    DisposableEffect(Unit) {
        // Loading the app list can take a moment, so do it off the main thread.
        val thread = Thread { apps = loadLaunchableApps(context) }
        thread.start()
        onDispose { }
    }
    var query by remember { mutableStateOf("") }
    val loaded = apps
    val shown = loaded?.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        ?: emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.apps_desc), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Section(stringResource(R.string.alt_position_title)) {
                Text(stringResource(R.string.alt_position_hint), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PositionPicker(
                        posX = settings.altPosX,
                        posY = settings.altPosY,
                        markerWdp = settings.widthDp + 24,
                        markerHdp = settings.heightDp + 24,
                        cornerDp = settings.cornerRadiusDp,
                        markerColor = if (settings.showFrame) {
                            Color(settings.colorArgb)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        },
                    ) { fx, fy ->
                        onChange(settings.copy(altPosX = fx.coerceIn(0f, 1f), altPosY = fy.coerceIn(0f, 1f)))
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search_apps)) },
            )
        }
        if (loaded == null) {
            item { Text(stringResource(R.string.loading_apps)) }
        } else if (shown.isEmpty()) {
            item { Text(stringResource(R.string.no_apps)) }
        } else {
            items(shown, key = { it.pkg }) { app ->
                val mode = settings.appRules[app.pkg] ?: RULE_NORMAL
                fun setRule(newMode: Int) {
                    val rules = settings.appRules.toMutableMap()
                    if (newMode == RULE_NORMAL) {
                        rules.remove(app.pkg)
                    } else {
                        rules[app.pkg] = newMode
                    }
                    onChange(settings.copy(appRules = rules))
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleSmall)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = mode == RULE_NORMAL,
                                onClick = { setRule(RULE_NORMAL) },
                                label = { Text(stringResource(R.string.rule_normal)) },
                            )
                            FilterChip(
                                selected = mode == RULE_HIDE,
                                onClick = { setRule(RULE_HIDE) },
                                label = { Text(stringResource(R.string.rule_hide)) },
                            )
                            FilterChip(
                                selected = mode == RULE_ALT_POSITION,
                                onClick = { setRule(RULE_ALT_POSITION) },
                                label = { Text(stringResource(R.string.rule_alt)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppScreen(settings: PanelSettings, onChange: (PanelSettings) -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }

    ScreenColumn {
        Section(stringResource(R.string.section_theme)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = settings.themeMode == THEME_SYSTEM,
                    onClick = { onChange(settings.copy(themeMode = THEME_SYSTEM)) },
                    label = { Text(stringResource(R.string.theme_system)) },
                )
                FilterChip(
                    selected = settings.themeMode == THEME_LIGHT,
                    onClick = { onChange(settings.copy(themeMode = THEME_LIGHT)) },
                    label = { Text(stringResource(R.string.theme_light)) },
                )
                FilterChip(
                    selected = settings.themeMode == THEME_DARK,
                    onClick = { onChange(settings.copy(themeMode = THEME_DARK)) },
                    label = { Text(stringResource(R.string.theme_dark)) },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(stringResource(R.string.dynamic_color), settings.dynamicColor) {
                    onChange(settings.copy(dynamicColor = it))
                }
            }
        }
        Text(stringResource(R.string.about_text, version), style = MaterialTheme.typography.bodySmall)
    }
}

// ───────────────────────────── The activity ─────────────────────────────

class MainActivity : ComponentActivity() {

    private var serviceEnabled by mutableStateOf(false)
    private var dndAccess by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled()
        dndAccess = getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted ?: true
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val cn = ComponentName(this, VolumeAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) ||
                it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    @Composable
    private fun AppRoot() {
        var settings by remember { mutableStateOf(Prefs.load(this@MainActivity)) }
        // Every change is saved immediately.
        val onChange: (PanelSettings) -> Unit = { new ->
            settings = new
            Prefs.save(this@MainActivity, new)
        }

        KanadeTheme(settings.themeMode, settings.dynamicColor) {
            var tab by rememberSaveable { mutableStateOf(0) }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        TABS.forEachIndexed { i, t ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { PathIcon(t.glyph.pathData, LocalContentColor.current, 24.dp) },
                                label = { Text(stringResource(t.labelRes), maxLines = 1) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (tab) {
                        0 -> HomeScreen(settings, serviceEnabled)
                        1 -> PanelScreen(settings, onChange)
                        2 -> BehaviorScreen(settings, dndAccess, onChange)
                        3 -> AppsScreen(settings, onChange)
                        else -> AppScreen(settings, onChange)
                    }
                }
            }
        }
    }
}
