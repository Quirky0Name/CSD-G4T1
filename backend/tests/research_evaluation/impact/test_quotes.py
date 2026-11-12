"""Quote matching shared by the service and the evals (impact/quotes.py)."""

from pathlib import Path

import pytest

from research_evaluation.impact import quotes

FIXTURES = Path(__file__).parents[2] / "fixtures"


def found(quote: str, text: str) -> bool:
    return quotes.quoted_in(quotes.norm(quote), quotes.norm(text))


def test_a_ligature_matches_the_letters_it_stands_for():
    assert found("the final model", "in the ﬁnal model")


def test_curly_quotes_match_straight_ones():
    assert found("the authors' \"main\" result", "the authors’ “main” result")


def test_case_and_whitespace_are_ignored():
    assert found("Improved  N-back\naccuracy", "caffeine improved n-back accuracy")


def test_an_ellipsis_skips_words_but_keeps_order():
    text = "Caffeine improved accuracy in the second session only."
    assert found("Caffeine improved ... second session", text)
    assert found("Caffeine improved … second session", text)
    assert not found("second session ... Caffeine improved", text)


def test_a_quote_not_in_the_text_is_not_found():
    assert not found("caffeine impaired accuracy", "caffeine improved accuracy")


def test_a_word_hyphenated_across_a_line_break_is_joined():
    assert quotes.join_hyphens("cell pro-\nliferation") == "cell proliferation"


def test_a_hyphen_before_a_capital_or_digit_stays():
    assert quotes.join_hyphens("COVID-\n19 and Spot-\nTest") == "COVID-\n19 and Spot-\nTest"


def test_pdf_pages_gives_one_text_per_page():
    pages = quotes.pdf_pages((FIXTURES / "pdfs" / "two_pages.pdf").read_bytes())
    assert len(pages) == 2
    assert "healthy adults" in pages[0]
    assert "200 mg caffeine improved n-back accuracy" in pages[1]


def test_pdf_pages_raises_pdf_unreadable_for_corrupt_bytes():
    with pytest.raises(quotes.PdfUnreadable):
        quotes.pdf_pages(b"%PDF-1.4 this is not really a pdf")
