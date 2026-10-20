package com.rigen.volumeui

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/*
 * Autofill: reading what Android tells us about the screen of another app. It only looks for
 * the user name and password fields (and what was typed in them when the person signs in).
 */

internal class AfField(val id: AutofillId, val isPassword: Boolean, val text: String?)

internal class AfForm(val pkg: String, val domain: String?, val fields: List<AfField>) {
    val password: AfField? get() = fields.firstOrNull { it.isPassword }

    /** The user name field nearest before the password field. */
    val username: AfField?
        get() {
            val p = password ?: return fields.firstOrNull { !it.isPassword }
            val before = fields.takeWhile { it !== p }.filter { !it.isPassword }
            return before.lastOrNull() ?: fields.firstOrNull { !it.isPassword }
        }
}

/** A save the person asked for, waiting until they unlock. It lives only in memory, for minutes. */
internal class AfPendingSave(val pkg: String, val domain: String?, val user: String, val password: String, val ts: Long)

internal object AfPending {
    @Volatile
    private var save: AfPendingSave? = null

    fun put(s: AfPendingSave) {
        save = s
    }

    fun take(): AfPendingSave? {
        val s = save
        save = null
        return if (s != null && System.currentTimeMillis() - s.ts < 5 * 60 * 1000L) s else null
    }
}

internal object AfParser {
    fun parse(structure: AssistStructure): AfForm {
        val fields = ArrayList<AfField>()
        var domain: String? = null
        val pkg = structure.activityComponent?.packageName ?: ""

        fun walk(n: AssistStructure.ViewNode) {
            if (domain == null) domain = n.webDomain
            val id = n.autofillId
            if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
                val kind = classify(n)
                if (kind != 0) {
                    val value = n.autofillValue
                    val text = if (value != null && value.isText) value.textValue?.toString() else null
                    fields.add(AfField(id, kind == 2, text))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChildAt(i))
        }

        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)
        return AfForm(pkg, domain?.lowercase()?.removePrefix("www."), fields)
    }

    /** 0 not interesting, 1 user name, 2 password. */
    private fun classify(n: AssistStructure.ViewNode): Int {
        val hints = n.autofillHints
        if (hints != null) {
            for (h in hints) {
                val l = h.lowercase()
                if (l.contains("password")) return 2
                if (l.contains("username") || l.contains("email") || l.contains("phone")) return 1
            }
        }
        val type = n.inputType
        if ((type and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            val v = type and InputType.TYPE_MASK_VARIATION
            if (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            ) return 2
            if (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return 1
        }
        val html = n.htmlInfo
        if (html != null && "input".equals(html.tag, ignoreCase = true)) {
            val attrs = html.attributes
            var htmlType: String? = null
            var name = ""
            if (attrs != null) {
                for (a in attrs) {
                    when (a.first.lowercase()) {
                        "type" -> htmlType = a.second?.lowercase()
                        "name", "id", "autocomplete" -> name += " " + (a.second ?: "")
                    }
                }
            }
            if (htmlType == "password") return 2
            if ((htmlType == "email" || htmlType == "text" || htmlType == "tel") && looksLikeUser(name)) return 1
        }
        val words = ((n.idEntry ?: "") + " " + (n.hint ?: "")).lowercase()
        if (words.contains("pass")) return 2
        if (looksLikeUser(words)) return 1
        return 0
    }

    private fun looksLikeUser(s: String): Boolean {
        val l = s.lowercase()
        return l.contains("user") || l.contains("email") || l.contains("login") || l.contains("mail") ||
            l.contains("phone") || l.contains("identifier")
    }
}
