package com.rigen.volumeui

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

// ───────────────────────────── Theme ─────────────────────────────

private val KanadeLight = lightColorScheme(
    primary = Color(0xFF4F5BD5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E2FF),
    onPrimaryContainer = Color(0xFF14196B),
    secondary = Color(0xFF8A5CC4),
    tertiary = Color(0xFFC2638F),
    background = Color(0xFFF3F3F8),
    surface = Color(0xFFF3F3F8),
    surfaceVariant = Color(0xFFE4E3EB),
    surfaceContainer = Color.White,
    outlineVariant = Color(0xFFE3E3EA),
)

private val KanadeDark = darkColorScheme(
    primary = Color(0xFFB4BBFF),
    onPrimary = Color(0xFF1C2178),
    primaryContainer = Color(0xFF343B9E),
    onPrimaryContainer = Color(0xFFE0E2FF),
    secondary = Color(0xFFD6BAFF),
    tertiary = Color(0xFFFFB0D2),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF2A2C33),
    surfaceContainer = Color(0xFF1A1C22),
    outlineVariant = Color(0xFF34363D),
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

// ───────────────────────────── Building blocks ─────────────────────────────

private val SWATCHES = listOf(
    0xFF1E1E1E, 0xFF000000, 0xFFFFFFFF, 0xFF1565C0,
    0xFF2E7D32, 0xFFC62828, 0xFF6A1B9A, 0xFFEF6C00,
    0xFF4F5BA5, 0xFF3A9DB5, 0xFF43B85A, 0xFFE0558A,
).map { it.toInt() }

private val ICON_BLUE = Color(0xFF3D7BF5)
private val ICON_PURPLE = Color(0xFF7B61FF)
private val ICON_GREEN = Color(0xFF34A853)
private val ICON_ORANGE = Color(0xFFF29900)
private val ICON_PINK = Color(0xFFE0558A)
private val ICON_RED = Color(0xFFE5484D)

private val LocalPageScroll = compositionLocalOf<ScrollState> { error("No page scroll state") }
private val LocalPageList = compositionLocalOf<LazyListState> { error("No page list state") }

private val HEADER_EXPANDED = 250.dp
private val HEADER_COLLAPSED = 96.dp

/** The empty room at the top of a page where the big title lives. */
@Composable
private fun HeaderSpacer() {
    Spacer(
        Modifier
            .statusBarsPadding()
            .height(HEADER_EXPANDED),
    )
}

/**
 * The page title. It starts big and centered. Scrolling moves it to the top corner and makes it
 * smaller, and scrolling further makes it fade away, like in the system Settings app.
 */
@Composable
private fun CollapsingTitle(title: String, startPad: Dp, scrollPx: () -> Int) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 36.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .padding(start = startPad, top = statusTop)
            .graphicsLayer {
                val expandedPx = HEADER_EXPANDED.toPx()
                val collapsedPx = HEADER_COLLAPSED.toPx()
                val status = statusTop.toPx()
                val range = (expandedPx - collapsedPx).coerceAtLeast(1f)
                val s = scrollPx().toFloat()
                val p = (s / range).coerceIn(0f, 1f)

                val scale = 1f + (0.62f - 1f) * p
                transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
                scaleX = scale
                scaleY = scale

                // From the middle of the screen to the corner.
                val centerShift = ((screenW.toPx() - size.width) / 2f - startPad.toPx()) * (1f - p)
                translationX = if (rtl) -centerShift else centerShift

                // From the big header's middle to the small header's middle.
                val center0 = status + expandedPx * 0.42f
                val center1 = status + collapsedPx * 0.45f
                val center = center0 + (center1 - center0) * p
                translationY = center - (status + size.height / 2f)

                // Once the page has scrolled further, the title fades out.
                val fade = ((s - range) / 60.dp.toPx()).coerceIn(0f, 1f)
                alpha = 1f - fade
            },
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    IconButton(
        onClick = onBack,
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 8.dp, top = 6.dp)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f), CircleShape),
    ) {
        PathIcon(
            Glyph.BACK.pathData,
            MaterialTheme.colorScheme.onBackground,
            24.dp,
            if (rtl) Modifier.scale(-1f, 1f) else Modifier,
        )
    }
}

