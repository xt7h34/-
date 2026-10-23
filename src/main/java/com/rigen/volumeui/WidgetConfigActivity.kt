package com.rigen.volumeui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The look of one home screen widget: which background, and how see-through. The launcher opens this
 * when a widget is added and again from the widget's own "reconfigure" option (Android 9 and newer).
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Prefs.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out must leave the widget un-added, so the answer starts as "cancelled".
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val kind = if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            null
        } else {
            kindOf(AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className)
        }
        if (kind == null) {
            finish()
            return
        }
        val settings = Prefs.load(this)
        setContent {
            KanadeTheme(settings.themeMode, settings.dynamicColor) {
                WidgetLookScreen(
                    kind = kind,
                    initial = WidgetLooks.load(this, id),
                    onCancel = { finish() },
                    onSave = { look ->
                        WidgetLooks.save(this, id, look)
                        AppWidgetManager.getInstance(this).updateAppWidget(id, WidgetDesigns.render(this, kind, id))
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    },
                )
            }
        }
    }

    private fun kindOf(className: String?): WidgetKind? = when (className?.substringAfterLast('.')) {
        "NextAlarmWidget" -> WidgetKind.ALARM
        "PrayerWidget" -> WidgetKind.PRAYER
        "RewardsWidget" -> WidgetKind.REWARDS
        "SleepWidget" -> WidgetKind.SLEEP
        "HabitsWidget" -> WidgetKind.HABITS
        "ReelsWidget" -> WidgetKind.REELS
        "GoalsWidget" -> WidgetKind.GOALS
        else -> null
    }
}

private fun kindEmoji(kind: WidgetKind): String = when (kind) {
    WidgetKind.ALARM -> "\u23F0"
    WidgetKind.PRAYER -> "\uD83D\uDD4C"
    WidgetKind.REWARDS -> "\uD83C\uDF81"
    WidgetKind.SLEEP -> "\uD83C\uDF19"
    WidgetKind.HABITS -> "\u2705"
    WidgetKind.REELS -> "\uD83E\uDDE0"
    WidgetKind.GOALS -> "\uD83C\uDFAF"
}

/** The two ends of the background a style gives this widget, for drawing a swatch or the preview. */
private fun swatchColors(kind: WidgetKind, style: Int, solid: Int): List<Color> {
    val p = WidgetStyle.palette(WidgetLook(style, solid, 100), kind)
    return listOf(Color(p.start), Color(p.end))
}

private fun styleName(style: Int): Int = when (style) {
    0 -> R.string.widget_style_0
    8 -> R.string.widget_style_8
    else -> WidgetPalettes.STYLES[style - 1].nameRes
}

@Composable
private fun WidgetLookScreen(kind: WidgetKind, initial: WidgetLook, onCancel: () -> Unit, onSave: (WidgetLook) -> Unit) {
    var style by remember { mutableIntStateOf(initial.style) }
    var solid by remember { mutableIntStateOf(initial.solid) }
    var opacity by remember { mutableFloatStateOf(initial.opacity.toFloat()) }
    val look = WidgetLook(style, solid, opacity.toInt())
    val pal = WidgetStyle.palette(look, kind)
    val alpha = opacity / 100f

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.widget_cfg_title), style = MaterialTheme.typography.headlineSmall)

            // A made-up wallpaper behind the card, so the transparency can be seen.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF3E7BD8), Color(0xFFF2C14E), Color(0xFFE05C7B))))
                    .padding(18.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(swatchColors(kind, style, solid).map { it.copy(alpha = alpha) }))
                        .padding(18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        Text(kindEmoji(kind), fontSize = 22.sp)
                        Text(
                            stringResource(R.string.widget_cfg_sample),
                            color = Color(pal.primary),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Text(stringResource(R.string.widget_cfg_style), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (i in 0..8) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { style = i },
                    ) {
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(swatchColors(kind, i, solid)))
                                .border(
                                    if (i == style) 3.dp else 1.dp,
                                    if (i == style) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                ),
                        )
                        Text(
                            stringResource(styleName(i)),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                            maxLines = 1,
                        )
                    }
                }
            }

            if (style == 8) {
                Text(stringResource(R.string.widget_cfg_color), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (c in WidgetPalettes.SOLIDS) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (c == solid) 3.dp else 1.dp,
                                    if (c == solid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                )
                                .clickable { solid = c },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.widget_cfg_opacity) + ": " + opacity.toInt() + "%",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 20f..100f)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_cfg_cancel)) }
                Button(onClick = { onSave(look) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_cfg_save)) }
            }
        }
    }
}
