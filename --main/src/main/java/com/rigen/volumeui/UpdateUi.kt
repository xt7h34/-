package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

/*
 * The two places the person sees updates: a question when the app opens and a newer version is
 * out, and a section in the app settings to check by hand.
 */

internal const val US_IDLE = 0
internal const val US_CHECKING = 1
internal const val US_UP_TO_DATE = 2
internal const val US_NEW = 3
internal const val US_DOWNLOADING = 4
internal const val US_READY = 5
internal const val US_FAILED = 6
internal const val US_NOT_CONFIGURED = 7
internal const val US_BAD_FILE = 8

/** Shared by the question and the settings section, so they always agree. */
internal object UpdateState {
    var status by mutableIntStateOf(US_IDLE)
    var info by mutableStateOf<UpdateInfo?>(null)
    var progress by mutableFloatStateOf(0f)
    var file by mutableStateOf<File?>(null)
    var needsPermission by mutableStateOf(false)

    /** "Later" was tapped. The question stays away until the app is opened again. */
    var postponed by mutableStateOf(false)

    suspend fun check(c: Context) {
        status = US_CHECKING
        val r = Updater.check(c)
        info = r.info
        status = when (r.kind) {
            UPD_NEW -> US_NEW
            UPD_UP_TO_DATE -> US_UP_TO_DATE
            UPD_NOT_CONFIGURED -> US_NOT_CONFIGURED
            else -> US_FAILED
        }
    }

    /** The automatic look when the app opens: at most about once a day. */
    suspend fun autoCheck(c: Context) {
        if (!UpdatePrefs.auto(c) || status != US_IDLE) return
        if (!UpdatePrefs.isDue(c)) {
            val cached = UpdatePrefs.cached(c)
            if (cached != null && cached.code > Updater.installedCode(c)) {
                info = cached
                status = US_NEW
            }
            return
        }
        check(c)
    }

    suspend fun downloadAndInstall(c: Context) {
        val i = info ?: return
        status = US_DOWNLOADING
        progress = 0f
        try {
            file = Updater.download(c, i) { done, total ->
                progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            }
            status = US_READY
            install(c)
        } catch (e: Exception) {
            status = if (e.message == "checksum" || e.message?.startsWith("not a newer") == true) US_BAD_FILE else US_FAILED
        }
    }

    /** Opens Android's installer. The first time, the person must allow it in the settings. */
    fun install(c: Context) {
        val f = file ?: return
        if (!Updater.canInstall(c)) {
            needsPermission = true
            Updater.openInstallPermission(c)
            return
        }
        needsPermission = false
        Updater.install(c, f)
    }
}

private fun cleanNotes(raw: String): String =
    raw.replace(Regex("[#*`>]"), "").replace(Regex("\n{3,}"), "\n\n").trim()

private fun megabytes(size: Long): String = String.format("%.1f", size / 1048576.0)

