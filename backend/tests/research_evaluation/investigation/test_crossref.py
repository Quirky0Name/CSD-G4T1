import httpx
import pytest
from support import load_fixture

from research_evaluation.investigation.crossref import CrossrefRecord, fetch_record
from research_evaluation.investigation.http import FetchStatus
from research_evaluation.investigation.links import LinkSource

IJAA_NOTICE = "10.1016/j.ijantimicag.2024.107416"
IJAA_PAPER = "10.1016/j.ijantimicag.2020.105949"
LANCET_PAPER = "10.1016/s0140-6736(20)31180-6"
LANCET_COMMENTARY = "10.1016/s0140-6736(20)31174-0"
REPUBLICATION = "10.1016/s0140-6736(20)31528-2"
REPUBLICATION_URL = "https://api.crossref.org/works/10.1016/s0140-6736%2820%2931528-2"
F1000_V1 = "10.12688/f1000research.187739.1"
F1000_V2 = "10.12688/f1000research.187739.2"
LANCET_EOC = "10.1016/s0140-6736(20)31290-3"
LANCET_RETRACTION = "10.1016/s0140-6736(20)31324-6"
PLOS_NOTICE = "10.1371/journal.pone.0322065"
PLOS_PAPER = "10.1371/journal.pone.0258361"


def url(doi: str) -> str:
    return f"https://api.crossref.org/works/{doi}"


@pytest.fixture
async def http():
    async with httpx.AsyncClient() as client:
        yield client


async def test_the_ijaa_notice_names_the_paper_it_retracts(respx_mock, http):
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=load_fixture("crossref", "ijaa_notice"))

    record = await fetch_record(http, IJAA_NOTICE, mailto="")

    assert isinstance(record, CrossrefRecord)
    assert record.doi == IJAA_NOTICE
    assert record.title.startswith("Retraction notice to")
    assert record.journal == "International Journal of Antimicrobial Agents"
    assert record.published == "2025-01"
    assert [(u.doi, u.type, u.source) for u in record.update_to] == [
        (IJAA_PAPER, "retraction", "retraction-watch"),
        (IJAA_PAPER, "erratum", "publisher"),
    ]
    assert record.update_to[0].date == "2024-12-16"
    assert record.updates(IJAA_PAPER)
    assert record.updates(IJAA_PAPER.upper())  # compared normalised


async def test_a_retract_and_republish_notice_names_the_commentary_and_the_lancet_paper(
    respx_mock, http
):
    # Elsevier's record lists the commentary it replaces, and the Lancet paper as an erratum
    respx_mock.get(REPUBLICATION_URL).respond(
        200, json=load_fixture("crossref", "lancet_republication")
    )

    record = await fetch_record(http, REPUBLICATION, mailto="")

    assert record.title.startswith("Retraction and republication")
    assert [(u.doi, u.type) for u in record.update_to] == [
        (LANCET_COMMENTARY, "retraction"),
        (LANCET_COMMENTARY, "erratum"),
        (LANCET_PAPER, "erratum"),
    ]
    assert record.updates(LANCET_COMMENTARY)
    assert not record.updates("10.1016/j.ijantimicag.2020.105949")


async def test_the_lancet_eoc_keeps_its_relations(respx_mock, http):
    respx_mock.get("https://api.crossref.org/works/10.1016/s0140-6736%2820%2931290-3").respond(
        200, json=load_fixture("crossref", "lancet_eoc")
    )

    record = await fetch_record(http, "10.1016/s0140-6736(20)31290-3", mailto="")

    assert record.title.startswith("Expression of concern")
    assert [(u.doi, u.type) for u in record.update_to] == [(LANCET_PAPER, "expression_of_concern")]
    assert set(record.relation) == {"erratum", "retraction"}
    assert record.published == "2020-06"


async def test_a_new_version_points_back_at_the_old_one(respx_mock, http):
    respx_mock.get(url(F1000_V2)).respond(200, json=load_fixture("crossref", "f1000_v2"))

    record = await fetch_record(http, F1000_V2, mailto="")

    assert [(u.doi, u.type) for u in record.update_to] == [
        ("10.12688/f1000research.187739.1", "new_version")
    ]
    assert "has-version" in record.relation
    assert all(doi.startswith("10.") for dois in record.relation.values() for doi in dois)
    assert record.published == "2026-09-25"


