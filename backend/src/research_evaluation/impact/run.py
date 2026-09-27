"""Impact over a list of report ids: for each, read the report, skip it unless it's
`investigated`, gather its inputs, assess it with the model, and store the evaluation in Storage
Management, which marks it `assessed` (docs/EVALUATION-IMPACT.md).

It runs in the background, after investigation, so it must never raise. A report that fails is
logged, stays `investigated` and nothing is stored for it; nothing retries it (a later sprint)."""

import logging
from dataclasses import dataclass

import httpx
from google.genai import errors as genai_errors
from pydantic import ValidationError

from research_evaluation.impact.assess import assess
from research_evaluation.impact.inputs import gather_inputs
from research_evaluation.impact.llm import Llm
from research_evaluation.storage import PaperGone, ReportGone, read_report, store_evaluation

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ImpactContext:
    """What impact runs with besides the Storage Management client: the model."""

    llm: Llm


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
        inputs = await gather_inputs(sm, report)
        evaluation = await assess(context.llm, sm, inputs)
        await store_evaluation(sm, report_id, evaluation.model_dump(mode="json"))
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
    return True


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
