"""What impact's first step reads from Storage Management for one report: the paper's details,
its stored PDF, and the PDF of each document that has one. Never the researcher's draft: that is
read only once the change turns out to be meaningful (docs/EVALUATION-IMPACT.md, "The gate")."""

from dataclasses import dataclass, field

import httpx

from research_evaluation.storage import (
    DocumentInReport,
    PaperDetails,
    Report,
    document_pdf,
    paper_details,
    paper_pdf,
)


@dataclass(frozen=True)
class MissingPdf:
    """A PDF impact wanted but doesn't have, and why; named in the prompt and in the result."""

    pdf: str
    why: str


@dataclass(frozen=True)
class ReportInputs:
    report: Report
    paper: PaperDetails
    paper_pdf: bytes | None
    # each document with a stored PDF (new versions and current copies), in the report's order
    document_pdfs: list[tuple[DocumentInReport, bytes]] = field(default_factory=list)
    missing: list[MissingPdf] = field(default_factory=list)


def label(document: DocumentInReport) -> str:
    """How a document's PDF is named to the model and in the result."""
    if document.kind == "current_version":
        return "current copy"
    return f"{document.kind.replace('_', ' ')} {document.doi}"


async def gather_inputs(sm: httpx.AsyncClient, report: Report) -> ReportInputs:
    """Reads step 1's inputs for a report already read with `read_report`. Raises PaperGone when
    the paper was deleted meanwhile; a PDF that isn't there is recorded, not raised."""
    details = await paper_details(sm, report.paper_id)
    stored = await paper_pdf(sm, report.paper_id)
    missing = [] if stored is not None else [MissingPdf("stored paper", "not stored")]
    pdfs = []
    for document in report.documents:
        if document.kind == "notice":
            continue  # notices have no PDF, only their record and text
        if document.pdf_status != "ok":
            missing.append(MissingPdf(label(document), f"not stored ({document.pdf_status})"))
            continue
        content = await document_pdf(sm, document.id)
        if content is None:
            missing.append(MissingPdf(label(document), "missing from Storage Management"))
        else:
            pdfs.append((document, content))
    return ReportInputs(
        report=report, paper=details, paper_pdf=stored, document_pdfs=pdfs, missing=missing
    )
