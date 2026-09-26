"""
Compares two consecutive snapshots of a paper, lists what changed and classify changes
"""

from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict

OK = "ok"
JOURNAL = "journal"


class ChangeType(StrEnum):
    RETRACTION = "retraction"
    CORRECTION = "correction"
    ERRATUM = "erratum"
    EXPRESSION_OF_CONCERN = "expression_of_concern"
    DOAJ_DELISTING = "doaj_delisting"
    OTHER = "other"  # a Crossref notice type we don't classify yet; stage 3 will investigate these


# Crossref `updated-by` types with their own alert; any other type becomes OTHER
_CLASSIFIED_NOTICES = {
    "retraction": ChangeType.RETRACTION,
    "correction": ChangeType.CORRECTION,
    "erratum": ChangeType.ERRATUM,
    "expression_of_concern": ChangeType.EXPRESSION_OF_CONCERN,
}

# most severe first, matching rules.SEVERITY (high, then medium, then low); when one notice
# is filed under several types, the first of them here decides its alert
_NOTICE_PRECEDENCE = ["retraction", "expression_of_concern", "correction", "other", "erratum"]


class _Payload(BaseModel):
    # make my wrappers immutable and ignore non-impt fields
    model_config = ConfigDict(extra="ignore", frozen=True)


class CrossrefUpdate(_Payload):
    notice_doi: str | None = None
    type: str | None = None
    label: str | None = None
    date: str | None = None


class SourceStatuses(_Payload):
    crossref: str
    openalex: str


class Snapshot(_Payload):
    """The fields detection needs. Every nullable field is null, never false, when its
    source didn't answer (docs/CONTRACTS.md, "Snapshot fields")."""

    snapshot_id: int
    fetched_at: datetime
    is_retracted: bool | None = None
    crossref_updates: list[CrossrefUpdate] | None = None
    in_doaj: bool | None = None
    journal_source_id: str | None = None
    journal_source_type: str | None = None
    journal: str | None = None
    source_status: SourceStatuses


class Change(BaseModel):
    model_config = ConfigDict(frozen=True)

    change_type: ChangeType
    # identifies the change within the paper, so storing it twice is a no-op in Storage Management
    change_key: str
    detected_at: datetime  # fetched_at of the snapshot the change first shows up in
    snapshot_id: int
    previous_snapshot_id: int
    notice_doi: str | None = None
    notice_type: str | None = None  # Crossref's raw type, kept for OTHER
    notice_label: str | None = None
    notice_date: str | None = None
    journal: str | None = None


def find_changes(previous: Snapshot, current: Snapshot) -> list[Change]:
    """The changes worth an alert between two consecutive snapshots of one paper.

    A field is only compared when it's non-null in both snapshots and its source was
    `ok` in both, so an outage or an unknown DOI never looks like a change."""
    found: list[Change] = []
    retraction = _retraction_flag(previous, current)
    for entry in _new_notices(previous, current):
        kind = _CLASSIFIED_NOTICES.get(entry.type or "", ChangeType.OTHER)
        if kind is ChangeType.RETRACTION:
            # one retraction alert per paper: the flag and the notice are the same event,
            # and the notice carries the details, so it wins
            if retraction is None or retraction.notice_doi is None:
                retraction = _change(previous, current, ChangeType.RETRACTION, "retraction", entry)
        elif kind is ChangeType.OTHER:
            found.append(_change(previous, current, kind, f"other:{entry.type}:{entry.notice_doi or ''}", entry))
        else:
            found.append(_change(previous, current, kind, f"{kind}:{entry.notice_doi or ''}", entry))
    if retraction is not None:
        found.insert(0, retraction)
    if (delisting := _doaj_delisting(previous, current)) is not None:
        found.append(delisting)
    return found


def _both_ok(previous: Snapshot, current: Snapshot, source: str) -> bool:
    """check if the Snapshot API source could call properly"""
    return getattr(previous.source_status, source) == OK and getattr(current.source_status, source) == OK


