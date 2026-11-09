# Quick on-phone check for date or time words, so texts without them never get sent to Claude
import re
WEEKDAYS = r"mon(day)?|tue(s|sday)?|wed(s|nesday)?|thu(rs?|rsday)?|fri(day)?|sat(urday)?|sun(day)?"
MONTHS = (r"jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|"
          r"aug(ust)?|sept?(ember)?|oct(ober)?|nov(ember)?|dec(ember)?")
RELATIVE = r"today|tonight|tonite|tomorrow|tmrw|tmr|tmw|2morrow|weekend|noon|midnight|next week|this week"
PATTERNS = [
    rf"\b({WEEKDAYS}|{MONTHS}|{RELATIVE})\b",  # day names, month names and words like tomorrow
    r"\b\d{1,2}(:\d{2})?\s?(am|pm|a\.m\.|p\.m\.)",  # times like 7pm or 7:30 am
    r"\b\d{1,2}:\d{2}\b",  # times like 19:00
    r"\b\d{1,2}\s?o['’]?clock\b",  # times like 7 o'clock
    r"\bat \d{1,2}\b",  # times like at 7
    r"\b\d{1,2}/\d{1,2}(/\d{2,4})?\b",  # dates like 12/3 or 12/3/26
    r"\b\d{1,2}[.-]\d{1,2}[.-]\d{2,4}\b",  # dates like 1-6-2026 or 1.6.2026
    r"\b\d{4}-\d{2}-\d{2}\b",  # dates like 2026-12-03
    r"\b\d{1,2}(st|nd|rd|th)\b",  # dates like the 3rd
]
DATE_HINT = re.compile("|".join(PATTERNS), re.IGNORECASE)

# Return True if the text looks like it mentions a date or time
def has_date_hint(text):
    return DATE_HINT.search(text or "") is not None
