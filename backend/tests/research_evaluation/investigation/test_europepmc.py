import httpx
import pytest
from support import load_fixture, load_xml_fixture

from research_evaluation.investigation import europepmc
from research_evaluation.investigation.europepmc import TextStatus, fetch_text, plain_text

BASE = "https://www.ebi.ac.uk/europepmc/webservices/rest"
SEARCH = f"{BASE}/search"
AUTHOR_CORRECTION = "10.1038/s41598-025-93099-x"
PLOS_EOC = "10.1371/journal.pone.0298436"
IJAA_NOTICE = "10.1016/j.ijantimicag.2024.107416"


@pytest.fixture
async def http():
    async with httpx.AsyncClient() as client:
        yield client


def search_returns(respx_mock, fixture: str):
    return respx_mock.get(url__startswith=SEARCH).respond(
        200, json=load_fixture("europepmc", fixture)
    )


def full_text_returns(respx_mock, pmcid: str, **response):
    return respx_mock.get(f"{BASE}/{pmcid}/fullTextXML").respond(**response)


async def test_an_open_access_notice_comes_back_as_plain_text(respx_mock, http):
    search_returns(respx_mock, "search_author_correction")
    full_text_returns(
        respx_mock,
        "PMC11906582",
        status_code=200,
        content=load_xml_fixture("europepmc", "fulltext_PMC11906582"),
    )

    result = await fetch_text(http, AUTHOR_CORRECTION)

    assert result.status is TextStatus.OK
    assert result.text.startswith("Author Correction")
    assert "The original Article has been corrected" in result.text
    assert result.truncated is False


async def test_the_eoc_keeps_its_findings_and_drops_the_reference_list(respx_mock, http):
    search_returns(respx_mock, "search_plos_eoc")
    full_text_returns(
        respx_mock,
        "PMC10836678",
        status_code=200,
        content=load_xml_fixture("europepmc", "fulltext_PMC10836678"),
    )

    result = await fetch_text(http, PLOS_EOC)

    assert result.status is TextStatus.OK
    assert "In Fig. 4" in result.text
    assert "The results shown in Fig. 7 do not correspond" in result.text
    assert "the number and rate of migrated cells was gradually reduced" in result.text
    # the only reference is the retracted article itself, cited as "Over-Expression of LSD1..."
    assert "Over-Expression of LSD1 Promotes" not in result.text.split("\n\n", 1)[1]
    assert "\n\n" in result.text  # one block per paragraph


async def test_a_paywalled_notice_is_not_open_access_and_no_full_text_is_asked_for(
    respx_mock, http
):
    search_returns(respx_mock, "search_ijaa_notice")
    full_text = respx_mock.get(url__startswith=f"{BASE}/PMC")

    result = await fetch_text(http, IJAA_NOTICE)

    assert result.status is TextStatus.NOT_OPEN_ACCESS
    assert result.text is None
    assert not full_text.called


async def test_no_results_is_not_indexed(respx_mock, http):
    search_returns(respx_mock, "search_no_match")

    assert (
        await fetch_text(http, "10.9999/no-such-doi-for-investigation")
    ).status is TextStatus.NOT_INDEXED


async def test_a_result_for_another_doi_is_not_indexed(respx_mock, http):
    search_returns(respx_mock, "search_author_correction")
    full_text = respx_mock.get(url__startswith=f"{BASE}/PMC")

    result = await fetch_text(http, "10.1038/s41598-025-99999-x")

    assert result.status is TextStatus.NOT_INDEXED
    assert not full_text.called


async def test_the_doi_is_matched_normalised(respx_mock, http):
    search_returns(respx_mock, "search_author_correction")
    full_text_returns(
        respx_mock,
        "PMC11906582",
        status_code=200,
        content=load_xml_fixture("europepmc", "fulltext_PMC11906582"),
    )

    assert (await fetch_text(http, AUTHOR_CORRECTION.upper())).status is TextStatus.OK


async def test_a_full_text_404_is_not_open_access(respx_mock, http):
    search_returns(respx_mock, "search_author_correction")
    full_text_returns(respx_mock, "PMC11906582", status_code=404)

    assert (await fetch_text(http, AUTHOR_CORRECTION)).status is TextStatus.NOT_OPEN_ACCESS


