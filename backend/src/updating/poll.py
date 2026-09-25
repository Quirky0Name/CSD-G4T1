"""One poll: sync the tracked papers, fetch each DOI once, store a snapshot per paper,
then nudge Research Evaluation about the papers that changed.

The outage gate (docs/DECISIONS.md, 2026-09-25): if Crossref or OpenAlex returned
`error` for a DOI, none of that DOI's papers gets a snapshot this poll. They're listed
under `source_errors` and retried next poll, so every stored snapshot is comparable
with the one before it. `not_found` is a stable answer and is stored. A failed author batch
doesn't gate: authors aren't compared, so the snapshot is stored with `error` and null stats.

Before a snapshot is posted, the paper's previous one is read back from Storage Management
and compared with it (compare.py). A difference sets `nudge_pending` on the paper; at the end
of the poll every pending paper is sent to Research Evaluation in one call, and the flag is
cleared only when it answers 202. A paper's first snapshot is a baseline and never nudges."""

import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, SecretStr
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from common.doi import Doi
from updating.compare import nudge_reasons
from updating.evaluation import send_changes
from updating.models import PollRun, PollTrigger, RunStatus, TrackedPaper
from updating.snapshot import Snapshot, author_ids, build_snapshot
from updating.sources import (
    OpenAlexWork,
    SourceStatus,
    fetch_crossref,
    fetch_openalex,
    fetch_openalex_authors,
    status_of,
)
from updating.storage import PaperId, SnapshotId, latest_snapshot, list_papers, post_snapshot

log = logging.getLogger(__name__)


class PollFailed(Exception):
    """The poll couldn't run at all (Storage Management's paper list was unavailable)."""


@dataclass(frozen=True)
class PollDeps:
    sm: httpx.AsyncClient
    sources: httpx.AsyncClient
    re: httpx.AsyncClient
    sessions: async_sessionmaker[AsyncSession]
    crossref_mailto: str
    openalex_api_key: SecretStr


class _Frozen(BaseModel):
    model_config = ConfigDict(frozen=True)


class Stored(_Frozen):
    paper_id: PaperId
    doi: Doi
    snapshot_id: SnapshotId


class SourceError(_Frozen):
    paper_id: PaperId
    doi: Doi
    crossref: SourceStatus
    openalex: SourceStatus


class StoreError(_Frozen):
    paper_id: PaperId
    doi: Doi
    reason: str


class NudgeError(_Frozen):
    paper_ids: list[PaperId]
    reason: str


class PollSummary(_Frozen):
    run_id: int
    trigger: PollTrigger
    started_at: datetime
    finished_at: datetime
    stored: list[Stored]
    skipped_no_doi: list[PaperId]
    source_errors: list[SourceError]
    store_errors: list[StoreError]
    nudged: list[PaperId]
    nudge_error: NudgeError | None


async def run_poll(deps: PollDeps, trigger: PollTrigger) -> PollSummary:
    started_at = datetime.now(UTC)
    async with deps.sessions() as session:
        run = PollRun(trigger=trigger, status=RunStatus.RUNNING, started_at=started_at)
        session.add(run)
        await session.commit()
        run_id = run.id

    try:
        summary = await _poll(deps, run_id, trigger, started_at)
    except Exception as exc:  # a cancelled run (shutdown) is a BaseException and stays `running`
        error = str(exc) if isinstance(exc, PollFailed) else type(exc).__name__
        await _close_run(deps, run_id, RunStatus.FAILED, summary=None, error=error)
        raise
    await _close_run(deps, run_id, RunStatus.SUCCEEDED, summary=summary, error=None)
    return summary


async def _poll(deps: PollDeps, run_id: int, trigger: PollTrigger, started_at: datetime) -> PollSummary:
    try:
        papers = await list_papers(deps.sm)
    except (httpx.HTTPError, ValueError) as exc:
        raise PollFailed(
            f"could not list papers from Storage Management: {type(exc).__name__}"
        ) from exc

    dois = {paper.id: paper.doi for paper in papers if paper.doi}
    skipped_no_doi = [paper.id for paper in papers if not paper.doi]
    for paper_id in skipped_no_doi:
        log.info("skipping paper %s: no DOI", paper_id)
    last_snapshot_ids = await _sync_tracked_papers(deps, dois)

    papers_by_doi: dict[Doi, list[PaperId]] = {}
    for paper_id, doi in dois.items():
        papers_by_doi.setdefault(doi, []).append(paper_id)

    stored: list[Stored] = []
    source_errors: list[SourceError] = []
    store_errors: list[StoreError] = []
    for doi, paper_ids in papers_by_doi.items():
        crossref = await fetch_crossref(deps.sources, doi, deps.crossref_mailto)
        openalex = await fetch_openalex(deps.sources, doi, deps.openalex_api_key)
        crossref_status, openalex_status = status_of(crossref), status_of(openalex)

        if SourceStatus.ERROR in (crossref_status, openalex_status):
            log.warning("no snapshot for %s this poll: crossref=%s openalex=%s",
                        doi, crossref_status, openalex_status)
            source_errors += [
                SourceError(paper_id=paper_id, doi=doi, crossref=crossref_status, openalex=openalex_status)
                for paper_id in paper_ids
            ]
            continue

        authors = (
            await fetch_openalex_authors(
                deps.sources, doi, author_ids(openalex), deps.openalex_api_key
            )
            if isinstance(openalex, OpenAlexWork)
            else openalex  # OpenAlex doesn't know the DOI: no authors to look up
        )
        snapshot = build_snapshot(
            doi, datetime.now(UTC).replace(microsecond=0), crossref, openalex, authors
        )
        for paper_id in paper_ids:
            match await _store_snapshot(deps, paper_id, last_snapshot_ids[paper_id], snapshot):
                case Stored() as done:
                    stored.append(done)
                case StoreError() as failed:
                    store_errors.append(failed)

    nudged: list[PaperId] = []
    nudge_error = None
    match await _send_nudges(deps):
        case NudgeError() as failed:
            nudge_error = failed
        case sent:
            nudged = sent

    return PollSummary(
        run_id=run_id,
        trigger=trigger,
        started_at=started_at,
        finished_at=datetime.now(UTC),
        stored=stored,
        skipped_no_doi=skipped_no_doi,
        source_errors=source_errors,
        store_errors=store_errors,
        nudged=nudged,
        nudge_error=nudge_error,
    )


