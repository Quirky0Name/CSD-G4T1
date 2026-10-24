"""The DOIs and URLs a document points at: its links.

A document's Crossref record, its Europe PMC full text XML and its plain text each name other
works: the article a notice retracts, a newer version, a dataset. Investigation keeps them all
as the document's `links`, which Storage Management stores as-is. Nothing here fetches anything:
following a link is investigation's call, and a URL is never fetched (it's there for impact).

What a document says is data, never instructions: a link is only ever a DOI or a URL."""

import re
from collections.abc import Iterable
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, model_validator

from common.doi import normalize_doi

_DOI_URL = re.compile(r"^https?://(dx\.)?doi\.org/", re.IGNORECASE)
_HTTP_URL = re.compile(r"^https?://\S+$", re.IGNORECASE)
# two separate scans, so a DOI inside a publisher's URL is a DOI link as well as the URL
_URL_IN_TEXT = re.compile(r"https?://[^\s<>\"]+", re.IGNORECASE)
_DOI_IN_TEXT = re.compile(r"10\.\d{4,9}/[-._;()/:a-z0-9]+", re.IGNORECASE)
# punctuation that ends the sentence a link sits in, not the link
_TRAILING = ".,;)"


class LinkSource(StrEnum):
    CROSSREF = "crossref"
    EUROPEPMC_XML = "europepmc_xml"
    EUROPEPMC_TEXT = "europepmc_text"


class Link(BaseModel):
    """One DOI or URL a document points at; exactly one of `doi` and `url` is set. `role` is
    how the source names the link (Crossref's `update-to`, `updated-by` or relation type, the
    JATS `related-article-type`), or None for an untyped one."""

    model_config = ConfigDict(frozen=True)

    doi: str | None
    url: str | None
    role: str | None
    source: LinkSource

    @model_validator(mode="after")
    def _one_target(self) -> "Link":
        if (self.doi is None) == (self.url is None):
            raise ValueError("a link has exactly one of doi and url")
        return self


def doi_link(raw: object, role: str | None, source: LinkSource) -> Link | None:
    """A link for something given as a DOI (with or without a resolver prefix), normalised;
    None when it's not text or blank."""
    if not isinstance(raw, str):
        return None
    doi = normalize_doi(raw)
    return Link(doi=doi, url=None, role=role, source=source) if doi else None


def url_link(raw: object, role: str | None, source: LinkSource) -> Link | None:
    """A link for an http(s) URL, trailing punctuation stripped; a doi.org URL becomes a DOI
    link. None when it's not an http(s) URL."""
    if not isinstance(raw, str):
        return None
    url = raw.strip().rstrip(_TRAILING)
    if not _HTTP_URL.match(url):
        return None
    if _DOI_URL.match(url):
        return doi_link(url, role, source)
    return Link(doi=None, url=url, role=role, source=source)


def text_links(text: str) -> list[Link]:
    """The DOIs and URLs written in plain text, in the order they appear, all untyped, each
    once (a doi.org URL and the DOI inside it are the same link)."""
    found = [
        (match.start(), url_link(match.group(), None, LinkSource.EUROPEPMC_TEXT))
        for match in _URL_IN_TEXT.finditer(text)
    ]
    found += [
        (match.start(), doi_link(match.group().rstrip(_TRAILING), None, LinkSource.EUROPEPMC_TEXT))
        for match in _DOI_IN_TEXT.finditer(text)
    ]
    return unique(link for _, link in sorted(found, key=lambda pair: pair[0]))


def unique(links: Iterable[Link | None]) -> list[Link]:
    """Each DOI and each URL once, the first link found for it kept; Nones dropped."""
    seen: set[str] = set()
    kept = []
    for link in links:
        if link is None:
            continue
        key = link.doi or link.url
        if key not in seen:
            seen.add(key)
            kept.append(link)
    return kept