@pytest.mark.parametrize(
    "search",
    [
        httpx.Response(500),
        httpx.Response(404),
        httpx.Response(200, text="<html>maintenance</html>"),
        httpx.Response(200, json={"hitCount": 0}),
        httpx.Response(200, json={"resultList": {"result": "nope"}}),
    ],
    ids=["500", "404", "not json", "no result list", "result not a list"],
)
async def test_a_failed_search_is_an_error(respx_mock, http, search):
    respx_mock.get(url__startswith=SEARCH).mock(return_value=search)

    assert (await fetch_text(http, AUTHOR_CORRECTION)).status is TextStatus.ERROR


@pytest.mark.parametrize("failure", [httpx.ReadTimeout("slow"), httpx.ConnectError("down")])
async def test_transport_failures_are_errors(respx_mock, http, failure):
    respx_mock.get(url__startswith=SEARCH).mock(side_effect=failure)

    assert (await fetch_text(http, AUTHOR_CORRECTION)).status is TextStatus.ERROR


@pytest.mark.parametrize(
    "response",
    [
        {"status_code": 500},
        {"status_code": 200, "content": b"<article><body><p>unclosed"},
        {"status_code": 200, "content": b"<article><body></body></article>"},
    ],
    ids=["500", "unreadable xml", "no text"],
)
async def test_a_failed_full_text_is_an_error(respx_mock, http, response):
    search_returns(respx_mock, "search_author_correction")
    full_text_returns(respx_mock, "PMC11906582", **response)

    assert (await fetch_text(http, AUTHOR_CORRECTION)).status is TextStatus.ERROR


async def test_text_over_the_cap_is_cut_and_flagged(respx_mock, http, monkeypatch):
    monkeypatch.setattr(europepmc, "MAX_TEXT_CHARS", 100)
    search_returns(respx_mock, "search_plos_eoc")
    full_text_returns(
        respx_mock,
        "PMC10836678",
        status_code=200,
        content=load_xml_fixture("europepmc", "fulltext_PMC10836678"),
    )

    result = await fetch_text(http, PLOS_EOC)

    assert result.status is TextStatus.OK
    assert len(result.text) == 100
    assert result.truncated is True


async def test_the_doi_is_quoted_safely_and_there_is_no_auth(respx_mock, http):
    route = search_returns(respx_mock, "search_no_match")

    await fetch_text(http, '10.1000/odd"doi\\x')

    request = route.calls.last.request
    assert request.url.params["query"] == 'DOI:"10.1000/odd\\"doi\\\\x"'
    assert request.url.params["resultType"] == "lite"
    assert request.url.params["format"] == "json"
    assert "authorization" not in request.headers


async def test_no_request_carries_auth(respx_mock, http):
    search = search_returns(respx_mock, "search_author_correction")
    full_text = full_text_returns(
        respx_mock,
        "PMC11906582",
        status_code=200,
        content=load_xml_fixture("europepmc", "fulltext_PMC11906582"),
    )

    await fetch_text(http, AUTHOR_CORRECTION)

    assert "authorization" not in search.calls.last.request.headers
    assert "authorization" not in full_text.calls.last.request.headers


def test_plain_text_keeps_title_abstract_body_and_captions_but_not_references():
    xml = b"""<article>
      <front><article-meta>
        <title-group><article-title>Correction: A <italic>study</italic></article-title></title-group>
        <abstract><p>The abstract.</p></abstract>
      </article-meta></front>
      <body>
        <sec><title>Results</title><p>First   paragraph
          <xref>[1]</xref>.</p>
          <fig><caption><title>Fig 1.</title><p>A caption.</p></caption></fig>
          <table-wrap><caption><p>Table caption.</p></caption><table><tr><td>cell</td></tr></table></table-wrap>
        </sec>
      </body>
      <back><ref-list><ref><mixed-citation>A cited work.</mixed-citation></ref></ref-list></back>
    </article>"""

    assert plain_text(xml).split("\n\n") == [
        "Correction: A study",
        "The abstract.",
        "Results",
        "First paragraph [1].",
        "Fig 1.",
        "A caption.",
        "Table caption.",
    ]


@pytest.mark.live
async def test_live_the_author_correction():
    async with httpx.AsyncClient(timeout=20) as http:
        result = await fetch_text(http, AUTHOR_CORRECTION)

    assert result.status is TextStatus.OK
    assert "The original Article has been corrected" in result.text
