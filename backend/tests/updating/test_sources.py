from types import SimpleNamespace

import httpx
import pytest
from pydantic import SecretStr
from support import load_fixture

from common.doi import Doi
from updating.sources import (
    CrossrefWork,
    OpenAlexAuthor,
    OpenAlexWork,
    SourceStatus,
    fetch_crossref,
    fetch_openalex,
    fetch_openalex_authors,
)

DOI = Doi("10.1016/s0140-6736(20)31180-6")
ENCODED = "10.1016/s0140-6736%2820%2931180-6"
AUTHORS_URL = "https://api.openalex.org/authors"
AUTHOR_IDS = ["A5056960938", "A5020874464", "A5013727186", "A5076233641"]


@pytest.fixture(params=["crossref", "openalex"])
def source(request):
    """The same behaviour is expected of both sources."""
    if request.param == "crossref":
        return SimpleNamespace(
            url=f"https://api.crossref.org/works/{ENCODED}",
            call=lambda http, doi=DOI: fetch_crossref(http, doi, mailto=""),
            ok_json=load_fixture("crossref", "lancet"),
            work_type=CrossrefWork,
            no_work_json={"status": "ok", "message": {}},
        )
    return SimpleNamespace(
        url=f"https://api.openalex.org/works/doi:{ENCODED}",
        call=lambda http, doi=DOI: fetch_openalex(http, doi, SecretStr("")),
        ok_json=load_fixture("openalex", "lancet"),
        work_type=OpenAlexWork,
        no_work_json={},
    )


@pytest.fixture
async def http():
    async with httpx.AsyncClient() as client:
        yield client


async def test_200_is_a_parsed_work(respx_mock, http, source):
    respx_mock.get(source.url).respond(200, json=source.ok_json)

    assert isinstance(await source.call(http), source.work_type)


async def test_404_is_not_found(respx_mock, http, source):
    respx_mock.get(source.url).respond(404, text="Resource not found.")

    assert await source.call(http) is SourceStatus.NOT_FOUND


@pytest.mark.parametrize("status", [429, 500, 503])
async def test_other_statuses_are_errors(respx_mock, http, source, status):
    respx_mock.get(source.url).respond(status)

    assert await source.call(http) is SourceStatus.ERROR


@pytest.mark.parametrize("failure", [httpx.ReadTimeout("slow"), httpx.ConnectError("down")])
async def test_transport_failures_are_errors(respx_mock, http, source, failure):
    respx_mock.get(source.url).mock(side_effect=failure)

    assert await source.call(http) is SourceStatus.ERROR


async def test_a_200_that_is_not_json_is_an_error(respx_mock, http, source):
    respx_mock.get(source.url).respond(200, text="<html>maintenance</html>")

    assert await source.call(http) is SourceStatus.ERROR


async def test_a_200_that_is_not_a_work_is_an_error(respx_mock, http, source):
    respx_mock.get(source.url).respond(200, json=source.no_work_json)

    assert await source.call(http) is SourceStatus.ERROR


async def test_crossref_sends_mailto_only_when_set(respx_mock, http):
    route = respx_mock.get(f"https://api.crossref.org/works/{ENCODED}").respond(
        200, json=load_fixture("crossref", "lancet")
    )

    await fetch_crossref(http, DOI, mailto="")
    await fetch_crossref(http, DOI, mailto="team@example.org")

    assert "mailto" not in route.calls[0].request.url.params
    assert route.calls[1].request.url.params["mailto"] == "team@example.org"


async def test_openalex_sends_api_key_only_when_set(respx_mock, http):
    route = respx_mock.get(f"https://api.openalex.org/works/doi:{ENCODED}").respond(
        200, json=load_fixture("openalex", "lancet")
    )

    await fetch_openalex(http, DOI, SecretStr(""))
    await fetch_openalex(http, DOI, SecretStr("k3y"))

    assert "api_key" not in route.calls[0].request.url.params
    assert route.calls[1].request.url.params["api_key"] == "k3y"