def _failure_reason(exc: Exception) -> str:
    if isinstance(exc, httpx.HTTPStatusError):
        return f"HTTP {exc.response.status_code}"
    return type(exc).__name__


async def _store_snapshot(
    deps: PollDeps, paper_id: PaperId, last_snapshot_id: SnapshotId | None, snapshot: Snapshot
) -> Stored | StoreError:
    # `after_id` is exclusive, so start one below the last snapshot we recorded: that row comes
    # back too, along with anything newer (a baseline seeded after our last poll). With no
    # record yet, read everything.
    after_id = None if last_snapshot_id is None else SnapshotId(last_snapshot_id - 1)
    try:
        previous = await latest_snapshot(deps.sm, paper_id, after_id)
    except (httpx.HTTPError, ValueError) as exc:
        # storing now would leave the next poll comparing against this snapshot and lose the change
        reason = f"reading history: {_failure_reason(exc)}"
        log.warning("reading the previous snapshot for paper %s failed: %s", paper_id, reason)
        return StoreError(paper_id=paper_id, doi=snapshot.doi, reason=reason)

    try:
        snapshot_id = await post_snapshot(deps.sm, paper_id, snapshot)
    except (httpx.HTTPError, ValueError) as exc:
        reason = _failure_reason(exc)
        log.warning("storing the snapshot for paper %s failed: %s", paper_id, reason)
        return StoreError(paper_id=paper_id, doi=snapshot.doi, reason=reason)

    reasons = [] if previous is None else nudge_reasons(previous, snapshot)
    if previous is None:
        log.info("paper %s: first snapshot %s is the baseline", paper_id, snapshot_id)
    elif reasons:
        log.info("paper %s: snapshot %s changed since %s: %s",
                 paper_id, snapshot_id, previous.snapshot_id, "; ".join(reasons))
    await _record_snapshot(deps, paper_id, snapshot_id, nudge=bool(reasons))
    return Stored(paper_id=paper_id, doi=snapshot.doi, snapshot_id=snapshot_id)


async def _sync_tracked_papers(
    deps: PollDeps, dois: dict[PaperId, Doi]
) -> dict[PaperId, SnapshotId | None]:
    """Mirror Storage Management's papers that have a DOI: add, re-DOI and drop rows.
    Returns each paper's last recorded snapshot id."""
    async with deps.sessions() as session:
        rows: dict[UUID, TrackedPaper] = {row.paper_id: row for row in await session.scalars(select(TrackedPaper))}
        for paper_id, row in rows.items():
            if paper_id not in dois:
                await session.delete(row)
            else:
                row.doi = dois[paper_id]
        session.add_all(
            TrackedPaper(paper_id=paper_id, doi=doi) for paper_id, doi in dois.items() if paper_id not in rows
        )
        await session.commit()
        return {paper_id: rows[paper_id].last_snapshot_id if paper_id in rows else None for paper_id in dois}


async def _record_snapshot(deps: PollDeps, paper_id: PaperId, snapshot_id: SnapshotId, nudge: bool) -> None:
    # one statement, so a snapshot can't be recorded without its flag; a flag left over from
    # an earlier failed nudge is never cleared here
    values = {"last_snapshot_id": snapshot_id, **({"nudge_pending": True} if nudge else {})}
    async with deps.sessions() as session:
        await session.execute(update(TrackedPaper).where(TrackedPaper.paper_id == paper_id).values(**values))
        await session.commit()


async def _send_nudges(deps: PollDeps) -> list[PaperId] | NudgeError:
    """Send every pending paper, including ones left over from a failed earlier nudge."""
    async with deps.sessions() as session:
        pending = [
            PaperId(paper_id)
            for paper_id in await session.scalars(
                select(TrackedPaper.paper_id).where(TrackedPaper.nudge_pending).order_by(TrackedPaper.paper_id)
            )
        ]
    if not pending:
        return []
    try:
        await send_changes(deps.re, pending)
    except httpx.HTTPError as exc:
        reason = _failure_reason(exc)
        log.warning("nudging Research Evaluation about %d paper(s) failed: %s", len(pending), reason)
        return NudgeError(paper_ids=pending, reason=reason)
    async with deps.sessions() as session:
        await session.execute(
            update(TrackedPaper).where(TrackedPaper.paper_id.in_(pending)).values(nudge_pending=False)
        )
        await session.commit()
    return pending


async def _close_run(
    deps: PollDeps, run_id: int, status: RunStatus, summary: PollSummary | None, error: str | None
) -> None:
    async with deps.sessions() as session:
        await session.execute(
            update(PollRun)
            .where(PollRun.id == run_id)
            .values(
                status=status,
                finished_at=datetime.now(UTC),
                summary=summary.model_dump(mode="json") if summary else None,
                error=error,
            )
        )
        await session.commit()
