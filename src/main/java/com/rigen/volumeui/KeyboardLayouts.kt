package com.rigen.volumeui

import org.json.JSONArray
import org.json.JSONObject

/*
 * Kanade Keyboard: the key layouts (Arabic, English, symbols) and the emoji lists.
 * Emoji are drawn by the phone's own emoji font, so on a Samsung phone they look like Samsung's.
 */

internal enum class KeyKind { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS, LETTERS, SYM_PAGE, LANG, EMOJI, GAP }

internal class KbKey(
    val label: String,
    val kind: KeyKind = KeyKind.CHAR,
    val weight: Float = 1f,
    val alts: List<String> = emptyList(),
    val hScale: Float = 1f,
)

private fun ch(s: String, vararg alts: String) = KbKey(s, KeyKind.CHAR, 1f, alts.toList())
private fun gap(w: Float) = KbKey("", KeyKind.GAP, w)

private val INDIC = listOf("٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩")

internal fun kbNumberRow(): List<KbKey> =
    "1234567890".map { ch(it.toString(), INDIC[it - '0']) }

private val EN_ALTS = mapOf(
    'a' to arrayOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
    'e' to arrayOf("è", "é", "ê", "ë", "ē"),
    'i' to arrayOf("ì", "í", "î", "ï"),
    'o' to arrayOf("ò", "ó", "ô", "ö", "œ", "õ"),
    'u' to arrayOf("ù", "ú", "û", "ü"),
    'c' to arrayOf("ç"),
    'n' to arrayOf("ñ"),
    's' to arrayOf("ß"),
    'y' to arrayOf("ý", "ÿ"),
)

private fun enKey(tok: String): KbKey {
    val alts = tok.singleOrNull()?.let { EN_ALTS[it] } ?: emptyArray<String>()
    return ch(tok, *alts)
}

private val AR_ALTS = mapOf(
    "ا" to arrayOf("أ", "إ", "آ", "ٱ"),
    "ل" to arrayOf("لا", "لأ", "لإ", "لآ"),
    "ئ" to arrayOf("ء", "ؤ"),
    "و" to arrayOf("ؤ"),
    "ي" to arrayOf("ى", "ئ"),
    "ه" to arrayOf("ة"),
    "ك" to arrayOf("گ"),
    "ب" to arrayOf("پ"),
    "ف" to arrayOf("ڤ"),
    "ج" to arrayOf("چ"),
    "ز" to arrayOf("ژ"),
)

private fun arKey(tok: String): KbKey = ch(tok, *(AR_ALTS[tok] ?: emptyArray<String>()))

// ── Splitting text into single characters (emoji count as one) ──

/** Splits text into what a person sees as single characters. Spaces are dropped. */
internal fun kbGraphemes(s: String): List<String> {
    val out = ArrayList<String>()
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        val g = s.substring(start, end)
        if (g.isNotBlank()) out.add(g)
        start = end
        end = it.next()
    }
    return out
}

// ── The letter rows, which the person can rearrange ──

private val DEFAULT_AR = listOf("ضصثقفغعهخحج", "شسيبلاتنمكط", "ظزرذدئوةى")
private val DEFAULT_EN = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

internal fun kbDefaultTokens(lang: String): List<List<String>> =
    (if (lang == "ar") DEFAULT_AR else DEFAULT_EN).map { kbGraphemes(it) }

/** The saved arrangement, or the default one when nothing is saved or the saved one is damaged. */
internal fun kbParseLayout(raw: String, lang: String): List<List<String>> {
    val def = kbDefaultTokens(lang)
    if (raw.isBlank()) return def
    val rows = raw.split("|").map { r -> r.split(" ").filter { it.isNotEmpty() } }
    if (rows.size != def.size) return def
    for (i in rows.indices) if (rows[i].size != def[i].size) return def
    return rows
}

internal fun kbEncodeLayout(rows: List<List<String>>): String =
    rows.joinToString("|") { it.joinToString(" ") }

