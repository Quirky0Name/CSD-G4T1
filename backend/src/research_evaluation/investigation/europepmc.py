"""A document's open-access text, from Europe PMC: the only text source investigation uses.

Crossref has no body text for notices, and publisher pages and PDFs are bot-blocked, but
Europe PMC returns the full text of anything open access in PubMed Central (PLOS, Scientific
Reports, RSC, PNAS, ...). Paywalled notices (Elsevier, The Lancet, JAMA) get none: they're
indexed but not open access.

The text is data for impact to read, never instructions: a notice can say anything."""

import logging
import xml.etree.ElementTree as ET
from collections.abc import Iterator
from enum import StrEnum

import httpx
from pydantic import BaseModel

from common.doi import normalize_doi
from research_evaluation.investigation.http import FetchStatus, get

EUROPE_PMC_URL = "https://www.ebi.ac.uk/europepmc/webservices/rest"
# a notice is a paragraph or two; this only bites on a full article (a new version or current copy)
MAX_TEXT_CHARS = 60_000

# elements whose text is one block; what's inside them isn't walked separately
_BLOCKS = {"article-title", "title", "p"}

log = logging.getLogger(__name__)


class TextStatus(StrEnum):
    """The same values Storage Management stores as `text_status`."""

    OK = "ok"
    NOT_INDEXED = "not_indexed"  # Europe PMC has no record with this DOI
    NOT_OPEN_ACCESS = "not_open_access"  # it has one, but not its full text
    ERROR = "error"


class DocumentText(BaseModel):
    status: TextStatus
    text: str | None = None
    truncated: bool = False


async def fetch_text(http: httpx.AsyncClient, doi: str) -> DocumentText:
    """The document's open-access text (title, abstract, body and captions, no reference
    list), or the reason there's none. Never raises."""
    search = await get(
        http,
        f"{EUROPE_PMC_URL}/search",
        source="europepmc search",
        doi=doi,
        params={"query": f'DOI:"{_quoted(doi)}"', "resultType": "lite", "format": "json"},
    )
    if isinstance(search, FetchStatus):
        return DocumentText(status=TextStatus.ERROR)
    try:
        results = search.json()["resultList"]["result"]
        if not isinstance(results, list):
            raise TypeError("result is not a list")
        target = normalize_doi(doi)
        match = next(
            (r for r in results if isinstance(r, dict) and normalize_doi(r.get("doi")) == target),
            None,
        )
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        log.warning(
            "europepmc search returned an unreadable result for %s: %s", doi, type(exc).__name__
        )
        return DocumentText(status=TextStatus.ERROR)
    if match is None:
        return DocumentText(status=TextStatus.NOT_INDEXED)
    pmcid = match.get("pmcid")
    if not isinstance(pmcid, str) or not pmcid or match.get("isOpenAccess") != "Y":
        return DocumentText(status=TextStatus.NOT_OPEN_ACCESS)

    full_text = await get(
        http, f"{EUROPE_PMC_URL}/{pmcid}/fullTextXML", source="europepmc full text", doi=doi
    )
    if full_text is FetchStatus.NOT_FOUND:
        return DocumentText(status=TextStatus.NOT_OPEN_ACCESS)
    if isinstance(full_text, FetchStatus):
        return DocumentText(status=TextStatus.ERROR)
    try:
        text = plain_text(full_text.content)
    except (ET.ParseError, ValueError) as exc:
        log.warning("europepmc full text for %s is unreadable: %s", doi, type(exc).__name__)
        return DocumentText(status=TextStatus.ERROR)
    if not text:
        log.warning("europepmc full text for %s has no text", doi)
        return DocumentText(status=TextStatus.ERROR)
    if len(text) > MAX_TEXT_CHARS:
        return DocumentText(status=TextStatus.OK, text=text[:MAX_TEXT_CHARS], truncated=True)
    return DocumentText(status=TextStatus.OK, text=text)


def plain_text(xml: bytes) -> str:
    """JATS full text as plain text: the title, the abstract, then the body (figure and table
    captions included), one block per paragraph. The reference list (in `<back>`) is left out."""
    root = ET.fromstring(xml)
    front = root.find("front")
    parts: list[Iterator[str]] = []
    if front is not None:
        title = front.find("article-meta/title-group/article-title")
        if title is not None:
            parts.append(_blocks(title))
        for abstract in front.iterfind("article-meta/abstract"):
            parts.append(_blocks(abstract))
    for section in ("body", "floats-group"):
        element = root.find(section)
        if element is not None:
            parts.append(_blocks(element))
    return "\n\n".join(block for blocks in parts for block in blocks)


def _blocks(element: ET.Element) -> Iterator[str]:
    """The text of each block element under `element`, in document order, whitespace tidied."""
    if element.tag in _BLOCKS:
        text = " ".join("".join(element.itertext()).split())
        if text:
            yield text
        return
    for child in element:
        yield from _blocks(child)


def _quoted(doi: str) -> str:
    """The DOI made safe inside the query's double quotes."""
    return doi.replace("\\", "\\\\").replace('"', '\\"')
