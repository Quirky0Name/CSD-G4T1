"""What impact reads from Storage Management (storage.py's impact calls and impact/inputs.py),
against the stub."""

from uuid import UUID, uuid4

import httpx
import pytest

from research_evaluation.impact.inputs import MissingPdf, gather_inputs
from research_evaluation.storage import (
    PaperGone,
    ReportGone,
    document_pdf,
    draft_pdf,
    paper_details,
    paper_pdf,
    read_report,
    store_evaluation,
)

PAPER_DOI = "10.1016/j.ijantimicag.2020.105949"  # the stub paper's DOI (conftest.StubSm)
NOTICE = "10.1016/j.ijantimicag.2024.107416"
NEW_VERSION = "10.1/new-version"
PAPER_BYTES = b"%PDF-1.7 the stored paper"
DRAFT_BYTES = b"%PDF-1.7 the researcher's draft"


async def add_alert(sm, paper: UUID, change_key: str, notice_doi: str | None = NOTICE) -> None:
    response = await sm.client.post(
        f"/internal/papers/{paper}/alerts",
        json={
            "change_type": "retraction",
            "change_key": change_key,
            "severity": "high",
            "description": "The paper was retracted.",
            "recommendation": "Read the notice.",
            "notice_doi": notice_doi,
            "detected_at": "2026-09-20T12:00:00Z",
            "snapshot_id": 2,
            "previous_snapshot_id": 1,
        },
    )
    response.raise_for_status()


async def add_document(sm, report_id: int, kind: str, doi: str) -> dict:
    response = await sm.client.post(
        "/internal/documents",
        json={
            "report_id": report_id,
            "kind": kind,
            "doi": doi,
            "crossref_status": "ok",
            "crossref_record": {"title": f"Record of {doi}"},
            "update_to_includes_paper": kind == "notice" or None,
            "text_status": "not_open_access",
            "text": None,
            "text_truncated": False,
        },
    )
    response.raise_for_status()
    return response.json()


async def investigated_report(sm, paper: UUID) -> int:
    await add_alert(sm, paper, "retraction")
    opened = await sm.client.post(f"/internal/papers/{paper}/reports")
    report_id = opened.json()["id"]
    await sm.client.post(
        "/dev/pdfs", json={"doi": PAPER_DOI, "source_url": "https://pmc.example/current.pdf"}
    )
    await add_document(sm, report_id, "notice", NOTICE)
    await add_document(sm, report_id, "current_version", PAPER_DOI)  # downloadable: ok
    await add_document(sm, report_id, "new_version", NEW_VERSION)  # not downloadable: not_found
    marked = await sm.client.patch(
        f"/internal/papers/{paper}/reports/{report_id}", json={"status": "investigated"}
    )
    marked.raise_for_status()
    return report_id


async def paper_with_everything(sm) -> UUID:
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(
        paper,
        2,
        title="Hydroxychloroquine and azithromycin as a treatment of COVID-19",
        authors=[
            {"name": "Philippe Gautret", "position": "first"},
            {"name": "Didier Raoult", "position": "last"},
        ],
    )
    await sm.client.post(f"/dev/papers/{paper}/pdf", content=PAPER_BYTES)
    await sm.client.post(f"/dev/papers/{paper}/research-paper", content=DRAFT_BYTES)
    return paper


async def test_a_report_read_by_id_has_its_paper_alerts_and_documents(sm):
    paper = await paper_with_everything(sm)
    report_id = await investigated_report(sm, paper)

    report = await read_report(sm.client, report_id)

    assert report.id == report_id
    assert report.paper_id == paper
    assert report.status == "investigated"
    [alert] = report.alerts
    assert (alert.change_key, alert.notice_doi, alert.severity) == ("retraction", NOTICE, "high")
    kinds = {d.doi: (d.kind, d.pdf_status) for d in report.documents}
    assert kinds == {
        NOTICE: ("notice", "skipped"),
        PAPER_DOI: ("current_version", "ok"),
        NEW_VERSION: ("new_version", "not_found"),
    }
    assert report.documents[0].crossref_record == {"title": f"Record of {NOTICE}"}


async def test_gathering_reads_step_ones_inputs_and_never_the_draft(sm):
    paper = await paper_with_everything(sm)
    report = await read_report(sm.client, await investigated_report(sm, paper))

    inputs = await gather_inputs(sm.client, report)

    assert inputs.report == report
    assert inputs.paper.doi == PAPER_DOI
    assert inputs.paper.title == "Hydroxychloroquine and azithromycin as a treatment of COVID-19"
    assert inputs.paper.publication_year == 2020
    assert inputs.paper.journal == "International Journal of Antimicrobial Agents"
    assert inputs.paper.authors == ["Philippe Gautret", "Didier Raoult"]
    assert inputs.paper_pdf == PAPER_BYTES
    [(document, content)] = inputs.document_pdfs
    assert (document.kind, document.doi) == ("current_version", PAPER_DOI)
    assert content.startswith(b"%PDF")
    assert inputs.missing == [MissingPdf(f"new version {NEW_VERSION}", "not stored (not_found)")]
    # the draft is never read here, and only the ok document's PDF is fetched
    assert sm.calls("GET", "/research-paper") == 0
    assert sm.calls("GET", f"/documents/{document.id}/pdf") == 1
    assert sm.calls("GET", "/pdf") == 2  # the paper's and that one document's


