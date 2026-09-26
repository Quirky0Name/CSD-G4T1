"""Change evaluation for Updating's nudge (docs/EVALUATION-REVIEW-CHANGES.md, S5).

The conductor: for each paper it reads the newest N snapshots from Storage Management
(N = EVALUATION_SNAPSHOT_WINDOW) and runs stage 1 (changes.py) on every consecutive pair.
It then asks Storage Management which change keys already have an alert, and runs stage 2
(rules.py), stage 3 (llm.py, for `other` changes) and storing only for the new ones, so a
change is never evaluated twice, which matters once stage 3 calls an LLM. It keeps no
state of its own: the window covers changes whose nudge failed on earlier polls (up to
N - 2 failed nudges in a row), and the stored keys say what's already done."""

import logging
from datetime import datetime
from itertools import pairwise
from uuid import UUID

import httpx
from pydantic import BaseModel, ValidationError

from research_evaluation import llm
from research_evaluation.changes import Change, ChangeType, find_changes
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


async def evaluate_papers(sm: httpx.AsyncClient, paper_ids: list[UUID], snapshot_window: int) -> EvaluationResult:
    """eval all papers
    saved failed to reply Updating"""
    created = 0
    skipped: list[UUID] = []
    failed: list[UUID] = []
    # a repeated id is evaluated once
    for paper_id in dict.fromkeys(paper_ids):  
        try:
            created += await evaluate_paper(sm, paper_id, snapshot_window)
        except PaperGone:
            log.info("skipping paper %s: Storage Management has no such paper", paper_id)
            skipped.append(paper_id)
        except (httpx.HTTPError, ValueError) as exc:
            log.warning("evaluating paper %s failed: %s", paper_id, _cause(exc))
            failed.append(paper_id)
        except Exception:
            log.exception("evaluating paper %s failed unexpectedly", paper_id)
            failed.append(paper_id)
    return EvaluationResult(alerts_created=created, skipped_paper_ids=skipped, failed_paper_ids=failed)


async def evaluate_paper(sm: httpx.AsyncClient, paper_id: UUID, snapshot_window: int) -> int:
    """
    1. detect changes (classified) - compare only N newest snapshots
        a. get all alerts for that paper to see if RE has already evaluated that change
    2. interpret what the classified changes mean
        investigate changes unable to be classified (LLM -> stochastic)
    3. LLM evaluate if the change is actually meaningful and how it impacts user
    
    
    store each new change as an alert in SM
    returns how many alerts were new
    """
    detected = [
        (llm.Context(paper_id=paper_id, previous=previous, current=current), change)
        for previous, current in pairwise(await snapshot_history(sm, paper_id, last=snapshot_window))
        for change in find_changes(previous, current)
    ]
    # nothing to evaluate, so no need to ask SM what's stored
    if not detected:
        return 0  
    # get old change keys
    evaluated = await stored_change_keys(sm, paper_id)
    created = 0
    for context, change in detected:
        # stored on an earlier nudge or already handled in this history 
        if change.change_key in evaluated:
            continue
        evaluated.add(change.change_key)
        assessment = assess(change)
        if change.change_type is ChangeType.OTHER:
            assessment = llm.investigate(change, context, assessment)
        if await store_alert(sm, paper_id, _alert(change, assessment)):
            created += 1
    return created


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