async def test_openalex_follows_a_redirect_to_the_surviving_work(respx_mock, http):
    respx_mock.get(f"https://api.openalex.org/works/doi:{ENCODED}").respond(
        301, headers={"location": "https://api.openalex.org/works/W3027680906"}
    )
    respx_mock.get("https://api.openalex.org/works/W3027680906").respond(
        200, json=load_fixture("openalex", "lancet")
    )

    assert isinstance(await fetch_openalex(http, DOI, SecretStr("")), OpenAlexWork)


@pytest.mark.parametrize("fetch", ["crossref", "openalex"])
async def test_awkward_doi_characters_are_percent_encoded(respx_mock, http, fetch):
    doi = Doi("10.1002/x#1?a<b>")
    base = "https://api.crossref.org/works/" if fetch == "crossref" else "https://api.openalex.org/works/doi:"
    route = respx_mock.get(url__startswith=base).respond(404)

    if fetch == "crossref":
        await fetch_crossref(http, doi, mailto="")
    else:
        await fetch_openalex(http, doi, SecretStr(""))

    assert route.calls[0].request.url.raw_path.split(b"?")[0].endswith(b"10.1002/x%231%3Fa%3Cb%3E")


async def test_the_author_batch_is_one_request_with_the_ids_in_order(respx_mock, http):
    route = respx_mock.get(AUTHORS_URL).respond(200, json=load_fixture("openalex_authors", "lancet"))

    result = await fetch_openalex_authors(http, DOI, AUTHOR_IDS, SecretStr(""))

    assert route.call_count == 1
    params = route.calls[0].request.url.params
    assert params["filter"] == "openalex:" + "|".join(AUTHOR_IDS)
    assert params["select"] == "id,works_count,summary_stats"
    assert "api_key" not in params
    assert len(result) == 4 and all(isinstance(row, OpenAlexAuthor) for row in result)
    assert {row.id.rsplit("/", 1)[-1] for row in result} == set(AUTHOR_IDS)


async def test_the_author_batch_sends_the_api_key_only_when_set(respx_mock, http):
    route = respx_mock.get(AUTHORS_URL).respond(200, json={"results": []})

    await fetch_openalex_authors(http, DOI, AUTHOR_IDS, SecretStr("k3y"))

    assert route.calls[0].request.url.params["api_key"] == "k3y"


async def test_no_author_ids_means_no_request(respx_mock, http):
    route = respx_mock.get(AUTHORS_URL)

    assert await fetch_openalex_authors(http, DOI, [], SecretStr("")) == []
    assert not route.called


@pytest.mark.parametrize("status", [404, 429, 500, 503])
async def test_author_batch_error_statuses_are_errors(respx_mock, http, status):
    respx_mock.get(AUTHORS_URL).respond(status)

    assert await fetch_openalex_authors(http, DOI, AUTHOR_IDS, SecretStr("")) is SourceStatus.ERROR


@pytest.mark.parametrize("failure", [httpx.ReadTimeout("slow"), httpx.ConnectError("down")])
async def test_author_batch_transport_failures_are_errors(respx_mock, http, failure):
    respx_mock.get(AUTHORS_URL).mock(side_effect=failure)

    assert await fetch_openalex_authors(http, DOI, AUTHOR_IDS, SecretStr("")) is SourceStatus.ERROR


@pytest.mark.parametrize(
    "body",
    [
        {"error": "maintenance"},  # a 200 without `results`
        {"results": [{"works_count": 3}]},  # a row that isn't an author
    ],
)
async def test_an_author_batch_that_is_not_a_page_of_authors_is_an_error(respx_mock, http, body):
    respx_mock.get(AUTHORS_URL).respond(200, json=body)

    assert await fetch_openalex_authors(http, DOI, AUTHOR_IDS, SecretStr("")) is SourceStatus.ERROR


async def test_a_200_author_batch_that_is_not_json_is_an_error(respx_mock, http):
    respx_mock.get(AUTHORS_URL).respond(200, text="<html>maintenance</html>")

    assert await fetch_openalex_authors(http, DOI, AUTHOR_IDS, SecretStr("")) is SourceStatus.ERROR
