"""LLM investigation"""

from dataclasses import dataclass
from uuid import UUID

from research_evaluation.changes import Change, Snapshot
from research_evaluation.rules import Assessment


@dataclass(frozen=True)
class Context:
    """What stage 3 knows about a change: the paper and the two snapshots it came from."""

    paper_id: UUID
    previous: Snapshot
    current: Snapshot


def investigate(change: Change, context: Context, assessment: Assessment) -> Assessment:
    return assessment
