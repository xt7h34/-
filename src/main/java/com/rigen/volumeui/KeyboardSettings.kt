package com.rigen.volumeui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Settings > Keyboard: turn the keyboard on, try it, see how the learning week is going,
 * and change how it looks.
 */

internal fun kbIsEnabled(c: Context): Boolean {
    val imm = c.getSystemService(InputMethodManager::class.java) ?: return false
    val name = KanadeKeyboardService::class.java.name
    return imm.enabledInputMethodList.any { it.packageName == c.packageName && it.serviceName == name }
}

internal fun kbIsSelected(c: Context): Boolean {
    val current = Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
    return current.startsWith(c.packageName + "/") && current.endsWith(KanadeKeyboardService::class.java.name)
}

@Composable
internal fun KeyboardPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(KbPrefs.load(context)) }
    fun update(n: KbConfig) {
        cfg = n
        KbPrefs.save(context, n)
    }

    var enabled by remember { mutableStateOf(kbIsEnabled(context)) }
    var selected by remember { mutableStateOf(kbIsSelected(context)) }
    var daysLeft by remember { mutableStateOf(KbPrefs.daysLeft(context)) }
    var words by remember { mutableIntStateOf(0) }
    var wipeCount by remember { mutableIntStateOf(0) }
    var confirmWipe by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }

    // The system settings are a different screen: look again every second while this page is open.
    LaunchedEffect(Unit) {
        while (true) {
            enabled = kbIsEnabled(context)
            selected = kbIsSelected(context)
            daysLeft = KbPrefs.daysLeft(context)
            delay(1000)
        }
    }
    LaunchedEffect(wipeCount) {
        words = withContext(Dispatchers.Default) { KbLearner(context).also { it.load() }.wordCount }
    }

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.keepalive_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    PageScaffold(stringResource(R.string.kb_title), onBack) { padding ->
        PageColumn(padding) {
            ChoiceRow(
                listOf(
                    stringResource(R.string.kb_tab_general),
                    stringResource(R.string.kb_tab_design),
                    stringResource(R.string.kb_tab_effects),
                    stringResource(R.string.kb_tab_layout),
                    stringResource(R.string.kbs_tab),
                ),
                tab,
            ) { tab = it }
            when (tab) {
                1 -> KbDesignTab(cfg) { update(it) }
                2 -> KbEffectsTab(cfg) { update(it) }
                3 -> KbLayoutTab(cfg) { update(it) }
                4 -> KbSnippetsTab()
                else -> KbGeneralTab(
                    cfg = cfg,
                    enabled = enabled,
                    selected = selected,
                    daysLeft = daysLeft,
                    words = words,
                    confirmWipe = confirmWipe,
                    test = test,
                    onTest = { test = it },
                    onOpenSettings = { open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onWipe = {
                        if (!confirmWipe) {
                            confirmWipe = true
                        } else {
                            KbLearner.wipeFile(context)
                            KbPrefs.resetLearning(context)
                            KbPrefs.bumpWipe(context)
                            confirmWipe = false
                            wipeCount++
                        }
                    },
                    update = { update(it) },
                )
            }
        }
    }
}