async def test_the_record_serialises_for_storage_management(respx_mock, http):
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=load_fixture("crossref", "ijaa_notice"))

    record = await fetch_record(http, IJAA_NOTICE, mailto="")

    dumped = record.model_dump(mode="json")
    # the links go out as the document's links, not inside its record
    assert set(dumped) == {
        "doi",
        "title",
        "published",
        "journal",
        "update_to",
        "updated_by",
        "relation",
    }
    assert CrossrefRecord.model_validate(dumped) == record.model_copy(update={"links": []})


async def test_404_is_not_found(respx_mock, http):
    respx_mock.get(url(IJAA_NOTICE)).respond(404, text="Resource not found.")

    assert await fetch_record(http, IJAA_NOTICE, mailto="") is FetchStatus.NOT_FOUND


@pytest.mark.parametrize("status", [429, 500, 503])
async def test_other_statuses_are_errors(respx_mock, http, status):
    respx_mock.get(url(IJAA_NOTICE)).respond(status)

    assert await fetch_record(http, IJAA_NOTICE, mailto="") is FetchStatus.ERROR


@pytest.mark.parametrize("failure", [httpx.ReadTimeout("slow"), httpx.ConnectError("down")])
async def test_transport_failures_are_errors(respx_mock, http, failure):
    respx_mock.get(url(IJAA_NOTICE)).mock(side_effect=failure)

    assert await fetch_record(http, IJAA_NOTICE, mailto="") is FetchStatus.ERROR


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(200, text="<html>maintenance</html>"),
        httpx.Response(200, json={"status": "ok", "message": {}}),
        httpx.Response(200, json={"status": "ok"}),
        httpx.Response(200, json=["not", "a", "work"]),
    ],
    ids=["not json", "no DOI", "no message", "not an object"],
)
async def test_a_200_that_isnt_a_work_is_an_error(respx_mock, http, response):
    respx_mock.get(url(IJAA_NOTICE)).mock(return_value=response)

    assert await fetch_record(http, IJAA_NOTICE, mailto="") is FetchStatus.ERROR


async def test_the_doi_is_encoded_like_updatings(respx_mock, http):
    route = respx_mock.get(REPUBLICATION_URL).respond(
        200, json=load_fixture("crossref", "lancet_republication")
    )

    await fetch_record(http, REPUBLICATION, mailto="")

    assert route.called
    assert route.calls.last.request.url.raw_path.decode().startswith(
        "/works/10.1016/s0140-6736%2820%2931528-2"
    )


async def test_mailto_is_sent_only_when_set_and_there_is_no_auth(respx_mock, http):
    route = respx_mock.get(url(IJAA_NOTICE)).respond(
        200, json=load_fixture("crossref", "ijaa_notice")
    )

    await fetch_record(http, IJAA_NOTICE, mailto="team@example.org")
    await fetch_record(http, IJAA_NOTICE, mailto="")

    with_mailto, without = (call.request for call in route.calls)
    assert with_mailto.url.params["mailto"] == "team@example.org"
    assert "mailto" not in without.url.params
    assert "authorization" not in with_mailto.headers and "authorization" not in without.headers


@pytest.mark.live
async def test_live_the_ijaa_notice():
    async with httpx.AsyncClient(timeout=20) as http:
        record = await fetch_record(http, IJAA_NOTICE, mailto="")

    assert isinstance(record, CrossrefRecord)
    assert record.title.startswith("Retraction notice to")
    assert record.updates(IJAA_PAPER)


def doi_links(record) -> list[tuple[str, str]]:
    return [(link.doi, link.role) for link in record.links if link.doi is not None]


def url_links(record) -> list[str]:
    return [link.url for link in record.links if link.url is not None]


def lancet_url(doi: str) -> str:
    return url(doi).replace("(", "%28").replace(")", "%29")


