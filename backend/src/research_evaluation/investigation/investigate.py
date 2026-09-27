"""Which documents a report needs, and fetching one of them.

`plan_documents` turns the detected changes behind a report's alerts into the DOIs to fetch;
`fetch_document` fetches one DOI's Crossref record (S3) and open-access text (S4) side by
side. Neither touches Storage Management: the caller stores the result
(`POST /internal/documents`), and Storage Management downloads the PDF then."""

import asyncio
import logging
from collections.abc import Iterable
from enum import StrEnum
from typing import Any

import httpx
from pydantic import BaseModel

from common.doi import normalize_doi
from research_evaluation.changes import Change, ChangeType
from research_evaluation.investigation.crossref import CrossrefRecord, fetch_record
from research_evaluation.investigation.europepmc import DocumentText, TextStatus, fetch_text
from research_evaluation.investigation.http import FetchStatus

log = logging.getLogger(__name__)

# Crossref types whose notice DOI *is* the newer version of the paper
_NEW_VERSION_TYPES = {"new_version", "new_edition"}
# changes that can alter the paper at its own DOI, so the report fetches its current copy
# (an EoC doesn't change the paper; a DOAJ delisting is about the journal)
_CHANGES_THE_PAPER = {
    ChangeType.RETRACTION,
    ChangeType.CORRECTION,
    ChangeType.ERRATUM,
    ChangeType.OTHER,
}


class DocumentKind(StrEnum):
    """The same values Storage Management stores as `kind`."""

    NOTICE = "notice"
    NEW_VERSION = "new_version"
    CURRENT_VERSION = "current_version"


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
    http: httpx.AsyncClient, planned: PlannedDocument, paper_doi: str | None, mailto: str
) -> FetchedDocument:
    """The document's Crossref record and open-access text, fetched side by side. One failing
    never stops the other, and nothing raises: a failure is a status."""
    record, text = await asyncio.gather(
        fetch_record(http, planned.doi, mailto),
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
    )


def _or_status(result: Any, failure: Any, source: str, doi: str) -> Any:
    """A fetcher that raised anyway (it shouldn't) counts as that source failing. Cancellation
    and other BaseExceptions still propagate."""
    if isinstance(result, Exception):
        log.warning("%s fetch raised for %s: %s", source, doi, type(result).__name__)
        return failure
    if isinstance(result, BaseException):
        raise result
    return result
