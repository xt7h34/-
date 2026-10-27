package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

class Snippet(val trigger: String, val text: String)

/**
 * Text shortcuts: a short word the person made up that turns into a longer text when space is pressed
 * after it. Kept on this phone. The keyboard asks [expansionFor] on every space, so the list is held
 * in memory and only read from storage once.
 */
object SnippetStore {
    const val MAX = 100
    private const val PREF = "kanade_kb_snippets"

    @Volatile
    private var cache: List<Snippet>? = null

    var tick by mutableIntStateOf(0)
        private set

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(c: Context): List<Snippet> = cache ?: load(c).also { cache = it }

    private fun load(c: Context): List<Snippet> = try {
        val a = JSONArray(sp(c).getString("list", "[]") ?: "[]")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Snippet(o.getString("t"), o.getString("x"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(c: Context, list: List<Snippet>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("t", s.trigger).put("x", s.text))
        sp(c).edit().putString("list", a.toString()).apply()
        cache = list
        tick++
    }

    fun validTrigger(t: String): Boolean = t.length in 2..20 && t.none { it.isWhitespace() }

    /** Adds the shortcut, or replaces the one with the same trigger. */
    fun set(c: Context, trigger: String, text: String) {
        val rest = all(c).filter { !it.trigger.equals(trigger, ignoreCase = true) }
        if (rest.size >= MAX) return
        save(c, (rest + Snippet(trigger, text.take(500))).sortedBy { it.trigger.lowercase() })
    }

    fun remove(c: Context, trigger: String) {
        save(c, all(c).filter { !it.trigger.equals(trigger, ignoreCase = true) })
    }

    /** What the word typed before a space turns into, or null when it is not a shortcut. */
    fun expansionFor(c: Context, token: String): String? =
        all(c).firstOrNull { it.trigger.equals(token, ignoreCase = true) }?.text
}

/** The keyboard settings tab where the shortcuts are made and removed. */
@Composable
internal fun KbSnippetsTab() {
    val ctx = LocalContext.current
    val tick = SnippetStore.tick
    val list = remember(tick) { SnippetStore.all(ctx) }
    var trigger by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val triggerOk = SnippetStore.validTrigger(trigger)
    val full = list.size >= SnippetStore.MAX && list.none { it.trigger.equals(trigger, ignoreCase = true) }

    Section(stringResource(R.string.kbs_title)) {
        Text(stringResource(R.string.kbs_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = trigger,
            onValueChange = { if (it.length <= 20) trigger = it },
            label = { Text(stringResource(R.string.kbs_trigger)) },
            singleLine = true,
            isError = tried && !triggerOk,
            supportingText = { if (tried && !triggerOk) Text(stringResource(R.string.kbs_err_trigger)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 500) text = it },
            label = { Text(stringResource(R.string.kbs_text)) },
            minLines = 2,
            isError = tried && text.isBlank(),
            supportingText = { if (tried && text.isBlank()) Text(stringResource(R.string.kbs_err_text)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (full) Text(stringResource(R.string.kbs_full), color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                tried = true
                if (triggerOk && text.isNotBlank() && !full) {
                    SnippetStore.set(ctx, trigger, text)
                    trigger = ""
                    text = ""
                    tried = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.kbs_save)) }
    }

    if (list.isNotEmpty()) {
        Section(stringResource(R.string.kbs_list)) {
            for (s in list) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        // Tapping one loads it into the fields above, to change it.
                        trigger = s.trigger
                        text = s.text
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.trigger, fontWeight = FontWeight.Bold)
                        Text(
                            s.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = {
                        SnippetStore.remove(ctx, s.trigger)
                        Undo.show(ctx.getString(R.string.kbs_deleted)) { SnippetStore.set(ctx, s.trigger, s.text) }
                    }) { Text(stringResource(R.string.goals_delete)) }
                }
            }
        }
    }
}