async def test_the_lancet_eoc_links_to_the_paper_and_the_retraction(respx_mock, http):
    respx_mock.get(lancet_url(LANCET_EOC)).respond(200, json=load_fixture("crossref", "lancet_eoc"))

    record = await fetch_record(http, LANCET_EOC, mailto="")

    # update-to names the paper; relation `retraction` and both updated-by entries repeat a DOI
    # already linked, so the first link for it is the one kept
    assert doi_links(record) == [(LANCET_PAPER, "update-to"), (LANCET_RETRACTION, "erratum")]
    assert [(u.doi, u.type) for u in record.updated_by] == [
        (LANCET_PAPER, "retraction"),
        (LANCET_RETRACTION, "erratum"),
    ]
    assert all(link.source is LinkSource.CROSSREF for link in record.links)


async def test_the_republication_links_each_doi_once_and_reads_no_references(respx_mock, http):
    respx_mock.get(REPUBLICATION_URL).respond(
        200, json=load_fixture("crossref", "lancet_republication")
    )

    record = await fetch_record(http, REPUBLICATION, mailto="")

    assert doi_links(record) == [(LANCET_COMMENTARY, "update-to"), (LANCET_PAPER, "update-to")]
    # its 12 references give nothing
    assert len(load_fixture("crossref", "lancet_republication")["message"]["reference"]) == 12
    assert len(record.links) == 4


async def test_f1000_v1_links_to_its_newer_version(respx_mock, http):
    respx_mock.get(url(F1000_V1)).respond(200, json=load_fixture("crossref", "f1000_v1"))

    record = await fetch_record(http, F1000_V1, mailto="")

    # relation comes before updated-by, so v2 is linked by `is-version-of`; the review too
    assert doi_links(record) == [
        ("10.5256/f1000research.207205.r512930", "has-review"),
        (F1000_V2, "is-version-of"),
    ]
    assert [(u.doi, u.type) for u in record.updated_by] == [(F1000_V2, "new_version")]


async def test_the_ijaa_notice_links_the_paper_once(respx_mock, http):
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=load_fixture("crossref", "ijaa_notice"))

    record = await fetch_record(http, IJAA_NOTICE, mailto="")

    # Crossref lists it twice (retraction and erratum)
    assert doi_links(record) == [(IJAA_PAPER, "update-to")]


@pytest.mark.parametrize(
    ("fixture", "doi"),
    [
        ("ijaa_notice", IJAA_NOTICE),
        ("lancet_eoc", LANCET_EOC),
        ("lancet_republication", REPUBLICATION),
        ("f1000_v1", F1000_V1),
        ("plos_retraction_notice", PLOS_NOTICE),
        ("evolution_eoc", "10.1093/evolut/qpad094"),
    ],
)
async def test_every_records_link_urls_are_url_links(respx_mock, http, fixture, doi):
    respx_mock.get(lancet_url(doi)).respond(200, json=load_fixture("crossref", fixture))

    record = await fetch_record(http, doi, mailto="")

    message = load_fixture("crossref", fixture)["message"]
    assert url_links(record) == list(dict.fromkeys(item["URL"] for item in message["link"]))
    assert all(link.role is None for link in record.links if link.url is not None)


async def test_the_plos_notice_links_the_paper_and_its_dx_plos_url(respx_mock, http):
    respx_mock.get(url(PLOS_NOTICE)).respond(
        200, json=load_fixture("crossref", "plos_retraction_notice")
    )

    record = await fetch_record(http, PLOS_NOTICE, mailto="")

    assert doi_links(record) == [(PLOS_PAPER, "update-to")]
    assert url_links(record) == ["https://dx.plos.org/10.1371/journal.pone.0322065"]


async def test_uri_relations_are_url_links_and_doi_urls_become_doi_links(respx_mock, http):
    body = minimal_record(
        relation={
            "is-supplemented-by": [{"id-type": "uri", "id": "https://example.org/data"}],
            "is-replaced-by": [{"id-type": "uri", "id": "https://doi.org/10.1/NEWER"}],
        },
        link=[{"URL": "https://doi.org/10.1/LINKED"}, {"URL": "https://example.org/pdf"}],
    )
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=body)

    record = await fetch_record(http, IJAA_NOTICE, mailto="")

    assert [(link.doi, link.url, link.role) for link in record.links] == [
        (None, "https://example.org/data", "is-supplemented-by"),
        ("10.1/newer", None, "is-replaced-by"),
        ("10.1/linked", None, None),
        (None, "https://example.org/pdf", None),
    ]


