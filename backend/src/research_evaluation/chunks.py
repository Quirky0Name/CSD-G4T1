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
can't read) is logged and leaves that source without chunks, and the others are still stored."""

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
from research_evaluation.storage import AlertInReport, DocumentInReport, store_chunks

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
