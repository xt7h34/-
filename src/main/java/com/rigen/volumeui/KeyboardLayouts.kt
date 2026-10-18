package com.rigen.volumeui

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
