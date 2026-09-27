"""The stub Storage Management's reports and documents behave like the real ones
(docs/EVALUATION-INVESTIGATION.md, S1), so Research Evaluation can be tested against it."""

import hashlib
from uuid import uuid4

import httpx
import jwt
import pytest
from support import TEST_JWT_KEY

from common.service_token import mint_service_token
from dev.stub_storage import create_app, stub_pdf


@pytest.fixture
async def client():
    transport = httpx.ASGITransport(app=create_app(TEST_JWT_KEY))
    headers = {
        "Authorization": f"Bearer {mint_service_token(TEST_JWT_KEY, 'svc:research-evaluation')}"
    }
    async with httpx.AsyncClient(
        transport=transport, base_url="http://stub", headers=headers
    ) as client:
        yield client


async def add_paper(client) -> str:
    response = await client.post("/dev/papers", json={"doi": "10.1016/j.ijantimicag.2020.105949"})
    return response.json()["id"]


async def add_alert(client, paper_id: str, change_key: str) -> dict:
    response = await client.post(
        f"/internal/papers/{paper_id}/alerts",
        json={
            "change_type": "retraction",
            "change_key": change_key,
            "severity": "high",
            "description": "Something changed.",
            "recommendation": "Read the notice.",
            "notice_doi": "10.1/notice",
            "detected_at": "2026-09-20T12:00:00Z",
            "snapshot_id": 42,
            "previous_snapshot_id": 41,
        },
    )
    assert response.status_code == 201
    return response.json()


async def open_report(client, paper_id: str) -> dict:
    response = await client.post(f"/internal/papers/{paper_id}/reports")
    assert response.status_code == 201
    return response.json()


def document(report_id: int, **fields) -> dict:
    return {
        "report_id": report_id,
        "kind": "notice",
        "doi": "10.1016/j.ijantimicag.2024.107416",
        "crossref_status": "ok",
        "crossref_record": {"title": "Retraction notice"},
        "update_to_includes_paper": True,
        "text_status": "not_open_access",
        "text": None,
        "text_truncated": False,
        **fields,
    }


async def test_opening_groups_only_the_papers_unreported_alerts(client):
    paper = await add_paper(client)
    other = await add_paper(client)
    retraction = await add_alert(client, paper, "retraction")
    correction = await add_alert(client, paper, "correction:10.1/c")
    await add_alert(client, other, "retraction")

    report = await open_report(client, paper)

    assert report["paper_id"] == paper
    assert report["status"] == "investigating"
    assert report["investigated_at"] is None
    assert (
        report["evaluation"] is None
        and report["recommendation"] is None
        and report["evaluated_at"] is None
    )
    # impact's fields, present and null
    for field in ("change_summary", "change_severity", "impact_level", "assessment"):
        assert field in report and report[field] is None
    assert [a["id"] for a in report["alerts"]] == [retraction["id"], correction["id"]]
    assert [a["change_key"] for a in report["alerts"]] == ["retraction", "correction:10.1/c"]
    assert report["documents"] == []
    # the other paper's alert is still unreported
    assert (await client.post(f"/internal/papers/{other}/reports")).status_code == 201


async def test_opening_again_with_nothing_new_is_204_and_new_alerts_go_to_the_next_report(client):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    first = await open_report(client, paper)

    again = await client.post(f"/internal/papers/{paper}/reports")
    assert again.status_code == 204
    assert again.content == b""

    later = await add_alert(client, paper, "correction:10.1/c")
    second = await open_report(client, paper)
    assert second["id"] != first["id"]
    assert [a["id"] for a in second["alerts"]] == [later["id"]]


async def test_the_alert_api_never_shows_report_id(client):
    paper = await add_paper(client)
    alert = await add_alert(client, paper, "retraction")
    assert "report_id" not in alert


async def test_unknown_paper_is_no_paper_404(client):
    missing = str(uuid4())
    response = await client.post(f"/internal/papers/{missing}/reports")
    assert response.status_code == 404
    assert response.json()["detail"] == f"No paper {missing}"


async def test_reading_a_report_gives_its_alerts_and_documents(client):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)
    stored = (await client.post("/internal/documents", json=document(report["id"]))).json()

    read = await client.get(f"/internal/papers/{paper}/reports/{report['id']}")

    assert read.status_code == 200
    assert read.json()["documents"] == [stored]
    assert [a["change_key"] for a in read.json()["alerts"]] == ["retraction"]
    for field in ("change_summary", "change_severity", "impact_level", "assessment"):
        assert field in read.json() and read.json()[field] is None


