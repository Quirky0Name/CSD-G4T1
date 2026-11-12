"""A report's feedback as one searchable chunk (docs/EVALUATION.md, "Embeddings"): Storage
Management nudges POST /embed/feedback after the researcher saves or deletes feedback, and the
report's evaluation with the verdict and note is embedded as RETRIEVAL_DOCUMENT and stored as the
report's one `feedback` chunk. A report with no feedback (deleted) stores an empty list, which
deletes the chunk.

Storing never raises and is never retried: a failure is logged by its class only, never with the
note's text, and saving the feedback again re-embeds it."""

import logging

import httpx

from research_evaluation.chunks import feedback_piece
from research_evaluation.embedding import Embedder
from research_evaluation.storage import paper_details, read_report, store_chunks

log = logging.getLogger(__name__)


async def store_feedback_chunk(sm: httpx.AsyncClient, embedder: Embedder, report_id: int) -> None:
    """Replaces the report's feedback chunk with one built from its feedback now. Never raises."""
    try:
        report = await read_report(sm, report_id)
        chunks = []
        if report.feedback is not None:
            piece = feedback_piece(report, await paper_details(sm, report.paper_id))
            [vector] = await embedder.embed([piece.content], "document")
            chunks = [{"content": piece.content, "page": None, "embedding": vector}]
        await store_chunks(sm, "feedback", str(report_id), embedder.model, chunks)
    # in the background, after Storage Management answered the researcher: nothing to fail; only the
    # class is logged, since an embedding or Storage Management error could carry the note's text
    except Exception as exc:  # noqa: BLE001
        log.warning("storing the feedback chunk of report %s failed: %s", report_id, type(exc).__name__)
