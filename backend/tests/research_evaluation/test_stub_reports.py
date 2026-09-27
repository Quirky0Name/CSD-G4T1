"""The stub Storage Management's reports and documents behave like the real ones
(docs/EVALUATION-INVESTIGATION.md, S1), so Research Evaluation can be tested against it."""

from uuid import uuid4

import httpx
import jwt
import pytest
from support import TEST_JWT_KEY

from common.service_token import mint_service_token
from dev.stub_storage import create_app


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
    [("notice", "skipped"), ("new_version", "pending"), ("current_version", "pending")],
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