async def test_an_unknown_report_or_another_papers_report_is_no_report_404(client):
    paper = await add_paper(client)
    other = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)

    for path in (
        f"/internal/papers/{paper}/reports/999",
        f"/internal/papers/{other}/reports/{report['id']}",
    ):
        response = await client.get(path)
        assert response.status_code == 404
        assert response.json()["detail"].startswith("No report ")


async def test_marking_investigated_keeps_the_first_time(client):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)
    url = f"/internal/papers/{paper}/reports/{report['id']}"

    first = await client.patch(url, json={"status": "investigated"})
    again = await client.patch(url, json={"status": "investigated"})

    assert first.status_code == again.status_code == 200
    assert first.json()["status"] == "investigated"
    assert first.json()["investigated_at"] is not None
    assert again.json()["investigated_at"] == first.json()["investigated_at"]


@pytest.mark.parametrize(
    ("status", "code"), [("assessed", 400), ("investigating", 400), ("done", 422)]
)
async def test_only_investigated_can_be_set(client, status, code):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)

    response = await client.patch(
        f"/internal/papers/{paper}/reports/{report['id']}", json={"status": status}
    )

    assert response.status_code == code


@pytest.mark.parametrize(
    ("kind", "pdf_status"),
    # with no scripted PDF, a new version or current copy's download finds nothing
    [("notice", "skipped"), ("new_version", "not_found"), ("current_version", "not_found")],
)
async def test_a_new_document_is_the_whole_row(client, kind, pdf_status):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)

    response = await client.post("/internal/documents", json=document(report["id"], kind=kind))

    assert response.status_code == 201
    row = response.json()
    assert row["report_id"] == report["id"]
    assert row["kind"] == kind
    assert row["crossref_record"] == {"title": "Retraction notice"}
    assert row["pdf_status"] == pdf_status
    assert row["file_key"] is None and row["sha256"] is None and row["pdf_source_url"] is None
    assert row["pdf_fetched_at"] is None and row["created_at"] is not None


async def test_the_same_doi_in_a_report_returns_the_stored_row_unchanged(client):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)
    first = (await client.post("/internal/documents", json=document(report["id"]))).json()

    again = await client.post(
        "/internal/documents",
        json=document(report["id"], kind="current_version", text="Different."),
    )

    assert again.status_code == 200
    assert again.json() == first


async def test_the_same_doi_in_another_report_is_a_new_row(client):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    first_report = await open_report(client, paper)
    await add_alert(client, paper, "correction:10.1/c")
    second_report = await open_report(client, paper)

    first = await client.post("/internal/documents", json=document(first_report["id"]))
    second = await client.post("/internal/documents", json=document(second_report["id"]))

    assert first.status_code == second.status_code == 201
    assert first.json()["id"] != second.json()["id"]


async def test_documents_for_an_unknown_report_are_no_report_404(client):
    response = await client.post("/internal/documents", json=document(999))
    assert response.status_code == 404
    assert response.json()["detail"] == "No report 999"


@pytest.mark.parametrize(
    "field", ["report_id", "kind", "doi", "crossref_status", "text_status", "text_truncated"]
)
async def test_a_missing_required_document_field_is_rejected(client, field):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)
    body = document(report["id"])
    del body[field]

    assert (await client.post("/internal/documents", json=body)).status_code == 422


async def test_the_new_endpoints_need_a_service_token(client):
    paper = await add_paper(client)
    user = {
        "Authorization": f"Bearer {jwt.encode({'sub': str(uuid4())}, TEST_JWT_KEY, algorithm='HS256')}"
    }

    assert (await client.post(f"/internal/papers/{paper}/reports", headers=user)).status_code == 403
    assert (
        await client.get(f"/internal/papers/{paper}/reports/1", headers=user)
    ).status_code == 403
    assert (
        await client.post("/internal/documents", json=document(1), headers=user)
    ).status_code == 403
    assert (
        await client.post(
            "/internal/documents", json=document(1), headers={"Authorization": "Bearer nope"}
        )
    ).status_code == 401
    assert (await client.get("/internal/reports/1", headers=user)).status_code == 403
    assert (
        await client.put("/internal/reports/1/evaluation", json=full_evaluation(), headers=user)
    ).status_code == 403
    assert (
        await client.put(
            "/internal/reports/1/evaluation",
            json=full_evaluation(),
            headers={"Authorization": "Bearer nope"},
        )
    ).status_code == 401


async def report_for_new_paper(client) -> dict:
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    return await open_report(client, paper)


