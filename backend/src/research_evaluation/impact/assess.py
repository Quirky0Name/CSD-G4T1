"""Impact's assessment of one report, in three steps (docs/EVALUATION-IMPACT.md):

1. what changed, and how severe is it? At severity none (not meaningful) it stops: only the
   summary and severity are kept, and the draft is never read;
2. how does it impact the researcher? The draft is read now, and the model works out how it uses
   the paper and whether the change affects each use;
3. what should the researcher do?

It returns the body for `PUT /internal/reports/{id}/evaluation`; storing it is the caller's job.
A model answer that doesn't fit its schema raises, and nothing is returned for the report."""

from typing import Any

import httpx

from research_evaluation.impact.inputs import ReportInputs
from research_evaluation.impact.llm import Llm
from research_evaluation.impact.prompts import (
    PROMPT_VERSION,
    SYSTEM,
    actions_prompt,
    change_prompt,
    impact_prompt,
    pdfs_record,
)
from research_evaluation.impact.schemas import (
    ChangeAssessment,
    Evaluation,
    ImpactAssessment,
    RecommendedActions,
)
from research_evaluation.storage import draft_pdf


async def assess(llm: Llm, sm: httpx.AsyncClient, inputs: ReportInputs) -> Evaluation:
    prompt = change_prompt(inputs)
    change = await llm.generate(system=SYSTEM, parts=prompt.parts, schema=ChangeAssessment)
    assessment: dict[str, Any] = {
        "prompt_version": PROMPT_VERSION,
        "model": llm.model,
        "model_version": change.model_version,
        "draft": "not_needed",
        "pdfs": pdfs_record(prompt),
        "change": change.value.model_dump(mode="json"),
        "impact": None,
        "actions": None,
    }
    if change.value.severity == "none":
        return Evaluation(
            change_summary=change.value.summary, change_severity="none", assessment=assessment
        )

    # only now: a change that isn't meaningful never touches the researcher's draft
    draft = await draft_pdf(sm, inputs.report.paper_id)
    assessment["draft"] = "read" if draft is not None else "none"
    impact = await llm.generate(
        system=SYSTEM,
        parts=impact_prompt(inputs.paper, change.value, draft),
        schema=ImpactAssessment,
    )
    actions = await llm.generate(
        system=SYSTEM,
        parts=actions_prompt(inputs.paper, change.value, impact.value, draft is not None),
        schema=RecommendedActions,
    )
    assessment["impact"] = impact.value.model_dump(mode="json")
    assessment["actions"] = actions.value.model_dump(mode="json")
    return Evaluation(
        change_summary=change.value.summary,
        change_severity=change.value.severity,
        impact_level=impact.value.impact_level,
        evaluation=impact.value.explanation,
        recommendation=actions.value.recommendation,
        assessment=assessment,
    )
