"""Change evaluation for Updating's nudge (docs/EVALUATION-REVIEW-CHANGES.md, S5).

The conductor: for each paper it reads the newest N snapshots from Storage Management
(N = EVALUATION_SNAPSHOT_WINDOW) and runs stage 1 (changes.py) on every consecutive pair.
It then asks Storage Management which change keys already have an alert, and runs stage 2
(rules.py) and storing only for the new ones, so a change is never evaluated twice, which
matters once the later LLM evaluation (insight) runs on them. It keeps no
state of its own: the window covers changes whose nudge failed on earlier polls (up to
N - 2 failed nudges in a row), and the stored keys say what's already done.

It also hands back, for each paper evaluated without failure, everything detection found and
the paper's DOI, so investigation can run after the reply (investigation/run.py)."""

import logging
from datetime import datetime
from itertools import pairwise
from uuid import UUID

import httpx
from pydantic import BaseModel, ValidationError

from research_evaluation.changes import Change, ChangeType, find_changes
from research_evaluation.investigation.run import PaperChanges
from research_evaluation.rules import Assessment, Severity, assess
from research_evaluation.storage import PaperGone, snapshot_history, store_alert, stored_change_keys

log = logging.getLogger(__name__)


class NewAlert(BaseModel):
    """The body of `POST /internal/papers/{id}/alerts`."""

    change_type: ChangeType
    change_key: str
    severity: Severity
    description: str
    recommendation: str
    notice_doi: str | None
    detected_at: datetime
    snapshot_id: int
    previous_snapshot_id: int


class EvaluationResult(BaseModel):
    alerts_created: int
    skipped_paper_ids: list[UUID]  # SM doesn't know them
    failed_paper_ids: list[UUID]


class Evaluation(BaseModel):
    """The reply to Updating, and what investigation needs afterwards: each paper evaluated
    without failure that had changes in its window."""

    result: EvaluationResult
    to_investigate: list[PaperChanges]


async def evaluate_papers(sm: httpx.AsyncClient, paper_ids: list[UUID], snapshot_window: int) -> Evaluation:
    """eval all papers
    saved failed to reply Updating"""
    created = 0
    skipped: list[UUID] = []
    failed: list[UUID] = []
    to_investigate: list[PaperChanges] = []
    # a repeated id is evaluated once
    for paper_id in dict.fromkeys(paper_ids):
        try:
            new_alerts, changes = await evaluate_paper(sm, paper_id, snapshot_window)
            created += new_alerts
            if changes is not None:
                to_investigate.append(changes)
        except PaperGone:
            log.info("skipping paper %s: Storage Management has no such paper", paper_id)
            skipped.append(paper_id)
        except (httpx.HTTPError, ValueError) as exc:
            log.warning("evaluating paper %s failed: %s", paper_id, _cause(exc))
            failed.append(paper_id)
        except Exception:
            log.exception("evaluating paper %s failed unexpectedly", paper_id)
            failed.append(paper_id)
    return Evaluation(
        result=EvaluationResult(alerts_created=created, skipped_paper_ids=skipped, failed_paper_ids=failed),
        to_investigate=to_investigate,
    )


async def evaluate_paper(
    sm: httpx.AsyncClient, paper_id: UUID, snapshot_window: int
) -> tuple[int, PaperChanges | None]:
    """
    1. detect changes (classified) - compare only N newest snapshots
        a. get all alerts for that paper to see if RE has already evaluated that change
    2. interpret what the classified changes mean
    3. LLM evaluate if the change is actually meaningful and how it impacts user
    
    
    store each new change as an alert in SM
    returns how many alerts were new, and the paper's detected changes (all of them, for
    investigation after the reply), or None when there were none
    """
    history = await snapshot_history(sm, paper_id, last=snapshot_window)
    detected = [
        change
        for previous, current in pairwise(history)
        for change in find_changes(previous, current)
    ]
    # nothing to evaluate, so no need to ask SM what's stored
    if not detected:
        return 0, None
    # get old change keys
    evaluated = await stored_change_keys(sm, paper_id)
    created = 0
    for change in _to_store(detected, evaluated):
        assessment = assess(change)
        if await store_alert(sm, paper_id, _alert(change, assessment)):
            created += 1
    paper_doi = history[-1].doi if history else None
    return created, PaperChanges(paper_id=paper_id, paper_doi=paper_doi, changes=detected)


def _to_store(detected: list[Change], stored_keys: set[str]) -> list[Change]:
    """The changes to assess and store, one per change key: those whose key isn't stored yet
    (stored on an earlier nudge), plus a retraction that has a notice even when `retraction` is
    stored. A retraction's key never changes with a new notice, so this is how a notice that
    arrives after OpenAlex's flag reaches Storage Management, which replaces a notice-less
    retraction alert with it (201) and changes nothing otherwise (200). Within the history, a
    retraction with a notice is used over one without (the earliest such)."""
    chosen: dict[str, Change] = {}
    for change in detected:
        key = change.change_key
        if key in stored_keys and not _brings_retraction_notice(change):
            continue
        current = chosen.get(key)
        if current is None or (_brings_retraction_notice(change) and not _brings_retraction_notice(current)):
            chosen[key] = change
    return list(chosen.values())


def _brings_retraction_notice(change: Change) -> bool:
    return change.change_type is ChangeType.RETRACTION and change.notice_doi is not None


def _alert(change: Change, assessment: Assessment) -> dict:
    """create alert dict for storage.py"""
    return NewAlert(
        change_type=change.change_type,
        change_key=change.change_key,
        severity=assessment.severity,
        description=assessment.description,
        recommendation=assessment.recommendation,
        notice_doi=change.notice_doi,
        detected_at=change.detected_at,
        snapshot_id=change.snapshot_id,
        previous_snapshot_id=change.previous_snapshot_id,
    ).model_dump(mode="json")


def _cause(exc: Exception) -> str:
    """What went wrong, without response bodies or URLs."""
    match exc:
        case httpx.HTTPStatusError():
            return f"Storage Management answered {exc.response.status_code} for {exc.request.url.path}"
        case httpx.TimeoutException():
            return f"Storage Management timed out ({type(exc).__name__})"
        case httpx.HTTPError():
            return f"could not reach Storage Management ({type(exc).__name__})"
        case ValidationError():
            return f"unreadable response from Storage Management ({exc.error_count()} validation errors)"
        case _:
            return f"{type(exc).__name__}: {exc}"
