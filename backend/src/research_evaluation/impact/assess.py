"""Impact's assessment of one report, in three steps (docs/EVALUATION.md, "Impact"):

1. what changed, and how severe is it? At change severity none (not meaningful) it stops:
   only the summary and change severity are kept, and the draft is never read;
2. how does it impact the researcher? The draft and the researcher's profile are read now, and the
   model works out how it uses the paper and whether the change affects each use;
3. what should the researcher do?

The profile tailors the text of steps 2 and 3, never a level; step 1 never gets it. Reading it
can't fail the report: `assessment["profile"]` records what happened, never the profile's values.
The researcher's past feedback (read with the past, impact.history) goes to steps 2 and 3 the same
way, after the profile: `assessment["feedback"]` records how reading it went, never a note.

It returns the body for `PUT /internal/reports/{id}/evaluation`; storing it is the caller's job.
A model answer that doesn't fit its schema raises, and nothing is returned for the report
(with FallbackLlm, the next model is tried instead, down to the placeholder below).

When every model the Llm falls back through fails one of the steps (AllModelsFailed), it returns
the placeholder evaluation instead (docs/EVALUATION.md, "Impact"): change severity and impact level low, every
text the placeholder message, and what did happen in `assessment`."""

import logging
from typing import Any

import httpx

from research_evaluation.impact.inputs import ReportInputs
from research_evaluation.impact.llm import AllModelsFailed, Llm
from research_evaluation.impact.prompts import (
    PROMPT_VERSION,
    SYSTEM,
    actions_prompt,
    change_prompt,
    impact_prompt,
    pdfs_record,
)
from research_evaluation.impact.schemas import (
    PLACEHOLDER_TEXT,
    ChangeAssessment,
    ContextChunk,
    Evaluation,
    FeedbackContextChunk,
    ImpactAssessment,
    RecommendedActions,
)
from research_evaluation.storage import Report, draft_pdf
from research_evaluation.users import ResearcherProfile, researcher_profile

log = logging.getLogger(__name__)


async def assess(
    llm: Llm,
    sm: httpx.AsyncClient,
    inputs: ReportInputs,
    um: httpx.AsyncClient | None = None,
    history: str | None = None,
    feedback: str | None = None,
) -> Evaluation:
    """`history` and `feedback` are how reading the paper's past and the researcher's feedback
    went (impact.history: read, none or failed); None works each out from `inputs`, as the evals
    do."""
    prompt = change_prompt(inputs)
    # the past chunks steps 1 and 2 get, in prompt order, for the frontend to show (I5); the
    # feedback steps 2 and 3 get is added once step 2 is reached
    context: list[ContextChunk | FeedbackContextChunk] = [
        ContextChunk(source_type=c.source_type, source_id=c.source_id, page=c.page)
        for c in inputs.chunks
    ]
    assessment: dict[str, Any] = {
        "prompt_version": PROMPT_VERSION,
        "model": llm.model,
        "model_version": None,
        "answered_by": {"change": None, "impact": None, "actions": None},
        "placeholder": False,
        "draft": "not_needed",
        "profile": "not_needed",
        "history": history or ("read" if inputs.chunks else "none"),
        "feedback": feedback or ("read" if inputs.feedback else "none"),
        "pdfs": pdfs_record(prompt),
        "change": None,
        "impact": None,
        "actions": None,
    }
    step = "change"
    try:
        change = await llm.generate(system=SYSTEM, parts=prompt.parts, schema=ChangeAssessment)
        assessment["model_version"] = change.model_version
        assessment["answered_by"]["change"] = change.model
        assessment["change"] = change.value.model_dump(mode="json")
        if change.value.severity == "none":
            return Evaluation(
                change_summary=change.value.summary,
                change_severity="none",
                assessment=assessment,
                context_chunks=context,
            )

        # only now: a change that isn't meaningful never touches the researcher's draft
        draft = await draft_pdf(sm, inputs.report.paper_id)
        assessment["draft"] = "read" if draft is not None else "none"
        assessment["profile"], profile = await _read_profile(um, inputs.report)
        step = "impact"
        context += [
            FeedbackContextChunk(
                source_id=item.chunk.source_id,
                paper_id=item.chunk.paper_id,
                paper_title=item.chunk.paper_title,
                source_date=item.chunk.source_date,
                verdict=item.verdict,
            )
            for item in inputs.feedback
        ]
        impact = await llm.generate(
            system=SYSTEM,
            parts=impact_prompt(
                inputs.paper, change.value, draft, profile, inputs.chunks, inputs.feedback
            ),
            schema=ImpactAssessment,
        )
        assessment["answered_by"]["impact"] = impact.model
        assessment["impact"] = impact.value.model_dump(mode="json")
        step = "actions"
        actions = await llm.generate(
            system=SYSTEM,
            parts=actions_prompt(
                inputs.paper, change.value, impact.value, draft is not None, profile, inputs.feedback
            ),
            schema=RecommendedActions,
        )
        assessment["answered_by"]["actions"] = actions.model
        assessment["actions"] = actions.value.model_dump(mode="json")
    except AllModelsFailed as exc:
        return _placeholder(assessment, step, exc, context)
    return Evaluation(
        change_summary=change.value.summary,
        change_severity=change.value.severity,
        impact_level=impact.value.impact_level,
        evaluation=impact.value.explanation,
        recommendation=actions.value.recommendation,
        assessment=assessment,
        context_chunks=context,
    )


async def _read_profile(
    um: httpx.AsyncClient | None, report: Report
) -> tuple[str, ResearcherProfile | None]:
    """The profile's status for `assessment` and the profile itself when there is one. `um` is
    checked first, so the evals (which pass none) record `none`. Any failure is `failed` and the
    report goes on without it; only the report id and the exception class are logged, never a
    response body or a profile value."""
    if um is None:
        return "none", None
    try:
        if report.owner_id is None:
            raise ValueError("the report has no owner_id")
        profile = await researcher_profile(um, report.owner_id)
    # a failed read never fails the report; no exc_info, since a traceback or a ValidationError's
    # message can carry the response body and so the profile's values
    except Exception as exc:  # noqa: BLE001
        log.warning("reading the profile for report %s failed: %s", report.id, type(exc).__name__)
        return "failed", None
    return ("read", profile) if profile is not None else ("none", None)


def _placeholder(
    assessment: dict[str, Any], step: str, exc: AllModelsFailed, context: list[ContextChunk]
) -> Evaluation:
    """Every model failed `step`: the placeholder, with the steps that did finish kept."""
    assessment["placeholder"] = True
    assessment["failed_step"] = step
    assessment["failures"] = [
        {"model": failure.model, "cause": failure.cause} for failure in exc.failures
    ]
    return Evaluation(
        change_summary=PLACEHOLDER_TEXT,
        change_severity="low",
        impact_level="low",
        evaluation=PLACEHOLDER_TEXT,
        recommendation=PLACEHOLDER_TEXT,
        assessment=assessment,
        context_chunks=context,
    )
