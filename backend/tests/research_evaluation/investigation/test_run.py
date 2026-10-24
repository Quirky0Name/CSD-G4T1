"""Investigation after a nudge, end to end: Research Evaluation against the stub Storage
Management, with Crossref and Europe PMC faked (conftest.ExternalApis)."""

from datetime import UTC, datetime
from urllib.parse import unquote
from uuid import UUID

import httpx
import pytest
from research_evaluation_support import notice, nudge
from support import load_fixture, load_xml_fixture

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


async def test_each_document_is_stored_with_its_links_and_only_crossref_and_europe_pmc_are_asked(
    client, sm, external
):
    external.crossref[RETRACTION_NOTICE] = "ijaa_notice"
    paper = await changed_paper(sm, **retraction_and_correction())

    await nudge(client, [paper])

    [report_id] = await report_ids(sm, paper)
    documents = {d["doi"]: d for d in (await read_report(sm, paper, report_id))["documents"]}
    elsevier = [
        item["URL"] for item in load_fixture("crossref", "ijaa_notice")["message"]["link"]
    ]
    # the stub stores links as sent: the notice's from its Crossref record (Europe PMC finds
    # nothing here), none for the others
    assert documents[RETRACTION_NOTICE]["links"] == [
        {"doi": PAPER_DOI, "url": None, "role": "update-to", "source": "crossref"},
        *({"doi": None, "url": url, "role": None, "source": "crossref"} for url in elsevier),
    ]
    assert documents[CORRECTION_NOTICE]["links"] == []
    assert documents[PAPER_DOI]["links"] == []
    # the URLs are stored, never fetched
    assert {request.url.host for request in external.requests} == {
        "api.crossref.org",
        "www.ebi.ac.uk",
    }


