import httpx
import pytest
from support import load_fixture, load_xml_fixture

from research_evaluation.investigation import europepmc
from research_evaluation.investigation.europepmc import TextStatus, fetch_text, plain_text
from research_evaluation.investigation.links import LinkSource

BASE = "https://www.ebi.ac.uk/europepmc/webservices/rest"
SEARCH = f"{BASE}/search"
AUTHOR_CORRECTION = "10.1038/s41598-025-93099-x"
PLOS_EOC = "10.1371/journal.pone.0298436"
IJAA_NOTICE = "10.1016/j.ijantimicag.2024.107416"
PLOS_NOTICE = "10.1371/journal.pone.0322065"
PLOS_PAPER = "10.1371/journal.pone.0258361"
CC_BY = "https://creativecommons.org/licenses/by/4.0/"


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


def links_of(result) -> list[tuple]:
    return [(link.doi, link.url, link.role, link.source) for link in result.links]


def id_search_returns(respx_mock, query: str, **response):
    """A Europe PMC search for one PMCID or PMID; added before the DOI search's route, which
    would otherwise answer it."""
    return respx_mock.get(url__startswith=SEARCH, params__contains={"query": query}).respond(
        **response
    )


def open_access_search(doi: str, pmcid: str) -> dict:
    """What Europe PMC's search answers for an open-access DOI (the PLOS notice's search
    couldn't be recorded: the live search was down)."""
    return {
        "hitCount": 1,
        "resultList": {"result": [{"doi": doi, "pmcid": pmcid, "isOpenAccess": "Y"}]},
    }


async def fetch_xml(respx_mock, http, xml: bytes, doi: str = "10.1/doc"):
    respx_mock.get(url__startswith=SEARCH, params__contains={"query": f'DOI:"{doi}"'}).respond(
        200, json=open_access_search(doi, "PMC1")
    )
    full_text_returns(respx_mock, "PMC1", status_code=200, content=xml)
    return await fetch_text(http, doi)


async def test_the_plos_notice_links_the_retracted_article_through_its_pmcid(respx_mock, http):
    pmcid = id_search_returns(
        respx_mock,
        "PMCID:PMC8504731",
        status_code=200,
        json=load_fixture("europepmc", "search_pmcid_PMC8504731"),
    )

    result = await fetch_xml(
        respx_mock, http, load_xml_fixture("europepmc", "fulltext_PMC12017829"), PLOS_NOTICE
    )

    assert result.status is TextStatus.OK
    # the license URL, then the retracted article: its <related-article> holds only a PMCID
    # ext-link, found by one search. The reference list (in the body here) gives nothing:
    # neither its DOI, PMID and Google Scholar ext-links nor its retraction-forward pointer
    assert links_of(result) == [
        (None, CC_BY, None, LinkSource.EUROPEPMC_XML),
        (PLOS_PAPER, None, "retracted-article", LinkSource.EUROPEPMC_XML),
    ]
    assert pmcid.call_count == 1


async def test_a_pmcid_that_cant_be_resolved_is_kept_as_its_pmc_url(respx_mock, http):
    id_search_returns(respx_mock, "PMCID:PMC8504731", status_code=500)

    result = await fetch_xml(
        respx_mock, http, load_xml_fixture("europepmc", "fulltext_PMC12017829"), PLOS_NOTICE
    )

    assert result.status is TextStatus.OK
    assert (
        None,
        "https://pmc.ncbi.nlm.nih.gov/articles/PMC8504731/",
        "retracted-article",
        LinkSource.EUROPEPMC_XML,
    ) in links_of(result)
    assert all(link.doi != PLOS_PAPER for link in result.links)


async def test_the_plos_eoc_links_its_object_of_concern_and_nothing_from_its_references(
    respx_mock, http
):
    search_returns(respx_mock, "search_plos_eoc")
    full_text_returns(
        respx_mock,
        "PMC10836678",
        status_code=200,
        content=load_xml_fixture("europepmc", "fulltext_PMC10836678"),
    )

    result = await fetch_text(http, PLOS_EOC)

    # the DOI is in the <related-article> itself, so its PMC link needs no search; the
    # reference list's pointer back at this EoC (PMC10836678) gives nothing
    assert links_of(result) == [
        (None, CC_BY, None, LinkSource.EUROPEPMC_XML),
        ("10.1371/journal.pone.0035065", None, "object-of-concern", LinkSource.EUROPEPMC_XML),
    ]


