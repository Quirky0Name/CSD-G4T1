"""Which documents a report needs, and fetching one of them.

`plan_documents` turns the detected changes behind a report's alerts into the DOIs to fetch;
`fetch_document` fetches one DOI's Crossref record (S3) and open-access text (S4) side by
side, and keeps the links both point at. Neither touches Storage Management: the caller
stores the result (`POST /internal/documents`), and Storage Management downloads the PDF
then."""

import asyncio
import logging
from collections.abc import Iterable
from enum import StrEnum
from typing import Any

import httpx
from pydantic import BaseModel

from common.doi import normalize_doi
from research_evaluation.changes import Change, ChangeType
from research_evaluation.investigation.crossref import CrossrefRecord, CrossrefResult, fetch_record
from research_evaluation.investigation.europepmc import DocumentText, TextStatus, fetch_text
from research_evaluation.investigation.http import FetchStatus
from research_evaluation.investigation.links import Link, LinkSource, unique

log = logging.getLogger(__name__)

# Crossref types whose notice DOI *is* the newer version of the paper
_NEW_VERSION_TYPES = {"new_version", "new_edition"}
# changes that can alter the paper at its own DOI, so the report fetches its current copy
# (an EoC doesn't change the paper; a DOAJ delisting is about the journal; a citation jump is about
# who cites it)
_CHANGES_THE_PAPER = {
    ChangeType.RETRACTION,
    ChangeType.CORRECTION,
    ChangeType.ERRATUM,
    ChangeType.OTHER,
}


# link roles that cite or point elsewhere, never into the paper's history: kept, not fetched.
# The back-pointers name the article a document updates; the paper itself is caught earlier, so
# one that reaches here names another article (R5)
_KEPT_ROLES = {
    "references",
    "cites",
    "has-review",
    "has-preprint",
    "is-supplemented-by",
    "is-related-material",
    "update-to",
    "retracted-article",
    "corrected-article",
    "object-of-concern",
}
# link roles leading to a later notice, a newer version or a replacement: Crossref's
# `updated-by` and relation types (Elsevier's named after the notice), and JATS's forward ones
_FOLLOWED_ROLES = {
    "updated-by",
    "is-replaced-by",
    "has-version",
    "is-version-of",
    "retraction",
    "correction",
    "erratum",
    "expression_of_concern",
    "addendum",
    "retraction-forward",
    "correction-forward",
    "concern-forward",
    "update",
}


class LinkOutcome(StrEnum):
    FOLLOW = "follow"  # fetched and stored as a related document
    CHECK = "check"  # its Crossref record decides (lineage_match)
    KEEP = "keep"  # stays in the document's links only


class DocumentKind(StrEnum):
    """The same values Storage Management stores as `kind`."""

    NOTICE = "notice"
    NEW_VERSION = "new_version"
    CURRENT_VERSION = "current_version"
    RELATED = "related"


class PlannedDocument(BaseModel):
    kind: DocumentKind
    doi: str


class FetchedDocument(BaseModel):
    """One document as investigation fetched it: everything `POST /internal/documents` takes
    except the report id."""

    kind: DocumentKind
    doi: str
    crossref_status: FetchStatus
    crossref_record: dict[str, Any] | None
    # notices only: whether the notice's Crossref `update-to` names the paper
    update_to_includes_paper: bool | None
    text_status: TextStatus
    text: str | None
    text_truncated: bool
    # the DOIs and URLs its record and full text point at; [] when its sources found nothing
    links: list[Link]

    def body(self, report_id: int) -> dict[str, Any]:
        return {"report_id": report_id, **self.model_dump(mode="json")}