/** The three letter rows of a language. [raw] is the person's own arrangement, or empty. */
internal fun kbLetterRows(lang: String, raw: String = ""): List<List<KbKey>> {
    val t = kbParseLayout(raw, lang)
    return if (lang == "ar") {
        listOf(
            t[0].map { arKey(it) },
            t[1].map { arKey(it) },
            t[2].map { arKey(it) } + KbKey("", KeyKind.BACKSPACE, 2f),
        )
    } else {
        listOf(
            t[0].map { enKey(it) },
            listOf(gap(0.5f)) + t[1].map { enKey(it) } + gap(0.5f),
            listOf(KbKey("", KeyKind.SHIFT, 1.5f)) + t[2].map { enKey(it) } + KbKey("", KeyKind.BACKSPACE, 1.5f),
        )
    }
}

/** Centers a short row of keys so they keep the normal key width. */
private fun padRow(keys: List<KbKey>, total: Float = 10f): List<KbKey> {
    val used = keys.sumOf { it.weight.toDouble() }.toFloat()
    if (used >= total || keys.isEmpty()) return if (keys.isEmpty()) listOf(gap(total)) else keys
    val side = (total - used) / 2f
    return listOf(gap(side)) + keys + gap(side)
}

/** The symbol rows. [page] is 1, 2, or 3 (the person's own symbols). */
internal fun kbSymbolRows(page: Int, custom: List<String>): List<List<KbKey>> =
    when (page) {
        1 -> listOf(
            "@#\$_&-+()/".map { ch(it.toString()) },
            listOf(ch("*"), ch("\""), ch("'"), ch(":"), ch(";"), ch("!"), ch("?"), ch("%"), ch("="), ch("\\")),
            listOf(KbKey("=\\<", KeyKind.SYM_PAGE, 1.5f)) +
                listOf("،", "؛", "؟", "…", "~").map { KbKey(it, KeyKind.CHAR, 1.4f) } +
                KbKey("", KeyKind.BACKSPACE, 1.5f),
        )
        2 -> listOf(
            "[]{}<>^~|`".map { ch(it.toString()) },
            listOf("€", "£", "¥", "₹", "¢", "©", "®", "™", "°", "·").map { ch(it) },
            listOf(KbKey(if (custom.isNotEmpty()) "★" else "?123", KeyKind.SYM_PAGE, 1.5f)) +
                listOf("«", "»", "•", "×", "÷").map { KbKey(it, KeyKind.CHAR, 1.4f) } +
                KbKey("", KeyKind.BACKSPACE, 1.5f),
        )
        else -> {
            val last = custom.drop(20).take(7)
            val w = if (last.isEmpty()) 1f else minOf(1.4f, 7f / last.size)
            listOf(
                padRow(custom.take(10).map { ch(it) }),
                padRow(custom.drop(10).take(10).map { ch(it) }),
                listOf(KbKey("?123", KeyKind.SYM_PAGE, 1.5f)) +
                    last.map { KbKey(it, KeyKind.CHAR, w) } +
                    KbKey("", KeyKind.BACKSPACE, 1.5f),
            )
        }
    }

/**
 * The bottom row: symbols or letters switch, language, comma, space, period, emoji, enter.
 * When [emojiInBar] is on, the emoji key lives in the top bar instead.
 */
internal fun kbBottomRow(lang: String, symbols: Boolean, emojiInBar: Boolean): List<KbKey> {
    val arabicComma = lang == "ar" && !symbols
    val commaKey = if (arabicComma) {
        KbKey("،", KeyKind.CHAR, 1f, listOf("؛", "؟", "◌َ", "◌ُ", "◌ِ", "◌ً", "◌ٌ", "◌ٍ", "◌ّ", "◌ْ"))
    } else {
        KbKey(",", KeyKind.CHAR, 1f, listOf(";", ":", "!", "?"))
    }
    val first = if (symbols) {
        KbKey(if (lang == "ar") "أب" else "ABC", KeyKind.LETTERS, 1.4f)
    } else {
        KbKey("?123", KeyKind.SYMBOLS, 1.4f)
    }
    val dot = KbKey(".", KeyKind.CHAR, 1f, listOf("؟", "!", "…"))
    val space = KbKey("", KeyKind.SPACE, if (emojiInBar) 4.5f else 4f)
    val emoji = KbKey("", KeyKind.EMOJI, 1f)
    val enter = KbKey("", KeyKind.ENTER, 1.5f)
    val lang1 = KbKey(if (lang == "ar") "EN" else "ع", KeyKind.LANG, 1f)
    val row = if (symbols) {
        listOf(first, commaKey, emoji, space, dot, enter)
    } else {
        listOf(first, lang1, commaKey, space, dot, emoji, enter)
    }
    return if (emojiInBar) row.filter { it.kind != KeyKind.EMOJI } else row
}