@pytest.mark.parametrize("kind", ["new_version", "current_version"])
async def test_a_new_row_downloads_its_scripted_pdf_once(client, kind):
    doi = "10.1016/j.ijantimicag.2020.105949"
    await client.post("/dev/pdfs", json={"doi": doi, "source_url": "https://pmc.example/main.pdf"})
    report = await report_for_new_paper(client)

    row = (
        await client.post("/internal/documents", json=document(report["id"], kind=kind, doi=doi))
    ).json()

    assert row["pdf_status"] == "ok"
    assert row["pdf_source_url"] == "https://pmc.example/main.pdf"
    assert row["file_key"] and row["pdf_fetched_at"]
    assert row["sha256"] == hashlib.sha256(stub_pdf(doi)).hexdigest()
    assert (await client.get("/dev/pdf-downloads")).json() == [doi]


async def test_a_notice_is_never_downloaded(client):
    report = await report_for_new_paper(client)

    await client.post("/internal/documents", json=document(report["id"], kind="notice"))

    assert (await client.get("/dev/pdf-downloads")).json() == []


@pytest.mark.parametrize("scripted", [True, False])
async def test_sending_a_stored_row_again_downloads_nothing(client, scripted):
    doi = "10.1016/j.ijantimicag.2020.105949"
    if scripted:
        await client.post(
            "/dev/pdfs", json={"doi": doi, "source_url": "https://pmc.example/main.pdf"}
        )
    report = await report_for_new_paper(client)
    body = document(report["id"], kind="current_version", doi=doi)

    first = await client.post("/internal/documents", json=body)
    again = await client.post("/internal/documents", json=body)

    assert (first.status_code, again.status_code) == (201, 200)
    assert again.json() == first.json()
    assert (await client.get("/dev/pdf-downloads")).json() == [doi]


async def test_the_stored_pdf_is_read_back_without_downloading(client):
    doi = "10.1016/j.ijantimicag.2020.105949"
    await client.post("/dev/pdfs", json={"doi": doi, "source_url": "https://pmc.example/main.pdf"})
    report = await report_for_new_paper(client)
    row = (
        await client.post(
            "/internal/documents", json=document(report["id"], kind="current_version", doi=doi)
        )
    ).json()

    response = await client.get(f"/internal/documents/{row['id']}/pdf")

    assert response.status_code == 200
    assert response.headers["content-type"] == "application/pdf"
    assert response.content == stub_pdf(doi)
    assert (await client.get("/dev/pdf-downloads")).json() == [doi]


async def test_reading_a_pdf_that_isnt_stored_is_404(client):
    report = await report_for_new_paper(client)
    notice = (await client.post("/internal/documents", json=document(report["id"]))).json()
    not_found = (
        await client.post(
            "/internal/documents", json=document(report["id"], kind="current_version", doi="10.1/x")
        )
    ).json()

    for row in (notice, not_found):
        response = await client.get(f"/internal/documents/{row['id']}/pdf")
        assert response.status_code == 404
        assert response.json()["detail"] == f"Document {row['id']} has no stored PDF"
    unknown = await client.get("/internal/documents/999/pdf")
    assert unknown.status_code == 404
    assert unknown.json()["detail"] == "No document 999"


# GET /internal/reports/{id} and PUT /internal/reports/{id}/evaluation


def full_evaluation(**fields) -> dict:
    body = {
        "change_summary": "The paper was retracted for fabricated data.",
        "change_severity": "high",
        "impact_level": "medium",
        "evaluation": "Your Discussion relies on it.",
        "recommendation": "Replace the citation.",
        "assessment": {"prompt_version": 1, "change": {"severity": "high"}, "impact": None},
    }
    body.update(fields)
    return body


async def investigated_report(client) -> tuple[str, int]:
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)
    marked = await client.patch(
        f"/internal/papers/{paper}/reports/{report['id']}", json={"status": "investigated"}
    )
    assert marked.status_code == 200
    return paper, report["id"]


async def test_reading_by_id_is_the_nested_read(client):
    paper, report_id = await investigated_report(client)
    await client.post("/internal/documents", json=document(report_id))

    flat = await client.get(f"/internal/reports/{report_id}")
    nested = await client.get(f"/internal/papers/{paper}/reports/{report_id}")

    assert flat.status_code == 200
    assert flat.json() == nested.json()
    assert flat.json()["paper_id"] == paper


async def test_reading_an_unknown_report_by_id_is_no_report_404(client):
    response = await client.get("/internal/reports/999")
    assert response.status_code == 404
    assert response.json()["detail"] == "No report 999"


async def test_a_full_evaluation_is_stored_and_the_report_assessed(client):
    paper, report_id = await investigated_report(client)
    body = full_evaluation()

    response = await client.put(f"/internal/reports/{report_id}/evaluation", json=body)

    assert response.status_code == 200
    stored = response.json()
    assert stored["status"] == "assessed"
    assert stored["evaluated_at"] is not None
    for field, value in body.items():
        assert stored[field] == value
    nested = (await client.get(f"/internal/papers/{paper}/reports/{report_id}")).json()
    assert nested["assessment"] == body["assessment"]