def plan_documents(
    changes: Iterable[Change], report_keys: set[str], paper_doi: str | None
) -> list[PlannedDocument]:
    """The documents a report needs, each DOI once: a notice per alert that has one (a new
    version instead, for a Crossref `new_version` / `new_edition`), then the paper's current copy
    once, if any of the report's changes can alter the paper. Only changes whose key is one of
    the report's alerts count; two changes sharing a key both count (a retraction flag and its
    notice). A notice whose DOI is the paper's own needs no notice document: the current copy
    covers it. Without a paper DOI there's no current copy."""
    paper = normalize_doi(paper_doi)
    planned: dict[str, PlannedDocument] = {}
    needs_current_copy = False
    for change in changes:
        if change.change_key not in report_keys:
            continue
        new_version = (
            change.change_type is ChangeType.OTHER and change.notice_type in _NEW_VERSION_TYPES
        )
        if change.change_type in _CHANGES_THE_PAPER and not new_version:
            needs_current_copy = True
        notice = normalize_doi(change.notice_doi)
        if notice is None:
            continue
        if notice == paper:
            needs_current_copy = True
            continue
        kind = DocumentKind.NEW_VERSION if new_version else DocumentKind.NOTICE
        planned.setdefault(notice, PlannedDocument(kind=kind, doi=notice))
    documents = list(planned.values())
    if needs_current_copy and paper is not None:
        documents.append(PlannedDocument(kind=DocumentKind.CURRENT_VERSION, doi=paper))
    return documents


async def fetch_document(
    http: httpx.AsyncClient,
    planned: PlannedDocument,
    paper_doi: str | None,
    mailto: str,
    known_record: CrossrefResult | None = None,
) -> FetchedDocument:
    """The document's Crossref record and open-access text, fetched side by side, and their
    links (Crossref's first, each DOI and URL once). A record already fetched (a checked link's)
    is used instead of asking Crossref again. One failing never stops the other, and nothing
    raises: a failure is a status."""
    record, text = await asyncio.gather(
        (
            _known(known_record)
            if known_record is not None
            else fetch_record(http, planned.doi, mailto)
        ),
        fetch_text(http, planned.doi),
        return_exceptions=True,
    )
    record = _or_status(record, FetchStatus.ERROR, "crossref", planned.doi)
    text = _or_status(text, DocumentText(status=TextStatus.ERROR), "europepmc", planned.doi)
    found = isinstance(record, CrossrefRecord)
    return FetchedDocument(
        kind=planned.kind,
        doi=planned.doi,
        crossref_status=FetchStatus.OK if found else record,
        crossref_record=record.model_dump(mode="json") if found else None,
        update_to_includes_paper=(
            record.updates(paper_doi)
            if found and planned.kind is DocumentKind.NOTICE and paper_doi
            else None
        ),
        text_status=text.status,
        text=text.text,
        text_truncated=text.truncated,
        links=unique([*(record.links if found else []), *text.links]),
    )


async def _known(record: CrossrefResult) -> CrossrefResult:
    return record


def classify_link(link: Link, paper_doi: str | None, seen: set[str]) -> LinkOutcome:
    """What investigation does with one of a document's links. A URL, the paper's own DOI and a
    DOI already seen in the report are kept; so are citations and back-pointers. A typed link
    into the paper's history is followed, and an untyped DOI the document's XML or text names
    is checked. Any other role is kept."""
    if link.doi is None or link.doi == normalize_doi(paper_doi) or link.doi in seen:
        return LinkOutcome.KEEP
    if link.role in _KEPT_ROLES:
        return LinkOutcome.KEEP
    if link.role in _FOLLOWED_ROLES:
        return LinkOutcome.FOLLOW
    if link.role is None and link.source is not LinkSource.CROSSREF:
        return LinkOutcome.CHECK
    return LinkOutcome.KEEP


def lineage_match(record: CrossrefRecord, paper_doi: str | None, report_dois: set[str]) -> bool:
    """Whether a checked DOI's record says it's in the paper's history: its `update-to`,
    `updated-by` or a relation names the paper or a DOI already in the report."""
    paper = normalize_doi(paper_doi)
    return record.names_any(report_dois | {paper} if paper is not None else report_dois)


def _or_status(result: Any, failure: Any, source: str, doi: str) -> Any:
    """A fetcher that raised anyway (it shouldn't) counts as that source failing. Cancellation
    and other BaseExceptions still propagate."""
    if isinstance(result, Exception):
        log.warning("%s fetch raised for %s: %s", source, doi, type(result).__name__)
        return failure
    if isinstance(result, BaseException):
        raise result
    return result
