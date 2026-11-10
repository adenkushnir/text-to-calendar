# Tests for the date and time pre-filter
import pytest
from t2c import heuristics
WITH_DATES = [
    "dinner fri 7pm",
    "meeting tomorrow at 3",
    "Party on Saturday!",
    "dentist 12/3",
    "call at 7:30",
    "Dec 5th brunch?",
    "see you tonight",
    "lunch at noon",
    "flight is 2026-12-03",
    "it starts at 9 o'clock",
    "starts 8 o’clock",
    "the game is the 21st",
    "practice 6:30 P.M.",
    "TMRW at 10",
    "party 01/06/2026",
    "party 1/6/2026",
    "party 1-6-2026",
    "party 1.6.2026",
]
WITHOUT_DATES = [
    "lol ok",
    "thanks!",
    "call me when you can",
    "I have 3 dogs",
    "sounds good",
    "on my way",
    "I'm at the store",
    "my number is 555-1234",
    "call 1-800-555-1234",
    "it was 2.5 miles",
    "",
]

# Texts that mention a date or time are caught
@pytest.mark.parametrize("text", WITH_DATES)
def test_finds_dates(text):
    assert heuristics.has_date_hint(text)

# Texts with no date or time are skipped
@pytest.mark.parametrize("text", WITHOUT_DATES)
def test_skips_texts_without_dates(text):
    assert not heuristics.has_date_hint(text)

# A missing text counts as no date
def test_none_is_no_date():
    assert not heuristics.has_date_hint(None)