// ───────────────────────── Emoji ─────────────────────────

internal class EmojiCategory(val icon: String, val items: String)

private const val E_SMILEYS =
    "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👹 👺 👻 👽 👾 🤖"

private const val E_PEOPLE =
    "👋 🤚 🖐 ✋ 🖖 👌 🤌 🤏 ✌\uFE0F 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝\uFE0F 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍\uFE0F 💅 🤳 💪 🦾 🧠 👀 👁 👅 👄 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🙇 🤦 🤷 👮 💂 👷 🤴 👸 🎅 🤶"

private const val E_NATURE =
    "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🐘 🦏 🐪 🐫 🦒 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘\uFE0F 🍀 🍁 🍂 🍃 🌺 🌻 🌹 🌷 🌼 🌸 💐 🍄 🌍 🌙 ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀\uFE0F ⛅ ☁\uFE0F ❄\uFE0F 💧 🌊"

private const val E_FOOD =
    "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🍍 🥥 🥝 🍅 🥑 🍆 🥕 🌽 🥔 🍞 🥐 🧀 🍳 🥞 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🍤 🍙 🍚 🍘 🍦 🍧 🍨 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍮 🍯 ☕ 🍵 🥛 🍼 🥤 🍺 🍷 🥂 🍾"

private const val E_ACTIVITY =
    "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🎣 🎿 🏂 🏋\uFE0F 🤸 🏊 🚴 🏆 🥇 🥈 🥉 🏅 🎖 🎫 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 🎯 🎳 🎮 🎰 🧩"

private const val E_TRAVEL =
    "🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚚 🚜 🏍 🚲 ✈\uFE0F 🚀 🛸 🚁 ⛵ 🚤 🚢 🚂 🚆 🚇 🏠 🏡 🏢 🏥 🏦 🏪 🏫 🏰 🕌 ⛪ 🕋 🗼 🗽 ⛲ 🌅 🌄 🌆 🌃 🌉 🎡 🎢 🏖 🏝 🏜 🌋 🗻 ⛺"

private const val E_OBJECTS =
    "⌚ 📱 💻 ⌨\uFE0F 🖥 🖨 🖱 💽 💾 📷 📹 🎥 📞 ☎\uFE0F 📺 📻 🎙 ⏰ ⏳ 🔋 🔌 💡 🔦 🕯 💰 💵 💳 💎 🔧 🔨 ⚙\uFE0F 🔒 🔓 🔑 🗝 🚪 🛏 🚿 🛁 🧴 🧼 📦 📬 ✉\uFE0F 📝 📁 📅 📌 📎 ✂\uFE0F 📖 📚 🔍 🔬 🔭 💊 💉 🎁 🎈 🎉 🎊"

private const val E_SYMBOLS =
    "❤\uFE0F 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣\uFE0F 💕 💞 💓 💗 💖 💘 💝 💯 ✅ ❌ ❎ ❓ ❗ ‼\uFE0F ⚠\uFE0F ⭕ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ ➕ ➖ ✖\uFE0F ➗ ♻\uFE0F ✔\uFE0F ☑\uFE0F 🔔 🔕 🔊 🔇 📢 💬 💭 🚫 🔞 🆗 🆒 🆕 🔝 ➡\uFE0F ⬅\uFE0F ⬆\uFE0F ⬇\uFE0F ☪\uFE0F 🕌 ☮\uFE0F ✝\uFE0F"

internal val KB_EMOJI = listOf(
    EmojiCategory("🕘", ""), // Recent: filled in from the saved list.
    EmojiCategory("😀", E_SMILEYS),
    EmojiCategory("👋", E_PEOPLE),
    EmojiCategory("🐱", E_NATURE),
    EmojiCategory("🍔", E_FOOD),
    EmojiCategory("⚽", E_ACTIVITY),
    EmojiCategory("🚗", E_TRAVEL),
    EmojiCategory("💡", E_OBJECTS),
    EmojiCategory("❤\uFE0F", E_SYMBOLS),
)

/** Only the emoji this phone can draw. Newer ones are hidden instead of showing as empty boxes. */
internal fun kbSupportedEmoji(items: List<String>): List<String> {
    val paint = android.graphics.Paint()
    return items.filter { runCatching { paint.hasGlyph(it) }.getOrDefault(true) }
}

