"""The paper's past for impact (docs/EVALUATION.md, "Impact"): before step 1, the current report's
alerts and notices are embedded as one query, and the paper's stored chunks nearest to it are
searched, one search per source type. Steps 1 and 2 get what is found as their PAST TIMELINE.

"Past" is every chunk of the same tracked paper: a report's chunks are only stored once impact has
assessed it (research_evaluation.chunks), so the report being assessed is never among them. Ranking
is by similarity only. Reading the past never fails the report: `failed` is recorded instead."""

import logging
from dataclasses import dataclass, field

import httpx

from research_evaluation.embedding import Embedder
from research_evaluation.storage import Chunk, Report, search_chunks

log = logging.getLogger(__name__)

# how many of each source type one search returns, at most
LIMITS = {"alert": 3, "report": 3, "document": 5}
# the most characters of the query that is embedded
MAX_QUERY_CHARS = 8000


@dataclass(frozen=True)
class History:
    """`status` is `read` (chunks found), `none` (nothing found, or no embedder) or `failed`; the
    chunks are nearest first across the three searches."""

    status: str
    chunks: list[Chunk] = field(default_factory=list)


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
    """The paper's past nearest to this report. Never raises."""
    text = query_text(report)
    # no embedder, or nothing to search with (no alerts and no notice text): nothing is found
    if embedder is None or not text.strip():
        return History("none")
    try:
        [query] = await embedder.embed([text], "query")
        found = [
            chunk
            for source_type, limit in LIMITS.items()
            for chunk in await search_chunks(sm, report.paper_id, source_type, query, limit)
        ]
    # a failed read never fails the report; only the class is logged, since the search's or
    # embedding's error could carry the query's text
    except Exception as exc:  # noqa: BLE001
        log.warning("reading the past for report %s failed: %s", report.id, type(exc).__name__)
        return History("failed")
    if not found:
        return History("none")
    return History("read", sorted(found, key=lambda chunk: chunk.score, reverse=True))
