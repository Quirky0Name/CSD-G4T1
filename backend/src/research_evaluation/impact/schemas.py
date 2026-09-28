"""The shapes of impact's three answers (Gemini's structured output) and of what it sends to
Storage Management. The field names are the ones the prompts ask for (impact/prompts.py)."""

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

Level = Literal["none", "low", "medium", "high"]
Role = Literal[
    "methods_or_data", "key_evidence", "supporting", "comparison", "background", "critical"
]


class _Answer(BaseModel):
    # the model's extra keys are dropped rather than failing a whole report
    model_config = ConfigDict(extra="ignore")


# Step 1: what changed, and how severe is it?


class AffectedPart(_Answer):
    part: str
    findings_affected: str


class Evidence(_Answer):
    source: str
    quote: str


class AlertJudgment(_Answer):
    alert_id: int
    about_this_paper: Literal["yes", "no", "unclear"]
    what_changed: str
    scope: Literal["whole_paper", "specific_parts", "administrative", "journal_only", "unclear"]
    affected_parts: list[AffectedPart]
    severity: Level
    reason: str
    evidence: list[Evidence]


class ChangeAssessment(_Answer):
    alerts: list[AlertJudgment]
    relations: str | None
    severity: Level
    summary: str


# Step 2: how does it impact the researcher?


class Use(_Answer):
    section: str
    quote: str
    role: Role
    claim_relied_on: str
    affected: Literal["yes", "no", "unclear"]
    reason: str


class ImpactAssessment(_Answer):
    cited: bool
    reference_entry: str | None
    uses: list[Use]
    impact_level: Level
    explanation: str


# Step 3: what should the researcher do?


class Action(_Answer):
    action: str
    where: str | None


class RecommendedActions(_Answer):
    actions: list[Action]
    recommendation: str


class Evaluation(BaseModel):
    """The body of `PUT /internal/reports/{id}/evaluation`. With change_severity none only the
    summary and severity are set (and assessment); the rest stays null."""

    change_summary: str
    change_severity: Level
    impact_level: Level | None = None
    evaluation: str | None = None
    recommendation: str | None = None
    assessment: dict[str, Any]
