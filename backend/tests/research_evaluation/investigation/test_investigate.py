from datetime import UTC, datetime

import httpx
import pytest
from support import load_fixture, load_xml_fixture

from research_evaluation.changes import Change, ChangeType, Snapshot
from research_evaluation.investigation import investigate
from research_evaluation.investigation.europepmc import TextStatus
from research_evaluation.investigation.http import FetchStatus
from research_evaluation.investigation.investigate import (
    DocumentKind,
    PlannedDocument,
    fetch_document,
    plan_documents,
)

LANCET = "10.1016/s0140-6736(20)31180-6"
LANCET_RETRACTION = "10.1016/s0140-6736(20)31324-6"
LANCET_EOC = "10.1016/s0140-6736(20)31290-3"
LANCET_CORRECTION = "10.1016/s0140-6736(20)31249-6"
LANCET_COMMENTARY = "10.1016/s0140-6736(20)31174-0"
IJAA = "10.1016/j.ijantimicag.2020.105949"
IJAA_NOTICE = "10.1016/j.ijantimicag.2024.107416"
F1000_V1 = "10.12688/f1000research.187739.1"
F1000_V2 = "10.12688/f1000research.187739.2"
CROSSREF = "https://api.crossref.org/works/"
EUROPE_PMC_SEARCH = "https://www.ebi.ac.uk/europepmc/webservices/rest/search"


def change(
    change_type: ChangeType, key: str, notice_doi: str | None = None, notice_type: str | None = None
):
    return Change(
        change_type=change_type,
        change_key=key,
        detected_at=datetime(2026, 9, 20, 12, tzinfo=UTC),
        snapshot_id=2,
        previous_snapshot_id=1,
        notice_doi=notice_doi,
        notice_type=notice_type,
    )


def retraction(notice_doi: str | None = LANCET_RETRACTION) -> Change:
    return change(
        ChangeType.RETRACTION, "retraction", notice_doi, "retraction" if notice_doi else None
    )


def eoc(notice_doi: str = LANCET_EOC) -> Change:
    return change(
        ChangeType.EXPRESSION_OF_CONCERN, f"expression_of_concern:{notice_doi}", notice_doi
    )


def correction(notice_doi: str = LANCET_CORRECTION) -> Change:
    return change(ChangeType.CORRECTION, f"correction:{notice_doi}", notice_doi)


def plan(changes: list[Change], paper_doi: str | None = LANCET, keys: set[str] | None = None):
    keys = {c.change_key for c in changes} if keys is None else keys
    return [(d.kind, d.doi) for d in plan_documents(changes, keys, paper_doi)]


NOTICE, NEW_VERSION, CURRENT = (
    DocumentKind.NOTICE,
    DocumentKind.NEW_VERSION,
    DocumentKind.CURRENT_VERSION,
)


@pytest.mark.parametrize(
    ("changes", "expected"),
    [
        ([retraction()], [(NOTICE, LANCET_RETRACTION), (CURRENT, LANCET)]),
        ([retraction(notice_doi=None)], [(CURRENT, LANCET)]),
        ([eoc()], [(NOTICE, LANCET_EOC)]),
        ([correction()], [(NOTICE, LANCET_CORRECTION), (CURRENT, LANCET)]),
        (
            [change(ChangeType.ERRATUM, "erratum:10.1/e", "10.1/e")],
            [(NOTICE, "10.1/e"), (CURRENT, LANCET)],
        ),
        (
            [change(ChangeType.OTHER, f"other:new_version:{F1000_V2}", F1000_V2, "new_version")],
            [(NEW_VERSION, F1000_V2)],
        ),
        (
            [change(ChangeType.OTHER, "other:new_edition:10.1/ed2", "10.1/ed2", "new_edition")],
            [(NEW_VERSION, "10.1/ed2")],
        ),
        (
            [change(ChangeType.OTHER, "other:withdrawal:10.1/w", "10.1/w", "withdrawal")],
            [(NOTICE, "10.1/w"), (CURRENT, LANCET)],
        ),
        ([change(ChangeType.DOAJ_DELISTING, "doaj_delisting:2")], []),
    ],
    ids=[
        "retraction with notice",
        "retraction from the flag only",
        "expression of concern",
        "correction",
        "erratum",
        "new version",
        "new edition",
        "other type",
        "doaj delisting",
    ],
)
def test_each_kind_of_change_plans_its_documents(changes, expected):
    assert plan(changes) == expected


