"""The shapes of impact's three answers (Gemini's structured output) and of what it sends to
Storage Management. The field names are the ones the prompts ask for (impact/prompts.py)."""

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field

Level = Literal["none", "low", "medium", "high"]
Role = Literal[
    "methods_or_data", "key_evidence", "supporting", "comparison", "background", "critical"
]

# every text of the evaluation stored when every Gemini model failed a step
# (docs/EVALUATION.md, "Impact"; the story owner's wording)
PLACEHOLDER_TEXT = "FUCK U GEMINI FLASH. WHO TF IS EVEN USING GEMINI FLASH"


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


class Claim(_Answer):
    """One factual statement of a step's answer and the quotes behind it (docs/EVALUATION.md,
    "Impact"). A claim is about anything the step says, not tied to an alert, use or action;
    a statement with nothing to quote has no passages. `absence` is the model saying the claim
    states only that something is missing from the material, so it has nothing to quote.
    `from_claims` makes it a derived claim: a conclusion (a level, "this use is touched") drawn
    from the claims it names, numbered from 1 in the same step's list, rather than quoted."""

    text: str
    passages: list[Evidence]
    absence: bool = False
    from_claims: list[int] = []


# every step states at least one thing, so an empty list is an answer that doesn't fit
Claims = Annotated[list[Claim], Field(min_length=1)]


class Sentence(_Answer):
    """One sentence of a step's free text. A fact names the claims (numbered from 1 in the same
    step's list) that state it; advice states nothing a quote could show, so it needs none."""

    text: str
    kind: Literal["fact", "advice"]
    claims: list[int]


Sentences = Annotated[list[Sentence], Field(min_length=1)]


def kept(sentences: list[Sentence], claims: list[Claim]) -> list[Sentence]:
    """The sentences the stored text keeps, in order, leaving out a fact that names no existing
    claim, since nothing backs it. If that would leave nothing, every sentence is kept."""
    backed = [
        s for s in sentences
        if s.kind == "advice" or any(1 <= n <= len(claims) for n in s.claims)
    ]
    return backed or sentences


def prose(sentences: list[Sentence], claims: list[Claim]) -> str:
    """The free text as stored: the kept sentences joined."""
    return " ".join(s.text.strip() for s in kept(sentences, claims))


class AlertJudgment(_Answer):
    alert_id: int
    about_this_paper: Literal["yes", "no", "unclear"]
    what_changed: str
    scope: Literal["whole_paper", "specific_parts", "administrative", "journal_only", "citations_only", "unclear"]
    affected_parts: list[AffectedPart]
    severity: Level
    reason: str
    evidence: list[Evidence]


class ChangeAssessment(_Answer):
    alerts: list[AlertJudgment]
    relations: str | None
    severity: Level
    # claims before the text, so the model writes what it can back before it writes prose
    claims: Claims
    summary: Sentences


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
    claims: Claims
    explanation: Sentences


# Step 3: what should the researcher do?


class Action(_Answer):
    action: str
    where: str | None


class RecommendedActions(_Answer):
    actions: list[Action]
    claims: Claims
    recommendation: Sentences


# What the evaluation carries: each step's claims, each quote checked in code (impact/claims.py;
# the `Claim` of docs/CONTRACTS.md). Model output and fetched text: shown as text, never as HTML.


Target = Literal["paper", "draft", "document", "alert"]
Verified = Literal["verbatim", "not_found", "no_text"]
ClaimKind = Literal["change", "impact", "action"]


class Passage(BaseModel):
    # where the quote was found (or the source it names); None when found nowhere and naming none
    target: Target | None
    document_id: int | None
    alert_id: int | None
    quote: str
    # 1-based page the quote starts on, when found in a PDF
    page: int | None
    verified: Verified


class StoredClaim(BaseModel):
    id: str  # "<kind>-<index in that step's claims>"
    kind: ClaimKind
    text: str
    supported: bool  # at least one passage is verbatim
    passages: list[Passage]


class ContextChunk(BaseModel):
    """A past chunk added to the prompt: enough to find it again (a document's PDF at `page`, or
    the alert or report in the paper's report list)."""

    source_type: str
    source_id: str
    page: int | None = None


class Evaluation(BaseModel):
    """The body of `PUT /internal/reports/{id}/evaluation`. With change_severity none only the
    summary and change severity are set (and assessment); the rest stays null."""

    change_summary: str
    change_severity: Level
    impact_level: Level | None = None
    evaluation: str | None = None
    recommendation: str | None = None
    assessment: dict[str, Any]
    # every finished step's claims, quotes checked (impact/claims.py); null on reports assessed
    # before claims existed
    claims: list[StoredClaim] | None = None
    # the past chunks steps 1 and 2 were given, in prompt order, so a frontend can show them (I5)
    context_chunks: list[ContextChunk] = []
