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

private fun en(c: Char) = ch(c.toString(), *(EN_ALTS[c] ?: emptyArray<String>()))

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

private fun ar(s: String) = ch(s, *(AR_ALTS[s] ?: emptyArray<String>()))

/** The three letter rows of a language. */
internal fun kbLetterRows(lang: String): List<List<KbKey>> =
    if (lang == "ar") {
        listOf(
            "ضصثقفغعهخحج".map { ar(it.toString()) },
            "شسيبلاتنمكط".map { ar(it.toString()) },
            "ظزرذدئوةى".map { ar(it.toString()) } + KbKey("", KeyKind.BACKSPACE, 2f),
        )
    } else {
        listOf(
            "qwertyuiop".map { en(it) },
            listOf(gap(0.5f)) + "asdfghjkl".map { en(it) } + gap(0.5f),
            listOf(KbKey("", KeyKind.SHIFT, 1.5f)) + "zxcvbnm".map { en(it) } + KbKey("", KeyKind.BACKSPACE, 1.5f),
        )
    }

/** The three symbol rows. [page] is 1 or 2. */
internal fun kbSymbolRows(page: Int): List<List<KbKey>> =
    if (page == 1) {
        listOf(
            "@#\$_&-+()/".map { ch(it.toString()) },
            listOf(ch("*"), ch("\""), ch("'"), ch(":"), ch(";"), ch("!"), ch("?"), ch("%"), ch("="), ch("\\")),
            listOf(KbKey("=\\<", KeyKind.SYM_PAGE, 1.5f)) +
                listOf("،", "؛", "؟", "…", "~").map { KbKey(it, KeyKind.CHAR, 1.4f) } +
                KbKey("", KeyKind.BACKSPACE, 1.5f),
        )
    } else {
        listOf(
            "[]{}<>^~|`".map { ch(it.toString()) },
            listOf("€", "£", "¥", "₹", "¢", "©", "®", "™", "°", "·").map { ch(it) },
            listOf(KbKey("?123", KeyKind.SYM_PAGE, 1.5f)) +
                listOf("«", "»", "•", "×", "÷").map { KbKey(it, KeyKind.CHAR, 1.4f) } +
                KbKey("", KeyKind.BACKSPACE, 1.5f),
        )
    }

/** The bottom row: symbols or letters switch, language, comma, space, period, emoji, enter. */
internal fun kbBottomRow(lang: String, symbols: Boolean): List<KbKey> {
    val comma = if (lang == "ar" && !symbols) "،" else ","
    val commaKey = if (lang == "ar" && !symbols) {
        KbKey(comma, KeyKind.CHAR, 1f, listOf("؛", "؟", "◌َ", "◌ُ", "◌ِ", "◌ً", "◌ٌ", "◌ٍ", "◌ّ", "◌ْ"))
    } else {
        KbKey(comma, KeyKind.CHAR, 1f, listOf(";", ":", "!", "?"))
    }
    val first = if (symbols) {
        KbKey(if (lang == "ar") "أب" else "ABC", KeyKind.LETTERS, 1.4f)
    } else {
        KbKey("?123", KeyKind.SYMBOLS, 1.4f)
    }
    val dot = KbKey(".", KeyKind.CHAR, 1f, listOf("؟", "!", "…"))
    val space = KbKey("", KeyKind.SPACE, 4f)
    val emoji = KbKey("", KeyKind.EMOJI, 1f)
    val enter = KbKey("", KeyKind.ENTER, 1.5f)
    return if (symbols) {
        listOf(first, commaKey, emoji, space, dot, enter)
    } else {
        listOf(first, KbKey(if (lang == "ar") "EN" else "ع", KeyKind.LANG, 1f), commaKey, space, dot, emoji, enter)
    }
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