async def test_a_change_that_isnt_meaningful_stores_only_the_summary_and_severity(client):
    _, report_id = await investigated_report(client)

    response = await client.put(
        f"/internal/reports/{report_id}/evaluation",
        json={"change_summary": "An affiliation was corrected.", "change_severity": "none"},
    )

    assert response.status_code == 200
    stored = response.json()
    assert stored["status"] == "assessed"
    assert (stored["change_summary"], stored["change_severity"]) == (
        "An affiliation was corrected.",
        "none",
    )
    assert stored["impact_level"] is None
    assert stored["evaluation"] is None and stored["recommendation"] is None


@pytest.mark.parametrize(
    "body",
    [
        {k: v for k, v in full_evaluation().items() if k != "change_summary"},
        full_evaluation(change_summary=" "),
        {k: v for k, v in full_evaluation().items() if k != "change_severity"},
        full_evaluation(change_severity="critical"),
        full_evaluation(impact_level="severe"),
        full_evaluation(impact_level=None),
        full_evaluation(evaluation=None),
        full_evaluation(recommendation="  "),
        {"change_summary": "s", "change_severity": "none", "impact_level": "none"},
        {"change_summary": "s", "change_severity": "none", "evaluation": "e"},
        {"change_summary": "s", "change_severity": "none", "recommendation": "r"},
    ],
)
async def test_a_bad_evaluation_is_rejected_and_changes_nothing(client, body):
    _, report_id = await investigated_report(client)

    response = await client.put(f"/internal/reports/{report_id}/evaluation", json=body)

    assert response.status_code == 422
    report = (await client.get(f"/internal/reports/{report_id}")).json()
    assert report["status"] == "investigated"


async def test_an_investigating_report_is_a_conflict(client):
    paper = await add_paper(client)
    await add_alert(client, paper, "retraction")
    report = await open_report(client, paper)

    response = await client.put(
        f"/internal/reports/{report['id']}/evaluation", json=full_evaluation()
    )

    assert response.status_code == 409
    assert response.json()["detail"] == f"Report {report['id']} is not investigated yet"


async def test_an_assessed_report_keeps_its_first_evaluation(client):
    _, report_id = await investigated_report(client)
    first = (
        await client.put(f"/internal/reports/{report_id}/evaluation", json=full_evaluation())
    ).json()

    again = await client.put(
        f"/internal/reports/{report_id}/evaluation",
        json={"change_summary": "Another.", "change_severity": "none"},
    )

    assert again.status_code == 409
    assert again.json()["detail"] == f"Report {report_id} is already assessed"
    assert (await client.get(f"/internal/reports/{report_id}")).json() == first


async def test_evaluating_an_unknown_report_is_no_report_404(client):
    response = await client.put("/internal/reports/999/evaluation", json=full_evaluation())
    assert response.status_code == 404
    assert response.json()["detail"] == "No report 999"


# GET /internal/papers/{id}/pdf and /research-paper, set with the stub-only /dev helpers


async def test_a_papers_pdf_and_draft_are_served_once_set(client):
    paper = await add_paper(client)
    await client.post(f"/dev/papers/{paper}/pdf", content=b"%PDF-1.7 paper")
    await client.post(f"/dev/papers/{paper}/research-paper", content=b"%PDF-1.7 draft")

    pdf = await client.get(f"/internal/papers/{paper}/pdf")
    draft = await client.get(f"/internal/papers/{paper}/research-paper")

    assert (pdf.status_code, pdf.content) == (200, b"%PDF-1.7 paper")
    assert pdf.headers["content-type"] == "application/pdf"
    assert (draft.status_code, draft.content) == (200, b"%PDF-1.7 draft")


async def test_missing_pdfs_have_storage_managements_404_details(client):
    paper = await add_paper(client)
    missing = str(uuid4())

    pdf = await client.get(f"/internal/papers/{paper}/pdf")
    draft = await client.get(f"/internal/papers/{paper}/research-paper")

    assert (pdf.status_code, pdf.json()["detail"]) == (404, f"Paper {paper} has no stored PDF")
    assert (draft.status_code, draft.json()["detail"]) == (
        404,
        f"No research paper in the project of paper {paper}",
    )
    for path in ("pdf", "research-paper"):
        response = await client.get(f"/internal/papers/{missing}/{path}")
        assert (response.status_code, response.json()["detail"]) == (404, f"No paper {missing}")


async def test_the_pdf_endpoints_need_a_service_token(client):
    paper = await add_paper(client)
    for path in ("pdf", "research-paper"):
        response = await client.get(
            f"/internal/papers/{paper}/{path}", headers={"Authorization": "Bearer nope"}
        )
        assert response.status_code == 401
