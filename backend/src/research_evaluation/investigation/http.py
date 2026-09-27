"""The one GET every investigation fetcher makes, and how its outcome is reported.

A fetch never raises: a failure comes back as a status, so one missing source never stops
the others. Log lines carry only the source, the DOI and a status code or exception class,
never URLs or response bodies."""

import logging
from enum import StrEnum
from typing import Any

import httpx

log = logging.getLogger(__name__)


class FetchStatus(StrEnum):
    """The same values Storage Management stores as `crossref_status`."""

    OK = "ok"
    NOT_FOUND = "not_found"
    ERROR = "error"


async def get(
    http: httpx.AsyncClient,
    url: str,
    *,
    source: str,
    doi: str,
    params: dict[str, Any] | None = None,
) -> httpx.Response | FetchStatus:
    """The response when it's a 200; NOT_FOUND for a 404; ERROR for any other status or a
    transport failure (OK is never returned)."""
    try:
        response = await http.get(url, params=params)
    except httpx.HTTPError as exc:
        log.warning("%s fetch failed for %s: %s", source, doi, type(exc).__name__)
        return FetchStatus.ERROR
    if response.status_code == httpx.codes.NOT_FOUND:
        return FetchStatus.NOT_FOUND
    if response.status_code != httpx.codes.OK:
        log.warning("%s returned %s for %s", source, response.status_code, doi)
        return FetchStatus.ERROR
    return response
