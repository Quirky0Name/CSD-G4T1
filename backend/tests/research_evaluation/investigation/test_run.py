"""Investigation after a nudge, end to end: Research Evaluation against the stub Storage
Management, with Crossref and Europe PMC faked (conftest.ExternalApis)."""

from datetime import UTC, datetime
from uuid import UUID

import httpx
import pytest
from research_evaluation_support import notice, nudge

from research_evaluation.changes import Change, ChangeType
from research_evaluation.investigation.run import (
    InvestigationContext,
    PaperChanges,
    investigate_papers,
    investigate_report,
)
from research_evaluation.storage import OpenedReport

PAPER_DOI = "10.1016/j.ijantimicag.2020.105949"  # every test snapshot's DOI (snapshot_dict)
RETRACTION_NOTICE = "10.1016/j.ijantimicag.2024.107416"
CORRECTION_NOTICE = "10.1/correction"


async def changed_paper(sm, **fields) -> UUID:
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, **fields)
    return paper


def retraction_and_correction() -> dict:
    return {
        "is_retracted": True,
        "crossref_updates": [
            notice(RETRACTION_NOTICE, "retraction", source="retraction-watch"),
            notice(CORRECTION_NOTICE, "correction"),
        ],
    }


async def report_ids(sm, paper: UUID) -> set:
    return {alert["report_id"] for alert in await sm.alerts(paper)}


async def read_report(sm, paper: UUID, report_id: int) -> dict:
    response = await sm.client.get(f"/internal/papers/{paper}/reports/{report_id}")
    response.raise_for_status()
    return response.json()


def context(external, pdf_timeout: float = 120) -> InvestigationContext:
    return InvestigationContext(
        external=external.client, crossref_mailto="", pdf_timeout=pdf_timeout
    )


async def test_a_nudge_with_new_alerts_gives_one_investigated_report_with_its_documents(
    client, sm, external
):
    external.crossref[RETRACTION_NOTICE] = "ijaa_notice"
    await sm.client.post(
        "/dev/pdfs", json={"doi": PAPER_DOI, "source_url": "https://pmc.example/main.pdf"}
    )
    paper = await changed_paper(sm, **retraction_and_correction())

    response = await nudge(client, [paper])

    assert response.status_code == 202
    assert response.json()["alerts_created"] == 2
    [report_id] = await report_ids(sm, paper)
    report = await read_report(sm, paper, report_id)
    assert report["status"] == "investigated"
    assert report["investigated_at"] is not None
    assert sorted(a["change_key"] for a in report["alerts"]) == [
        f"correction:{CORRECTION_NOTICE}",
        "retraction",
    ]
    documents = {d["doi"]: d for d in report["documents"]}
    assert set(documents) == {RETRACTION_NOTICE, CORRECTION_NOTICE, PAPER_DOI}
    assert sm.calls("POST", "/internal/documents") == 3
    assert documents[RETRACTION_NOTICE]["kind"] == "notice"
    assert documents[RETRACTION_NOTICE]["crossref_status"] == "ok"
    assert documents[RETRACTION_NOTICE]["crossref_record"]["title"].startswith(
        "Retraction notice to"
    )
    assert documents[RETRACTION_NOTICE]["update_to_includes_paper"] is True
    assert documents[CORRECTION_NOTICE]["crossref_status"] == "not_found"
    assert documents[PAPER_DOI]["kind"] == "current_version"
    assert documents[PAPER_DOI]["pdf_status"] == "ok"


async def test_the_stored_row_is_used_even_when_it_differs_from_what_was_fetched(sm, external):
    paper = await sm.add_paper()
    await sm.client.post(
        f"/internal/papers/{paper}/alerts",
        json={
            "change_type": "correction",
            "change_key": f"correction:{CORRECTION_NOTICE}",
            "severity": "medium",
            "description": "A correction.",
            "recommendation": "Read it.",
            "notice_doi": CORRECTION_NOTICE,
            "detected_at": "2026-09-20T12:00:00Z",
            "snapshot_id": 2,
            "previous_snapshot_id": 1,
        },
    )
    opened = (await sm.client.post(f"/internal/papers/{paper}/reports")).json()
    stored_earlier = {
        "report_id": opened["id"],
        "kind": "notice",
        "doi": CORRECTION_NOTICE,
        "crossref_status": "error",
        "crossref_record": None,
        "update_to_includes_paper": None,
        "text_status": "ok",
        "text": "Stored earlier.",
        "text_truncated": False,
    }
    await sm.client.post("/internal/documents", json=stored_earlier)
    change = Change(
        change_type=ChangeType.CORRECTION,
        change_key=f"correction:{CORRECTION_NOTICE}",
        detected_at=datetime(2026, 9, 20, 12, tzinfo=UTC),
        snapshot_id=2,
        previous_snapshot_id=1,
        notice_doi=CORRECTION_NOTICE,
    )

    rows = await investigate_report(
        sm.client,
        context(external),
        PaperChanges(paper_id=paper, paper_doi=PAPER_DOI, changes=[change]),
        OpenedReport.model_validate(opened),
    )

    [notice_row] = [row for row in rows if row["doi"] == CORRECTION_NOTICE]
    assert notice_row["text"] == "Stored earlier."
    assert notice_row["crossref_status"] == "error"
    report = await read_report(sm, paper, opened["id"])
    assert [d["text"] for d in report["documents"] if d["doi"] == CORRECTION_NOTICE] == [
        "Stored earlier."
    ]