/** A page with the Settings-style title. Set [lazy] when the page scrolls with a LazyColumn. */
@Composable
private fun PageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    lazy: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = rememberScrollState()
    val list = rememberLazyListState()
    CompositionLocalProvider(LocalPageScroll provides scroll, LocalPageList provides list) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            content(PaddingValues(0.dp))
            CollapsingTitle(title, if (onBack != null) 64.dp else 24.dp) {
                if (lazy) {
                    if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset else Int.MAX_VALUE / 4
                } else {
                    scroll.value
                }
            }
            if (onBack != null) BackButton(onBack)
        }
    }
}

@Composable
private fun PageColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(LocalPageScroll.current)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeaderSpacer()
        content()
        Spacer(
            Modifier
                .navigationBarsPadding()
                .height(24.dp),
        )
    }
}

/** A rounded group of rows, like the groups in the system Settings. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(content = content)
    }
}

@Composable
private fun ItemDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 76.dp, end = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** One row: a colored round icon, a title and a short summary. */
@Composable
private fun SettingsItem(
    glyph: Glyph,
    iconColor: Color,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    var rowModifier = Modifier.fillMaxWidth()
    if (enabled && onClick != null) rowModifier = rowModifier.clickable(onClick = onClick)
    Row(
        modifier = rowModifier
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconColor),
            contentAlignment = Alignment.Center,
        ) {
            PathIcon(glyph.pathData, Color.White, 22.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A card with a small colored title and free content (sliders, switches...). */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
        KanadeSwitch(checked, onChange)
    }
}

