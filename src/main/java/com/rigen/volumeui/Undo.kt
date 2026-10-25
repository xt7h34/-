package com.rigen.volumeui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

/** One thing that was just removed and can still come back. */
class UndoItem(val id: Long, val message: String, val onUndo: () -> Unit, val onExpire: () -> Unit)

/**
 * "Deleted. Undo" instead of "Are you sure?". The thing is removed right away; for a few seconds a bar
 * offers to put it back. [Undo.show] takes what restores it, and what to finish once the time is up.
 */
object Undo {
    var item by mutableStateOf<UndoItem?>(null)
        private set
    private var next = 0L

    fun show(message: String, onExpire: () -> Unit = {}, onUndo: () -> Unit) {
        // A new removal ends the chance to undo the previous one.
        item?.onExpire?.invoke()
        item = UndoItem(++next, message, onUndo, onExpire)
    }

    fun undo() {
        val i = item ?: return
        item = null
        i.onUndo()
    }

    fun expire() {
        val i = item ?: return
        item = null
        i.onExpire()
    }
}

/** The bar itself, floating above the page at the bottom. Put it once, near the top of the app. */
@Composable
fun UndoBar() {
    val i = Undo.item ?: return
    LaunchedEffect(i.id) {
        delay(5_000)
        if (Undo.item?.id == i.id) Undo.expire()
    }
    val navBar = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    val lift = with(LocalDensity.current) { 24.dp.roundToPx() }
    Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -(navBar + lift))) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
        ) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(i.message, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(end = 12.dp))
                TextButton(onClick = { Undo.undo() }) {
                    Text(stringResource(R.string.undo_action), color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}
