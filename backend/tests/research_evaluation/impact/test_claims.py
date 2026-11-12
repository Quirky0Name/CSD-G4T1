"""impact/claims.py: every step's claims, each quote checked against the text its source names
(docs/EVALUATION.md, "Impact")."""

import io
import logging
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import pytest
from pypdf import PdfWriter

from research_evaluation.impact.claims import build_claims
from research_evaluation.impact.inputs import ReportInputs
from research_evaluation.impact.schemas import (
    ChangeAssessment,
    ImpactAssessment,
    RecommendedActions,
)
from research_evaluation.storage import AlertInReport, DocumentInReport, PaperDetails, Report

FIXTURES = Path(__file__).parents[2] / "fixtures"
TWO_PAGES = (FIXTURES / "pdfs" / "two_pages.pdf").read_bytes()
PAPER_DOI = "10.1016/j.ijantimicag.2020.105949"
NOTICE = "10.1016/j.ijantimicag.2024.107416"
ON_PAGE_2 = "200 mg caffeine improved n-back accuracy"


def change(*claims: dict) -> ChangeAssessment:
    return ChangeAssessment.model_validate(
        {
            "alerts": [],
            "relations": None,
            "severity": "high",
            "summary": [{"text": "The paper was retracted.", "kind": "fact", "claims": [1]}],
            "claims": list(claims) or [claim("The paper was retracted.")],
        }
    )


def impact(*claims: dict) -> ImpactAssessment:
    return ImpactAssessment.model_validate(
        {
            "cited": True,
            "reference_entry": None,
            "uses": [],
            "impact_level": "high",
            "explanation": [{"text": "Your Discussion rests on it.", "kind": "fact", "claims": [1]}],
            "claims": list(claims) or [claim("Your Discussion rests on it.")],
        }
    )


def actions(*claims: dict) -> RecommendedActions:
    return RecommendedActions.model_validate(
        {
            "actions": [],
            "recommendation": [{"text": "Replace the citation.", "kind": "fact", "claims": [1]}],
            "claims": list(claims) or [claim("Replace the citation.")],
        }
    )


def claim(text: str, *passages: tuple[str, str]) -> dict:
    return {"text": text, "passages": [{"source": s, "quote": q} for s, q in passages]}


def notice(**fields) -> DocumentInReport:
    return DocumentInReport.model_validate(
        {
            "id": 1,
            "kind": "notice",
            "doi": NOTICE,
            "crossref_status": "ok",
            "crossref_record": {"title": "Retraction notice to an article", "type": "retraction"},
            "update_to_includes_paper": True,
            "text_status": "ok",
            "text": "This article has been retracted at the request of the Editor.",
            "pdf_status": "skipped",
        }
        | fields
    )


def current_copy(**fields) -> DocumentInReport:
    return DocumentInReport.model_validate(
        {
            "id": 2,
            "kind": "current_version",
            "doi": PAPER_DOI,
            "crossref_status": "ok",
            "crossref_record": None,
            "text_status": "not_open_access",
            "pdf_status": "ok",
        }
        | fields
    )


JUMP = AlertInReport(
    id=9,
    change_type="citation_jump",
    change_key="citations",
    alert_severity="low",
    description="Citations rose from 3 to 40 in a month.",
    detected_at=datetime(2026, 9, 24, 12, tzinfo=UTC),
)

PAPER = PaperDetails(
    doi=PAPER_DOI,
    title="Hydroxychloroquine and azithromycin as a treatment of COVID-19",
    publication_year=2020,
    journal="International Journal of Antimicrobial Agents",
    authors=["Philippe Gautret"],
)


def inputs(
    documents: list[DocumentInReport] | None = None,
    paper_pdf: bytes | None = TWO_PAGES,
    document_pdfs: list[tuple[DocumentInReport, bytes]] | None = None,
) -> ReportInputs:
    documents = [notice()] if documents is None else documents
    return ReportInputs(
        report=Report(
            id=3,
            paper_id=UUID(int=1),
            status="investigated",
            alerts=[JUMP],
            documents=documents,
        ),
        paper=PAPER,
        paper_pdf=paper_pdf,
        document_pdfs=document_pdfs or [],
    )


async def test_every_steps_claims_come_in_order_with_their_ids_and_kinds():
    claims = await build_claims(
        inputs(),
        None,
        change(claim("First."), claim("Second.")),
        impact(claim("Third.")),
        actions(claim("Fourth.")),
    )

    assert [(c.id, c.kind, c.text) for c in claims] == [
        ("change-0", "change", "First."),
        ("change-1", "change", "Second."),
        ("impact-0", "impact", "Third."),
        ("action-0", "action", "Fourth."),
    ]


async def test_steps_that_didnt_run_give_no_claims():
    claims = await build_claims(inputs(), None, change(claim("Only.")), None, None)

    assert [c.id for c in claims] == ["change-0"]


async def test_a_claim_with_no_passages_is_not_supported():
    [only] = await build_claims(inputs(), None, change(claim("Nothing shows it.")), None, None)

    assert only.passages == []
    assert only.supported is False


def passage(claims, claim_index: int = 0, passage_index: int = 0) -> dict:
    return claims[claim_index].passages[passage_index].model_dump()


