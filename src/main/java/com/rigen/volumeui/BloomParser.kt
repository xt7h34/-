package com.rigen.volumeui

import java.time.DayOfWeek
import java.time.LocalDate

// What Kanade Bloom can offer for a notification.
const val BA_EVENT = 0
const val BA_EXPENSE = 1
const val BA_CODE = 2
const val BA_LINK = 3
const val BA_CALL = 4

/**
 * One button Bloom can show. [value] is the title (event), the currency (expense), the code, the link or the number.
 * [at] is when the event starts (the start of the day when [allDay]); [amount] is in minor units.
 */
class BloomAction(
    val kind: Int,
    val value: String,
    val at: Long = 0L,
    val allDay: Boolean = false,
    val amount: Long = 0L,
)

/**
 * Reads a notification's words and finds what could be acted on. It works on the phone alone, in Arabic and
 * English, with Arabic digits too. It keeps nothing: text in, a short list of actions out.
 */
object BloomParser {
    private const val CURRENCIES =
        "egp|usd|sar|aed|eur|gbp|kwd|جنيه|ج\\.م|ريال|درهم|دولار|يورو|\\$|€|£"

    private val MONTHS: List<Pair<Int, String>> = listOf(
        1 to "يناير|كانون الثاني|january|jan",
        2 to "فبراير|شباط|february|feb",
        3 to "مارس|اذار|march|mar",
        4 to "ابريل|نيسان|april|apr",
        5 to "مايو|ايار|may",
        6 to "يونيو|حزيران|june|jun",
        7 to "يوليو|تموز|july|jul",
        8 to "اغسطس|august|aug",
        9 to "سبتمبر|ايلول|september|sept|sep",
        10 to "اكتوبر|تشرين الاول|october|oct",
        11 to "نوفمبر|تشرين الثاني|november|nov",
        12 to "ديسمبر|كانون الاول|december|dec",
    )
    private val MONTH_ANY = MONTHS.joinToString("|") { it.second }

    private val WEEKDAYS: List<Pair<DayOfWeek, List<String>>> = listOf(
        DayOfWeek.SUNDAY to listOf("الاحد", "sunday"),
        DayOfWeek.MONDAY to listOf("الاثنين", "الاتنين", "monday"),
        DayOfWeek.TUESDAY to listOf("الثلاثاء", "التلات", "tuesday"),
        DayOfWeek.WEDNESDAY to listOf("الاربعاء", "wednesday"),
        DayOfWeek.THURSDAY to listOf("الخميس", "thursday"),
        DayOfWeek.FRIDAY to listOf("الجمعه", "friday"),
        DayOfWeek.SATURDAY to listOf("السبت", "saturday"),
    )

    private val ISO_DATE = Regex("""(?<!\d)(20\d{2})-(\d{2})-(\d{2})(?!\d)""")
    private val DAY_MONTH = Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?\s*(?:of\s+|من\s+)?($MONTH_ANY)(?![a-z])""")
    private val MONTH_DAY = Regex("""($MONTH_ANY)(?![a-z])\s*(\d{1,2})(?!\d)(?:st|nd|rd|th)?""")
    private val SLASH_DATE = Regex("""(?<![\d/])(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?(?![\d/])""")
    private val ORDINAL = Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)(?![a-z])""")