def _retraction_flag(previous: Snapshot, current: Snapshot) -> Change | None:
    if not _both_ok(previous, current, "openalex"):
        return None
    if previous.is_retracted is False and current.is_retracted is True:
        return _change(previous, current, ChangeType.RETRACTION, "retraction")
    return None


def _severity_rank(crossref_type: str) -> int:
    """Lower is more severe. Unclassified types rank with `other` (medium), below the
    classified medium types."""
    return _NOTICE_PRECEDENCE.index(crossref_type if crossref_type in _CLASSIFIED_NOTICES else "other")


def _new_notices(previous: Snapshot, current: Snapshot) -> list[CrossrefUpdate]:
    """New Crossref notices in `current`: one entry per notice, in the order first listed.

    Crossref lists a notice once per source (publisher, Retraction Watch), so a new source
    for a known (notice_doi, type) pair isn't new, and entries with no type are skipped.

    One notice is one alert, even when Crossref files it under several types (IJAA's
    retraction notice is also a publisher "erratum"; a Lancet correction notice is also an
    "erratum"): the most severe type wins. A notice already seen in `previous` under another
    type isn't new either, unless it's now filed as a retraction. TEMPORARY: the plan is to
    hand the whole list of flagged changes to an LLM that evaluates them together (see
    docs/DECISIONS.md, 2026-09-26). Entries without a notice DOI can't be matched up and
    each count on their own."""
    if not _both_ok(previous, current, "crossref"):
        return []
    if previous.crossref_updates is None or current.crossref_updates is None:
        return []
    known_pairs = {(entry.notice_doi, entry.type) for entry in previous.crossref_updates}
    known_notices = {entry.notice_doi for entry in previous.crossref_updates if entry.notice_doi}
    chosen: dict[object, CrossrefUpdate] = {}  # notice DOI (or the pair, without a DOI) -> entry
    for entry in current.crossref_updates:
        pair = (entry.notice_doi, entry.type)
        if entry.type is None or pair in known_pairs:
            continue
        if entry.notice_doi in known_notices and entry.type != "retraction":
            continue
        group = entry.notice_doi or pair
        best = chosen.get(group)
        if best is None or _severity_rank(entry.type) < _severity_rank(best.type):
            chosen[group] = entry
    return list(chosen.values())


def _doaj_delisting(previous: Snapshot, current: Snapshot) -> Change | None:
    """`in_doaj` true -> false only counts for the same journal. DOAJ status belongs to the
    journal, and OpenAlex switching the paper's primary location to a repository (always
    false) would otherwise look like a delisting."""
    if not _both_ok(previous, current, "openalex"):
        return None
    if not (previous.in_doaj is True and current.in_doaj is False):
        return None
    same_journal = previous.journal_source_id is not None and previous.journal_source_id == current.journal_source_id
    if not same_journal or current.journal_source_type != JOURNAL:
        return None
    # keyed by snapshot: a journal can be delisted, relisted and delisted again
    return Change(
        change_type=ChangeType.DOAJ_DELISTING,
        change_key=f"doaj_delisting:{current.snapshot_id}",
        detected_at=current.fetched_at,
        snapshot_id=current.snapshot_id,
        previous_snapshot_id=previous.snapshot_id,
        journal=current.journal,
    )


def _change(
    previous: Snapshot, current: Snapshot, kind: ChangeType, key: str, notice: CrossrefUpdate | None = None
) -> Change:
    """create Change object"""
    return Change(
        change_type=kind,
        change_key=key,
        detected_at=current.fetched_at,
        snapshot_id=current.snapshot_id,
        previous_snapshot_id=previous.snapshot_id,
        notice_doi=notice.notice_doi if notice else None,
        notice_type=notice.type if notice else None,
        notice_label=notice.label if notice else None,
        notice_date=notice.date if notice else None,
    )