async def test_a_paper_without_a_stored_pdf_is_recorded_not_raised(sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    report = await read_report(sm.client, await investigated_report(sm, paper))

    inputs = await gather_inputs(sm.client, report)

    assert inputs.paper_pdf is None
    assert MissingPdf("stored paper", "not stored") in inputs.missing


async def test_a_document_pdf_gone_between_the_read_and_the_fetch_is_recorded(sm):
    paper = await paper_with_everything(sm)
    report = await read_report(sm.client, await investigated_report(sm, paper))
    current = next(d for d in report.documents if d.kind == "current_version")
    sm.transport.faults[f"/documents/{current.id}/pdf"] = httpx.Response(
        404, json={"detail": f"The PDF for document {current.id} is missing from disk"}
    )

    inputs = await gather_inputs(sm.client, report)

    assert inputs.document_pdfs == []
    assert MissingPdf("current copy", "missing from Storage Management") in inputs.missing


async def test_the_draft_is_read_on_its_own(sm):
    paper = await paper_with_everything(sm)
    assert await draft_pdf(sm.client, paper) == DRAFT_BYTES


async def test_no_draft_is_none_not_an_error(sm):
    paper = await sm.add_paper()
    assert await draft_pdf(sm.client, paper) is None


@pytest.mark.parametrize(
    "detail",
    [
        "No research paper in the project of paper {paper}",
        "The research paper for paper {paper} is missing from disk",
    ],
)
async def test_each_no_draft_404_is_none(sm, detail):
    paper = await paper_with_everything(sm)
    sm.transport.faults["/research-paper"] = httpx.Response(
        404, json={"detail": detail.format(paper=paper)}
    )
    assert await draft_pdf(sm.client, paper) is None


@pytest.mark.parametrize(
    "detail",
    ["Paper {paper} has no stored PDF", "The PDF for paper {paper} is missing from disk"],
)
async def test_each_no_file_404_for_the_paper_is_none(sm, detail):
    paper = await paper_with_everything(sm)
    sm.transport.faults[f"/papers/{paper}/pdf"] = httpx.Response(
        404, json={"detail": detail.format(paper=paper)}
    )
    assert await paper_pdf(sm.client, paper) is None


async def test_an_unknown_paper_is_paper_gone_on_every_paper_read(sm):
    missing = uuid4()
    for read in (paper_details, paper_pdf, draft_pdf):
        with pytest.raises(PaperGone):
            await read(sm.client, missing)


async def test_an_unknown_report_is_report_gone(sm):
    with pytest.raises(ReportGone):
        await read_report(sm.client, 999)
    with pytest.raises(ReportGone):
        await store_evaluation(
            sm.client, 999, {"change_summary": "s", "change_severity": "none"}
        )


async def test_any_other_error_status_is_raised(sm):
    paper = await paper_with_everything(sm)
    report_id = await investigated_report(sm, paper)
    sm.transport.faults[f"/reports/{report_id}"] = 500
    with pytest.raises(httpx.HTTPStatusError):
        await read_report(sm.client, report_id)
    sm.transport.faults["/research-paper"] = 503
    with pytest.raises(httpx.HTTPStatusError):
        await draft_pdf(sm.client, paper)


async def test_a_document_without_a_pdf_is_none(sm):
    paper = await paper_with_everything(sm)
    report = await read_report(sm.client, await investigated_report(sm, paper))
    notice = next(d for d in report.documents if d.kind == "notice")
    assert await document_pdf(sm.client, notice.id) is None
    assert await document_pdf(sm.client, 999) is None


async def test_the_paper_details_come_from_the_newest_snapshot(sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1, title="Old title")
    await sm.add_snapshot(paper, 2, title="New title", publication_year=2021)

    details = await paper_details(sm.client, paper)

    assert (details.title, details.publication_year) == ("New title", 2021)
    assert details.authors == []  # the test snapshots have authors null
    assert sm.queries("GET", "/background-info/history") == ["last=1"]


async def test_a_paper_with_no_snapshot_has_empty_details(sm):
    paper = await sm.add_paper()
    details = await paper_details(sm.client, paper)
    assert (details.doi, details.title, details.authors) == (None, None, [])


async def test_storing_an_evaluation_assesses_the_report(sm):
    paper = await paper_with_everything(sm)
    report_id = await investigated_report(sm, paper)

    stored = await store_evaluation(
        sm.client, report_id, {"change_summary": "Not meaningful.", "change_severity": "none"}
    )

    assert stored["status"] == "assessed"
    with pytest.raises(httpx.HTTPStatusError) as conflict:
        await store_evaluation(
            sm.client, report_id, {"change_summary": "Again.", "change_severity": "none"}
        )
    assert conflict.value.response.status_code == 409


async def test_every_request_carries_the_service_token_and_stays_in_storage_management(sm):
    paper = await paper_with_everything(sm)
    report = await read_report(sm.client, await investigated_report(sm, paper))
    await gather_inputs(sm.client, report)
    await draft_pdf(sm.client, paper)

    assert sm.transport.sent
    for request in sm.transport.sent:
        assert request.url.host == "stub"
        assert request.headers["Authorization"].startswith("Bearer ")
