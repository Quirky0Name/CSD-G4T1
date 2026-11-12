"""Storing a report's chunks after impact (research_evaluation.chunks), through assess_report and
POST /evaluate/reports, with Storage Management mocked by respx and fakes for the model and the
embedder."""

import json
import logging
from uuid import UUID

import httpx
import pytest
import respx
from research_evaluation_support import updating_token
from support import TEST_JWT_KEY, TEST_JWT_SECRET

from research_evaluation.auth import jwt_key
from research_evaluation.config import ResearchEvaluationSettings
from research_evaluation.impact.llm import AllModelsFailed, Answer, ModelFailure
from research_evaluation.impact.run import ImpactContext, assess_report
from research_evaluation.impact.schemas import (
    ChangeAssessment,
    ImpactAssessment,
    RecommendedActions,
)
from research_evaluation.main import create_app, impact_context
from research_evaluation.storage import sm_client

SM = "http://sm"
PAPER = UUID("6f1c2a52-8e8e-4c55-9d0b-2f4d6b0c1a01")
REPORT = 7
NOTICE_TEXT = "".join(chr(ord("a") + i % 26) for i in range(7000))
PDF_PAGES = ["Results of the corrected Table 2.", "Discussion of the corrected trial."]


def text_pdf(pages: list[str]) -> bytes:
    """A small PDF with one line of Helvetica text per page, as pypdf reads it."""
    objects = ["<< /Type /Catalog /Pages 2 0 R >>"]
    kids = " ".join(f"{4 + 2 * i} 0 R" for i in range(len(pages)))
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {len(pages)} >>")
    objects.append("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    for i, text in enumerate(pages):
        stream = f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET"
        objects.append(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {5 + 2 * i} 0 R >>"
        )
        objects.append(f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream")
    out = b"%PDF-1.4\n"
    offsets = []
    for number, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n{body}\nendobj\n".encode("latin-1")
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += "".join(f"{offset:010d} 00000 n \n" for offset in offsets).encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return out


def alert(alert_id: int, change_type: str) -> dict:
    return {
        "id": alert_id,
        "change_type": change_type,
        "change_key": f"key:{alert_id}",
        "alert_severity": "high",
        "description": f"The paper had a {change_type}.",
        "recommendation": f"Check the {change_type}.",
        "notice_doi": None,
        "detected_at": "2026-10-01T08:00:00Z",
    }


def document(document_id: int, kind: str, doi: str, text: str | None, pdf_status: str) -> dict:
    return {
        "id": document_id,
        "kind": kind,
        "doi": doi,
        "crossref_status": "ok",
        "text_status": "ok" if text else "none",
        "text": text,
        "pdf_status": pdf_status,
    }


REPORT_JSON = {
    "id": REPORT,
    "paper_id": str(PAPER),
    "owner_id": None,
    "status": "investigated",
    "created_at": "2026-10-02T08:00:00Z",
    "alerts": [alert(41, "correction"), alert(42, "expression_of_concern")],
    "documents": [
        document(101, "notice", "10.1/notice", NOTICE_TEXT, "none"),
        document(102, "new_version", "10.1/version", None, "ok"),
        document(103, "related", "10.1/related", None, "failed"),
    ],
}


class FakeLlm:
    model = "fake-gemini"

    def __init__(self, fail: bool = False, severity: str = "high"):
        self.fail = fail
        self.severity = severity

    async def generate(self, *, system, parts, schema):
        if self.fail:
            raise AllModelsFailed([ModelFailure("fake-gemini", "Gemini answered 503 (UNAVAILABLE)")])
        answers = {
            ChangeAssessment: ChangeAssessment.model_validate(
                {
                    "alerts": [], "relations": None, "severity": self.severity,
                    "claims": [{"text": "Table 2 was corrected.", "passages": []}],
                    "summary": [{"text": "Table 2 was corrected.", "kind": "fact", "claims": [1]}],
                }
            ),
            ImpactAssessment: ImpactAssessment.model_validate(
                {
                    "cited": True, "reference_entry": None, "uses": [], "impact_level": "medium",
                    "claims": [{"text": "Your Discussion relies on Table 2.", "passages": []}],
                    "explanation": [
                        {"text": "Your Discussion relies on Table 2.", "kind": "fact", "claims": [1]}
                    ],
                }
            ),
            RecommendedActions: RecommendedActions.model_validate(
                {
                    "actions": [],
                    "claims": [{"text": "Table 2 should be rechecked.", "passages": []}],
                    "recommendation": [{"text": "Recheck Table 2.", "kind": "advice", "claims": []}],
                }
            ),
        }
        return Answer(answers[schema], "fake-gemini-001")


class FakeEmbedder:
    """One vector per text; `fail_on` makes that call (1-based) of stored text raise. The search
    query impact embeds before step 1 (impact.history) is answered but not counted."""

    model = "gemini-embedding-001"

    def __init__(self, events: list[str], fail_on: int | None = None):
        self.events = events
        self.fail_on = fail_on
        self.calls: list[tuple[list[str], str]] = []

    async def embed(self, texts, task):
        if task == "query":
            return [[0.5] * 1536 for _ in texts]
        self.events.append("embed")
        self.calls.append((texts, task))
        if len(self.calls) == self.fail_on:
            raise RuntimeError("embedding failed")
        return [[0.5] * 1536 for _ in texts]


@pytest.fixture
def events() -> list[str]:
    return []


@pytest.fixture
def mock_sm(events):
    with respx.mock(base_url=SM, assert_all_called=False) as mock:
        mock.get(f"/internal/reports/{REPORT}").respond(200, json=REPORT_JSON)
        mock.get(f"/internal/papers/{PAPER}/snapshots").respond(200, json={"snapshots": []})
        mock.get(f"/internal/papers/{PAPER}/pdf").respond(404, json={"detail": "No PDF"})
        mock.get(f"/internal/papers/{PAPER}/draft").respond(404, json={"detail": "No draft"})
        mock.get("/internal/documents/102/pdf").respond(200, content=text_pdf(PDF_PAGES))

        def evaluation(request):
            events.append("evaluation")
            return httpx.Response(200, json={**REPORT_JSON, "status": "assessed"})

        def chunks(request):
            events.append("chunks")
            return httpx.Response(204)

        mock.put(f"/internal/reports/{REPORT}/evaluation").mock(side_effect=evaluation)
        mock.route(method="PUT", path="/internal/chunks", name="chunks").mock(side_effect=chunks)
        # the paper has no past yet
        mock.post("/internal/chunks/search").respond(200, json=[])
        yield mock


@pytest.fixture
async def sm(mock_sm):
    async with httpx.AsyncClient(base_url=SM) as client:
        yield client


def chunk_calls(mock) -> list[dict]:
    return [json.loads(call.request.content) for call in mock.routes["chunks"].calls]


def by_source(calls: list[dict]) -> dict[tuple[str, str], dict]:
    return {(call["source_type"], call["source_id"]): call for call in calls}


async def test_report_chunks_after_evaluation(sm, mock_sm, events):
    embedder = FakeEmbedder(events)

    assert await assess_report(sm, ImpactContext(FakeLlm(), embedder=embedder), REPORT)

    calls = by_source(chunk_calls(mock_sm))
    assert set(calls) == {
        ("alert", "41"), ("alert", "42"), ("document", "101"), ("document", "102"), ("report", "7")
    }
    for call in calls.values():
        assert call["embed_model"] == "gemini-embedding-001"
        for chunk in call["chunks"]:
            assert len(chunk["embedding"]) == 1536
    [alert_chunk] = calls[("alert", "41")]["chunks"]
    assert alert_chunk["page"] is None
    for expected in ("correction", "high", "2026-10-01", "The paper had a correction.",
                     "Check the correction."):
        assert expected in alert_chunk["content"]
    assert len(calls[("alert", "42")]["chunks"]) == 1

    notice_chunks = calls[("document", "101")]["chunks"]
    assert len(notice_chunks) == 3
    for chunk in notice_chunks:
        assert chunk["page"] is None
        assert chunk["content"].startswith("notice 10.1/notice (report of 2026-10-02)\n\n")
    assert notice_chunks[0]["content"].endswith(NOTICE_TEXT[:3200])

    pdf_chunks = calls[("document", "102")]["chunks"]
    assert [chunk["page"] for chunk in pdf_chunks] == [1, 2]
    for chunk, page_text in zip(pdf_chunks, PDF_PAGES, strict=True):
        assert chunk["content"].startswith("new version 10.1/version (report of 2026-10-02)\n\n")
        assert page_text in chunk["content"]

    [report_chunk] = calls[("report", "7")]["chunks"]
    for expected in ("Table 2 was corrected.", "high", "medium",
                     "Your Discussion relies on Table 2.", "Recheck Table 2."):
        assert expected in report_chunk["content"]

    assert all(task == "document" for _, task in embedder.calls)


async def test_chunks_after_evaluation_only(sm, mock_sm, events):
    await assess_report(sm, ImpactContext(FakeLlm(), embedder=FakeEmbedder(events)), REPORT)

    assert "chunks" in events
    assert events.index("evaluation") < events.index("chunks")
    assert "evaluation" not in events[events.index("chunks"):]


async def test_evaluation_before_embedding(sm, mock_sm, events):
    embedder = FakeEmbedder(events, fail_on=1)

    await assess_report(sm, ImpactContext(FakeLlm(), embedder=embedder), REPORT)

    assert events[0] == "evaluation"
    assert events.index("evaluation") < events.index("embed")


async def test_placeholder(sm, mock_sm, events):
    assert await assess_report(
        sm, ImpactContext(FakeLlm(fail=True), embedder=FakeEmbedder(events)), REPORT
    )

    sources = {(call["source_type"], call["source_id"]) for call in chunk_calls(mock_sm)}
    assert sources == {("alert", "41"), ("alert", "42"), ("document", "101"), ("document", "102")}


async def test_no_embedder_stores_no_chunks(sm, mock_sm, events):
    assert await assess_report(sm, ImpactContext(FakeLlm()), REPORT)

    assert chunk_calls(mock_sm) == []


async def test_unreadable_pdf_gets_no_chunks_and_the_rest_are_stored(sm, mock_sm, events, caplog):
    mock_sm.get("/internal/documents/102/pdf").respond(200, content=b"%PDF-1.4 not really")

    with caplog.at_level(logging.WARNING, logger="research_evaluation.chunks"):
        assert await assess_report(sm, ImpactContext(FakeLlm(), embedder=FakeEmbedder(events)), REPORT)

    sources = {(call["source_type"], call["source_id"]) for call in chunk_calls(mock_sm)}
    assert ("document", "102") not in sources
    assert {("alert", "41"), ("alert", "42"), ("document", "101"), ("report", "7")} <= sources
    assert any("document 102" in record.getMessage() for record in caplog.records)


async def test_change_severity_none_gets_a_report_chunk(sm, mock_sm, events):
    llm = FakeLlm(severity="none")

    assert await assess_report(sm, ImpactContext(llm, embedder=FakeEmbedder(events)), REPORT)

    [report_chunk] = by_source(chunk_calls(mock_sm))[("report", "7")]["chunks"]
    assert "change summary: Table 2 was corrected." in report_chunk["content"]
    assert "change severity: none" in report_chunk["content"]


async def test_a_pdf_page_over_8000_characters_is_split_with_its_page(sm, mock_sm, events):
    long_page = "".join(chr(ord("a") + i % 26) for i in range(9000))
    mock_sm.get("/internal/documents/102/pdf").respond(
        200, content=text_pdf([long_page, "Discussion."])
    )

    await assess_report(sm, ImpactContext(FakeLlm(), embedder=FakeEmbedder(events)), REPORT)

    pdf_chunks = by_source(chunk_calls(mock_sm))[("document", "102")]["chunks"]
    prefix = "new version 10.1/version (report of 2026-10-02)\n\n"
    assert [chunk["page"] for chunk in pdf_chunks] == [1, 1, 2]
    assert all(len(chunk["content"]) <= len(prefix) + 8000 for chunk in pdf_chunks)
    assert pdf_chunks[0]["content"] == prefix + long_page[:8000]


async def test_nul_characters_are_dropped_from_pdf_text(sm, mock_sm, events):
    # \000 in a PDF string is a NUL character, which Postgres text rejects
    mock_sm.get("/internal/documents/102/pdf").respond(
        200, content=text_pdf([r"Table\0002 was corrected."])
    )

    await assess_report(sm, ImpactContext(FakeLlm(), embedder=FakeEmbedder(events)), REPORT)

    [chunk] = by_source(chunk_calls(mock_sm))[("document", "102")]["chunks"]
    assert chunk["page"] == 1
    assert "Table2 was corrected." in chunk["content"]
    assert chr(0) not in chunk["content"]


@pytest.mark.parametrize("failure", ["embedding", "storage"])
async def test_failure_is_logged(sm, mock_sm, events, caplog, failure):
    embedder = FakeEmbedder(events, fail_on=1 if failure == "embedding" else None)
    if failure == "storage":
        calls = 0

        def first_fails(request):
            nonlocal calls
            calls += 1
            return httpx.Response(500 if calls == 1 else 204)

        mock_sm.routes["chunks"].mock(side_effect=first_fails)

    with caplog.at_level(logging.WARNING, logger="research_evaluation.chunks"):
        assessed = await assess_report(sm, ImpactContext(FakeLlm(), embedder=embedder), REPORT)

    assert assessed is True
    assert "evaluation" in events
    # the first source (alert 41) failed; every other source was still sent
    sent = {
        (json.loads(call.request.content)["source_type"], json.loads(call.request.content)["source_id"])
        for call in mock_sm.routes["chunks"].calls
    }
    assert {("alert", "42"), ("document", "101"), ("document", "102"), ("report", "7")} <= sent
    [warning] = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert "alert 41" in warning.getMessage()


async def test_evaluate_reports_endpoint_stores_chunks(sm, mock_sm, events):
    app = create_app(
        ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET, sm_base_url=SM)
    )
    app.dependency_overrides[jwt_key] = lambda: TEST_JWT_KEY
    app.dependency_overrides[sm_client] = lambda: sm
    context = ImpactContext(FakeLlm(), embedder=FakeEmbedder(events))
    app.dependency_overrides[impact_context] = lambda: context

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://re") as re:
        response = await re.post(
            "/evaluate/reports",
            json={"report_ids": [REPORT]},
            headers={"Authorization": f"Bearer {updating_token()}"},
        )

    assert response.status_code == 202
    assert {call["source_type"] for call in chunk_calls(mock_sm)} == {"alert", "document", "report"}
    assert events.index("evaluation") < events.index("chunks")
