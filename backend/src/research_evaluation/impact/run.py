"""Impact over a list of report ids: for each, read the report, skip it unless it's
`investigated`, read the paper's past (impact.history) and gather its inputs, assess it with the
model, and store the evaluation in Storage
Management, which marks it `assessed` (docs/EVALUATION.md, "Impact"), then store the chunks of the
report, its alerts and its documents for later reports to search (research_evaluation.chunks).

It runs in the background, after investigation, so it must never raise. A report that fails is
logged, stays `investigated` and nothing is stored for it; nothing retries it (a later sprint)."""

import logging
from dataclasses import dataclass, replace
from typing import Any

import httpx
from google.genai import errors as genai_errors
from pydantic import ValidationError

from research_evaluation.chunks import store_report_chunks
from research_evaluation.embedding import Embedder
from research_evaluation.impact.assess import assess
from research_evaluation.impact.history import read_history
from research_evaluation.impact.inputs import gather_inputs
from research_evaluation.impact.llm import Llm
from research_evaluation.storage import (
    PaperGone,
    Report,
    ReportGone,
    paper_details,
    read_report,
    store_evaluation,
)
from research_evaluation.users import notify_quietly, paper_body

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ImpactContext:
    """What impact runs with besides the Storage Management client: the model, the User
    Management client the researcher's profile is read with (None reads no profile, as in the
    evals), and the embedder the paper's past is searched and the report's chunks are stored with
    (None does neither)."""

    llm: Llm
    um: httpx.AsyncClient | None = None
    embedder: Embedder | None = None


async def assess_reports(
    sm: httpx.AsyncClient, context: ImpactContext, report_ids: list[int]
) -> list[int]:
    """Assesses each report in turn and returns the ids assessed in this run. Never raises."""
    assessed = []
    for report_id in report_ids:
        if await assess_report(sm, context, report_id):
            assessed.append(report_id)
    log.info("impact assessed reports %s of %s", assessed, report_ids)
    return assessed


async def assess_report(sm: httpx.AsyncClient, context: ImpactContext, report_id: int) -> bool:
    """True once the report's evaluation is stored; False when it was skipped or failed."""
    try:
        report = await read_report(sm, report_id)
        if report.status != "investigated":
            log.info("impact skipped report %s: it is %s", report_id, report.status)
            return False
        # the paper's past, read before step 1 and given to steps 1 and 2, and the researcher's
        # feedback, given to steps 2 and 3; never raises
        history = await read_history(sm, context.embedder, report)
        inputs = replace(
            await gather_inputs(sm, report), chunks=history.chunks, feedback=history.feedback
        )
        evaluation = await assess(
            context.llm,
            sm,
            inputs,
            um=context.um,
            history=history.status,
            feedback=history.feedback_status,
        )
        stored = await store_evaluation(sm, report_id, evaluation.model_dump(mode="json"))
    except (PaperGone, ReportGone):
        log.info("impact skipped report %s: Storage Management has no such report or paper", report_id)
        return False
    except Exception as exc:  # a background task: nothing may escape it
        log.warning(
            "assessing report %s failed: %s",
            report_id,
            _cause(exc),
            exc_info=not isinstance(
                exc, httpx.HTTPError | ValidationError | ValueError | genai_errors.APIError
            ),
        )
        return False
    await notify_assessed(sm, context, report, stored)
    # only once the report is assessed, so the researcher sees it first and impact never
    # retrieves the report it is assessing; inline, so the next report waits. Never raises
    if context.embedder is not None:
        await store_report_chunks(sm, context.embedder, inputs, evaluation)
    return True


async def notify_assessed(
    sm: httpx.AsyncClient, context: ImpactContext, report: Report, stored: dict[str, Any]
) -> None:
    """Posts the evaluation just stored to the report's owner; User Management decides how to
    tell them. A placeholder has no real content to send, so it isn't posted."""
    if context.um is None:
        return
    if report.owner_id is None:
        log.info("report %s has no owner: its evaluation is not posted", report.id)
        return
    try:
        if stored["assessment"].get("placeholder"):
            return
        details = await paper_details(sm, report.paper_id)
        body = {
            "event": "report_assessed",
            "paper": paper_body(report.paper_id, details),
            "report_id": report.id,
            "change_severity": stored["change_severity"],
            "change_summary": stored["change_summary"],
            "impact_level": None if stored["impact_level"] == "none" else stored["impact_level"],
            "evaluation": stored["evaluation"],
            "recommendation": stored["recommendation"],
        }
    except Exception as exc:  # noqa: BLE001 the report is assessed whatever happens to its notification
        log.warning("reading report %s for its notification failed: %s", report.id, _cause(exc))
        return
    await notify_quietly(context.um, report.owner_id, report.id, body)


def _cause(exc: Exception) -> str:
    """What went wrong, without response bodies, prompts or the draft's text."""
    match exc:
        case httpx.HTTPStatusError():
            return (
                f"Storage Management answered {exc.response.status_code} "
                f"for {exc.request.url.path}"
            )
        case genai_errors.APIError():
            return f"Gemini answered {exc.code} ({exc.status})"
        case httpx.TimeoutException():
            return f"a request timed out ({type(exc).__name__})"
        case httpx.HTTPError():
            return f"a request failed ({type(exc).__name__})"
        case ValidationError():
            return f"an answer didn't fit its schema ({exc.error_count()} errors)"
        case _:
            return f"{type(exc).__name__}: {exc}"
