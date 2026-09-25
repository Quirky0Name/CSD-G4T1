from datetime import UTC, datetime

import pytest
from support import load_fixture
from updating_support import author_batch

from common.doi import Doi
from updating.snapshot import MAX_AUTHORS, build_snapshot
from updating.sources import (
    CrossrefWork,
    OpenAlexWork,
    SourceStatus,
)

FETCHED_AT = datetime(2026, 9, 25, 12, 0, tzinfo=UTC)


def crossref(name: str) -> CrossrefWork:
    return CrossrefWork.model_validate(load_fixture("crossref", name)["message"])


def openalex(name: str) -> OpenAlexWork:
    return OpenAlexWork.model_validate(load_fixture("openalex", name))


def snapshot(doi, cr, oa, authors=()):
    """`authors` is the batch result; the default is a batch that returned no rows."""
    return build_snapshot(Doi(doi), FETCHED_AT, cr, oa, authors)


def author_tuples(snap):
    return [(a.openalex_author_id, a.h_index, a.works_count) for a in snap.authors]


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
    snap = snapshot(
        "10.1016/s0140-6736(20)31180-6", crossref("lancet"), openalex("lancet"), author_batch("lancet")
    )

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
        "openalex_authors": "ok",
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
    snap = snapshot(
        "10.48550/arxiv.1201.0490", SourceStatus.NOT_FOUND, openalex("arxiv"), author_batch("arxiv")
    )

    assert snap.crossref_updates is None
    assert snap.is_retracted is False
    assert snap.journal_source_type == "repository"
    assert snap.issn_l is None
    assert snap.source_status.model_dump() == {
        "crossref": "not_found",
        "openalex": "ok",
        "openalex_authors": "ok",
    }


def test_both_sources_not_found_leaves_only_identity_and_status():
    snap = snapshot(
        "10.9999/x", SourceStatus.NOT_FOUND, SourceStatus.NOT_FOUND, SourceStatus.NOT_FOUND
    )

    stored = snap.model_dump(exclude={"doi", "fetched_at", "source_status"})
    assert all(value is None for value in stored.values())
    assert snap.source_status.model_dump() == {
        "crossref": "not_found",
        "openalex": "not_found",
        "openalex_authors": "not_found",
    }


def test_openalex_failure_makes_every_openalex_field_null_never_false():
    snap = snapshot("10.1/x", crossref("lancet"), SourceStatus.ERROR, SourceStatus.ERROR)

    assert snap.is_retracted is None
    assert snap.in_doaj is None
    assert snap.cited_by_count is None
    assert snap.authors is None
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


# (author id, h_index, works_count) in authorship order, copied from the recorded batches
EXPECTED_AUTHORS = {
    "ijaa": [
        ("A5012924666", 64, 519),
        ("A5044290242", 71, 565),
        ("A5061281389", 87, 828),
        ("A5015077227", 31, 165),
        ("A5014150641", 14, 53),
        ("A5042090217", 18, 74),
        ("A5017977503", 17, 48),
        ("A5032564627", 25, 134),
        ("A5013087130", 21, 68),
        ("A5088058381", 4, 4),
    ],
    "lancet": [
        ("A5056960938", 98, 1071),
        ("A5020874464", 29, 148),
        ("A5013727186", 122, 846),
        ("A5076233641", 30, 171),
    ],
    "jbc": [
        ("A5110127684", 2, 2),
        ("A5032482932", 1, 1),
        ("A5004071084", 8, 10),
        ("A5109894513", 1, 1),
    ],
    "arxiv": [
        ("A5105141183", 13, 75),
        ("A5074733625", 61, 439),
        ("A5018256474", 56, 384),
        ("A5027430303", 39, 108),
        ("A5026762833", 55, 449),
        ("A5112700874", 19, 51),
        ("A5049123454", 25, 97),
        (None, None, None),
        (None, None, None),
        (None, None, None),
    ],
}