    private const val MARKER = "am|pm|a\\.m\\.|p\\.m\\.|صباحا|صباح|مساء|ليلا|عصرا|ظهرا|ص|م"
    private val TIME_COLON = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?![:\d])\s*($MARKER)?(?![\p{L}])""")
    private val TIME_WORD = Regex("""(?:الساعه|(?<![a-z])at|@|عند)\s*(\d{1,2})(?::(\d{2}))?(?![\d:])\s*($MARKER)?(?![\p{L}])""")
    private val TIME_AMPM = Regex("""(?<![\d:.,])(\d{1,2})\s*(am|pm)(?![a-z])""")

    private val AMOUNT_AFTER = Regex(
        """(?<![\d.,])(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(?<c>$CURRENCIES|le|l\.e)(?![a-z])""",
    )
    private val AMOUNT_BEFORE = Regex(
        """(?<![a-z])(?<c>$CURRENCIES)\s*(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
    )
    private val SPEND_WORDS = Regex(
        "خصم|سحب|دفع|شراء|مشتريات|عمليه|purchase|debited|debit|spent|paid|payment|withdrawn|withdrawal|transaction|charged",
    )
    private val INCOME_WORDS = Regex("ايداع|استرداد|راتب|credited|received|refund|salary|deposit")

    private val CODE_WORDS = Regex(
        "كود|رمز|otp|code|verification|verify|تحقق|pin|password|passcode|one-time|one time|الرقم السري|رقم سري",
    )
    private val CODE_NUMBER = Regex("""(?<![\d.,/:-])(\d{4,8})(?!\d)(?![,.:/]\d)""")

    private val LINK = Regex("""(https?://[^\s<>"']+|www\.[^\s<>"']+)""")
    private val PHONE = Regex("""(?<![\d+])(\+?\d[\d\s-]{7,16}\d)(?!\d)""")

    /** Digits to 0-9, Arabic letters to one spelling, no diacritics, lower case. */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append('0' + (ch - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (ch - '\u06F0'))
                '\u066C' -> sb.append(',')
                '\u066B' -> sb.append('.')
                'أ', 'إ', 'آ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                'ـ' -> {}
                in '\u064B'..'\u065F' -> {}
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun parse(sender: String, text: String, kinds: Set<Int>, now: Long = System.currentTimeMillis()): List<BloomAction> {
        val body = text.trim()
        if (body.isEmpty()) return emptyList()
        val n = normalize(body)
        val out = mutableListOf<BloomAction>()

        val code = findCode(n)
        if (code != null) {
            // A message with a code is about the code: nothing else is offered next to it.
            if (BA_CODE in kinds) out.add(BloomAction(BA_CODE, code))
            link(body)?.let { if (BA_LINK in kinds) out.add(BloomAction(BA_LINK, it)) }
            return out
        }

        if (BA_EVENT in kinds) {
            findEvent(n, now)?.let { (at, allDay) ->
                out.add(BloomAction(BA_EVENT, snippet(body, sender), at, allDay))
            }
        }
        if (BA_EXPENSE in kinds) {
            findExpense(n)?.let { (minor, currency) ->
                out.add(BloomAction(BA_EXPENSE, currency, amount = minor))
            }
        }
        if (BA_LINK in kinds) {
            link(body)?.let { out.add(BloomAction(BA_LINK, it)) }
        }
        if (BA_CALL in kinds) {
            findPhone(n)?.let { out.add(BloomAction(BA_CALL, it)) }
        }
        return out
    }

    private fun snippet(body: String, sender: String): String {
        val flat = body.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return sender
        if (flat.length <= 60) return flat
        val cut = flat.substring(0, 60)
        val space = cut.lastIndexOf(' ')
        return (if (space > 30) cut.substring(0, space) else cut) + "…"
    }

    // ───────────── Codes, links, phones ─────────────

    private fun findCode(n: String): String? {
        if (!CODE_WORDS.containsMatchIn(n)) return null
        return CODE_NUMBER.find(n)?.groupValues?.get(1)
    }

    private fun link(body: String): String? {
        val raw = LINK.find(body)?.value ?: return null
        val t = raw.trimEnd('.', ',', ';', ':', ')', '!', '?', '،')
        return if (t.length < 8) null else t
    }

    private fun findPhone(n: String): String? {
        for (m in PHONE.findAll(n)) {
            val raw = m.value.trim()
            if (!(raw.startsWith("+") || raw.startsWith("0"))) continue
            val digits = raw.filter { it.isDigit() }
            if (digits.length !in 9..14) continue
            return (if (raw.startsWith("+")) "+" else "") + digits
        }
        return null
    }

    // ───────────── Money ─────────────

    private fun findExpense(n: String): Pair<Long, String>? {
        if (!SPEND_WORDS.containsMatchIn(n) || INCOME_WORDS.containsMatchIn(n)) return null
        var number: String? = null
        var currency = ""
        AMOUNT_AFTER.find(n)?.let {
            number = it.groupValues[1]
            currency = it.groups["c"]?.value.orEmpty()
        }
        if (number == null) {
            AMOUNT_BEFORE.find(n)?.let {
                number = it.groupValues[2]
                currency = it.groups["c"]?.value.orEmpty()
            }
        }
        val minor = parseMoney(number ?: return null) ?: return null
        if (minor <= 0L) return null
        return Pair(minor, currency.uppercase())
    }

    // ───────────── Dates and times ─────────────

    /** When the thing happens (start of the day for "all day") and whether it has no time, or null. */
    private fun findEvent(n: String, now: Long): Pair<Long, Boolean>? {
        val today = CalendarEngine.ld(now)
        val date = findDate(n, today)
        val minutes = findTime(n)
        if (date == null && minutes == null) return null
        if (date == null) {
            // Only a time: today if it is still to come, tomorrow if not.
            val t = CalendarEngine.startMs(today) + minutes!! * 60_000L
            val at = if (t > now) t else t + 86_400_000L
            return Pair(at, false)
        }
        if (date.isBefore(today)) return null
        if (minutes == null) return Pair(CalendarEngine.startMs(date), true)
        val at = CalendarEngine.startMs(date) + minutes * 60_000L
        return if (at > now) Pair(at, false) else null
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (e: Exception) {
        null
    }

    /** This year's date, or next year's when this year's has gone by. */
    private fun upcoming(m: Int, d: Int, today: LocalDate): LocalDate? {
        val a = safeDate(today.year, m, d) ?: return null
        if (!a.isBefore(today)) return a
        return safeDate(today.year + 1, m, d)
    }

    private fun monthOf(name: String): Int =
        MONTHS.firstOrNull { (_, alts) -> alts.split('|').contains(name) }?.first ?: 0

    private fun findDate(n: String, today: LocalDate): LocalDate? {
        ISO_DATE.find(n)?.let {
            safeDate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())?.let { d -> return d }
        }
        DAY_MONTH.find(n)?.let {
            val m = monthOf(it.groupValues[2])
            val d = it.groupValues[1].toInt()
            if (m > 0) upcoming(m, d, today)?.let { r -> return r }
        }
        MONTH_DAY.find(n)?.let {
            val m = monthOf(it.groupValues[1])
            val d = it.groupValues[2].toInt()
            if (m > 0) upcoming(m, d, today)?.let { r -> return r }
        }
        SLASH_DATE.find(n)?.let {
            val d = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            val yRaw = it.groupValues[3]
            if (yRaw.isEmpty()) {
                if (m in 1..12 && d in 1..31) upcoming(m, d, today)?.let { r -> return r }
            } else {
                val y = if (yRaw.length == 2) 2000 + yRaw.toInt() else yRaw.toInt()
                safeDate(y, m, d)?.let { r -> return r }
            }
        }
        if (n.contains("بعد بكره") || n.contains("after tomorrow")) return today.plusDays(2)
        if (n.contains("بكره") || n.contains("غدا") || n.contains("tomorrow")) return today.plusDays(1)
        val isToday = n.contains("اليوم") || n.contains("النهارده") || n.contains("النهارد") || n.contains("today") || n.contains("tonight")
        for ((dow, names) in WEEKDAYS) {
            if (names.any { n.contains(it) }) {
                var ahead = (dow.value - today.dayOfWeek.value + 7) % 7
                if (ahead == 0 && !isToday) ahead = 7
                return today.plusDays(ahead.toLong())
            }
        }
        if (isToday) return today
        ORDINAL.find(n)?.let {
            val d = it.groupValues[1].toInt()
            if (d in 1..31) {
                safeDate(today.year, today.monthValue, d)?.let { r -> if (!r.isBefore(today)) return r }
                val next = today.plusMonths(1)
                safeDate(next.year, next.monthValue, d)?.let { r -> return r }
            }
        }
        if (n.contains("الاسبوع الجاي") || n.contains("الاسبوع القادم") || n.contains("الاسبوع اللي جاي") || n.contains("next week")) {
            return today.plusDays(7)
        }
        return null
    }

    private fun applyMarker(hour: Int, marker: String?, casual: Boolean): Int {
        val pm = marker != null && marker in setOf("pm", "p.m.", "م", "مساء", "ليلا", "عصرا", "ظهرا")
        val am = marker != null && marker in setOf("am", "a.m.", "ص", "صباح", "صباحا")
        return when {
            pm && hour < 12 -> hour + 12
            am && hour == 12 -> 0
            marker == null && casual && hour in 1..6 -> hour + 12
            else -> hour
        }
    }

    /** Minutes after midnight, or null. */
    private fun findTime(n: String): Int? {
        TIME_COLON.find(n)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            val marker = it.groupValues[3].ifEmpty { null }
            if (h < 24 && m < 60) {
                val hh = applyMarker(h, marker, false)
                if (hh < 24) return hh * 60 + m
            }
        }
        TIME_WORD.find(n)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            val marker = it.groupValues[3].ifEmpty { null }
            if (h < 24 && m < 60) {
                val hh = applyMarker(h, marker, true)
                if (hh < 24) return hh * 60 + m
            }
        }
        TIME_AMPM.find(n)?.let {
            val h = it.groupValues[1].toInt()
            if (h in 1..12) return applyMarker(h, it.groupValues[2], false) * 60
        }
        return null
    }
}