/** The first tab: turning the keyboard on, trying it, and how the learning is going. */
@Composable
private fun KbGeneralTab(
    cfg: KbConfig,
    enabled: Boolean,
    selected: Boolean,
    daysLeft: Int?,
    words: Int,
    confirmWipe: Boolean,
    test: String,
    onTest: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onWipe: () -> Unit,
    update: (KbConfig) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup {
                SettingsItem(
                    glyph = Glyph.KEYBOARD,
                    iconColor = if (enabled) ICON_GREEN else ICON_ORANGE,
                    title = stringResource(R.string.kb_step_enable),
                    summary = stringResource(if (enabled) R.string.kb_enabled_on else R.string.kb_enabled_off),
                ) { onOpenSettings() }
                ItemDivider()
                SettingsItem(
                    glyph = Glyph.TUNE,
                    iconColor = if (selected) ICON_GREEN else ICON_ORANGE,
                    title = stringResource(R.string.kb_step_choose),
                    summary = stringResource(if (selected) R.string.kb_chosen_on else R.string.kb_chosen_off),
                    enabled = enabled,
                ) {
                    context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                }
            }

            Section(stringResource(R.string.kb_try_title)) {
                OutlinedTextField(
                    value = test,
                    onValueChange = { onTest(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.kb_try_hint)) },
                )
            }

            Section(stringResource(R.string.kb_learn_title)) {
                val status = when {
                    !cfg.suggest -> stringResource(R.string.kb_learn_off)
                    cfg.earlySuggest -> stringResource(R.string.kb_learn_early)
                    daysLeft == null -> stringResource(R.string.kb_learn_notstarted)
                    daysLeft == 0 -> stringResource(R.string.kb_learn_active)
                    else -> stringResource(R.string.kb_learn_days, daysLeft ?: 0)
                }
                Text(status)
                val done = daysLeft?.let { (KB_LEARN_DAYS - it).coerceIn(0, KB_LEARN_DAYS) } ?: 0
                LinearProgressIndicator(
                    progress = { done / KB_LEARN_DAYS.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.kb_learn_words, words),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(stringResource(R.string.kb_suggest), cfg.suggest) { update(cfg.copy(suggest = it)) }
                SwitchRow(stringResource(R.string.kb_learn), cfg.learn) { update(cfg.copy(learn = it)) }
                SwitchRow(stringResource(R.string.kb_hint), cfg.showHint) { update(cfg.copy(showHint = it)) }
                SwitchRow(stringResource(R.string.kb_early), cfg.earlySuggest) { update(cfg.copy(earlySuggest = it)) }
                Text(
                    stringResource(R.string.kb_early_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onWipe,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (confirmWipe) R.string.kb_clear_confirm else R.string.kb_clear))
                }
            }

            var confirmClip by remember { mutableStateOf(false) }
            Section(stringResource(R.string.kb_clip_title)) {
                SwitchRow(stringResource(R.string.kb_clip_on), cfg.clipOn) { update(cfg.copy(clipOn = it)) }
                Text(
                    stringResource(R.string.kb_clip_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cfg.clipOn) {
                    Column {
                        Text(stringResource(R.string.kb_clip_max, cfg.clipMax))
                        Slider(
                            value = cfg.clipMax.toFloat(),
                            onValueChange = { update(cfg.copy(clipMax = it.roundToInt())) },
                            valueRange = 5f..50f,
                        )
                    }
                    Text(stringResource(R.string.kb_clip_expire))
                    val hours = intArrayOf(0, 1, 24, 168)
                    ChoiceRow(
                        listOf(
                            stringResource(R.string.kb_clip_never),
                            stringResource(R.string.kb_clip_1h),
                            stringResource(R.string.kb_clip_1d),
                            stringResource(R.string.kb_clip_7d),
                        ),
                        hours.indexOf(cfg.clipHours),
                    ) { update(cfg.copy(clipHours = hours[it])) }
                }
                OutlinedButton(
                    onClick = {
                        if (!confirmClip) {
                            confirmClip = true
                        } else {
                            KbClipStore.wipeFile(context)
                            KbPrefs.bumpClip(context)
                            confirmClip = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (confirmClip) R.string.kb_clip_clear_confirm else R.string.kb_clip_clear))
                }
            }

            KbTranslateSection(cfg, update)

            Section(stringResource(R.string.kb_feedback_title)) {
                SwitchRow(stringResource(R.string.kb_haptics), cfg.haptics) { update(cfg.copy(haptics = it)) }
                SwitchRow(stringResource(R.string.kb_sound), cfg.sound) { update(cfg.copy(sound = it)) }
            }

            Section(stringResource(R.string.kb_privacy_title)) {
                Text(
                    stringResource(R.string.kb_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
internal fun ChoiceRow(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    .clickable { onPick(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}