internal fun kbEmojiList(category: EmojiCategory): List<String> =
    category.items.split(' ').filter { it.isNotEmpty() }


// ───────────────────────── Keys the person can edit ─────────────────────────

/** One key as the person edited it: what it is, how wide, how tall, and its extra letters. */
internal data class KeySpec(
    val kind: KeyKind,
    val label: String,
    val weight: Float,
    val hScale: Float,
    val alts: List<String>,
)

/** One row of keys. The number row is hidden when the number row is turned off. */
internal data class RowSpec(val number: Boolean, val keys: List<KeySpec>)

private fun KbKey.toSpec() = KeySpec(kind, label, weight, hScale, alts)

private fun rowOf(keys: List<KbKey>) = RowSpec(false, keys.map { it.toSpec() })

/**
 * The untouched keys of a page. [idx]: 0 Arabic letters, 1 English letters, 2 and 3 symbols,
 * 4 the person's own symbols (never edited).
 */
internal fun kbDefaultPage(idx: Int, cfg: KbConfig, custom: List<String>): List<RowSpec> {
    val numbers = RowSpec(true, kbNumberRow().map { it.toSpec() })
    val body: List<List<KbKey>>
    val bottom: List<KbKey>
    when (idx) {
        0 -> {
            body = kbLetterRows("ar", cfg.layoutAr)
            bottom = kbBottomRow("ar", false, false)
        }
        1 -> {
            body = kbLetterRows("en", cfg.layoutEn)
            bottom = kbBottomRow("en", false, false)
        }
        2 -> {
            body = kbSymbolRows(1, custom)
            bottom = kbBottomRow("en", true, false)
        }
        3 -> {
            body = kbSymbolRows(2, custom)
            bottom = kbBottomRow("en", true, false)
        }
        else -> {
            body = kbSymbolRows(3, custom)
            bottom = kbBottomRow("en", true, false)
        }
    }
    return listOf(numbers) + body.map { rowOf(it) } + rowOf(bottom)
}

/** The keys of a page: the person's edited version when there is one, otherwise the default. */
internal fun kbPageRows(cfg: KbConfig, idx: Int, custom: List<String>): List<RowSpec> {
    val saved = if (idx in 0 until KB_PAGES) cfg.layouts.getOrNull(idx) else null
    val decoded = if (!saved.isNullOrBlank()) kbDecodeRows(saved) else null
    return decoded ?: kbDefaultPage(idx, cfg, custom)
}

internal fun kbEncodeRows(rows: List<RowSpec>): String {
    val arr = JSONArray()
    for (r in rows) {
        val keys = JSONArray()
        for (k in r.keys) {
            keys.put(
                JSONObject()
                    .put("c", k.kind.name)
                    .put("l", k.label)
                    .put("w", k.weight.toDouble())
                    .put("h", k.hScale.toDouble())
                    .put("a", JSONArray(k.alts)),
            )
        }
        arr.put(JSONObject().put("n", r.number).put("k", keys))
    }
    return arr.toString()
}

/** Null when the saved text is damaged, so the default keys are used instead. */
internal fun kbDecodeRows(raw: String): List<RowSpec>? {
    return try {
        val arr = JSONArray(raw)
        val rows = ArrayList<RowSpec>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ka = o.getJSONArray("k")
            val keys = ArrayList<KeySpec>()
            for (j in 0 until ka.length()) {
                val k = ka.getJSONObject(j)
                val alts = ArrayList<String>()
                val aa = k.optJSONArray("a")
                if (aa != null) for (x in 0 until aa.length()) alts.add(aa.getString(x))
                keys.add(
                    KeySpec(
                        kind = KeyKind.valueOf(k.getString("c")),
                        label = k.getString("l"),
                        weight = k.getDouble("w").toFloat().coerceIn(0.3f, 6f),
                        hScale = k.getDouble("h").toFloat().coerceIn(0.5f, 1.8f),
                        alts = alts,
                    ),
                )
            }
            if (keys.isNotEmpty()) rows.add(RowSpec(o.optBoolean("n", false), keys))
        }
        if (rows.isEmpty()) null else rows
    } catch (e: Exception) {
        null
    }
}
