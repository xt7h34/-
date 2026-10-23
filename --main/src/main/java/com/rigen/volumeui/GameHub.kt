package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Game Hub: the list of games, and the frame every new game screen shares
 * (title, score cards, hint, play area, restart button).
 */

@Composable
internal fun GameHubPage(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val bestBlocks = remember { context.getSharedPreferences("kanade_blocks", Context.MODE_PRIVATE).getInt("best", 0) }
    val best2048 = remember { context.getSharedPreferences("kanade_2048", Context.MODE_PRIVATE).getInt("best", 0) }
    val bestStack = remember { context.getSharedPreferences("kanade_stack", Context.MODE_PRIVATE).getInt("best", 0) }

    @Composable
    fun summary(desc: Int, best: Int): String =
        if (best > 0) stringResource(desc) + " · " + stringResource(R.string.games_best, best.toString()) else stringResource(desc)

    PageScaffold(stringResource(R.string.games_title), onBack) { padding ->
        PageColumn(padding) {
            SettingsGroup {
                SettingsItem(Glyph.BLOCKS, ICON_PURPLE, stringResource(R.string.blocks_title), summary(R.string.blocks_summary, bestBlocks)) { onOpen(PAGE_BLOCKS) }
                ItemDivider()
                SettingsItem(Glyph.TILES, ICON_ORANGE, stringResource(R.string.g2048_title), summary(R.string.g2048_summary, best2048)) { onOpen(PAGE_2048) }
                ItemDivider()
                SettingsItem(Glyph.CARDS, ICON_PINK, stringResource(R.string.memory_title), stringResource(R.string.memory_summary)) { onOpen(PAGE_MEMORY) }
                ItemDivider()
                SettingsItem(Glyph.LAYERS, ICON_BLUE, stringResource(R.string.stack_title), summary(R.string.stack_summary, bestStack)) { onOpen(PAGE_STACK) }
                ItemDivider()
                SettingsItem(Glyph.FACTORY, ICON_GREEN, stringResource(R.string.ty_title), stringResource(R.string.ty_summary)) { onOpen(PAGE_TYCOON) }
            }
        }
    }
}

/** The page every simple game uses: title in the corner, score cards, a hint line, the play area and a restart button. */
@Composable
internal fun GameFrame(
    title: String,
    onBack: () -> Unit,
    stats: List<Pair<String, String>>,
    hint: String,
    onRestart: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit,
) {
    PageScaffold(title, onBack, pinTitle = true) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                stats.forEach { (label, value) -> GameScoreCard(label, value, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp)),
            ) { content() }
            if (onRestart != null) {
                TextButton(onClick = onRestart) { Text(stringResource(R.string.blocks_restart)) }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
internal fun GameScoreCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

/** The card that appears in the middle of the play area when a round ends. */
@Composable
internal fun BoxScope.GameOverlay(
    title: String,
    lines: List<String>,
    button: String,
    onClick: () -> Unit,
    secondButton: String? = null,
    onSecond: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.align(Alignment.Center).padding(18.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xF21B2038),
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            lines.forEach {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFBFC8FF), fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) { Text(button) }
            if (secondButton != null && onSecond != null) TextButton(onClick = onSecond) { Text(secondButton) }
        }
    }
}