async def test_the_stored_row_is_used_even_when_it_differs_from_what_was_fetched(sm, external):
    paper = await sm.add_paper()
    await sm.client.post(
        f"/internal/papers/{paper}/alerts",
        json={
            "change_type": "correction",
            "change_key": f"correction:{CORRECTION_NOTICE}",
            "alert_severity": "medium",
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
                "alert_severity": "high",
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
            "alert_severity": "high",
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
    sm.transport.faults["GET", f"/papers/{broken}/snapshots"] = 500

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


async def test_a_late_retraction_notice_moves_its_alert_into_a_new_report_and_is_fetched(
    client, sm, external
):
    external.crossref[RETRACTION_NOTICE] = "ijaa_notice"
    paper = await changed_paper(sm, is_retracted=True)
    await nudge(client, [paper])
    [first_report] = await report_ids(sm, paper)
    await sm.add_snapshot(
        paper,
        3,
        is_retracted=True,
        crossref_updates=[notice(RETRACTION_NOTICE, "retraction", source="retraction-watch")],
    )

    assert (await nudge(client, [paper])).status_code == 202

    [second_report] = await report_ids(sm, paper)
    assert second_report != first_report
    report = await read_report(sm, paper, second_report)
    assert [a["change_key"] for a in report["alerts"]] == ["retraction"]
    assert [a["notice_doi"] for a in report["alerts"]] == [RETRACTION_NOTICE]
    assert ("notice", RETRACTION_NOTICE) in {(d["kind"], d["doi"]) for d in report["documents"]}
    # the earlier report keeps its documents but no longer lists the alert
    earlier = await read_report(sm, paper, first_report)
    assert earlier["alerts"] == []
    assert [d["kind"] for d in earlier["documents"]] == ["current_version"]


async def test_a_re_sent_notice_opens_no_report(client, sm):
    paper = await changed_paper(
        sm,
        is_retracted=True,
        crossref_updates=[notice(RETRACTION_NOTICE, "retraction", source="retraction-watch")],
    )
    await nudge(client, [paper])

    await nudge(client, [paper])

    assert len(await report_ids(sm, paper)) == 1
    assert sm.calls("POST", f"/papers/{paper}/reports") == 2  # the second answered 204


# following links (each case starts from the paper's alerts, as after a nudge)

LANCET = "10.1016/s0140-6736(20)31180-6"
LANCET_EOC = "10.1016/s0140-6736(20)31290-3"
LANCET_RETRACTION = "10.1016/s0140-6736(20)31324-6"
LANCET_REPUBLICATION = "10.1016/s0140-6736(20)31528-2"
LANCET_COMMENTARY = "10.1016/s0140-6736(20)31174-0"
PLOS_PAPER = "10.1371/journal.pone.0258361"
PLOS_NOTICE = "10.1371/journal.pone.0322065"
F1000_V1 = "10.12688/f1000research.187739.1"
F1000_V2 = "10.12688/f1000research.187739.2"


def alerted_change(
    change_type: ChangeType, notice_doi: str | None, notice_type: str | None = None
) -> Change:
    key = change_type.value if notice_doi is None else f"{change_type.value}:{notice_doi}"
    return Change(
        change_type=change_type,
        change_key=key,
        detected_at=datetime(2026, 9, 20, 12, tzinfo=UTC),
        snapshot_id=2,
        previous_snapshot_id=1,
        notice_doi=notice_doi,
        notice_type=notice_type,
    )


async def investigate_one(sm, external, paper_doi: str, changes: list[Change]) -> list[dict]:
    """Stores an alert per change, investigates the paper and returns its report's documents."""
    paper = await sm.add_paper()
    for change in changes:
        response = await sm.client.post(
            f"/internal/papers/{paper}/alerts",
            json={
                "change_type": change.change_type.value,
                "change_key": change.change_key,
                "alert_severity": "medium",
                "description": "A change.",
                "recommendation": "Read it.",
                "notice_doi": change.notice_doi,
                "detected_at": "2026-09-20T12:00:00Z",
                "snapshot_id": 2,
                "previous_snapshot_id": 1,
            },
        )
        response.raise_for_status()
    [report_id] = await investigate_papers(
        sm.client,
        context(external),
        [PaperChanges(paper_id=paper, paper_doi=paper_doi, changes=changes)],
    )
    return (await read_report(sm, paper, report_id))["documents"]


def kinds(documents: list[dict]) -> dict[str, str]:
    return {d["doi"]: d["kind"] for d in documents}


def crossref_requests(external) -> list[str]:
    """The DOIs asked of Crossref, in order."""
    return [
        unquote(request.url.path.removeprefix("/works/"))
        for request in external.requests
        if request.url.host == "api.crossref.org"
    ]


def notice_text(*mentions: str) -> bytes:
    """An open-access notice's JATS whose text names these DOIs and URLs."""
    text = f"This notice concerns {' and '.join(mentions)}."
    return f"<article><body><p>{text}</p></body></article>".encode()


async def test_an_eoc_reaches_the_later_retraction_through_its_erratum_relation(sm, external):
    # E5: the report holds only the EoC alert; the EoC's record relates it to the retraction
    external.crossref[LANCET_EOC] = "lancet_eoc"

    documents = await investigate_one(
        sm, external, LANCET, [alerted_change(ChangeType.EXPRESSION_OF_CONCERN, LANCET_EOC)]
    )

    assert kinds(documents) == {LANCET_EOC: "notice", LANCET_RETRACTION: "related"}
    related = next(d for d in documents if d["kind"] == "related")
    # Crossref has no record for it here: stored anyway, and never checked against the paper
    assert related["crossref_status"] == "not_found"
    assert related["update_to_includes_paper"] is None
    assert related["pdf_status"] == "not_found"
    assert crossref_requests(external) == [LANCET_EOC, LANCET_RETRACTION]
    # the EoC's links are stored whole, whatever was done with each
    assert {link["doi"] for link in documents[0]["links"] if link["doi"]} == {
        LANCET,
        LANCET_RETRACTION,
    }


async def test_a_plos_retraction_notice_that_links_only_back_reaches_nothing(sm, external):
    # R1: the notice's update-to and its <related-article> (a PMCID, unresolved here) both name
    # the paper or its PMC page; the license and dx.plos.org URLs are never fetched
    external.crossref[PLOS_NOTICE] = "plos_retraction_notice"
    external.full_texts[PLOS_NOTICE] = load_xml_fixture("europepmc", "fulltext_PMC12017829")

    documents = await investigate_one(
        sm, external, PLOS_PAPER, [alerted_change(ChangeType.RETRACTION, PLOS_NOTICE)]
    )

    assert kinds(documents) == {PLOS_NOTICE: "notice", PLOS_PAPER: "current_version"}
    assert sorted(crossref_requests(external)) == sorted([PLOS_NOTICE, PLOS_PAPER])
    assert {request.url.host for request in external.requests} == {
        "api.crossref.org",
        "www.ebi.ac.uk",
    }


async def test_a_notice_about_another_article_keeps_it_in_links_and_doesnt_fetch_it(
    sm, external
):
    # R5: Elsevier's retract-and-republish notice updates the commentary as well as the paper
    external.crossref[LANCET_REPUBLICATION] = "lancet_republication"
    external.crossref[LANCET] = "lancet"

    documents = await investigate_one(
        sm, external, LANCET, [alerted_change(ChangeType.ERRATUM, LANCET_REPUBLICATION)]
    )

    assert kinds(documents) == {LANCET_REPUBLICATION: "notice", LANCET: "current_version"}
    notice_row = next(d for d in documents if d["doi"] == LANCET_REPUBLICATION)
    assert LANCET_COMMENTARY in {link["doi"] for link in notice_row["links"]}
    assert LANCET_COMMENTARY not in crossref_requests(external)
    # the current copy's updated-by list names every Lancet notice: not followed
    assert len(crossref_requests(external)) == 2


async def test_a_new_version_pointing_back_at_the_paper_reaches_nothing(sm, external):
    # C6: v2's has-version and update-to name v1, the paper
    external.crossref[F1000_V2] = "f1000_v2"

    documents = await investigate_one(
        sm,
        external,
        F1000_V1,
        [alerted_change(ChangeType.OTHER, F1000_V2, notice_type="new_version")],
    )

    assert kinds(documents) == {F1000_V2: "new_version"}
    assert crossref_requests(external) == [F1000_V2]


# synthetic records on real DOIs (no real case goes this deep or this wide)
NOTICE = LANCET_EOC
B = LANCET_RETRACTION
C = LANCET_REPUBLICATION
D = "10.1016/s0140-6736(20)31249-6"


def eoc_report(external, **notice_record) -> list[Change]:
    external.records[NOTICE] = {"update-to": [{"DOI": LANCET}], **notice_record}
    return [alerted_change(ChangeType.EXPRESSION_OF_CONCERN, NOTICE)]


def updated_by(*dois: str) -> dict:
    return {"updated-by": [{"DOI": doi} for doi in dois]}


async def test_an_untyped_doi_is_followed_only_when_its_record_names_the_paper(sm, external):
    in_lineage, unrelated = "10.1093/evolut/qpad094", "10.1111/evo.14496"
    changes = eoc_report(external)
    external.full_texts[NOTICE] = notice_text(in_lineage, unrelated)
    external.records[in_lineage] = {"update-to": [{"DOI": LANCET}]}
    external.records[unrelated] = {"update-to": [{"DOI": "10.1/someone-else"}]}

    documents = await investigate_one(sm, external, LANCET, changes)

    assert kinds(documents) == {NOTICE: "notice", in_lineage: "related"}
    # one Crossref request each: the check's record is the related document's record
    assert crossref_requests(external) == [NOTICE, in_lineage, unrelated]
    related = next(d for d in documents if d["doi"] == in_lineage)
    assert related["crossref_status"] == "ok"


async def test_a_check_naming_a_document_already_in_the_report_is_followed(sm, external):
    candidate = "10.1093/evolut/qpad094"
    changes = eoc_report(external)
    external.full_texts[NOTICE] = notice_text(candidate)
    external.records[candidate] = {
        "relation": {"is-related-to": [{"id-type": "doi", "id": NOTICE}]}
    }

    documents = await investigate_one(sm, external, LANCET, changes)

    assert kinds(documents)[candidate] == "related"


async def test_links_are_followed_two_deep_and_no_further(sm, external):
    changes = eoc_report(external, **updated_by(B))
    external.records[B] = updated_by(C)
    external.records[C] = updated_by(D)

    documents = await investigate_one(sm, external, LANCET, changes)

    assert kinds(documents) == {NOTICE: "notice", B: "related", C: "related"}
    assert D not in crossref_requests(external)
    # C is stored with its links, unclassified
    assert next(d for d in documents if d["doi"] == C)["links"][0]["doi"] == D


async def test_a_cycle_fetches_each_doi_once(sm, external):
    changes = eoc_report(external, **updated_by(B))
    external.records[B] = updated_by(NOTICE, LANCET)

    documents = await investigate_one(sm, external, LANCET, changes)

    assert kinds(documents) == {NOTICE: "notice", B: "related"}
    assert crossref_requests(external) == [NOTICE, B]


def many_dois(count: int) -> list[str]:
    return [f"10.5555/candidate.{n}" for n in range(count)]


async def test_at_most_ten_related_documents_are_stored(sm, external):
    changes = eoc_report(external, **updated_by(*many_dois(12)))

    documents = await investigate_one(sm, external, LANCET, changes)

    assert [d["doi"] for d in documents if d["kind"] == "related"] == many_dois(12)[:10]
    assert crossref_requests(external) == [NOTICE, *many_dois(12)[:10]]


async def test_at_most_twenty_untyped_dois_are_checked(sm, external):
    changes = eoc_report(external)
    external.full_texts[NOTICE] = notice_text(*many_dois(25))

    documents = await investigate_one(sm, external, LANCET, changes)

    assert kinds(documents) == {NOTICE: "notice"}
    assert crossref_requests(external) == [NOTICE, *many_dois(25)[:20]]
    # all 25 stay in the notice's links
    assert len([link for link in documents[0]["links"] if link["source"] == "europepmc_text"]) == 25


async def test_url_links_never_count_and_are_never_fetched(sm, external):
    urls = [f"https://data.example/{n}" for n in range(5)]
    changes = eoc_report(external, **updated_by(*many_dois(10)), link=[{"URL": u} for u in urls])

    documents = await investigate_one(sm, external, LANCET, changes)

    assert len([d for d in documents if d["kind"] == "related"]) == 10
    assert {request.url.host for request in external.requests} == {
        "api.crossref.org",
        "www.ebi.ac.uk",
    }
    assert [link["url"] for link in documents[0]["links"] if link["url"]] == urls


async def test_the_current_copys_links_are_stored_but_not_followed(sm, external):
    external.records[LANCET] = updated_by(B)

    documents = await investigate_one(
        sm, external, LANCET, [alerted_change(ChangeType.RETRACTION, None)]
    )

    assert kinds(documents) == {LANCET: "current_version"}
    assert documents[0]["links"][0]["doi"] == B
    assert crossref_requests(external) == [LANCET]


async def test_a_failed_related_store_fails_the_report(sm, external):
    changes = eoc_report(external, **updated_by(B))
    stored = sm.transport.handle_async_request

    async def related_fails(request):
        if request.url.path == "/internal/documents" and b'"related"' in request.content:
            return httpx.Response(500, json={"detail": "injected"}, request=request)
        return await stored(request)

    sm.transport.handle_async_request = related_fails

    with pytest.raises(ValueError):  # no report finished
        await investigate_one(sm, external, LANCET, changes)
