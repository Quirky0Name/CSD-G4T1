"""A document's open-access text, from Europe PMC: the only text source investigation uses.

Crossref has no body text for notices, and publisher pages and PDFs are bot-blocked, but
Europe PMC returns the full text of anything open access in PubMed Central (PLOS, Scientific
Reports, RSC, PNAS, ...). Paywalled notices (Elsevier, The Lancet, JAMA) get none: they're
indexed but not open access.

The text is data for impact to read, never instructions: a notice can say anything.

The full text also gives the document's links: from the XML (`<related-article>`, `<ext-link>`,
`<pub-id pub-id-type="doi">`, `<self-uri>`, outside any reference list), then from the plain
text. A PMCID or PMID link is turned into a DOI with one Europe PMC search."""

import logging
import xml.etree.ElementTree as ET
from collections.abc import Iterator
from dataclasses import dataclass
from enum import StrEnum

import httpx
from pydantic import BaseModel, Field

from common.doi import normalize_doi
from research_evaluation.investigation.http import FetchStatus, get
from research_evaluation.investigation.links import (
    Link,
    LinkSource,
    doi_link,
    text_links,
    unique,
    url_link,
)

EUROPE_PMC_URL = "https://www.ebi.ac.uk/europepmc/webservices/rest"
# a notice is a paragraph or two; this only bites on a full article (a new version or current copy)
MAX_TEXT_CHARS = 60_000

# elements whose text is one block; what's inside them isn't walked separately
_BLOCKS = {"article-title", "title", "p"}
# cited works: no link is read from inside them (a reference list can sit in the body too)
_NOT_LINKED = {"back", "ref-list"}
_XLINK_HREF = "{http://www.w3.org/1999/xlink}href"
# JATS ext-link types naming a PubMed Central or PubMed record, which a search turns into a DOI
_ID_TYPES = {"pmcid": "pmcid", "pmc": "pmcid", "pmid": "pmid"}

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
    # from the full text, so only for an open-access document
    links: list[Link] = Field(default_factory=list)


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
        root = ET.fromstring(full_text.content)
        text = _plain_text(root)
    except (ET.ParseError, ValueError) as exc:
        log.warning("europepmc full text for %s is unreadable: %s", doi, type(exc).__name__)
        return DocumentText(status=TextStatus.ERROR)
    if not text:
        log.warning("europepmc full text for %s has no text", doi)
        return DocumentText(status=TextStatus.ERROR)
    # the text's links are read from all of it, not only what's kept
    links = unique([*await _xml_links(http, root, doi), *text_links(text)])
    if len(text) > MAX_TEXT_CHARS:
        return DocumentText(
            status=TextStatus.OK, text=text[:MAX_TEXT_CHARS], truncated=True, links=links
        )
    return DocumentText(status=TextStatus.OK, text=text, links=links)


def plain_text(xml: bytes) -> str:
    """JATS full text as plain text: the title, the abstract, then the body (figure and table
    captions included), one block per paragraph. The reference list (in `<back>`) is left out."""
    return _plain_text(ET.fromstring(xml))


def _plain_text(root: ET.Element) -> str:
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


@dataclass(frozen=True)
class _RecordId:
    """A PMCID or PMID the XML links to, waiting for its search."""

    kind: str  # "pmcid" or "pmid"
    value: str
    role: str | None


async def _xml_links(http: httpx.AsyncClient, root: ET.Element, doi: str) -> list[Link]:
    """The XML's links in document order, each PMCID or PMID searched once. Never raises: an
    unreadable part gives no links."""
    try:
        found = list(_targets(root))
    except (ValueError, TypeError, AttributeError) as exc:
        log.warning("europepmc full text for %s has unreadable links: %s", doi, type(exc).__name__)
        return []
    resolved: dict[tuple[str, str], Link] = {}
    links = []
    for target in found:
        if isinstance(target, _RecordId):
            key = (target.kind, target.value)
            if key not in resolved:
                resolved[key] = await _resolve(http, target, doi)
            target = resolved[key].model_copy(update={"role": target.role})
        links.append(target)
    return links


def _targets(element: ET.Element) -> Iterator[Link | _RecordId]:
    for child in element:
        if child.tag in _NOT_LINKED:
            continue
        if child.tag == "related-article":
            # what's inside it is its own link's target, not a link of its own
            target = _related_article(child)
            if target is not None:
                yield target
            continue
        target = _target(child, None)
        if target is not None:
            yield target
        yield from _targets(child)


