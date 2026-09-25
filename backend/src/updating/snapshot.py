"""Building the snapshot Storage Management stores (docs/CONTRACTS.md, "Snapshot fields").

Every field is null, never false, when its source didn't give an answer. The
poll job never builds a snapshot when a source errored (the outage gate), so
`error` only appears here for `openalex_authors`, which PR 2 fills in."""

from datetime import datetime

from pydantic import BaseModel, ConfigDict

from common.doi import Doi, normalize_doi
from updating.sources import (
    CrossrefDate,
    CrossrefResult,
    CrossrefWork,
    OpenAlexResult,
    OpenAlexWork,
    SourceStatus,
    status_of,
)


class _Frozen(BaseModel):
    model_config = ConfigDict(frozen=True)


class CrossrefUpdate(_Frozen):
    notice_doi: Doi | None
    type: str | None
    label: str | None
    source: str | None
    date: str | None
    record_id: str | None


class SourceStatuses(_Frozen):
    crossref: SourceStatus
    openalex: SourceStatus
    # null until authors are fetched (PR 2): there was no author lookup to succeed or fail
    openalex_authors: SourceStatus | None = None


class Snapshot(_Frozen):
    doi: Doi
    fetched_at: datetime
    openalex_id: str | None
    title: str | None
    publication_year: int | None
    is_retracted: bool | None
    crossref_updates: list[CrossrefUpdate] | None
    in_doaj: bool | None
    journal_source_id: str | None
    journal_source_type: str | None
    journal: str | None
    issn_l: str | None
    publisher: str | None
    authors: None = None  # PR 2
    cited_by_count: int | None
    source_status: SourceStatuses


def _short_id(openalex_id: str | None) -> str | None:
    return None if openalex_id is None else openalex_id.rsplit("/", 1)[-1]


def _format_date(date: CrossrefDate | None) -> str | None:
    """`[[2020, 6, 3]]` -> `2020-06-03`; a partial date stays partial (`2020-06`)."""
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


def _crossref_updates(work: CrossrefWork) -> list[CrossrefUpdate]:
    return [
        CrossrefUpdate(
            notice_doi=normalize_doi(entry.doi),
            type=entry.type,
            label=entry.label,
            source=entry.source,
            date=_format_date(entry.updated),
            record_id=entry.record_id,
        )
        for entry in work.updated_by
    ]


def build_snapshot(
    doi: Doi, fetched_at: datetime, crossref: CrossrefResult, openalex: OpenAlexResult
) -> Snapshot:
    crossref_updates = _crossref_updates(crossref) if isinstance(crossref, CrossrefWork) else None
    source = None
    if isinstance(openalex, OpenAlexWork) and openalex.primary_location:
        source = openalex.primary_location.source
    work = openalex if isinstance(openalex, OpenAlexWork) else None

    return Snapshot(
        doi=doi,
        fetched_at=fetched_at,
        openalex_id=_short_id(work.id) if work else None,
        title=work.title if work else None,
        publication_year=work.publication_year if work else None,
        is_retracted=work.is_retracted if work else None,
        crossref_updates=crossref_updates,
        in_doaj=source.is_in_doaj if source else None,
        journal_source_id=_short_id(source.id) if source else None,
        journal_source_type=source.type if source else None,
        journal=source.display_name if source else None,
        issn_l=source.issn_l if source else None,
        publisher=source.host_organization_name if source else None,
        cited_by_count=work.cited_by_count if work else None,
        source_status=SourceStatuses(crossref=status_of(crossref), openalex=status_of(openalex)),
    )
