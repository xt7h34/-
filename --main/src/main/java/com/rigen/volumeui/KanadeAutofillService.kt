package com.rigen.volumeui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews

/*
 * Autofill for Kanade Pass. When the person taps a sign-in field in another app or site, this
 * offers "Unlock Kanade Pass". Nothing from the vault is shown or sent until they unlock it.
 * When they sign in somewhere new, Android asks if they want to save it here.
 */
class KanadeAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                callback.onSuccess(null)
                return
            }
            val form = AfParser.parse(structure)
            if (form.pkg == packageName || form.fields.isEmpty() || !Vault.exists(this)) {
                callback.onSuccess(null)
                return
            }
            val user = form.username
            val pass = form.password

            // Choosing the suggestion opens the unlock screen, which gives back the real values.
            val intent = Intent(this, AutofillAuthActivity::class.java)
                .putExtra(AutofillAuthActivity.EXTRA_MODE, AutofillAuthActivity.MODE_FILL)
                .putExtra(AutofillAuthActivity.EXTRA_PKG, form.pkg)
                .putExtra(AutofillAuthActivity.EXTRA_DOMAIN, form.domain)
                .putExtra(AutofillAuthActivity.EXTRA_USER, user?.id)
                .putExtra(AutofillAuthActivity.EXTRA_PASS, pass?.id)
            val sender = PendingIntent.getActivity(
                this, nextCode(), intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
            ).intentSender

            val presentation = RemoteViews(packageName, R.layout.autofill_item)
            presentation.setTextViewText(R.id.af_text, getString(R.string.af_unlock))
            val dataset = Dataset.Builder(presentation).apply {
                if (user != null) setValue(user.id, null)
                if (pass != null) setValue(pass.id, null)
                setAuthentication(sender)
            }.build()

            val response = FillResponse.Builder().addDataset(dataset)
            if (pass != null) {
                var types = SaveInfo.SAVE_DATA_TYPE_PASSWORD
                if (user != null) types = types or SaveInfo.SAVE_DATA_TYPE_USERNAME
                val save = SaveInfo.Builder(types, arrayOf<AutofillId>(pass.id))
                if (user != null) save.setOptionalIds(arrayOf<AutofillId>(user.id))
                response.setSaveInfo(save.build())
            }
            callback.onSuccess(response.build())
        } catch (e: Exception) {
            Diag.error(this, "Autofill.fill", e)
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                callback.onSuccess()
                return
            }
            val form = AfParser.parse(structure)
            val typed = form.password?.text
            if (form.pkg == packageName || typed.isNullOrEmpty() || !Vault.exists(this)) {
                callback.onSuccess()
                return
            }
            AfPending.put(AfPendingSave(form.pkg, form.domain, form.username?.text ?: "", typed, System.currentTimeMillis()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // The vault is locked, so the person unlocks it first and confirms there.
                val intent = Intent(this, AutofillAuthActivity::class.java)
                    .putExtra(AutofillAuthActivity.EXTRA_MODE, AutofillAuthActivity.MODE_SAVE)
                val sender = PendingIntent.getActivity(
                    this, nextCode(), intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ).intentSender
                callback.onSuccess(sender)
            } else {
                callback.onSuccess()
            }
        } catch (e: Exception) {
            Diag.error(this, "Autofill.save", e)
            callback.onSuccess()
        }
    }

    private companion object {
        private var code = 100

        fun nextCode(): Int {
            code++
            return code
        }
    }
}
