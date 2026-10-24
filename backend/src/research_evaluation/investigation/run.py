"""Investigation after a nudge: per paper, open a report for its new alerts, fetch each
document the report needs and store it in Storage Management, follow the links that lead into
the paper's own history (storing what they reach as related documents), then mark the report
investigated.

It runs in the background, after Research Evaluation has replied to Updating's nudge
(docs/EVALUATION.md, "Investigation"), so nothing here changes that reply. A failure is
logged and leaves the report `investigating`; nothing resumes it (a later sprint's job)."""

import logging
from collections import deque
from dataclasses import dataclass
from typing import Any
from uuid import UUID

import httpx
from pydantic import BaseModel

from common.doi import normalize_doi
from research_evaluation.changes import Change
from research_evaluation.investigation.crossref import CrossrefRecord, fetch_record
from research_evaluation.investigation.investigate import (
    DocumentKind,
    FetchedDocument,
    LinkOutcome,
    PlannedDocument,
    classify_link,
    fetch_document,
    lineage_match,
    plan_documents,
)
from research_evaluation.storage import (
    OpenedReport,
    PaperGone,
    mark_investigated,
    open_report,
    store_document,
)

log = logging.getLogger(__name__)

# a related document this many links from a planned one is stored, but its links aren't followed
MAX_DEPTH = 2
# related documents per report (planned ones don't count)
MAX_RELATED = 10
# untyped DOIs whose Crossref record is checked, per report
MAX_CHECKS = 20


@dataclass(frozen=True)
class InvestigationContext:
    """What investigation needs besides the Storage Management client: its own client for
    Crossref and Europe PMC (no auth, never the service token), the Crossref `mailto`, and
    the longer timeout for storing a document (Storage Management downloads its PDF first)."""

    external: httpx.AsyncClient
    crossref_mailto: str
    pdf_timeout: float


class PaperChanges(BaseModel):
    """A paper the nudge evaluated without failure, with everything detection found in its
    window (before the key check) and its DOI from the newest snapshot."""

    paper_id: UUID
    paper_doi: str | None
    changes: list[Change]


async def investigate_papers(
    sm: httpx.AsyncClient, context: InvestigationContext, papers: list[PaperChanges]
) -> list[int]:
    """Investigates each paper in turn and returns the ids of the reports finished in this
    run: the handoff to impact, which the impact plan adds. Never raises."""
    finished = []
    for paper in papers:
        report_id = await investigate_paper(sm, context, paper)
        if report_id is not None:
            finished.append(report_id)
    log.info("investigation finished reports %s", finished)
    return finished


async def investigate_paper(
    sm: httpx.AsyncClient, context: InvestigationContext, paper: PaperChanges
) -> int | None:
    """The report's id once it's investigated; None when the paper had no new alerts, or the
    report couldn't be finished (logged, and left `investigating`)."""
    report: OpenedReport | None = None
    try:
        report = await open_report(sm, paper.paper_id)
        if report is None:
            return None
        await investigate_report(sm, context, paper, report)
        await mark_investigated(sm, paper.paper_id, report.id)
    except PaperGone:
        log.info(
            "investigation skipped paper %s (report %s): Storage Management has no such paper",
            paper.paper_id,
            report.id if report else None,
        )
        return None
    except Exception as exc:  # a background task: nothing may escape it
        log.warning(
            "investigating paper %s (report %s) failed: %s",
            paper.paper_id,
            report.id if report else None,
            _cause(exc),
            exc_info=not isinstance(exc, httpx.HTTPError | ValueError),
        )
        return None
    return report.id


async def investigate_report(
    sm: httpx.AsyncClient, context: InvestigationContext, paper: PaperChanges, report: OpenedReport
) -> list[dict[str, Any]]:
    """Fetches and stores each document the report needs, one at a time, then the related
    documents its links lead to, and returns the rows Storage Management stored. The stored row
    is the document from then on, whether or not it differs from what was just fetched (whether
    that matters is a later sprint's call)."""
    keys = {alert.change_key for alert in report.alerts}
    stored = []
    fetched_planned = []
    for planned in plan_documents(paper.changes, keys, paper.paper_doi):
        fetched = await fetch_document(
            context.external, planned, paper.paper_doi, context.crossref_mailto
        )
        stored.append(
            await store_document(sm, fetched.body(report.id), timeout=context.pdf_timeout)
        )
        fetched_planned.append(fetched)
    stored += await follow_links(sm, context, paper, report, fetched_planned)
    log.info(
        "report %s for paper %s: %s",
        report.id,
        paper.paper_id,
        # .get: a log line must never fail a report whose documents are all stored
        ", ".join(
            f"{row.get('kind')} {row.get('doi')} (pdf {row.get('pdf_status')})" for row in stored
        )
        or "no documents",
    )
    return stored


async def follow_links(
    sm: httpx.AsyncClient,
    context: InvestigationContext,
    paper: PaperChanges,
    report: OpenedReport,
    planned: list[FetchedDocument],
) -> list[dict[str, Any]]:
    """Follows the planned documents' links breadth first, by `classify_link`'s fixed rules, and
    stores each document reached as `related`; returns the stored rows. The walk starts from
    every planned document but the current copy. Each DOI is fetched at most once per report
    (the paper's and the planned ones count as fetched), with at most MAX_RELATED related
    documents, MAX_CHECKS checks and MAX_DEPTH links from a planned document. No LLM takes part,
    and a URL is never fetched."""
    in_report = {doc.doi for doc in planned}
    if (paper_doi := normalize_doi(paper.paper_doi)) is not None:
        in_report.add(paper_doi)
    seen = set(in_report)
    queue = deque((doc, 0) for doc in planned if doc.kind is not DocumentKind.CURRENT_VERSION)
    stored: list[dict[str, Any]] = []
    checks = 0
    while queue and len(stored) < MAX_RELATED:
        document, depth = queue.popleft()
        if depth >= MAX_DEPTH:
            continue
        for link in document.links:
            if len(stored) >= MAX_RELATED:
                break
            outcome = classify_link(link, paper.paper_doi, seen)
            if outcome is LinkOutcome.KEEP:
                continue
            record = None
            if outcome is LinkOutcome.CHECK:
                if checks >= MAX_CHECKS:
                    continue
                checks += 1
                seen.add(link.doi)
                record = await fetch_record(context.external, link.doi, context.crossref_mailto)
                # a failed check counts as no match
                if not (
                    isinstance(record, CrossrefRecord)
                    and lineage_match(record, paper.paper_doi, in_report)
                ):
                    continue
            seen.add(link.doi)
            in_report.add(link.doi)
            related = await fetch_document(
                context.external,
                PlannedDocument(kind=DocumentKind.RELATED, doi=link.doi),
                paper.paper_doi,
                context.crossref_mailto,
                known_record=record,
            )
            stored.append(
                await store_document(sm, related.body(report.id), timeout=context.pdf_timeout)
            )
            queue.append((related, depth + 1))
    return stored


def _cause(exc: Exception) -> str:
    """What went wrong, without response bodies or URLs."""
    match exc:
        case httpx.HTTPStatusError():
            return (
                f"Storage Management answered {exc.response.status_code} for {exc.request.url.path}"
            )
        case httpx.TimeoutException():
            return f"Storage Management timed out ({type(exc).__name__})"
        case httpx.HTTPError():
            return f"could not reach Storage Management ({type(exc).__name__})"
        case _:
            return f"{type(exc).__name__}: {exc}"