/** A glassy pill switch: a green track and a wide, shiny knob. */
@Composable
private fun KanadeSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val trackW = 62.dp
    val trackH = 34.dp
    val knobW = 40.dp
    val knobH = 30.dp
    val margin = 2.dp
    val offset by animateDpAsState(if (checked) trackW - knobW - margin else margin, label = "knob")
    val trackTop by animateColorAsState(if (checked) Color(0xFF5BC76F) else Color(0xFF8D8F99), label = "trackTop")
    val trackBottom by animateColorAsState(if (checked) Color(0xFF3AAE55) else Color(0xFF6F717B), label = "trackBottom")
    val interaction = remember { MutableInteractionSource() }
    val knobTop = if (checked) Color(0xFF93E3A6) else Color(0xFFD2D4DB)
    val knobBottom = if (checked) Color(0xFF62CC7B) else Color(0xFFA9ABB4)

    Box(
        modifier = Modifier
            .size(trackW, trackH)
            .clip(RoundedCornerShape(trackH / 2))
            .background(Brush.verticalGradient(listOf(trackTop, trackBottom)))
            .clickable(interactionSource = interaction, indication = null, role = Role.Switch) {
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = offset)
                .size(knobW, knobH)
                .clip(RoundedCornerShape(knobH / 2))
                .background(Brush.verticalGradient(listOf(knobTop, knobBottom)))
                .border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(knobH / 2)),
        ) {
            if (checked) {
                // The white "C" shine inside the knob.
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val top = h * 0.24f
                    val bottom = h * 0.76f
                    val r = (bottom - top) / 2f
                    val rightX = w * 0.80f
                    val leftX = w * 0.28f
                    val shine = Path().apply {
                        moveTo(leftX, top)
                        lineTo(rightX - r, top)
                        arcTo(Rect(rightX - 2 * r, top, rightX, bottom), -90f, 180f, false)
                        lineTo(leftX, bottom)
                    }
                    drawPath(
                        shine,
                        Color.White.copy(alpha = 0.92f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
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

// ───────────────────────────── Pages ─────────────────────────────

@Composable
private fun AccountCard() {
    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF4F1FF)),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1.4f),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.account_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.account_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomePage(serviceEnabled: Boolean, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    PageScaffold(stringResource(R.string.app_title), null) { padding ->
        Box(Modifier.fillMaxSize()) {
            PageColumn(padding) {
                AccountCard()

                SettingsGroup {
                    SettingsItem(
                        glyph = Glyph.ACCESS,
                        iconColor = if (serviceEnabled) ICON_GREEN else ICON_ORANGE,
                        title = stringResource(if (serviceEnabled) R.string.service_on else R.string.service_off),
                        summary = stringResource(
                            if (serviceEnabled) R.string.service_on_summary else R.string.service_off_summary,
                        ),
                    ) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    ItemDivider()
                    SettingsItem(
                        glyph = Glyph.BATTERY,
                        iconColor = if (unrestricted) ICON_GREEN else ICON_ORANGE,
                        title = stringResource(R.string.keepalive_title),
                        summary = stringResource(
                            if (unrestricted) R.string.keepalive_summary_on else R.string.keepalive_summary_off,
                        ),
                    ) { onOpen(PAGE_KEEPALIVE) }
                }

                SettingsGroup {
                    SettingsItem(
                        Glyph.VOLUME, ICON_BLUE,
                        stringResource(R.string.item_volume_title),
                        stringResource(R.string.item_volume_summary),
                    ) { onOpen(PAGE_VOLUME) }
                    ItemDivider()
                    // Not available yet: it arrives with the Kanade ecosystem.
                    SettingsItem(
                        Glyph.CLOCK, ICON_PURPLE,
                        stringResource(R.string.clock_title),
                        stringResource(R.string.clock_summary),
                        enabled = false,
                    )
                }

                SettingsGroup {
                    SettingsItem(
                        Glyph.PALETTE, ICON_PINK,
                        stringResource(R.string.item_app_title),
                        stringResource(R.string.item_app_summary),
                    ) { onOpen(PAGE_APP) }
                }

                // Room for the floating search bar.
                Spacer(Modifier.height(72.dp))
            }
            SettingsSearch(onOpen, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private class SearchEntry(val title: String, val where: String, val page: String)

/** Every setting people may look for, and the page it lives on. */
@Composable
private fun searchIndex(): List<SearchEntry> {
    val panelPage = stringResource(R.string.item_panel_title)
    val behaviorPage = stringResource(R.string.item_behavior_title)
    val stationPage = stringResource(R.string.section_station)
    val appsPage = stringResource(R.string.apps_title)
    val appPage = stringResource(R.string.item_app_title)
    val keepPage = stringResource(R.string.keepalive_title)
    return listOf(
        SearchEntry(stringResource(R.string.item_volume_title), stringResource(R.string.app_title), PAGE_VOLUME),
        SearchEntry(stringResource(R.string.panel_style), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.vertical_panel), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.show_frame), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.show_dnd_icon), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.label_corner_radius), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.label_panel_width), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.label_panel_height), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.label_red_threshold), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.panel_color), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.bar_color), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.section_position), panelPage, PAGE_PANEL),
        SearchEntry(stringResource(R.string.touch_control), behaviorPage, PAGE_BEHAVIOR),
        SearchEntry(stringResource(R.string.haptics), behaviorPage, PAGE_BEHAVIOR),
        SearchEntry(stringResource(R.string.label_hide_delay), behaviorPage, PAGE_BEHAVIOR),
        SearchEntry(stringResource(R.string.double_press_title), behaviorPage, PAGE_BEHAVIOR),
        SearchEntry(stringResource(R.string.section_limits), behaviorPage, PAGE_BEHAVIOR),
        SearchEntry(stringResource(R.string.section_station), stationPage, PAGE_STATION),
        SearchEntry(stringResource(R.string.station_toggle), stationPage, PAGE_STATION),
        SearchEntry(stringResource(R.string.station_mute_all), stationPage, PAGE_STATION),
        SearchEntry(stringResource(R.string.station_dnd_button), stationPage, PAGE_STATION),
        SearchEntry(stringResource(R.string.apps_title), appsPage, PAGE_APPS),
        SearchEntry(stringResource(R.string.alt_position_title), appsPage, PAGE_APPS),
        SearchEntry(stringResource(R.string.section_theme), appPage, PAGE_APP),
        SearchEntry(stringResource(R.string.dynamic_color), appPage, PAGE_APP),
        SearchEntry(stringResource(R.string.section_language), appPage, PAGE_APP),
        SearchEntry(stringResource(R.string.section_diag), appPage, PAGE_APP),
        SearchEntry(stringResource(R.string.keepalive_title), keepPage, PAGE_KEEPALIVE),
    )
}

/** The floating search bar: a purple pill with a glass magnifier, and the results above it. */
@Composable
private fun SettingsSearch(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val index = searchIndex()
    val q = query.trim()
    val results = if (q.isEmpty()) {
        emptyList()
    } else {
        index.filter { it.title.contains(q, ignoreCase = true) || it.where.contains(q, ignoreCase = true) }.take(6)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (q.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                if (results.isEmpty()) {
                    Text(
                        stringResource(R.string.search_empty),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    results.forEachIndexed { i, r ->
                        if (i > 0) ItemDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = ""
                                    focus.clearFocus()
                                    onOpen(r.page)
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Text(r.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                r.where,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            // The purple pill, starting under the glass circle.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 28.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF5A1FD6), Color(0xFF8A2BFA))))
                    .padding(start = 40.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.search_hint),
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        focus.clearFocus()
                    }) {
                        PathIcon(Glyph.CLOSE.pathData, Color.White, 20.dp)
                    }
                }
            }
            // The glass circle with the magnifier.
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFD9C2FF), Color(0xFFB287F7))))
                    .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                PathIcon(Glyph.SEARCH.pathData, Color.White, 26.dp)
            }
        }
    }
}