def test_the_lancets_first_report_plans_three_notices_and_one_current_copy():
    assert plan([retraction(), eoc(), correction()]) == [
        (NOTICE, LANCET_RETRACTION),
        (NOTICE, LANCET_EOC),
        (NOTICE, LANCET_CORRECTION),
        (CURRENT, LANCET),
    ]


def test_a_report_of_only_an_eoc_plans_no_current_copy():
    assert CURRENT not in [kind for kind, _ in plan([eoc()])]


def test_a_flag_and_its_later_notice_in_one_window_plan_the_notice():
    # both changes have the key "retraction"; the report has one alert for them
    assert plan([retraction(notice_doi=None), retraction()]) == [
        (NOTICE, LANCET_RETRACTION),
        (CURRENT, LANCET),
    ]


def test_ijaas_self_referencing_entry_plans_no_notice_only_the_current_copy():
    # IJAA's 2020 publisher "retraction" entry points at the paper itself
    assert plan([retraction(notice_doi=IJAA)], paper_doi=IJAA) == [(CURRENT, IJAA)]


def test_a_self_referencing_new_edition_is_the_current_copy():
    new_edition = change(ChangeType.OTHER, f"other:new_edition:{LANCET}", LANCET, "new_edition")
    assert plan([new_edition]) == [(CURRENT, LANCET)]


def test_changes_whose_key_isnt_in_the_report_plan_nothing():
    assert plan([retraction(), eoc()], keys={"correction:10.1/other"}) == []
    assert plan([retraction(), eoc()], keys={eoc().change_key}) == [(NOTICE, LANCET_EOC)]


def test_no_paper_doi_means_no_current_copy():
    assert plan([retraction(), correction()], paper_doi=None) == [
        (NOTICE, LANCET_RETRACTION),
        (NOTICE, LANCET_CORRECTION),
    ]


def test_each_doi_is_planned_once():
    same_notice = change(ChangeType.CORRECTION, "correction:10.1/x", "10.1/X")
    also = change(ChangeType.ERRATUM, "erratum:10.1/x", "10.1/x")
    assert plan([same_notice, also]) == [(NOTICE, "10.1/x"), (CURRENT, LANCET)]


def test_the_snapshot_keeps_the_papers_doi():
    snapshot = Snapshot.model_validate(
        {
            "snapshot_id": 1,
            "fetched_at": "2026-09-20T12:00:00Z",
            "doi": LANCET,
            "source_status": {"crossref": "ok", "openalex": "ok"},
        }
    )
    assert snapshot.doi == LANCET


@pytest.fixture
async def http():
    async with httpx.AsyncClient() as client:
        yield client


def crossref_returns(respx_mock, doi: str, fixture: str):
    encoded = doi.replace("(", "%28").replace(")", "%29")
    return respx_mock.get(CROSSREF + encoded).respond(200, json=load_fixture("crossref", fixture))


async def test_ijaas_notice_names_the_paper(respx_mock, http):
    crossref_returns(respx_mock, IJAA_NOTICE, "ijaa_notice")
    respx_mock.get(url__startswith=EUROPE_PMC_SEARCH).respond(
        200, json=load_fixture("europepmc", "search_ijaa_notice")
    )

    document = await fetch_document(
        http, PlannedDocument(kind=NOTICE, doi=IJAA_NOTICE), IJAA, mailto=""
    )

    assert document.crossref_status is FetchStatus.OK
    assert document.crossref_record["title"].startswith("Retraction notice to")
    assert document.update_to_includes_paper is True
    assert document.text_status is TextStatus.NOT_OPEN_ACCESS
    assert document.text is None and document.text_truncated is False


async def test_a_notice_checked_against_a_paper_it_doesnt_name_is_false(respx_mock, http):
    crossref_returns(respx_mock, IJAA_NOTICE, "ijaa_notice")
    respx_mock.get(url__startswith=EUROPE_PMC_SEARCH).respond(
        200, json=load_fixture("europepmc", "search_no_match")
    )

    document = await fetch_document(
        http, PlannedDocument(kind=NOTICE, doi=IJAA_NOTICE), LANCET, mailto=""
    )

    assert document.update_to_includes_paper is False


