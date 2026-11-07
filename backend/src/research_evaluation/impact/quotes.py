"""Finding a model's quote in the text it names: one matcher for the service (claims) and the
evals (score.py, real/extract.py), so a quote counts as found in both or in neither.

Compare `quoted_in(norm(quote), norm(text))`. Text from a PDF comes from `pdf_pages`, one string
per page; `join_hyphens` joins a word hyphenated across a line break before matching."""

import io
import re
import unicodedata

from pypdf import PdfReader

# a line ending in "<letter>-", then a line starting with a letter (joined if it is lowercase)
_HYPHEN_BREAK = re.compile(r"([^\W\d_])-[ \t]*\n[ \t]*([^\W\d_])")
_ELLIPSIS = re.compile(r"\.\.\.|…")


class PdfUnreadable(Exception):
    """The bytes aren't a PDF pypdf can read."""


def norm(text: str) -> str:
    """Lowercased, whitespace collapsed, curly quotes straightened. NFKC first, so a real PDF's
    ligature ("ﬁ") matches a quote that spells it out ("fi")."""
    text = unicodedata.normalize("NFKC", text)
    text = text.replace("’", "'").replace("“", '"').replace("”", '"')
    return re.sub(r"\s+", " ", text).strip().lower()


def quoted_in(quote: str, text: str) -> bool:
    """A normalised quote is in a normalised text; "..." or "…" in the quote skips words, so each
    piece must be there, in order."""
    start = 0
    for piece in filter(None, (p.strip() for p in _ELLIPSIS.split(quote))):
        found = text.find(piece, start)
        if found < 0:
            return False
        start = found + len(piece)
    return True


def _join(match: re.Match) -> str:
    before, after = match.groups()
    return before + after if after.islower() else match.group()


def join_hyphens(text: str) -> str:
    """Joins a word hyphenated across a line break ("pro-" / "liferation" becomes
    "proliferation"); a hyphen before a capital or a digit ("COVID-" / "19") stays."""
    return _HYPHEN_BREAK.sub(_join, text)


def pdf_pages(data: bytes) -> list[str]:
    """The text of each page of a PDF, in order ("" for a page with no text layer). Raises
    PdfUnreadable when pypdf can't read the bytes."""
    try:
        return [page.extract_text() or "" for page in PdfReader(io.BytesIO(data)).pages]
    except Exception as exc:  # pypdf raises many types for a broken file
        raise PdfUnreadable(str(exc)) from exc