/** How to stop the system from putting the service to sleep. */
@Composable
private fun KeepAlivePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.keepalive_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    PageScaffold(stringResource(R.string.keepalive_title), onBack) { padding ->
        PageColumn(padding) {
            Section(stringResource(R.string.keepalive_title)) {
                Text(stringResource(R.string.keepalive_why), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(if (unrestricted) R.string.keepalive_status_on else R.string.keepalive_status_off),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Section(stringResource(R.string.keepalive_steps_title)) {
                Text(stringResource(R.string.keepalive_steps), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text(stringResource(R.string.keepalive_open_battery)) }
                OutlinedButton(onClick = {
                    open(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                }) { Text(stringResource(R.string.keepalive_open_app)) }
            }
        }
    }
}

/** The "Volume Panel" option: a live preview and the pages that belong to it. */
@Composable
private fun VolumeHubPage(settings: PanelSettings, onOpen: (String) -> Unit, onBack: () -> Unit) {
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
    var previewDnd by remember { mutableStateOf(false) }
    val actions = remember {
        PanelActions(
            onSeek = { stream, f -> levels[stream] = (f * 15).roundToInt().coerceIn(0, 15) },
            onTouch = { },
            onToggleStation = { expanded = !expanded },
            onMedia = { },
            onMuteAll = {
                val anyOn = STREAMS.any { (levels[it.stream] ?: 0) > 0 }
                STREAMS.forEach { levels[it.stream] = if (anyOn) 0 else 8 }
            },
            onToggleDnd = { previewDnd = !previewDnd },
            // In the preview the gear just closes the card.
            onOpenSettings = { expanded = false },
            onToggleMute = {
                val now = levels[AudioManager.STREAM_MUSIC] ?: 0
                levels[AudioManager.STREAM_MUSIC] = if (now > 0) 0 else 8
            },
        )
    }

    PageScaffold(stringResource(R.string.item_volume_title), onBack) { padding ->
        PageColumn(padding) {
            Section(stringResource(R.string.preview_title)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PanelOrStation(
                        settings = settings,
                        state = PanelState(
                            stream = AudioManager.STREAM_MUSIC,
                            level = levels[AudioManager.STREAM_MUSIC] ?: 0,
                            max = 15,
                            dnd = previewDnd,
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
            }

            SettingsGroup {
                SettingsItem(
                    Glyph.VOLUME, ICON_BLUE,
                    stringResource(R.string.item_panel_title),
                    stringResource(R.string.item_panel_summary),
                ) { onOpen(PAGE_PANEL) }
                ItemDivider()
                SettingsItem(
                    Glyph.TUNE, ICON_PURPLE,
                    stringResource(R.string.item_behavior_title),
                    stringResource(R.string.item_behavior_summary),
                ) { onOpen(PAGE_BEHAVIOR) }
                ItemDivider()
                SettingsItem(
                    Glyph.MORE, ICON_GREEN,
                    stringResource(R.string.section_station),
                    stringResource(if (settings.stationEnabled) R.string.station_on else R.string.station_off),
                ) { onOpen(PAGE_STATION) }
                ItemDivider()
                SettingsItem(
                    Glyph.APPS, ICON_ORANGE,
                    stringResource(R.string.apps_title),
                    stringResource(R.string.apps_summary),
                ) { onOpen(PAGE_APPS) }
            }

            SettingsGroup {
                SettingsItem(
                    glyph = Glyph.PLAY,
                    iconColor = ICON_BLUE,
                    title = stringResource(R.string.test_panel),
                ) {
                    val s = VolumeAccessibilityService.instance
                    if (s != null) {
                        s.showPanel(ignoreRules = true)
                    } else {
                        Toast.makeText(context, R.string.enable_service_first, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelPage(settings: PanelSettings, onChange: (PanelSettings) -> Unit, onBack: () -> Unit) {
    var previewLevel by remember { mutableIntStateOf(13) }
    var previewDnd by remember { mutableStateOf(false) }

    // The capsule and the bar are tall shapes, so choosing one turns the panel vertical.
    fun setStyle(style: Int) {
        var next = settings.copy(panelStyle = style)
        if (style != STYLE_CLASSIC && !settings.vertical) {
            next = next.copy(vertical = true, widthDp = 64, heightDp = 260)
        }
        onChange(next)
    }

    PageScaffold(stringResource(R.string.item_panel_title), onBack) { padding ->
        PageColumn(padding) {
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

            Section(stringResource(R.string.preview_title)) {
                LabeledSlider(stringResource(R.string.preview_level, previewLevel), previewLevel, 0f..15f) {
                    previewLevel = it
                }
                SwitchRow(stringResource(R.string.preview_dnd), previewDnd) { previewDnd = it }
            }

            Section(stringResource(R.string.section_look)) {
                Text(stringResource(R.string.panel_style))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = settings.panelStyle == STYLE_CLASSIC,
                        onClick = { setStyle(STYLE_CLASSIC) },
                        label = { Text(stringResource(R.string.style_classic)) },
                    )
                    FilterChip(
                        selected = settings.panelStyle == STYLE_CAPSULE,
                        onClick = { setStyle(STYLE_CAPSULE) },
                        label = { Text(stringResource(R.string.style_capsule)) },
                    )
                    FilterChip(
                        selected = settings.panelStyle == STYLE_BAR,
                        onClick = { setStyle(STYLE_BAR) },
                        label = { Text(stringResource(R.string.style_bar)) },
                    )
                }
                Text(stringResource(R.string.style_hint), style = MaterialTheme.typography.bodySmall)
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
}

@Composable
private fun BehaviorPage(settings: PanelSettings, onChange: (PanelSettings) -> Unit, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.item_behavior_title), onBack) { padding ->
        PageColumn(padding) {
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
        }
    }
}

@Composable
private fun StationPage(
    settings: PanelSettings,
    dndAccess: Boolean,
    onChange: (PanelSettings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
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
    var previewDnd by remember { mutableStateOf(false) }
    val actions = remember {
        PanelActions(
            onSeek = { stream, f -> levels[stream] = (f * 15).roundToInt().coerceIn(0, 15) },
            onTouch = { },
            onToggleStation = { },
            onMedia = { },
            onMuteAll = {
                val anyOn = STREAMS.any { (levels[it.stream] ?: 0) > 0 }
                STREAMS.forEach { levels[it.stream] = if (anyOn) 0 else 8 }
            },
            onToggleDnd = { previewDnd = !previewDnd },
            onOpenSettings = { },
            onToggleMute = { },
        )
    }

    PageScaffold(stringResource(R.string.section_station), onBack) { padding ->
        PageColumn(padding) {
            Section(stringResource(R.string.section_station)) {
                Text(stringResource(R.string.station_desc), style = MaterialTheme.typography.bodySmall)
                SwitchRow(stringResource(R.string.station_toggle), settings.stationEnabled) {
                    onChange(settings.copy(stationEnabled = it))
                }
                SwitchRow(stringResource(R.string.station_mute_all), settings.stationMuteAll) {
                    onChange(settings.copy(stationMuteAll = it))
                }
                SwitchRow(stringResource(R.string.station_dnd_button), settings.stationDnd) {
                    onChange(settings.copy(stationDnd = it))
                }
            }

            Section(stringResource(R.string.preview_title)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PanelOrStation(
                        settings = settings.copy(stationEnabled = true),
                        state = PanelState(
                            stream = AudioManager.STREAM_MUSIC,
                            level = levels[AudioManager.STREAM_MUSIC] ?: 0,
                            max = 15,
                            dnd = previewDnd,
                            expanded = true,
                            levels = levels,
                            maxes = maxes,
                        ),
                        actions = actions,
                    )
                }
            }

            if (!dndAccess) {
                Section(stringResource(R.string.dnd_perm_title)) {
                    Text(stringResource(R.string.dnd_perm_text), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }) { Text(stringResource(R.string.dnd_perm_button)) }
                }
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
private fun AppsPage(settings: PanelSettings, onChange: (PanelSettings) -> Unit, onBack: () -> Unit) {
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

    PageScaffold(stringResource(R.string.apps_title), onBack, lazy = true) { padding ->
        LazyColumn(
            state = LocalPageList.current,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeaderSpacer() }
            item {
                Text(stringResource(R.string.apps_desc), style = MaterialTheme.typography.bodySmall)
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

private class DiagSnapshot(
    val created: Long,
    val connected: Long,
    val destroyed: Long,
    val errors: String,
)

private fun readDiag(context: Context) = DiagSnapshot(
    Diag.time(context, "svc_created"),
    Diag.time(context, "svc_connected"),
    Diag.time(context, "svc_destroyed"),
    Diag.errors(context),
)

@Composable
private fun AppPage(settings: PanelSettings, onChange: (PanelSettings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }
    var diag by remember { mutableStateOf(readDiag(context)) }
    val never = stringResource(R.string.diag_never)
    fun whenText(ms: Long): String =
        if (ms == 0L) never else DateFormat.getDateTimeInstance().format(Date(ms))

    fun setLanguage(code: String) {
        if (settings.language != code) {
            onChange(settings.copy(language = code))
            (context as? Activity)?.recreate()
        }
    }

    PageScaffold(stringResource(R.string.item_app_title), onBack) { padding ->
        PageColumn(padding) {
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

            Section(stringResource(R.string.section_language)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = settings.language.isEmpty(),
                        onClick = { setLanguage("") },
                        label = { Text(stringResource(R.string.lang_system)) },
                    )
                    FilterChip(
                        selected = settings.language == "ar",
                        onClick = { setLanguage("ar") },
                        label = { Text(stringResource(R.string.lang_ar)) },
                    )
                    FilterChip(
                        selected = settings.language == "en",
                        onClick = { setLanguage("en") },
                        label = { Text(stringResource(R.string.lang_en)) },
                    )
                }
            }

            Section(stringResource(R.string.section_diag)) {
                Text(stringResource(R.string.diag_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.diag_created, whenText(diag.created)))
                Text(stringResource(R.string.diag_connected, whenText(diag.connected)))
                Text(stringResource(R.string.diag_destroyed, whenText(diag.destroyed)))
                Text(
                    stringResource(R.string.diag_errors),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (diag.errors.isEmpty()) stringResource(R.string.diag_no_errors) else diag.errors,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { diag = readDiag(context) }) {
                        Text(stringResource(R.string.diag_refresh))
                    }
                    OutlinedButton(onClick = {
                        val report = "Kanade System $version | Android ${Build.VERSION.RELEASE} " +
                            "(API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                            "created: ${whenText(diag.created)}\n" +
                            "connected: ${whenText(diag.connected)}\n" +
                            "stopped: ${whenText(diag.destroyed)}\n\n" +
                            diag.errors
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Kanade diagnostics", report))
                        Toast.makeText(context, R.string.diag_copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.diag_copy)) }
                    OutlinedButton(onClick = {
                        Diag.clearErrors(context)
                        diag = readDiag(context)
                    }) { Text(stringResource(R.string.diag_clear)) }
                }
            }

            Text(stringResource(R.string.about_text, version), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ───────────────────────────── The activity ─────────────────────────────

class MainActivity : ComponentActivity() {

    private var serviceEnabled by mutableStateOf(false)
    private var dndAccess by mutableStateOf(true)

    /** Uses the language chosen inside the app, if there is one. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Prefs.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            // The service can ask for a page, for example from the Volume Station's gear.
            val startPage = this@MainActivity.intent?.getStringExtra(EXTRA_PAGE) ?: PAGE_HOME
            var page by rememberSaveable { mutableStateOf(startPage) }
            val parent = when (page) {
                PAGE_PANEL, PAGE_BEHAVIOR, PAGE_STATION, PAGE_APPS -> PAGE_VOLUME
                else -> PAGE_HOME
            }
            BackHandler(enabled = page != PAGE_HOME) { page = parent }
            val goBack = { page = parent }
            when (page) {
                PAGE_VOLUME -> VolumeHubPage(settings, { page = it }, goBack)
                PAGE_PANEL -> PanelPage(settings, onChange, goBack)
                PAGE_BEHAVIOR -> BehaviorPage(settings, onChange, goBack)
                PAGE_STATION -> StationPage(settings, dndAccess, onChange, goBack)
                PAGE_APPS -> AppsPage(settings, onChange, goBack)
                PAGE_APP -> AppPage(settings, onChange, goBack)
                PAGE_KEEPALIVE -> KeepAlivePage(goBack)
                else -> HomePage(serviceEnabled) { page = it }
            }
        }
    }
}
