"""The plain comparison that decides whether to nudge Research Evaluation
(docs/CONTRACTS.md, "When Updating nudges").

Nothing is classified or stored: the reasons are only logged. A field that is null in either
snapshot, or whose source wasn't `ok`, is never compared, so a source that doesn't know the
DOI can't look like a status change. (Stored snapshots never have `error` for Crossref or
OpenAlex, so in practice that only skips `not_found`.)"""

from updating.snapshot import CrossrefUpdate, Snapshot
from updating.sources import SourceStatus

NUDGE_UPDATE_TYPES = frozenset({"retraction", "correction", "erratum", "expression_of_concern"})


def _both_ok(previous: SourceStatus, new: SourceStatus) -> bool:
    return previous is SourceStatus.OK and new is SourceStatus.OK


def _update_key(update: CrossrefUpdate) -> tuple[str | None, str | None]:
    """Crossref lists a notice once per source, so an entry is its (notice DOI, type)."""
    return update.notice_doi, update.type


def nudge_reasons(previous: Snapshot, new: Snapshot) -> list[str]:
    reasons: list[str] = []
    openalex_ok = _both_ok(previous.source_status.openalex, new.source_status.openalex)

    if openalex_ok and previous.is_retracted is False and new.is_retracted is True:
        reasons.append("is_retracted false -> true")

    # No journal check on purpose: OpenAlex switching the work's primary location from the
    # journal to a repository also flips this. Telling that from a delisting is Research
    # Evaluation's job (docs/CONTRACTS.md, "Snapshot fields").
    if openalex_ok and previous.in_doaj is True and new.in_doaj is False:
        reasons.append("in_doaj true -> false")

    if (
        _both_ok(previous.source_status.crossref, new.source_status.crossref)
        and previous.crossref_updates is not None
        and new.crossref_updates is not None
    ):
        known = {_update_key(update) for update in previous.crossref_updates}
        for update in new.crossref_updates:
            key = _update_key(update)
            if update.type in NUDGE_UPDATE_TYPES and key not in known:
                known.add(key)  # a pair listed by two sources is one entry
                reasons.append(f"new {update.type} notice {update.notice_doi}")

    return reasons
