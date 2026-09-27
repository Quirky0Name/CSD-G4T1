"""A DOI's own Crossref record: for a notice, what it is and which articles it says it updates.

Updating already reads the *paper's* record (its `updated-by` list gives the notice DOIs);
this reads the record behind any DOI, a notice's or a new version's. The request is the same
as Updating's `fetch_crossref` (same URL, `mailto` polite pool, ok / not found / error), but
Research Evaluation doesn't import from `updating/`, and it keeps different fields."""

import logging
from typing import Any
from urllib.parse import quote

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from common.doi import normalize_doi
from research_evaluation.investigation.http import FetchStatus, get

CROSSREF_WORKS_URL = "https://api.crossref.org/works/"

log = logging.getLogger(__name__)


class _Payload(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)


class _Date(_Payload):
    date_parts: list[list[int | None]] = Field(default_factory=list, alias="date-parts")


def _format_date(date: _Date | None) -> str | None:
    """A partial date stays partial ("2020-06"), as in Updating's snapshots."""
    if date is None or not date.date_parts:
        return None
    parts: list[int] = []
    for part in date.date_parts[0]:
        if part is None:
            break
        parts.append(part)
    if not parts:
        return None
    return "-".join([str(parts[0]), *(f"{part:02d}" for part in parts[1:])])


class _UpdateTo(_Payload):
    doi: str | None = Field(default=None, alias="DOI")
    type: str | None = None
    label: str | None = None
    source: str | None = None
    updated: _Date | None = None


class _Work(_Payload):
    # required, so a 200 that isn't a work is an error rather than an empty record
    doi: str = Field(alias="DOI")
    title: list[str] = Field(default_factory=list)
    container_title: list[str] = Field(default_factory=list, alias="container-title")
    published: _Date | None = None
    update_to: list[_UpdateTo] = Field(default_factory=list, alias="update-to")
    # items are checked one by one in _relation_dois, so one odd item doesn't lose the record
    relation: dict[str, list[Any]] = Field(default_factory=dict)

    @field_validator("title", "container_title", mode="before")
    @classmethod
    def _none_is_empty(cls, value: object) -> object:
        return [] if value is None else value


class UpdateTo(BaseModel):
    """One article the record says it updates, and how (Crossref's `update-to`)."""

    doi: str | None
    type: str | None
    label: str | None
    source: str | None
    date: str | None


class CrossrefRecord(BaseModel):
    """What investigation keeps of a Crossref record. It's sent to Storage Management as the
    document's `crossref_record`, which stores it as-is."""

    doi: str
    title: str | None
    published: str | None
    journal: str | None
    update_to: list[UpdateTo]
    # relation type (e.g. "has-version") -> the DOIs it points at
    relation: dict[str, list[str]]

    def updates(self, doi: str) -> bool:
        """Whether `update_to` names this DOI (compared normalised). An entry with no DOI never
        matches."""
        target = normalize_doi(doi)
        return target is not None and any(entry.doi == target for entry in self.update_to)


CrossrefResult = CrossrefRecord | FetchStatus  # NOT_FOUND or ERROR when there's no record


async def fetch_record(http: httpx.AsyncClient, doi: str, mailto: str) -> CrossrefResult:
    """The record for `doi`, NOT_FOUND when Crossref has none, or ERROR. Never raises."""
    url = CROSSREF_WORKS_URL + quote(doi, safe="/")
    params = {"mailto": mailto} if mailto else None
    response = await get(http, url, source="crossref", doi=doi, params=params)
    if isinstance(response, FetchStatus):
        return response
    try:
        # everything read from the body is in here, so an odd record is an ERROR, never a raise
        work = _Work.model_validate(response.json()["message"])
        record_doi = normalize_doi(work.doi)
        if record_doi is None:
            raise ValueError("record has a blank DOI")
        return CrossrefRecord(
            doi=record_doi,
            title=work.title[0] if work.title else None,
            published=_format_date(work.published),
            journal=work.container_title[0] if work.container_title else None,
            update_to=[
                UpdateTo(
                    doi=normalize_doi(entry.doi),
                    type=entry.type,
                    label=entry.label,
                    source=entry.source,
                    date=_format_date(entry.updated),
                )
                for entry in work.update_to
            ],
            relation={kind: _relation_dois(items) for kind, items in work.relation.items()},
        )
    except (ValueError, KeyError, TypeError, AttributeError, ValidationError) as exc:
        log.warning("crossref returned an unreadable record for %s: %s", doi, type(exc).__name__)
        return FetchStatus.ERROR


def _relation_dois(items: list[Any]) -> list[str]:
    """The DOIs of one relation type; ids that aren't DOIs, aren't text, or are blank are skipped."""
    dois = []
    for item in items:
        if (
            isinstance(item, dict)
            and item.get("id-type") == "doi"
            and isinstance(item.get("id"), str)
        ):
            doi = normalize_doi(item["id"])
            if doi is not None:
                dois.append(doi)
    return dois