async def test_a_re_nudge_with_nothing_new_opens_no_report_and_calls_nothing_outside(
    client, sm, external
):
    paper = await changed_paper(sm, **retraction_and_correction())
    await nudge(client, [paper])
    external_calls = len(external.requests)
    documents_stored = sm.calls("POST", "/internal/documents")

    assert (await nudge(client, [paper])).status_code == 202

    assert sm.calls("POST", f"/papers/{paper}/reports") == 2  # the second answered 204
    assert len(await report_ids(sm, paper)) == 1
    assert len(external.requests) == external_calls
    assert sm.calls("POST", "/internal/documents") == documents_stored


async def test_alerts_whose_report_never_opened_join_the_next_report(client, sm):
    paper = await changed_paper(sm, is_retracted=True)
    sm.transport.faults["/reports"] = 500

    assert (await nudge(client, [paper])).status_code == 202
    assert await report_ids(sm, paper) == {None}

    del sm.transport.faults["/reports"]
    await sm.add_snapshot(
        paper, 3, is_retracted=True, crossref_updates=[notice(CORRECTION_NOTICE, "correction")]
    )
    assert (await nudge(client, [paper])).status_code == 202

    [report_id] = await report_ids(sm, paper)
    report = await read_report(sm, paper, report_id)
    assert sorted(a["change_key"] for a in report["alerts"]) == [
        f"correction:{CORRECTION_NOTICE}",
        "retraction",
    ]
    assert report["status"] == "investigated"


async def test_a_left_over_alert_gets_its_documents_from_changes_already_stored(client, sm):
    # the retraction's alert is stored on the first nudge, but its report never opens; the
    # second nudge finds nothing new, so the key check skips every change, yet investigation
    # still plans from all of them and fetches the notice and the current copy
    paper = await changed_paper(
        sm,
        is_retracted=True,
        crossref_updates=[notice(RETRACTION_NOTICE, "retraction", source="retraction-watch")],
    )
    sm.transport.faults["/reports"] = 500
    assert (await nudge(client, [paper])).status_code == 202
    del sm.transport.faults["/reports"]

    response = await nudge(client, [paper])

    assert response.json()["alerts_created"] == 0
    [report_id] = await report_ids(sm, paper)
    report = await read_report(sm, paper, report_id)
    assert report["status"] == "investigated"
    assert {(d["kind"], d["doi"]) for d in report["documents"]} == {
        ("notice", RETRACTION_NOTICE),
        ("current_version", PAPER_DOI),
    }


async def test_a_failure_midway_leaves_the_report_investigating_and_the_next_nudge_leaves_it(
    client, sm
):
    paper = await changed_paper(sm, **retraction_and_correction())
    sm.transport.faults["/internal/documents"] = 500

    assert (await nudge(client, [paper])).status_code == 202

    [report_id] = await report_ids(sm, paper)
    assert (await read_report(sm, paper, report_id))["status"] == "investigating"

    del sm.transport.faults["/internal/documents"]
    documents_tried = sm.calls("POST", "/internal/documents")
    assert (await nudge(client, [paper])).status_code == 202

    report = await read_report(sm, paper, report_id)
    assert report["status"] == "investigating"
    assert report["documents"] == []
    assert sm.calls("POST", "/internal/documents") == documents_tried


async def test_the_returned_ids_are_exactly_the_reports_finished_in_this_run(sm, external, caplog):
    finished = await changed_paper(sm)
    failing = await changed_paper(sm)
    nothing_new = await changed_paper(sm)
    for paper in (finished, failing):
        await sm.client.post(
            f"/internal/papers/{paper}/alerts",
            json={
                "change_type": "retraction",
                "change_key": "retraction",
                "severity": "high",
                "description": "Retracted.",
                "recommendation": "Stop citing it.",
                "notice_doi": None,
                "detected_at": "2026-09-20T12:00:00Z",
                "snapshot_id": 2,
                "previous_snapshot_id": 1,
            },
        )
    sm.transport.faults[f"/papers/{failing}/reports"] = 503
    flag = Change(
        change_type=ChangeType.RETRACTION,
        change_key="retraction",
        detected_at=datetime(2026, 9, 20, 12, tzinfo=UTC),
        snapshot_id=2,
        previous_snapshot_id=1,
    )
    papers = [
        PaperChanges(paper_id=p, paper_doi=PAPER_DOI, changes=[flag])
        for p in (finished, failing, nothing_new)
    ]

    ids = await investigate_papers(sm.client, context(external), papers)

    [finished_report] = await report_ids(sm, finished)
    assert ids == [finished_report]
    assert f"investigating paper {failing}" in caplog.text


