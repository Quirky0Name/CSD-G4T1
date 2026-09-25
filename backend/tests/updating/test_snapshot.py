from datetime import UTC, datetime

import pytest
from support import load_fixture

from common.doi import Doi
from updating.snapshot import build_snapshot
from updating.sources import CrossrefWork, OpenAlexWork, SourceStatus

FETCHED_AT = datetime(2026, 9, 25, 12, 0, tzinfo=UTC)


def crossref(name: str) -> CrossrefWork:
    return CrossrefWork.model_validate(load_fixture("crossref", name)["message"])


def openalex(name: str) -> OpenAlexWork:
    return OpenAlexWork.model_validate(load_fixture("openalex", name))


def snapshot(doi, cr, oa):
    return build_snapshot(Doi(doi), FETCHED_AT, cr, oa)


def test_lancet_keeps_every_updated_by_entry_in_order():
    snap = snapshot("10.1016/s0140-6736(20)31180-6", crossref("lancet"), openalex("lancet"))

    assert [(u.type, u.source) for u in snap.crossref_updates] == [
        ("expression_of_concern", "retraction-watch"),
        ("correction", "retraction-watch"),
        ("retraction", "retraction-watch"),
        ("retraction", "publisher"),
        ("erratum", "publisher"),
        ("erratum", "publisher"),
        ("erratum", "publisher"),
    ]
    first, _, _, publisher_retraction, *_ = snap.crossref_updates
    assert first.notice_doi == "10.1016/s0140-6736(20)31290-3"
    assert first.label == "Expression of concern"
    assert first.date == "2020-06-03"
    assert first.record_id == "23527"
    assert publisher_retraction.date == "2020-05-22"
    assert publisher_retraction.record_id is None


def test_lancet_openalex_fields():
    snap = snapshot("10.1016/s0140-6736(20)31180-6", crossref("lancet"), openalex("lancet"))

    assert snap.is_retracted is True
    assert snap.openalex_id == "W3027680906"
    assert snap.publication_year == 2020
    assert snap.cited_by_count == 1252
    assert snap.in_doaj is False
    assert snap.journal_source_id == "S49861241"
    assert snap.journal_source_type == "journal"
    assert snap.journal == "The Lancet"
    assert snap.publisher == "Elsevier BV"
    assert snap.source_status.model_dump() == {
        "crossref": "ok",
        "openalex": "ok",
        "openalex_authors": None,
    }


def test_ijaa_is_retracted_in_both_sources():
    snap = snapshot("10.1016/j.ijantimicag.2020.105949", crossref("ijaa"), openalex("ijaa"))

    assert snap.is_retracted is True
    assert "retraction" in {u.type for u in snap.crossref_updates}


def test_jbc_without_updated_by_has_an_empty_list_and_is_in_doaj():
    snap = snapshot("10.1016/s0021-9258(19)52451-6", crossref("jbc"), openalex("jbc"))

    assert snap.crossref_updates == []
    assert snap.is_retracted is False
    assert snap.in_doaj is True


def test_crossref_not_found_gives_null_updates_but_keeps_openalex():
    snap = snapshot("10.48550/arxiv.1201.0490", SourceStatus.NOT_FOUND, openalex("arxiv"))

    assert snap.crossref_updates is None
    assert snap.is_retracted is False
    assert snap.journal_source_type == "repository"
    assert snap.issn_l is None
    assert snap.source_status.model_dump() == {
        "crossref": "not_found",
        "openalex": "ok",
        "openalex_authors": None,
    }


def test_both_sources_not_found_leaves_only_identity_and_status():
    snap = snapshot("10.9999/x", SourceStatus.NOT_FOUND, SourceStatus.NOT_FOUND)

    stored = snap.model_dump(exclude={"doi", "fetched_at", "source_status"})
    assert all(value is None for value in stored.values())
    assert snap.source_status.crossref == snap.source_status.openalex == "not_found"


def test_openalex_failure_makes_every_openalex_field_null_never_false():
    snap = snapshot("10.1/x", crossref("lancet"), SourceStatus.ERROR)

    assert snap.is_retracted is None
    assert snap.in_doaj is None
    assert snap.cited_by_count is None
    assert snap.source_status.openalex == "error"
    assert snap.crossref_updates is not None


def test_work_without_a_primary_location_source_has_null_journal_fields():
    work = OpenAlexWork.model_validate({"id": "https://openalex.org/W1", "primary_location": None})

    snap = snapshot("10.1/x", SourceStatus.NOT_FOUND, work)

    assert snap.openalex_id == "W1"
    assert snap.in_doaj is None
    assert snap.journal_source_id is None
    assert snap.is_retracted is None


@pytest.mark.parametrize(
    ("date_parts", "expected"),
    [([[2020, 6, 3]], "2020-06-03"), ([[2020, 6]], "2020-06"), ([[2020]], "2020"), ([[None]], None)],
)
def test_partial_crossref_dates(date_parts, expected):
    work = CrossrefWork.model_validate(
        {"DOI": "10.1/x", "updated-by": [{"type": "correction", "updated": {"date-parts": date_parts}}]}
    )

    snap = snapshot("10.1/x", work, SourceStatus.NOT_FOUND)

    assert snap.crossref_updates[0].date == expected


def test_authors_stay_null_until_pr_2():
    snap = snapshot("10.1016/j.ijantimicag.2020.105949", crossref("ijaa"), openalex("ijaa"))

    assert snap.authors is None


def test_serialises_to_the_contract_shape():
    snap = snapshot("10.1016/s0140-6736(20)31180-6", crossref("lancet"), openalex("lancet"))

    body = snap.model_dump(mode="json")

    assert body["fetched_at"] == "2026-09-25T12:00:00Z"
    assert set(body) == {
        "doi", "fetched_at", "openalex_id", "title", "publication_year", "is_retracted",
        "crossref_updates", "in_doaj", "journal_source_id", "journal_source_type", "journal",
        "issn_l", "publisher", "authors", "cited_by_count", "source_status",
    }  # fmt: skip
    assert set(body["crossref_updates"][0]) == {
        "notice_doi", "type", "label", "source", "date", "record_id",
    }  # fmt: skip