/** The question at the top of the app: there is a new version, do you want it? */
@Composable
internal fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { UpdateState.autoCheck(context) }

    val info = UpdateState.info
    val status = UpdateState.status
    val open = info != null && !UpdateState.postponed &&
        UpdatePrefs.skipped(context) != info.code &&
        (status == US_NEW || status == US_DOWNLOADING || status == US_READY || status == US_FAILED || status == US_BAD_FILE)
    if (!open || info == null) return

    AlertDialog(
        onDismissRequest = { if (status != US_DOWNLOADING) UpdateState.postponed = true },
        title = { Text(stringResource(R.string.upd_dialog_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.upd_dialog_text, info.name))
                if (info.size > 0) Text(stringResource(R.string.upd_size, megabytes(info.size)), style = MaterialTheme.typography.bodySmall)
                if (info.notes.isNotBlank()) {
                    Text(stringResource(R.string.upd_notes), style = MaterialTheme.typography.labelLarge)
                    Text(cleanNotes(info.notes), style = MaterialTheme.typography.bodySmall, maxLines = 12)
                }
                when (status) {
                    US_DOWNLOADING -> {
                        LinearProgressIndicator(progress = { UpdateState.progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.upd_downloading, (UpdateState.progress * 100).toInt()))
                    }
                    US_FAILED -> Text(stringResource(R.string.upd_failed), color = MaterialTheme.colorScheme.error)
                    US_BAD_FILE -> Text(stringResource(R.string.upd_bad_file), color = MaterialTheme.colorScheme.error)
                    US_READY -> if (UpdateState.needsPermission) Text(stringResource(R.string.upd_allow))
                }
            }
        },
        confirmButton = {
            when (status) {
                US_DOWNLOADING -> {}
                US_READY -> TextButton(onClick = { UpdateState.install(context) }) {
                    Text(stringResource(R.string.upd_install))
                }
                else -> TextButton(onClick = { scope.launch { UpdateState.downloadAndInstall(context) } }) {
                    Text(stringResource(R.string.upd_download))
                }
            }
        },
        dismissButton = {
            if (status != US_DOWNLOADING) {
                Row {
                    TextButton(onClick = {
                        UpdatePrefs.skip(context, info.code)
                        UpdateState.postponed = true
                    }) { Text(stringResource(R.string.upd_skip)) }
                    TextButton(onClick = { UpdateState.postponed = true }) { Text(stringResource(R.string.upd_later)) }
                }
            }
        },
    )
}

/** Inside the app settings: the installed version, a button to check, and the new version if any. */
@Composable
internal fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(UpdatePrefs.auto(context)) }
    var repo by remember { mutableStateOf(UpdatePrefs.repoOverride(context)) }
    val status = UpdateState.status
    val info = UpdateState.info

    Section(stringResource(R.string.upd_title)) {
        Text(
            stringResource(R.string.upd_current, Updater.installedName(context), Updater.installedCode(context)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            when (status) {
                US_CHECKING -> stringResource(R.string.upd_checking)
                US_UP_TO_DATE -> stringResource(R.string.upd_uptodate)
                US_NEW, US_DOWNLOADING, US_READY -> stringResource(R.string.upd_new, info?.name ?: "")
                US_FAILED -> stringResource(R.string.upd_failed)
                US_BAD_FILE -> stringResource(R.string.upd_bad_file)
                US_NOT_CONFIGURED -> stringResource(R.string.upd_noconfig)
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status == US_FAILED || status == US_BAD_FILE) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (info != null && (status == US_NEW || status == US_DOWNLOADING || status == US_READY)) {
            if (info.size > 0) Text(stringResource(R.string.upd_size, megabytes(info.size)), style = MaterialTheme.typography.bodySmall)
            if (info.notes.isNotBlank()) {
                Text(stringResource(R.string.upd_notes), style = MaterialTheme.typography.labelLarge)
                Text(cleanNotes(info.notes), style = MaterialTheme.typography.bodySmall, maxLines = 12)
            }
            if (status == US_DOWNLOADING) {
                LinearProgressIndicator(progress = { UpdateState.progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.upd_downloading, (UpdateState.progress * 100).toInt()))
            } else if (status == US_READY) {
                if (UpdateState.needsPermission) Text(stringResource(R.string.upd_allow))
                Button(onClick = { UpdateState.install(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.upd_install))
                }
            } else {
                Button(
                    onClick = { scope.launch { UpdateState.downloadAndInstall(context) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.upd_download)) }
            }
        }
        OutlinedButton(
            onClick = {
                UpdateState.postponed = false
                scope.launch { UpdateState.check(context) }
            },
            enabled = status != US_CHECKING && status != US_DOWNLOADING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.upd_check)) }
        SwitchRow(stringResource(R.string.upd_auto), auto) {
            auto = it
            UpdatePrefs.setAuto(context, it)
        }
        OutlinedTextField(
            value = repo,
            onValueChange = {
                repo = it.take(100)
                UpdatePrefs.setRepoOverride(context, repo)
            },
            label = { Text(stringResource(R.string.upd_repo)) },
            placeholder = { Text(BuildConfig.UPDATE_REPO) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
