// Quick on-phone check for date or time words, so texts without them are never sent to Claude (port of heuristics.py)
package io.github.adenkushnir.datebook.pipeline
private const val WEEKDAYS = "mon(day)?|tue(s|sday)?|wed(s|nesday)?|thu(rs?|rsday)?|fri(day)?|sat(urday)?|sun(day)?"
private const val MONTHS = "jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|" +
    "aug(ust)?|sept?(ember)?|oct(ober)?|nov(ember)?|dec(ember)?"
private const val RELATIVE = "today|tonight|tonite|tomorrow|tmrw|tmr|tmw|2morrow|weekend|noon|midnight|" +
    "next week|this week"
private val PATTERNS = listOf(
    """\b($WEEKDAYS|$MONTHS|$RELATIVE)\b""",  // day names, month names and words like tomorrow
    """\b\d{1,2}(:\d{2})?\s?(am|pm|a\.m\.|p\.m\.)""",  // times like 7pm or 7:30 am
    """\b\d{1,2}:\d{2}\b""",  // times like 19:00
    """\b\d{1,2}\s?o['’]?clock\b""",  // times like 7 o'clock
    """\bat \d{1,2}\b""",  // times like at 7
    """\b\d{1,2}/\d{1,2}(/\d{2,4})?\b""",  // dates like 12/3 or 12/3/26
    """\b\d{1,2}[.-]\d{1,2}[.-]\d{2,4}\b""",  // dates like 1-6-2026 or 1.6.2026
    """\b\d{4}-\d{2}-\d{2}\b""",  // dates like 2026-12-03
    """\b\d{1,2}(st|nd|rd|th)\b""",  // dates like the 3rd
)
private val DATE_HINT = Regex(PATTERNS.joinToString("|"), RegexOption.IGNORE_CASE)

// Return true if the text looks like it mentions a date or time
fun hasDateHint(text: String?): Boolean {
    return text != null && DATE_HINT.containsMatchIn(text)
}
private val CLEAR_PATTERNS = listOf(
    """\b(today|tonight|tonite|tomorrow|tmrw|tmr|tmw|2morrow|noon|midnight)\b""",  // a specific day or time of day
    """\b\d{1,2}(:\d{2})?\s?(am|pm|a\.m\.|p\.m\.)""",  // times like 7pm or 7:30 am
    """\b\d{1,2}:\d{2}\b""",  // times like 19:00
    """\b\d{1,2}\s?o['’]?clock\b""",  // times like 7 o'clock
    """\b\d{1,2}/\d{1,2}(/\d{2,4})?\b""",  // dates like 12/3 or 12/3/26
    """\b\d{1,2}[.-]\d{1,2}[.-]\d{2,4}\b""",  // dates like 1-6-2026 or 1.6.2026
    """\b\d{4}-\d{2}-\d{2}\b""",  // dates like 2026-12-03
    """\b($MONTHS)\.?\s+\d{1,2}(st|nd|rd|th)?\b""",  // dates like Nov 14 or November 14th
    """\b\d{1,2}(st|nd|rd|th)?\s+(of\s+)?($MONTHS)\b""",  // dates like 14 Nov or the 14th of November
)
private val CLEAR_DATE = Regex(CLEAR_PATTERNS.joinToString("|"), RegexOption.IGNORE_CASE)

// Stricter check for group chats: true only for a clear date or time, not loose hints like "sat", "may" or "the 3rd"
fun hasClearDate(text: String?): Boolean {
    return text != null && CLEAR_DATE.containsMatchIn(text)
}
