"""A fixed report for impact's prompt builders, and a JSON form of the parts they build, so a
prompt can be compared with one saved earlier (tests/fixtures/impact/*_prompt_parts.json). Also the
real IJAA retraction, for the live tests."""

import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import httpx
from support import load_fixture

from research_evaluation.impact.inputs import MissingPdf, ReportInputs
from research_evaluation.impact.llm import Part, Pdf
from research_evaluation.impact.schemas import ChangeAssessment, ImpactAssessment
from research_evaluation.investigation.crossref import CrossrefRecord, fetch_record
from research_evaluation.storage import AlertInReport, DocumentInReport, PaperDetails, Report

FIXTURES = Path(__file__).parent / "fixtures" / "impact"

PAPER = PaperDetails(
    doi="10.1016/j.ijantimicag.2020.105949",
    title="Hydroxychloroquine and azithromycin as a treatment of COVID-19",
    publication_year=2020,
    journal="International Journal of Antimicrobial Agents",
    authors=["Philippe Gautret", "Jean-Christophe Lagier"],
)
PAPER_PDF = b"%PDF-1.7 the stored paper"
CURRENT_PDF = b"%PDF-1.7 the current copy"
DRAFT = b"%PDF-1.7 the researcher's draft"


def sample_inputs() -> ReportInputs:
    notice = DocumentInReport(
        id=1,
        kind="notice",
        doi="10.1016/j.ijantimicag.2024.107416",
        crossref_status="ok",
        crossref_record={"title": "Retraction notice"},
        update_to_includes_paper=True,
        text_status="ok",
        text="This article has been retracted at the request of the Editor.",
        pdf_status="skipped",
    )
    current = DocumentInReport(
        id=2,
        kind="current_version",
        doi=PAPER.doi,
        crossref_status="ok",
        text_status="not_open_access",
        pdf_status="ok",
        pdf_source_url="https://pmc.example/current.pdf",
        pdf_fetched_at=datetime(2026, 9, 27, 8, tzinfo=UTC),
    )
    report = Report(
        id=3,
        paper_id=UUID("6f1c2a52-8e8e-4c55-9d0b-2f4d6b0c1a01"),
        status="investigated",
        alerts=[
            AlertInReport(
                id=7,
                change_type="retraction",
                change_key="retraction",
                alert_severity="high",
                description="The paper was retracted.",
                notice_doi=notice.doi,
                detected_at=datetime(2026, 9, 24, 12, tzinfo=UTC),
            )
        ],
        documents=[notice, current],
    )
    return ReportInputs(
        report=report,
        paper=PAPER,
        paper_pdf=PAPER_PDF,
        document_pdfs=[(current, CURRENT_PDF)],
        missing=[MissingPdf("new version 10.1/v2", "not stored (not_found)")],
    )



async def ijaa_retraction_inputs(sm, owner: UUID | None = None) -> ReportInputs:
    """The IJAA retraction with its notice's real Crossref record and no notice text, and a draft
    in the stub Storage Management that cites the paper, so steps 2 and 3 run."""
    reply = load_fixture("crossref", "ijaa_notice")
    sample = sample_inputs().report
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json=reply))
    ) as crossref:
        record = await fetch_record(crossref, sample.documents[0].doi, "")
    assert isinstance(record, CrossrefRecord)
    notice = sample.documents[0].model_copy(
        update={
            "crossref_record": record.model_dump(mode="json"),
            "text": None,
            "text_status": "not_open_access",
        }
    )
    paper = await sm.add_paper()
    await sm.client.post(
        f"/dev/papers/{paper}/draft", content=(FIXTURES / "draft_cites_ijaa.pdf").read_bytes()
    )
    return ReportInputs(
        report=sample.model_copy(update={"paper_id": paper, "owner_id": owner, "documents": [notice]}),
        paper=PAPER,
        paper_pdf=None,
        document_pdfs=[],
        missing=[MissingPdf("stored paper", "not stored")],
    )

CHANGE = ChangeAssessment.model_validate(
    {
        "alerts": [],
        "relations": None,
        "severity": "high",
        "summary": [{"text": "The paper was retracted.", "kind": "fact", "claims": [1]}],
        "claims": [
            {
                "text": "The paper was retracted.",
                "passages": [
                    {"source": "10.1016/j.ijantimicag.2024.107416", "quote": "This article has been retracted"}
                ],
            }
        ],
    }
)
IMPACT = ImpactAssessment.model_validate(
    {
        "cited": True,
        "reference_entry": "Gautret P, et al. Int J Antimicrob Agents. 2020.",
        "uses": [],
        "impact_level": "medium",
        "explanation": [
            {"text": "Your Discussion relies on its viral clearance results.", "kind": "fact", "claims": [1]}
        ],
        "claims": [{"text": "Your Discussion relies on its viral clearance results.", "passages": []}],
    }
)


def serialise(parts: list[Part]) -> list[dict]:
    return [
        {"pdf": hashlib.sha256(part.data).hexdigest()} if isinstance(part, Pdf) else {"text": part}
        for part in parts
    ]


def saved(name: str) -> list[dict]:
    return json.loads((FIXTURES / f"{name}_prompt_parts.json").read_text(encoding="utf-8"))