async def test_a_quote_on_page_two_of_the_stored_paper_gives_page_two_verbatim():
    claims = await build_claims(
        inputs(), None, change(claim("Caffeine helped.", ("stored paper", ON_PAGE_2))), None, None
    )

    assert passage(claims) == {
        "target": "paper",
        "document_id": None,
        "alert_id": None,
        "quote": ON_PAGE_2,
        "page": 2,
        "verified": "verbatim",
    }
    assert claims[0].supported is True


def blank_pdf() -> bytes:
    """A PDF whose one page has no text layer (as a scan without OCR)."""
    writer = PdfWriter()
    writer.add_blank_page(width=200, height=200)
    out = io.BytesIO()
    writer.write(out)
    return out.getvalue()


async def one(source: str, quote: str, **given) -> tuple[dict, bool]:
    claims = await build_claims(
        inputs(**given), None, change(claim("A statement.", (source, quote))), None, None
    )
    return passage(claims), claims[0].supported


async def test_a_quote_in_the_notices_text_gives_the_document_with_no_page():
    found, supported = await one(NOTICE, "has been retracted at the request of the Editor")

    assert found["target"] == "document"
    assert found["document_id"] == 1
    assert found["page"] is None
    assert found["verified"] == "verbatim"
    assert supported is True


async def test_a_quote_from_a_notices_crossref_record_with_no_text_is_verbatim():
    found, _ = await one(
        NOTICE, "Retraction notice to an article", documents=[notice(text=None, text_status="none")]
    )

    assert (found["target"], found["document_id"], found["page"], found["verified"]) == (
        "document",
        1,
        None,
        "verbatim",
    )


@pytest.mark.parametrize(
    "quote", ["update_to_includes_paper: true", NOTICE], ids=["update_to line", "doi"]
)
async def test_a_quote_of_a_line_the_prompt_shows_for_a_notice_is_verbatim(quote):
    found, _ = await one(NOTICE, quote)

    assert (found["target"], found["document_id"], found["verified"]) == ("document", 1, "verbatim")


async def test_a_quote_of_a_current_copys_pdf_label_is_verbatim():
    copy = current_copy(pdf_source_url="https://example.org/paper.pdf")
    found, _ = await one(
        "current copy", "downloaded on ? from https://example.org/paper.pdf",
        documents=[notice(), copy], paper_pdf=None, document_pdfs=[(copy, TWO_PAGES)],
    )

    assert (found["target"], found["document_id"], found["verified"]) == ("document", 2, "verbatim")


async def test_a_quote_from_the_papers_details_gives_the_paper_with_no_page():
    found, _ = await one("stored paper", "International Journal of Antimicrobial Agents")

    assert (found["target"], found["page"], found["verified"]) == ("paper", None, "verbatim")


async def test_an_invented_quote_is_not_found_and_its_claim_not_supported():
    found, supported = await one("stored paper", "caffeine abolished all memory effects")

    assert (found["target"], found["page"], found["verified"]) == ("paper", None, "not_found")
    assert supported is False


@pytest.mark.parametrize(
    "pdf", [b"%PDF-1.4 this is not really a pdf", blank_pdf()], ids=["corrupt", "no text layer"]
)
async def test_a_quote_from_a_pdf_with_no_text_to_check_is_no_text(pdf):
    found, supported = await one("stored paper", ON_PAGE_2, paper_pdf=pdf)

    assert (found["target"], found["page"], found["verified"]) == ("paper", None, "no_text")
    assert supported is False


async def test_a_quote_naming_the_wrong_source_is_retargeted_to_where_it_is():
    found, supported = await one(NOTICE, ON_PAGE_2)

    assert (found["target"], found["document_id"], found["page"]) == ("paper", None, 2)
    assert found["verified"] == "verbatim"
    assert supported is True


async def test_a_quote_naming_no_known_source_and_found_nowhere_has_no_target():
    found, _ = await one("the internet", "words that appear nowhere")

    assert (found["target"], found["verified"]) == (None, "not_found")


async def test_a_quote_in_a_current_copys_pdf_gives_its_document_and_page():
    copy = current_copy()
    found, _ = await one(
        "current copy", ON_PAGE_2, documents=[notice(), copy], paper_pdf=None,
        document_pdfs=[(copy, TWO_PAGES)],
    )

    assert (found["target"], found["document_id"], found["page"]) == ("document", 2, 2)


async def test_a_citation_jumps_alert_line_quote_gives_the_alert():
    found, _ = await one("alert 9", "Citations rose from 3 to 40 in a month.")

    assert (found["target"], found["alert_id"], found["verified"]) == ("alert", 9, "verbatim")


async def test_a_draft_quote_is_checked_against_the_draft():
    claims = await build_claims(
        inputs(paper_pdf=None), TWO_PAGES, change(claim("It does.", ("draft", ON_PAGE_2))), None, None
    )

    assert (passage(claims)["target"], passage(claims)["page"]) == ("draft", 2)


async def test_nothing_from_a_quote_or_a_pdf_is_logged(caplog):
    caplog.set_level(logging.DEBUG)

    await build_claims(
        inputs(paper_pdf=b"%PDF-1.4 secret corrupt bytes"),
        TWO_PAGES,
        change(
            claim("A.", ("draft", ON_PAGE_2)),
            claim("B.", ("stored paper", "an invented private sentence")),
        ),
        None,
        None,
    )

    for secret in ("n-back", "healthy adults", "invented private sentence", "secret corrupt"):
        assert secret not in caplog.text
