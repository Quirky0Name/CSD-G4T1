"""The paper's past for impact (docs/EVALUATION.md, "Impact"): before step 1, the current report's
alerts and notices are embedded as one query, and the paper's stored chunks nearest to it are
searched, one search per source type. Steps 1 and 2 get what is found as their PAST TIMELINE.

"Past" is every chunk of the same tracked paper: a report's chunks are only stored once impact has
assessed it (research_evaluation.chunks), so the report being assessed is never among them. Ranking
is by similarity only. Reading the past never fails the report: `failed` is recorded instead.

With the same query, one more search finds the researcher's nearest past feedback, across every
paper they track (this one included). It is kept apart from the past: steps 2 and 3 get it, never
step 1, and its own status is recorded. A note's text is never logged."""

import logging
import re
from dataclasses import dataclass, field

import httpx

from research_evaluation.embedding import Embedder
from research_evaluation.storage import Chunk, Report, search_chunks

log = logging.getLogger(__name__)

# how many of each source type one search returns, at most
LIMITS = {"alert": 3, "report": 3, "document": 5}
# how many of the researcher's feedback chunks one search returns, at most
FEEDBACK_LIMIT = 3
# the most characters of the query that is embedded
MAX_QUERY_CHARS = 8000

VERDICTS = ("fair", "overstated", "understated")
# a feedback chunk's labels: research_evaluation.chunks.feedback_piece writes them, past_feedback reads
FEEDBACK_HEAD = "feedback on report of"
VERDICT_LINE = "researcher's verdict: "
NOTE_LINE = "researcher's note: "
UNKNOWN = "unknown"
_REPORT_DATE = re.compile(rf"^{re.escape(FEEDBACK_HEAD)} (\d{{4}}-\d{{2}}-\d{{2}}|{UNKNOWN}):")


@dataclass(frozen=True)
class PastFeedback:
    """A feedback chunk found for this report, with the report date and verdict read back from its
    content (research_evaluation.chunks.feedback_piece writes them)."""

    chunk: Chunk
    report_date: str
    verdict: str


def past_feedback(chunk: Chunk) -> PastFeedback:
    """Reads the date and verdict out of a feedback chunk; `unknown` for one that doesn't parse.
    The verdict is the last verdict line before the note, so a note can't put one of its own."""
    lines = chunk.content.splitlines()
    date = _REPORT_DATE.match(lines[0]) if lines else None
    before_note = next((i for i, line in enumerate(lines) if line.startswith(NOTE_LINE)), len(lines))
    verdicts = [
        line.removeprefix(VERDICT_LINE) for line in lines[:before_note] if line.startswith(VERDICT_LINE)
    ]
    verdict = verdicts[-1] if verdicts and verdicts[-1] in VERDICTS else UNKNOWN
    return PastFeedback(chunk, date.group(1) if date else UNKNOWN, verdict)


@dataclass(frozen=True)
class History:
    """`status` is `read` (chunks found), `none` (nothing found, or no embedder) or `failed`; the
    chunks are nearest first across the three searches. `feedback_status` and `feedback` are the
    same for the researcher's past feedback, nearest first."""

    status: str
    chunks: list[Chunk] = field(default_factory=list)
    feedback_status: str = "none"
    feedback: list[PastFeedback] = field(default_factory=list)


def query_text(report: Report) -> str:
    """The report's alerts, one line each (change type, notice DOI, description), then each
    notice's text, cut to MAX_QUERY_CHARS in all."""
    lines = [
        f"{alert.change_type} {alert.notice_doi or 'no notice'} {alert.description}"
        for alert in report.alerts
    ]
    lines += [
        document.text
        for document in report.documents
        if document.kind == "notice" and document.text
    ]
    return "\n".join(lines)[:MAX_QUERY_CHARS]


async def read_history(
    sm: httpx.AsyncClient, embedder: Embedder | None, report: Report
) -> History:
    """The paper's past and the researcher's feedback nearest to this report. Never raises."""
    text = query_text(report)
    # no embedder, or nothing to search with (no alerts and no notice text): nothing is found
    if embedder is None or not text.strip():
        return History("none")
    # a failed read never fails the report; only the class is logged, since the search's or
    # embedding's error could carry the query's text
    try:
        [query] = await embedder.embed([text], "query")
    except Exception as exc:  # noqa: BLE001
        log.warning("reading the past for report %s failed: %s", report.id, type(exc).__name__)
        return History("failed", feedback_status="failed")
    try:
        found = [
            chunk
            for source_type, limit in LIMITS.items()
            for chunk in await search_chunks(sm, report.paper_id, source_type, query, limit)
        ]
        status = "read" if found else "none"
    except Exception as exc:  # noqa: BLE001
        log.warning("reading the past for report %s failed: %s", report.id, type(exc).__name__)
        found, status = [], "failed"
    feedback_status, feedback = await _read_feedback(sm, report, query)
    return History(
        status,
        sorted(found, key=lambda chunk: chunk.score, reverse=True),
        feedback_status,
        feedback,
    )


async def _read_feedback(
    sm: httpx.AsyncClient, report: Report, query: list[float]
) -> tuple[str, list[PastFeedback]]:
    """The owner's feedback chunks nearest to the query, nearest first. One with no date is left
    out: its feedback was deleted and the chunk not yet (Storage Management sends it undated)."""
    try:
        found = await search_chunks(sm, report.paper_id, "feedback", query, FEEDBACK_LIMIT)
    except Exception as exc:  # noqa: BLE001
        log.warning("reading the feedback for report %s failed: %s", report.id, type(exc).__name__)
        return "failed", []
    feedback = [past_feedback(chunk) for chunk in found if chunk.source_date is not None]
    return ("read" if feedback else "none"), feedback
