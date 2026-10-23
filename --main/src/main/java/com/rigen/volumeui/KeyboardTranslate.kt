package com.rigen.volumeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/*
 * Translation, on the phone. Nothing is sent anywhere to translate: the language packs are
 * downloaded once, only when the person asks for it in the settings, and after that it all
 * works without the internet. The keyboard itself never downloads anything.
 */

/** The languages offered. English is needed for every pair that does not include it. */
internal val KB_TR_LANGS = listOf(
    "ar", "en", "fr", "de", "es", "tr", "ru", "it", "pt", "hi", "ur", "fa",
    "zh", "ja", "ko", "id", "nl", "el", "he", "uk", "pl", "sv", "bn",
)

internal fun kbLangName(code: String): String =
    Locale.forLanguageTag(code).getDisplayName(Locale.getDefault()).replaceFirstChar { it.uppercase() }

internal const val KB_TR_IDLE = 0
internal const val KB_TR_WORKING = 1
internal const val KB_TR_DONE = 2
internal const val KB_TR_FAILED = 3
internal const val KB_TR_MISSING = 4
internal const val KB_TR_EMPTY = 5

/** The language packs on the phone. */
internal object KbTrModels {
    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    fun isDownloaded(code: String, done: (Boolean) -> Unit) {
        RemoteModelManager.getInstance().isModelDownloaded(model(code))
            .addOnSuccessListener { done(it == true) }
            .addOnFailureListener { done(false) }
    }

    fun download(code: String, wifiOnly: Boolean, done: (Boolean) -> Unit) {
        val conditions = DownloadConditions.Builder().also { if (wifiOnly) it.requireWifi() }.build()
        RemoteModelManager.getInstance().download(model(code), conditions)
            .addOnSuccessListener { done(true) }
            .addOnFailureListener { done(false) }
    }

    fun delete(code: String, done: (Boolean) -> Unit) {
        RemoteModelManager.getInstance().deleteDownloadedModel(model(code))
            .addOnSuccessListener { done(true) }
            .addOnFailureListener { done(false) }
    }

    /** The languages, out of [codes], that are not on the phone yet. */
    fun notDownloaded(codes: List<String>, done: (List<String>) -> Unit) {
        val out = ArrayList<String>()
        var left = codes.size
        if (left == 0) {
            done(out)
            return
        }
        for (c in codes) {
            isDownloaded(c) { ok ->
                if (!ok) out.add(c)
                left--
                if (left == 0) done(out)
            }
        }
    }
}

/** The translate screen of the keyboard: what to translate, the languages, and the result. */
internal class KbTr(private val ctx: Context, private val io: Io) {

    interface Io {
        /** The selected text, or else all the text in the field. */
        fun fieldText(): String
        fun replaceWith(text: String)
        fun insert(text: String)
    }

    var from by mutableStateOf("ar")
    var to by mutableStateOf("en")
    var source by mutableStateOf("")
    var result by mutableStateOf("")
    var status by mutableIntStateOf(KB_TR_IDLE)
    var missing by mutableStateOf<List<String>>(emptyList())
    private var token = 0

    fun start(cfg: KbConfig) {
        from = cfg.trFrom
        to = cfg.trTo
        refresh()
    }

    /** Reads the field again and translates it. */
    fun refresh() {
        source = io.fieldText().trim().take(2000)
        run()
    }

    fun pickFrom(code: String) {
        from = code
        KbPrefs.saveTrLangs(ctx, from, to)
        run()
    }

    fun pickTo(code: String) {
        to = code
        KbPrefs.saveTrLangs(ctx, from, to)
        run()
    }

    fun swap() {
        val f = from
        from = to
        to = f
        KbPrefs.saveTrLangs(ctx, from, to)
        run()
    }

    fun replace() {
        if (status == KB_TR_DONE && result.isNotEmpty()) io.replaceWith(result)
    }

    fun insert() {
        if (status == KB_TR_DONE && result.isNotEmpty()) io.insert(result)
    }

    fun copy() {
        if (result.isEmpty()) return
        try {
            ctx.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("translation", result))
        } catch (e: Exception) {
            // Copying can be refused; nothing else to do.
        }
    }

    private fun run() {
        val mine = ++token
        result = ""
        missing = emptyList()
        if (source.isEmpty()) {
            status = KB_TR_EMPTY
            return
        }
        if (from == to) {
            result = source
            status = KB_TR_DONE
            return
        }
        status = KB_TR_WORKING
        val needed = (listOf(from, to) + if (from != "en" && to != "en") listOf("en") else emptyList()).distinct()
        KbTrModels.notDownloaded(needed) lambda@{ absent ->
            if (mine != token) return@lambda
            if (absent.isNotEmpty()) {
                missing = absent
                status = KB_TR_MISSING
                return@lambda
            }
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build(),
            )
            translator.translate(source)
                .addOnSuccessListener { text ->
                    if (mine == token) {
                        result = text
                        status = KB_TR_DONE
                    }
                }
                .addOnFailureListener {
                    if (mine == token) status = KB_TR_FAILED
                }
                .addOnCompleteListener { translator.close() }
        }
    }
}

// ───────────────────────── Settings: the language packs ─────────────────────────

@Composable
internal fun KbTranslateSection(cfg: KbConfig, update: (KbConfig) -> Unit) {
    // 0 not on the phone, 1 ready, 2 downloading
    val states = remember { mutableStateMapOf<String, Int>() }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        for (code in KB_TR_LANGS) {
            KbTrModels.isDownloaded(code) { ok -> if (states[code] != 2) states[code] = if (ok) 1 else 0 }
        }
    }

    Section(stringResource(R.string.kb_tr_title)) {
        SwitchRow(stringResource(R.string.kb_tr_on), cfg.trOn) { update(cfg.copy(trOn = it)) }
        Text(
            stringResource(R.string.kb_tr_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(stringResource(R.string.kb_tr_wifi), cfg.trWifi) { update(cfg.copy(trWifi = it)) }
        if (failed) {
            Text(
                stringResource(R.string.kb_tr_download_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        for (code in KB_TR_LANGS) {
            val st = states[code] ?: 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(kbLangName(code), Modifier.weight(1f))
                when (st) {
                    1 -> {
                        Text(
                            stringResource(R.string.kb_tr_ready),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedButton(onClick = {
                            KbTrModels.delete(code) { states[code] = 0 }
                        }) { Text(stringResource(R.string.kb_tr_delete)) }
                    }
                    2 -> Text(
                        stringResource(R.string.kb_tr_busy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Button(onClick = {
                        failed = false
                        states[code] = 2
                        KbTrModels.download(code, cfg.trWifi) { ok ->
                            states[code] = if (ok) 1 else 0
                            if (!ok) failed = true
                        }
                    }) { Text(stringResource(R.string.kb_tr_download)) }
                }
            }
        }
    }
}