@pytest.mark.parametrize("failing", ["/internal/documents", "/reports/1"], ids=["store", "mark"])
async def test_a_report_that_fails_midway_returns_no_id_and_the_log_names_it(
    sm, external, caplog, failing
):
    paper = await changed_paper(sm)
    await sm.client.post(
        f"/internal/papers/{paper}/alerts",
        json={
            "change_type": "retraction",
            "change_key": "retraction",
            "severity": "high",
            "description": "Retracted.",
            "recommendation": "Stop citing it.",
            "notice_doi": None,
            "detected_at": "2026-09-20T12:00:00Z",
            "snapshot_id": 2,
            "previous_snapshot_id": 1,
        },
    )
    sm.transport.faults[failing] = 500
    flag = Change(
        change_type=ChangeType.RETRACTION,
        change_key="retraction",
        detected_at=datetime(2026, 9, 20, 12, tzinfo=UTC),
        snapshot_id=2,
        previous_snapshot_id=1,
    )

    ids = await investigate_papers(
        sm.client,
        context(external),
        [PaperChanges(paper_id=paper, paper_doi=PAPER_DOI, changes=[flag])],
    )

    assert ids == []
    [report_id] = await report_ids(sm, paper)
    assert report_id == 1
    assert f"investigating paper {paper} (report 1) failed" in caplog.text
    assert "Storage Management answered 500" in caplog.text
    del sm.transport.faults[failing]
    assert (await read_report(sm, paper, report_id))["status"] == "investigating"


async def test_a_paper_that_failed_evaluation_still_lets_the_others_be_investigated(client, sm):
    good = await changed_paper(sm, is_retracted=True)
    broken = await changed_paper(sm, is_retracted=True)
    sm.transport.faults[f"/papers/{broken}/background-info/history"] = 500

    response = await nudge(client, [good, broken])

    assert response.status_code == 503
    [report_id] = await report_ids(sm, good)
    assert (await read_report(sm, good, report_id))["status"] == "investigated"
    assert sm.calls("POST", f"/papers/{broken}/reports") == 0


@pytest.mark.parametrize(
    "failure",
    [500, httpx.ConnectError("offline"), httpx.ReadTimeout("slow")],
    ids=["500", "offline", "timeout"],
)
async def test_crossref_and_europe_pmc_failing_changes_nothing_in_the_reply(
    client, sm, external, failure
):
    external.failure = failure
    paper = await changed_paper(sm, **retraction_and_correction())

    response = await nudge(client, [paper])

    assert response.status_code == 202
    assert response.json() == {"alerts_created": 2, "skipped_paper_ids": [], "failed_paper_ids": []}
    assert len(await sm.alerts(paper)) == 2
    [report_id] = await report_ids(sm, paper)
    report = await read_report(sm, paper, report_id)
    assert report["status"] == "investigated"
    assert {d["crossref_status"] for d in report["documents"]} == {"error"}
    assert {d["text_status"] for d in report["documents"]} == {"error"}


@pytest.mark.parametrize(
    ("suffix", "fault"),
    [
        ("/reports", 500),
        ("/reports", httpx.ConnectError("down")),
        ("/internal/documents", 503),
        ("/internal/documents", httpx.ReadTimeout("slow")),
    ],
    ids=["open 500", "open unreachable", "store 503", "store timeout"],
)
async def test_storage_management_failing_during_investigation_changes_nothing_in_the_reply(
    client, sm, suffix, fault
):
    sm.transport.faults[suffix] = fault
    paper = await changed_paper(sm, **retraction_and_correction())

    response = await nudge(client, [paper])

    assert response.status_code == 202
    assert response.json() == {"alerts_created": 2, "skipped_paper_ids": [], "failed_paper_ids": []}
    assert len(await sm.alerts(paper)) == 2


async def test_only_storage_management_requests_carry_the_token_and_documents_get_the_long_timeout(
    client, sm, external, settings
):
    external.crossref[RETRACTION_NOTICE] = "ijaa_notice"
    paper = await changed_paper(sm, **retraction_and_correction())

    await nudge(client, [paper])

    assert external.requests
    assert all("authorization" not in request.headers for request in external.requests)
    assert {request.url.host for request in external.requests} <= {
        "api.crossref.org",
        "www.ebi.ac.uk",
    }
    assert all("authorization" in request.headers for request in sm.transport.sent)
    documents = [r for r in sm.transport.sent if r.url.path == "/internal/documents"]
    assert documents
    assert {r.extensions["timeout"]["read"] for r in documents} == {
        settings.investigation_pdf_timeout_seconds
    }
    # every other Storage Management call keeps the client's own timeout
    others = [r for r in sm.transport.sent if r.url.path != "/internal/documents"]
    assert {r.extensions["timeout"]["read"] for r in others} == {sm.client.timeout.read}
    assert sm.client.timeout.read != settings.investigation_pdf_timeout_seconds