@pytest.mark.parametrize(
    "item",
    [
        {"id-type": "doi"},
        {"id-type": "uri", "id": "not a url"},
        {"id-type": "uri", "id": 5},
        {"id": "10.1/no-type"},
        ["10.1/in-a-list"],
        None,
    ],
    ids=["no id", "uri not a url", "uri not text", "no id-type", "a list", "null"],
)
async def test_a_malformed_relation_item_gives_no_link_and_no_exception(respx_mock, http, item):
    body = minimal_record(relation={"is-replaced-by": [item]}, link=[item, {"URL": None}, "x"])
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=body)

    record = await fetch_record(http, IJAA_NOTICE, mailto="")

    assert isinstance(record, CrossrefRecord)
    assert record.links == []


async def test_updated_by_entries_are_links_after_relations(respx_mock, http):
    body = minimal_record(
        **{
            "update-to": [{"DOI": IJAA_PAPER, "type": "retraction"}],
            "updated-by": [{"DOI": "10.1/LATER", "type": "correction"}],
            "relation": {"has-version": [{"id-type": "doi", "id": "10.1/v2"}]},
        }
    )
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=body)

    record = await fetch_record(http, IJAA_NOTICE, mailto="")

    assert doi_links(record) == [
        (IJAA_PAPER, "update-to"),
        ("10.1/v2", "has-version"),
        ("10.1/later", "updated-by"),
    ]


def minimal_record(**fields) -> dict:
    return {"status": "ok", "message": {"DOI": IJAA_NOTICE, **fields}}


@pytest.mark.parametrize(
    "item",
    [
        {"id-type": "doi", "id": " "},
        {"id-type": "doi", "id": "https://doi.org/"},
        {"id-type": "doi", "id": "doi:"},
        {"id-type": "doi", "id": 123},
        {"id-type": "doi", "id": None},
        {"id-type": "issn", "id": "0924-8579"},
        "not an object",
    ],
    ids=["blank", "bare resolver", "bare prefix", "number", "null", "not a doi", "not an object"],
)
async def test_relation_ids_that_arent_dois_are_skipped_not_raised(respx_mock, http, item):
    body = minimal_record(
        relation={
            "has-version": [item, {"id-type": "doi", "id": "10.12688/f1000research.187739.1"}]
        }
    )
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=body)

    record = await fetch_record(http, IJAA_NOTICE, mailto="")

    assert record.relation == {"has-version": ["10.12688/f1000research.187739.1"]}


@pytest.mark.parametrize("doi", ["", "  ", "https://doi.org/"])
async def test_a_record_with_a_blank_doi_is_an_error(respx_mock, http, doi):
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=minimal_record(DOI=doi))

    assert await fetch_record(http, IJAA_NOTICE, mailto="") is FetchStatus.ERROR


@pytest.mark.parametrize(
    "fields",
    [{"relation": None}, {"relation": {"has-version": "x"}}, {"update-to": None}, {"DOI": 12}],
    ids=["null relation", "relation not a list", "null update-to", "numeric DOI"],
)
async def test_odd_shapes_are_errors_not_raises(respx_mock, http, fields):
    respx_mock.get(url(IJAA_NOTICE)).respond(200, json=minimal_record(**fields))

    assert await fetch_record(http, IJAA_NOTICE, mailto="") is FetchStatus.ERROR


def test_an_entry_without_a_doi_never_matches():
    record = CrossrefRecord(
        doi=IJAA_NOTICE,
        title=None,
        published=None,
        journal=None,
        update_to=[
            {"doi": None, "type": "retraction", "label": None, "source": None, "date": None}
        ],
        relation={},
    )

    assert not record.updates("")
    assert not record.updates("https://doi.org/")
