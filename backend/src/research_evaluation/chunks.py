"""The paper's past, stored for impact to search later (docs/EVALUATION.md, "Embeddings"): right
after impact stores a report's evaluation, the text of the report, each of its alerts and each of
its documents is cut into chunks, embedded and stored in Storage Management, one source at a time.

- An alert: one chunk of its change type, severity, detection date, description and recommendation.
- The report: one chunk of its evaluation's fields that are present; none for the placeholder.
- A document, each chunk prefixed with its kind, DOI and the report's date:
  - with a stored PDF: the PDF's text, one chunk per page with that page number (from 1), a page
    over PAGE_LIMIT characters split into several chunks with the same page;
  - no PDF but text (Europe PMC): chunk_text's 3200-character chunks, with no page;
  - neither: no chunks.

Storing never raises and is never retried: a failure (embedding, Storage Management, a PDF pypdf
can't read) is logged and leaves that source without chunks, and the others are still stored.

Apart from those, a report's feedback is one chunk of its own (feedback_piece, stored by
research_evaluation.feedback when the researcher saves or deletes it)."""

import asyncio
import io
import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

import httpx
from google.genai import errors as genai_errors
from pypdf import PdfReader
from pypdf.errors import PyPdfError

from research_evaluation.embedding import Embedder, chunk_text
from research_evaluation.impact.inputs import ReportInputs
from research_evaluation.impact.schemas import Evaluation
from research_evaluation.storage import (
    AlertInReport,
    DocumentInReport,
    PaperDetails,
    Report,
    store_chunks,
)

log = logging.getLogger(__name__)

# the most characters of one PDF page in one chunk; a longer page is split
PAGE_LIMIT = 8000


@dataclass(frozen=True)
class Piece:
    """One chunk's text, and the PDF page it came from (None when it didn't)."""

    content: str
    page: int | None = None


def alert_pieces(alert: AlertInReport) -> list[Piece]:
    lines = [
        f"alert: {alert.change_type}",
        f"alert severity: {alert.alert_severity}",
        f"detected: {alert.detected_at.date().isoformat()}",
        f"description: {alert.description}",
    ]
    if alert.recommendation:
        lines.append(f"recommendation: {alert.recommendation}")
    return [Piece("\n".join(lines))]


def report_pieces(evaluation: Evaluation) -> list[Piece]:
    """The evaluation's fields that are present; none for the placeholder, which says nothing
    about the paper."""
    if evaluation.assessment.get("placeholder"):
        return []
    fields = [
        ("change summary", evaluation.change_summary),
        ("change severity", evaluation.change_severity),
        ("impact level", evaluation.impact_level),
        ("evaluation", evaluation.evaluation),
        ("recommendation", evaluation.recommendation),
    ]
    return [Piece("\n".join(f"{name}: {value}" for name, value in fields if value))]


def feedback_piece(report: Report, paper: PaperDetails) -> Piece:
    """The report's evaluation with the researcher's verdict and note, as one chunk of at most
    PAGE_LIMIT characters: the title line, the alerts' change types and the evaluation fields that
    are present, then the verdict and the note (left out when there is none). A long evaluation is
    cut; the title line, verdict and note never are. The report must have feedback."""
    feedback = report.feedback
    if feedback is None:
        raise ValueError(f"report {report.id} has no feedback")
    date = report.created_at.date().isoformat() if report.created_at else "unknown"
    head = f'feedback on report of {date}: "{paper.title or paper.doi or "unknown paper"}"'
    change_types = ", ".join(dict.fromkeys(alert.change_type for alert in report.alerts))
    fields = [
        ("changes", change_types),
        ("change summary", report.change_summary),
        ("change severity", report.change_severity),
        ("impact level", report.impact_level),
        ("evaluation", report.evaluation),
        ("recommendation", report.recommendation),
    ]
    middle = "\n".join(f"{name}: {value}" for name, value in fields if value)
    tail = f"researcher's verdict: {feedback.verdict}"
    if feedback.note:
        tail += f"\nresearcher's note: {feedback.note}"
    # what's left for the middle once the head, the tail and the two newlines around it are in
    room = PAGE_LIMIT - len(head) - len(tail) - 2
    middle = middle[: max(room, 0)].rstrip()
    return Piece("\n".join(part for part in (head, middle, tail) if part))


def document_pieces(
    document: DocumentInReport, report_date: datetime | None, pdf: bytes | None
) -> list[Piece]:
    """Raises pypdf's errors for a PDF it can't read."""
    date = report_date.date().isoformat() if report_date else "unknown"
    prefix = f"{document.kind.replace('_', ' ')} {document.doi} (report of {date})\n\n"
    if pdf is not None:
        return [
            Piece(prefix + text, page)
            for page, page_text in pdf_pages(pdf)
            for text in chunk_text(page_text, size=PAGE_LIMIT)
        ]
    if document.text:
        return [Piece(prefix + text) for text in chunk_text(document.text)]
    return []


def pdf_pages(pdf: bytes) -> list[tuple[int, str]]:
    """Each page's text with its number from 1; pages with no text (a scan) are left out. NUL
    characters, which pypdf can return and Postgres text rejects, are dropped."""
    reader = PdfReader(io.BytesIO(pdf))
    pages = [
        (number, (page.extract_text() or "").replace("\x00", ""))
        for number, page in enumerate(reader.pages, 1)
    ]
    return [(number, text) for number, text in pages if text.strip()]


async def store_report_chunks(
    sm: httpx.AsyncClient,
    embedder: Embedder,
    inputs: ReportInputs,
    evaluation: Evaluation,
) -> None:
    """Stores the chunks of the report, its alerts and its documents. Never raises."""
    report = inputs.report
    pdfs = {document.id: pdf for document, pdf in inputs.document_pdfs}
    sources: list[tuple[str, int, Callable[[], list[Piece]]]] = [
        *(("alert", alert.id, lambda alert=alert: alert_pieces(alert)) for alert in report.alerts),
        ("report", report.id, lambda: report_pieces(evaluation)),
        *(
            (
                "document",
                document.id,
                lambda document=document: document_pieces(
                    document, report.created_at, pdfs.get(document.id)
                ),
            )
            for document in report.documents
        ),
    ]
    for source_type, source_id, pieces_of in sources:
        try:
            # off the event loop: pypdf reading a long PDF would stall every other request
            pieces = await asyncio.to_thread(pieces_of)
            if not pieces:
                continue
            vectors = await embedder.embed([piece.content for piece in pieces], "document")
            await store_chunks(
                sm,
                source_type,
                str(source_id),
                embedder.model,
                [
                    {"content": piece.content, "page": piece.page, "embedding": vector}
                    for piece, vector in zip(pieces, vectors, strict=True)
                ],
            )
        except Exception as exc:  # after the report is assessed: nothing may undo or stop that
            log.warning(
                "storing the chunks of %s %s failed: %s",
                source_type,
                source_id,
                _cause(exc),
                # a traceback only for what isn't an expected failure (a bug)
                exc_info=not isinstance(
                    exc, httpx.HTTPError | genai_errors.APIError | PyPdfError | ValueError
                ),
            )


def _cause(exc: Exception) -> str:
    """What went wrong, without response bodies or the text being embedded."""
    match exc:
        case httpx.HTTPStatusError():
            return f"Storage Management answered {exc.response.status_code}"
        case genai_errors.APIError():
            return f"Gemini answered {exc.code} ({exc.status})"
        case _:
            return type(exc).__name__