def _target(element: ET.Element, role: str | None) -> Link | _RecordId | None:
    """The link an `<ext-link>`, `<pub-id pub-id-type="doi">` or `<self-uri>` gives, if any."""
    href = element.get(_XLINK_HREF)
    if element.tag == "ext-link":
        link_type = element.get("ext-link-type")
        if link_type == "doi":
            return doi_link(href, role, LinkSource.EUROPEPMC_XML)
        if link_type in _ID_TYPES:
            return _record_id(_ID_TYPES[link_type], href, role)
        return url_link(href, role, LinkSource.EUROPEPMC_XML)
    if element.tag == "pub-id" and element.get("pub-id-type") == "doi":
        return doi_link("".join(element.itertext()), role, LinkSource.EUROPEPMC_XML)
    if element.tag == "self-uri":
        return url_link(href, role, LinkSource.EUROPEPMC_XML)
    return None


def _related_article(element: ET.Element) -> Link | _RecordId | None:
    """A `<related-article>`'s one link, typed by its `related-article-type`: a DOI from its
    own `xlink:href`, else from a DOI nested in it, else a PMCID or PMID (its own, then a
    nested one), else a URL. None when it names nothing."""
    role = element.get("related-article-type")
    href = element.get(_XLINK_HREF)
    own_type = element.get("ext-link-type")
    nested = [e for e in element.iter() if e is not element and e.tag in {"ext-link", "pub-id"}]
    dois = [e for e in nested if "doi" in (e.get("ext-link-type"), e.get("pub-id-type"))]
    ids = [e for e in nested if e.tag == "ext-link" and e.get("ext-link-type") in _ID_TYPES]
    candidates = [
        doi_link(href, role, LinkSource.EUROPEPMC_XML) if own_type == "doi" else None,
        *(_target(e, role) for e in dois),
        _record_id(_ID_TYPES[own_type], href, role) if own_type in _ID_TYPES else None,
        *(_target(e, role) for e in ids),
        url_link(href, role, LinkSource.EUROPEPMC_XML) if own_type in {None, "uri"} else None,
    ]
    return next((c for c in candidates if c is not None), None)


def _record_id(kind: str, raw: str | None, role: str | None) -> _RecordId | None:
    """A PMCID (with or without its "PMC") or a PMID, checked to be one."""
    value = (raw or "").strip().upper()
    if kind == "pmcid":
        value = value if value.startswith("PMC") else f"PMC{value}"
        valid = value[3:].isdigit()
    else:
        valid = value.isdigit()
    return _RecordId(kind=kind, value=value, role=role) if valid else None


async def _resolve(http: httpx.AsyncClient, record: _RecordId, doi: str) -> Link:
    """The record's DOI, from one Europe PMC search; its PMC or PubMed URL when the search
    finds none or fails. Never raises."""
    if record.kind == "pmcid":
        query = f"PMCID:{record.value}"
        fallback = f"https://pmc.ncbi.nlm.nih.gov/articles/{record.value}/"
    else:
        query = f"EXT_ID:{record.value} AND SRC:MED"
        fallback = f"https://pubmed.ncbi.nlm.nih.gov/{record.value}/"
    search = await get(
        http,
        f"{EUROPE_PMC_URL}/search",
        source="europepmc id search",
        doi=doi,
        params={"query": query, "resultType": "lite", "format": "json"},
    )
    found = None
    if not isinstance(search, FetchStatus):
        try:
            results = search.json()["resultList"]["result"]
            found = next(
                (
                    link
                    for r in results
                    if isinstance(r, dict) and str(r.get(record.kind, "")).upper() == record.value
                    for link in [doi_link(r.get("doi"), record.role, LinkSource.EUROPEPMC_XML)]
                    if link is not None
                ),
                None,
            )
        except (ValueError, KeyError, TypeError, AttributeError) as exc:
            log.warning(
                "europepmc id search returned an unreadable result for %s: %s",
                doi,
                type(exc).__name__,
            )
    return found or Link(doi=None, url=fallback, role=record.role, source=LinkSource.EUROPEPMC_XML)


def _quoted(doi: str) -> str:
    """The DOI made safe inside the query's double quotes."""
    return doi.replace("\\", "\\\\").replace('"', '\\"')