async def test_the_lancet_commentarys_record_names_the_lancet_paper(respx_mock, http):
    # Elsevier's entry on the Lancet paper points at this retracted commentary (R5); the
    # commentary's own record lists the Lancet paper in update-to, so the flag is true here and
    # only the title ("RETRACTED: <another article>") shows it isn't the paper's notice
    crossref_returns(respx_mock, LANCET_COMMENTARY, "lancet_commentary")
    respx_mock.get(url__startswith=EUROPE_PMC_SEARCH).respond(
        200, json=load_fixture("europepmc", "search_no_match")
    )

    document = await fetch_document(
        http, PlannedDocument(kind=NOTICE, doi=LANCET_COMMENTARY), LANCET, mailto=""
    )

    assert document.update_to_includes_paper is True
    assert document.crossref_record["title"].startswith(
        "RETRACTED: Chloroquine or hydroxychloroquine"
    )
    assert document.text_status is TextStatus.NOT_INDEXED


async def test_only_a_notice_gets_update_to_includes_paper(respx_mock, http):
    crossref_returns(respx_mock, F1000_V2, "f1000_v2")
    respx_mock.get(url__startswith=EUROPE_PMC_SEARCH).respond(
        200, json=load_fixture("europepmc", "search_no_match")
    )

    document = await fetch_document(
        http, PlannedDocument(kind=NEW_VERSION, doi=F1000_V2), F1000_V1, mailto=""
    )

    assert document.crossref_status is FetchStatus.OK
    assert document.update_to_includes_paper is None


async def test_a_crossref_failure_still_fetches_the_text(respx_mock, http):
    respx_mock.get(url__startswith=CROSSREF).respond(500)
    respx_mock.get(url__startswith=EUROPE_PMC_SEARCH).respond(
        200, json=load_fixture("europepmc", "search_author_correction")
    )
    respx_mock.get(
        url__startswith="https://www.ebi.ac.uk/europepmc/webservices/rest/PMC11906582"
    ).respond(200, content=load_xml_fixture("europepmc", "fulltext_PMC11906582"))

    document = await fetch_document(
        http, PlannedDocument(kind=NOTICE, doi="10.1038/s41598-025-93099-x"), "10.1/p", mailto=""
    )

    assert document.crossref_status is FetchStatus.ERROR
    assert document.crossref_record is None
    assert document.update_to_includes_paper is None
    assert document.text_status is TextStatus.OK
    assert "The original Article has been corrected" in document.text


async def test_a_europe_pmc_failure_still_keeps_the_record(respx_mock, http):
    crossref_returns(respx_mock, IJAA_NOTICE, "ijaa_notice")
    respx_mock.get(url__startswith=EUROPE_PMC_SEARCH).respond(503)

    document = await fetch_document(
        http, PlannedDocument(kind=NOTICE, doi=IJAA_NOTICE), IJAA, mailto=""
    )

    assert document.crossref_status is FetchStatus.OK
    assert document.text_status is TextStatus.ERROR


async def test_a_total_outage_gives_statuses_not_an_exception(respx_mock, http):
    respx_mock.route().mock(side_effect=httpx.ConnectError("offline"))

    document = await fetch_document(
        http, PlannedDocument(kind=CURRENT, doi=LANCET), LANCET, mailto=""
    )

    assert document.crossref_status is FetchStatus.ERROR
    assert document.text_status is TextStatus.ERROR
    assert document.crossref_record is None and document.text is None


async def test_a_fetcher_that_raises_anyway_counts_as_that_source_failing(http, monkeypatch):
    async def boom(*args, **kwargs):
        raise RecursionError("too deep")

    async def text_ok(*args, **kwargs):
        return investigate.DocumentText(status=TextStatus.NOT_INDEXED)

    monkeypatch.setattr(investigate, "fetch_record", boom)
    monkeypatch.setattr(investigate, "fetch_text", text_ok)

    document = await fetch_document(
        http, PlannedDocument(kind=NOTICE, doi=IJAA_NOTICE), IJAA, mailto=""
    )

    assert document.crossref_status is FetchStatus.ERROR
    assert document.text_status is TextStatus.NOT_INDEXED


async def test_the_body_is_what_storage_management_takes(respx_mock, http):
    crossref_returns(respx_mock, IJAA_NOTICE, "ijaa_notice")
    respx_mock.get(url__startswith=EUROPE_PMC_SEARCH).respond(
        200, json=load_fixture("europepmc", "search_ijaa_notice")
    )
    document = await fetch_document(
        http, PlannedDocument(kind=NOTICE, doi=IJAA_NOTICE), IJAA, mailto=""
    )

    body = document.body(report_id=3)

    assert body == {
        "report_id": 3,
        "kind": "notice",
        "doi": IJAA_NOTICE,
        "crossref_status": "ok",
        "crossref_record": document.crossref_record,
        "update_to_includes_paper": True,
        "text_status": "not_open_access",
        "text": None,
        "text_truncated": False,
    }