@pytest.mark.parametrize("name", EXPECTED_AUTHORS)
def test_authors_follow_the_authorships_not_the_batch_order(name):
    batch = author_batch(name)
    work = openalex(name)
    authorship_ids = [a.author.id.rsplit("/", 1)[-1] for a in work.authorships if a.author.id]
    batch_ids = [row.id.rsplit("/", 1)[-1] for row in batch]
    assert batch_ids != authorship_ids[: len(batch_ids)]  # the fixture really is out of order

    snap = snapshot("10.1/x", SourceStatus.NOT_FOUND, work, batch)

    assert author_tuples(snap) == EXPECTED_AUTHORS[name]
    assert snap.source_status.openalex_authors == "ok"


def test_only_the_first_ten_authorships_are_kept():
    work = openalex("ijaa")
    assert len(work.authorships) == 18
    assert work.authorships[-1].author_position == "last"

    snap = snapshot("10.1/x", SourceStatus.NOT_FOUND, work, author_batch("ijaa"))

    assert len(snap.authors) == MAX_AUTHORS
    assert "last" not in {a.position for a in snap.authors}
    assert snap.authors[0].position == "first"


def test_author_name_position_and_institutions_come_from_the_authorship():
    snap = snapshot(
        "10.1/x", SourceStatus.NOT_FOUND, openalex("lancet"), author_batch("lancet")
    )

    first, no_institution, _, last = snap.authors
    assert (first.name, first.position) == ("Mandeep R. Mehra", "first")
    assert first.institutions == ["Brigham and Women's Hospital", "Harvard University"]
    assert (no_institution.position, no_institution.institutions) == ("middle", [])
    assert (last.position, last.institutions) == ("last", ["University of Utah"])


def test_a_failed_author_batch_keeps_the_authors_with_null_stats():
    snap = snapshot("10.1/x", SourceStatus.NOT_FOUND, openalex("lancet"), SourceStatus.ERROR)

    assert [a.openalex_author_id for a in snap.authors] == [
        "A5056960938", "A5020874464", "A5013727186", "A5076233641",
    ]  # fmt: skip
    assert all(a.h_index is None and a.works_count is None for a in snap.authors)
    assert all(a.name and a.position for a in snap.authors)
    assert snap.authors[0].institutions == ["Brigham and Women's Hospital", "Harvard University"]
    assert snap.source_status.openalex_authors == "error"


def test_a_work_with_no_authors_has_an_empty_list():
    work = OpenAlexWork.model_validate({"id": "https://openalex.org/W1", "authorships": []})

    snap = snapshot("10.1/x", SourceStatus.NOT_FOUND, work)

    assert snap.authors == []
    assert snap.source_status.openalex_authors == "ok"


def test_an_author_missing_from_the_batch_has_null_stats():
    batch = [row for row in author_batch("lancet") if not row.id.endswith("A5013727186")]

    snap = snapshot("10.1/x", SourceStatus.NOT_FOUND, openalex("lancet"), batch)

    assert [a.h_index for a in snap.authors] == [98, 29, None, 30]
    assert snap.source_status.openalex_authors == "ok"


def test_serialises_to_the_contract_shape():
    snap = snapshot("10.1016/s0140-6736(20)31180-6", crossref("lancet"), openalex("lancet"))

    body = snap.model_dump(mode="json")

    assert body["fetched_at"] == "2026-09-25T12:00:00Z"
    assert set(body) == {
        "doi", "fetched_at", "openalex_id", "title", "publication_year", "is_retracted",
        "crossref_updates", "in_doaj", "journal_source_id", "journal_source_type", "journal",
        "issn_l", "publisher", "authors", "cited_by_count", "source_status",
    }  # fmt: skip
    assert set(body["authors"][0]) == {
        "name", "openalex_author_id", "position", "institutions", "h_index", "works_count",
    }  # fmt: skip
    assert set(body["crossref_updates"][0]) == {
        "notice_doi", "type", "label", "source", "date", "record_id",
    }  # fmt: skip
