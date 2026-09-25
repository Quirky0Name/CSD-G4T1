"""Fetching a paper's current status from Crossref and OpenAlex.

Each fetch returns a parsed work, or `NOT_FOUND` / `ERROR` when the source has no
work for the DOI / couldn't be read. Callers match on the result (see snapshot.py
and poll.py). Log lines carry only the DOI and a status code or exception class:
OpenAlex's key travels in the query string, so URLs and exception text stay out."""

import logging
from enum import StrEnum
from typing import Annotated, Literal
from urllib.parse import quote

import httpx
from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, SecretStr

from common.doi import Doi

CROSSREF_WORKS_URL = "https://api.crossref.org/works/"
OPENALEX_WORKS_URL = "https://api.openalex.org/works/doi:"

log = logging.getLogger(__name__)


class SourceStatus(StrEnum):
    OK = "ok"
    NOT_FOUND = "not_found"
    ERROR = "error"


FetchFailure = Literal[SourceStatus.NOT_FOUND, SourceStatus.ERROR]


class _Payload(BaseModel):
    model_config = ConfigDict(extra="ignore")


def _text(value: object) -> str | None:
    return None if value is None else str(value)


class CrossrefDate(_Payload):
    date_parts: list[list[int | None]] = Field(alias="date-parts")


class CrossrefUpdatedBy(_Payload):
    doi: str | None = Field(default=None, alias="DOI")
    type: str | None = None
    label: str | None = None
    source: str | None = None
    updated: CrossrefDate | None = None
    # present on Retraction Watch entries, absent on publisher ones
    record_id: Annotated[str | None, BeforeValidator(_text)] = Field(
        default=None, alias="record-id"
    )


class CrossrefWork(_Payload):
    # required, so a 200 that isn't a work counts as an error rather than an empty "ok"
    doi: str = Field(alias="DOI")
    updated_by: list[CrossrefUpdatedBy] = Field(default_factory=list, alias="updated-by")


class OpenAlexSource(_Payload):
    id: str | None = None
    type: str | None = None
    is_in_doaj: bool | None = None
    display_name: str | None = None
    issn_l: str | None = None
    host_organization_name: str | None = None


class OpenAlexLocation(_Payload):
    source: OpenAlexSource | None = None


class OpenAlexWork(_Payload):
    id: str
    title: str | None = None
    publication_year: int | None = None
    # a missing flag stays null (never false), so it can't look like a status change
    is_retracted: bool | None = None
    cited_by_count: int | None = None
    primary_location: OpenAlexLocation | None = None


CrossrefResult = CrossrefWork | FetchFailure
OpenAlexResult = OpenAlexWork | FetchFailure


def status_of(result: CrossrefResult | OpenAlexResult) -> SourceStatus:
    return result if isinstance(result, SourceStatus) else SourceStatus.OK


async def fetch_crossref(http: httpx.AsyncClient, doi: Doi, mailto: str) -> CrossrefResult:
    url = CROSSREF_WORKS_URL + quote(doi, safe="/")
    params = {"mailto": mailto} if mailto else None
    try:
        response = await http.get(url, params=params)
        if response.status_code == httpx.codes.NOT_FOUND:
            return SourceStatus.NOT_FOUND
        if response.status_code != httpx.codes.OK:
            log.warning("crossref returned %s for %s", response.status_code, doi)
            return SourceStatus.ERROR
        return CrossrefWork.model_validate(response.json()["message"])
    except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
        log.warning("crossref fetch failed for %s: %s", doi, type(exc).__name__)
        return SourceStatus.ERROR


async def fetch_openalex(http: httpx.AsyncClient, doi: Doi, api_key: SecretStr) -> OpenAlexResult:
    url = OPENALEX_WORKS_URL + quote(doi, safe="/")
    key = api_key.get_secret_value()
    params = {"api_key": key} if key else None
    try:
        # OpenAlex redirects a merged work to its surviving id
        response = await http.get(url, params=params, follow_redirects=True)
        if response.status_code == httpx.codes.NOT_FOUND:
            return SourceStatus.NOT_FOUND
        if response.status_code != httpx.codes.OK:
            log.warning("openalex returned %s for %s", response.status_code, doi)
            return SourceStatus.ERROR
        return OpenAlexWork.model_validate(response.json())
    except (httpx.HTTPError, ValueError, TypeError) as exc:
        log.warning("openalex fetch failed for %s: %s", doi, type(exc).__name__)
        return SourceStatus.ERROR
