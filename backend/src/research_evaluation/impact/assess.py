"""Impact's assessment of one report, in three steps (docs/EVALUATION-IMPACT.md):

1. what changed, and how severe is it? At severity none (not meaningful) it stops: only the
   summary and severity are kept, and the draft is never read;
2. how does it impact the researcher? The draft is read now, and the model works out how it uses
   the paper and whether the change affects each use;
3. what should the researcher do?

It returns the body for `PUT /internal/reports/{id}/evaluation`; storing it is the caller's job.
A model answer that doesn't fit its schema raises, and nothing is returned for the report
(with FallbackLlm, the next model is tried instead, down to the placeholder below).

When every model the Llm falls back through fails one of the steps (AllModelsFailed), it returns
the placeholder evaluation instead (docs/EVAL-GEM-FAILSAFE.md): severity and impact low, every
text the placeholder message, and what did happen in `assessment`."""

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
    Evaluation,
    ImpactAssessment,
    RecommendedActions,
)
from research_evaluation.storage import draft_pdf


async def assess(llm: Llm, sm: httpx.AsyncClient, inputs: ReportInputs) -> Evaluation:
    prompt = change_prompt(inputs)
    assessment: dict[str, Any] = {
        "prompt_version": PROMPT_VERSION,
        "model": llm.model,
        "model_version": None,
        "answered_by": {"change": None, "impact": None, "actions": None},
        "placeholder": False,
        "draft": "not_needed",
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
                change_summary=change.value.summary, change_severity="none", assessment=assessment
            )

        # only now: a change that isn't meaningful never touches the researcher's draft
        draft = await draft_pdf(sm, inputs.report.paper_id)
        assessment["draft"] = "read" if draft is not None else "none"
        step = "impact"
        impact = await llm.generate(
            system=SYSTEM,
            parts=impact_prompt(inputs.paper, change.value, draft),
            schema=ImpactAssessment,
        )
        assessment["answered_by"]["impact"] = impact.model
        assessment["impact"] = impact.value.model_dump(mode="json")
        step = "actions"
        actions = await llm.generate(
            system=SYSTEM,
            parts=actions_prompt(inputs.paper, change.value, impact.value, draft is not None),
            schema=RecommendedActions,
        )
        assessment["answered_by"]["actions"] = actions.model
        assessment["actions"] = actions.value.model_dump(mode="json")
    except AllModelsFailed as exc:
        return _placeholder(assessment, step, exc)
    return Evaluation(
        change_summary=change.value.summary,
        change_severity=change.value.severity,
        impact_level=impact.value.impact_level,
        evaluation=impact.value.explanation,
        recommendation=actions.value.recommendation,
        assessment=assessment,
    )


def _placeholder(assessment: dict[str, Any], step: str, exc: AllModelsFailed) -> Evaluation:
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
    )
