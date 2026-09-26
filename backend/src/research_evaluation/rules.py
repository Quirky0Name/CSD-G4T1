"""Stage 2 of change evaluation: the rule-based assessment (docs/EVALUATION-REVIEW-CHANGES.md, S4).

Gives every change from stage 1 a severity, a description and a recommendation from
fixed rules, so every alert always has a complete assessment. Later stages (LLM
investigation, meaningfulness, stance checks) revise this one instead of starting from
nothing. Pure: no HTTP, no LLM."""

from enum import StrEnum

from pydantic import BaseModel, ConfigDict

from research_evaluation.changes import Change, ChangeType


class Severity(StrEnum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class Assessment(BaseModel):
    model_config = ConfigDict(frozen=True)

    severity: Severity
    description: str
    recommendation: str


SEVERITY = {
    ChangeType.RETRACTION: Severity.HIGH,
    ChangeType.EXPRESSION_OF_CONCERN: Severity.MEDIUM,
    ChangeType.CORRECTION: Severity.MEDIUM,
    ChangeType.ERRATUM: Severity.LOW,
    ChangeType.DOAJ_DELISTING: Severity.LOW,
    # unknown kinds of notice get attention rather than being overlooked
    ChangeType.OTHER: Severity.MEDIUM,
}


def assess(change: Change) -> Assessment:
    notice = _notice(change)
    match change.change_type:
        case ChangeType.RETRACTION:
            description = (
                f"This paper has been retracted{notice}. Its findings should no longer be relied on."
            )
            recommendation = (
                "Stop citing it as supporting evidence and check any of your work that builds on "
                "its findings. Read the retraction notice to see why it was retracted."
            )
        case ChangeType.EXPRESSION_OF_CONCERN:
            description = (
                f"The publisher issued an expression of concern about this paper{notice}. Its "
                "reliability is in question, and it may later be corrected or retracted."
            )
            recommendation = (
                "Treat its findings with caution until the concern is resolved, and read the "
                "notice to see which parts are in question."
            )
        case ChangeType.CORRECTION:
            description = (
                f"A correction to this paper was published{notice}. Part of the paper was changed "
                "after publication."
            )
            recommendation = (
                "Read the correction to see whether it changes anything you rely on, and cite the "
                "corrected version."
            )
        case ChangeType.ERRATUM:
            description = (
                f"An erratum for this paper was published{notice}, fixing an error in the "
                "published version."
            )
            recommendation = "Check the erratum to confirm it doesn't affect the parts of the paper you rely on."
        case ChangeType.DOAJ_DELISTING:
            journal = change.journal or "The journal this paper was published in"
            description = (
                f"{journal} was removed from the Directory of Open Access Journals (DOAJ). DOAJ "
                "removes journals that no longer meet its standards, so the journal's quality "
                "control may be weaker than it was."
            )
            recommendation = "Look up why the journal was delisted, and weigh this paper's findings with more care."
        case ChangeType.OTHER:
            kind = change.notice_label or change.notice_type or "an unrecognised"
            description = (
                f"Crossref recorded a '{kind}' notice on this paper{notice}. It isn't a type of "
                "change we assess yet."
            )
            recommendation = "Read the notice to see what changed and whether it affects how you use this paper."
    return Assessment(severity=SEVERITY[change.change_type], description=description, recommendation=recommendation)


def _notice(change: Change) -> str:
    """` (notice 10.x/y, 2020-07-01)`, or whatever part of it is known, or nothing."""
    parts = [f"notice {change.notice_doi}" if change.notice_doi else None, change.notice_date]
    known = [part for part in parts if part]
    return f" ({', '.join(known)})" if known else ""