async def test_the_author_correction_links_its_corrected_article(respx_mock, http):
    id_search_returns(
        respx_mock,
        "PMCID:PMC11607393",
        status_code=200,
        json=load_fixture("europepmc", "search_no_match"),
    )
    search_returns(respx_mock, "search_author_correction")
    full_text_returns(
        respx_mock,
        "PMC11906582",
        status_code=200,
        content=load_xml_fixture("europepmc", "fulltext_PMC11906582"),
    )

    result = await fetch_text(http, AUTHOR_CORRECTION)

    assert [(link.url, link.role) for link in result.links if link.role is not None] == [
        ("https://pmc.ncbi.nlm.nih.gov/articles/PMC11607393/", "corrected-article")
    ]


async def test_jats_links_by_element(respx_mock, http):
    pmid = id_search_returns(
        respx_mock,
        "EXT_ID:12345 AND SRC:MED",
        status_code=200,
        json={"resultList": {"result": [{"pmid": "12345", "doi": "10.1/FROM-PMID"}]}},
    )
    xml = b"""<article xmlns:xlink="http://www.w3.org/1999/xlink">
      <front><article-meta>
        <article-id pub-id-type="doi">10.1/doc</article-id>
        <title-group><article-title>Correction</article-title></title-group>
        <self-uri xlink:href="doc.pdf"/>
        <self-uri xlink:href="https://example.org/self"/>
        <related-article related-article-type="corrected-article" ext-link-type="doi"
          xlink:href="10.1/OWN-HREF"/>
        <related-article related-article-type="retraction-forward">
          <ext-link ext-link-type="doi" xlink:href="10.1/nested"/></related-article>
        <related-article related-article-type="update">No link at all.</related-article>
      </article-meta></front>
      <body>
        <p>See <ext-link ext-link-type="uri" xlink:href="https://doi.org/10.1/FROM-URI">x</ext-link>,
          <ext-link xlink:href="https://example.org/data.">data</ext-link>,
          <ext-link ext-link-type="pmid" xlink:href="12345">a PubMed record</ext-link>,
          <ext-link ext-link-type="google-scholar" xlink:href="title=x">scholar</ext-link>
          and <pub-id pub-id-type="doi">10.1/PUB-ID</pub-id>.</p>
        <ref-list><ref><ext-link ext-link-type="doi" xlink:href="10.1/cited-in-body"/></ref></ref-list>
      </body>
      <back><ref-list><ref><pub-id pub-id-type="doi">10.1/cited</pub-id></ref></ref-list></back>
    </article>"""

    result = await fetch_xml(respx_mock, http, xml)

    xml_links = [
        (link.doi, link.url, link.role)
        for link in result.links
        if link.source is LinkSource.EUROPEPMC_XML
    ]
    assert xml_links == [
        (None, "https://example.org/self", None),
        ("10.1/own-href", None, "corrected-article"),
        ("10.1/nested", None, "retraction-forward"),
        ("10.1/from-uri", None, None),
        (None, "https://example.org/data", None),
        ("10.1/from-pmid", None, None),
        ("10.1/pub-id", None, None),
    ]
    assert pmid.call_count == 1


async def test_text_links_come_after_xml_links_and_repeat_none(respx_mock, http):
    xml = b"""<article xmlns:xlink="http://www.w3.org/1999/xlink"><body>
      <p>The original <ext-link ext-link-type="doi" xlink:href="10.1234/orig">article</ext-link>
        (doi: 10.1234/orig). Data: https://example.org/text-data; also 10.1234/ONLY-IN-TEXT).</p>
    </body></article>"""

    result = await fetch_xml(respx_mock, http, xml)

    assert links_of(result) == [
        ("10.1234/orig", None, None, LinkSource.EUROPEPMC_XML),
        (None, "https://example.org/text-data", None, LinkSource.EUROPEPMC_TEXT),
        ("10.1234/only-in-text", None, None, LinkSource.EUROPEPMC_TEXT),
    ]


async def test_a_related_article_without_a_link_gives_none_and_no_exception(respx_mock, http):
    xml = b"""<article xmlns:xlink="http://www.w3.org/1999/xlink"><front><article-meta>
      <related-article related-article-type="retracted-article"/>
      <related-article related-article-type="corrected-article" ext-link-type="doi"/>
      <related-article related-article-type="update" xlink:href="   "/>
      </article-meta></front><body><p>Retraction.</p></body></article>"""

    result = await fetch_xml(respx_mock, http, xml)

    assert result.status is TextStatus.OK
    assert result.links == []


async def test_a_paywalled_document_has_no_links(respx_mock, http):
    search_returns(respx_mock, "search_ijaa_notice")

    assert (await fetch_text(http, IJAA_NOTICE)).links == []


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
