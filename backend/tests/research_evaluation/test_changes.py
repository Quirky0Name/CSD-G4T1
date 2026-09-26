from datetime import UTC, datetime

import pytest
from research_evaluation_support import notice, snapshot_dict
from support import load_fixture

from common.doi import Doi
from research_evaluation.changes import ChangeType, Snapshot, find_changes
from updating.snapshot import build_snapshot
from updating.sources import CrossrefWork, OpenAlexWork


def snap(snapshot_id: int, **fields) -> Snapshot:
    return Snapshot.model_validate(snapshot_dict(snapshot_id, **fields))


def kinds_and_keys(changes):
    return [(change.change_type, change.change_key) for change in changes]


def test_identical_snapshots_have_no_changes():
    assert find_changes(snap(1), snap(2)) == []


# --- retraction ---


def test_is_retracted_turning_true_is_a_retraction_found_in_the_new_snapshot():
    [change] = find_changes(snap(1), snap(2, is_retracted=True))

    assert change.change_type is ChangeType.RETRACTION
    assert change.change_key == "retraction"
    assert change.detected_at == snap(2).fetched_at
    assert (change.snapshot_id, change.previous_snapshot_id) == (2, 1)
    assert change.notice_doi is None


def test_a_new_crossref_retraction_notice_is_a_retraction_with_its_details():
    retraction = notice("10.1016/j.ijantimicag.2024.107416", "retraction", date="2024-12-16")

    [change] = find_changes(snap(1), snap(2, crossref_updates=[retraction]))

    assert (change.change_type, change.change_key) == (ChangeType.RETRACTION, "retraction")
    assert change.notice_doi == "10.1016/j.ijantimicag.2024.107416"
    assert change.notice_date == "2024-12-16"


def test_the_flag_and_the_notice_in_one_poll_are_one_retraction_with_the_notice():
    retraction = notice("10.1/notice", "retraction")

    changes = find_changes(snap(1), snap(2, is_retracted=True, crossref_updates=[retraction]))

    assert kinds_and_keys(changes) == [(ChangeType.RETRACTION, "retraction")]
    assert changes[0].notice_doi == "10.1/notice"


def test_the_flag_and_the_notice_on_different_polls_share_one_change_key():
    # Storage Management keeps the first alert for a key, so they end up as one alert
    first = find_changes(snap(1), snap(2, is_retracted=True))
    second = find_changes(snap(2, is_retracted=True), snap(3, is_retracted=True,
                                                            crossref_updates=[notice("10.1/n", "retraction")]))

    assert [c.change_key for c in first] == [c.change_key for c in second] == ["retraction"]


@pytest.mark.parametrize(
    ("before", "after"),
    [(True, True), (False, False), (True, False), (None, True), (False, None)],
)
def test_is_retracted_only_counts_going_from_false_to_true(before, after):
    assert find_changes(snap(1, is_retracted=before), snap(2, is_retracted=after)) == []


@pytest.mark.parametrize(("before", "after"), [("not_found", "ok"), ("ok", "not_found"), ("ok", "error")])
def test_is_retracted_is_not_compared_unless_openalex_was_ok_in_both(before, after):
    previous = snap(1, source_status={"crossref": "ok", "openalex": before})
    current = snap(2, is_retracted=True, source_status={"crossref": "ok", "openalex": after})

    assert find_changes(previous, current) == []


# --- corrections, errata, expressions of concern ---


@pytest.mark.parametrize(
    ("crossref_type", "kind"),
    [
        ("correction", ChangeType.CORRECTION),
        ("erratum", ChangeType.ERRATUM),
        ("expression_of_concern", ChangeType.EXPRESSION_OF_CONCERN),
    ],
)
def test_a_new_notice_of_a_classified_type_is_that_change(crossref_type, kind):
    [change] = find_changes(snap(1), snap(2, crossref_updates=[notice("10.1/n", crossref_type)]))

    assert change.change_type is kind
    assert change.change_key == f"{crossref_type}:10.1/n"
    assert change.notice_doi == "10.1/n"
    assert change.notice_label == crossref_type.replace("_", " ").capitalize()


def test_a_new_source_for_a_known_notice_is_not_a_change():
    before = [notice("10.1/n", "correction", source="retraction-watch")]
    after = [*before, notice("10.1/n", "correction", source="publisher")]

    assert find_changes(snap(1, crossref_updates=before), snap(2, crossref_updates=after)) == []


def test_one_new_notice_listed_by_two_sources_is_one_change():
    after = [notice("10.1/n", "correction", source="retraction-watch"), notice("10.1/n", "correction")]

    assert kinds_and_keys(find_changes(snap(1), snap(2, crossref_updates=after))) == [
        (ChangeType.CORRECTION, "correction:10.1/n")
    ]


@pytest.mark.parametrize(
    ("types", "kind", "key"),
    [
        # listed in either order, the most severe type decides the one alert
        (["correction", "erratum"], ChangeType.CORRECTION, "correction:10.1/n"),
        (["erratum", "correction"], ChangeType.CORRECTION, "correction:10.1/n"),
        # both medium: a classified type beats an unclassified one, and a concern beats a correction
        (["correction", "expression_of_concern"], ChangeType.EXPRESSION_OF_CONCERN, "expression_of_concern:10.1/n"),
        (["withdrawal", "correction"], ChangeType.CORRECTION, "correction:10.1/n"),
        (["erratum", "withdrawal"], ChangeType.OTHER, "other:withdrawal:10.1/n"),
    ],
)
def test_one_notice_under_several_types_is_one_change_of_the_most_severe_type(types, kind, key):
    after = [notice("10.1/n", crossref_type) for crossref_type in types]

    assert kinds_and_keys(find_changes(snap(1), snap(2, crossref_updates=after))) == [(kind, key)]


def test_a_known_notice_filed_under_a_new_type_later_is_not_a_new_change():
    before = [notice("10.1/n", "correction")]
    after = [*before, notice("10.1/n", "erratum")]

    assert find_changes(snap(1, crossref_updates=before), snap(2, crossref_updates=after)) == []


def test_a_known_notice_filed_as_a_retraction_later_is_the_retraction():
    before = [notice("10.1/n", "expression_of_concern")]
    after = [*before, notice("10.1/n", "retraction")]

    changes = find_changes(snap(1, crossref_updates=before), snap(2, crossref_updates=after))

    assert kinds_and_keys(changes) == [(ChangeType.RETRACTION, "retraction")]
    assert changes[0].notice_doi == "10.1/n"


def test_notices_without_a_doi_cannot_be_matched_up_and_each_count():
    after = [notice(None, "correction"), notice(None, "erratum")]

    assert kinds_and_keys(find_changes(snap(1), snap(2, crossref_updates=after))) == [
        (ChangeType.CORRECTION, "correction:"),
        (ChangeType.ERRATUM, "erratum:"),
    ]


def test_a_retraction_notice_also_filed_under_another_type_is_only_the_retraction():
    # IJAA's retraction notice is a Retraction Watch "retraction" and a publisher "erratum"
    after = [
        notice("10.1016/j.ijantimicag.2024.107416", "retraction", source="retraction-watch"),
        notice("10.1016/j.ijantimicag.2024.107416", "erratum"),
        notice("10.1/unrelated", "erratum"),
    ]

    assert kinds_and_keys(find_changes(snap(1), snap(2, crossref_updates=after))) == [
        (ChangeType.RETRACTION, "retraction"),
        (ChangeType.ERRATUM, "erratum:10.1/unrelated"),
    ]


def test_notices_already_in_the_previous_snapshot_are_not_changes():
    known = [notice("10.1/old", "correction")]
    after = [*known, notice("10.1/new", "erratum")]

    assert kinds_and_keys(find_changes(snap(1, crossref_updates=known), snap(2, crossref_updates=after))) == [
        (ChangeType.ERRATUM, "erratum:10.1/new")
    ]


@pytest.mark.parametrize(("before", "after"), [(None, [notice("10.1/n", "correction")]), ([], None)])
def test_crossref_updates_are_not_compared_when_null_in_either(before, after):
    assert find_changes(snap(1, crossref_updates=before), snap(2, crossref_updates=after)) == []


@pytest.mark.parametrize(("before", "after"), [("not_found", "ok"), ("ok", "not_found"), ("error", "ok")])
def test_crossref_updates_are_not_compared_unless_crossref_was_ok_in_both(before, after):
    previous = snap(1, source_status={"crossref": before, "openalex": "ok"})
    current = snap(2, crossref_updates=[notice("10.1/n", "correction")],
                   source_status={"crossref": after, "openalex": "ok"})

    assert find_changes(previous, current) == []


# --- types we don't classify ---


@pytest.mark.parametrize("crossref_type", ["withdrawal", "removal", "partial_retraction", "new_edition"])
def test_a_new_notice_of_a_type_we_dont_classify_is_kept_as_other(crossref_type):
    entry = notice("10.1/n", crossref_type, label="Some label", date="2025-01-01")

    [change] = find_changes(snap(1), snap(2, crossref_updates=[entry]))

    assert change.change_type is ChangeType.OTHER
    assert change.change_key == f"other:{crossref_type}:10.1/n"
    assert (change.notice_type, change.notice_label, change.notice_date) == (crossref_type, "Some label", "2025-01-01")


def test_a_notice_with_no_type_is_skipped():
    assert find_changes(snap(1), snap(2, crossref_updates=[notice("10.1/n", None)])) == []


def test_a_notice_with_no_doi_still_gets_a_key():
    [change] = find_changes(snap(1), snap(2, crossref_updates=[notice(None, "correction")]))

    assert change.change_key == "correction:"


# --- DOAJ ---


def test_leaving_doaj_in_the_same_journal_is_a_delisting_keyed_by_snapshot():
    [change] = find_changes(snap(1), snap(2, in_doaj=False))

    assert change.change_type is ChangeType.DOAJ_DELISTING
    assert change.change_key == "doaj_delisting:2"
    assert change.journal == "International Journal of Antimicrobial Agents"
    assert change.detected_at == snap(2).fetched_at


def test_a_journal_delisted_again_after_being_relisted_is_a_new_change():
    first = find_changes(snap(1), snap(2, in_doaj=False))
    second = find_changes(snap(3, in_doaj=True), snap(4, in_doaj=False))

    assert [c.change_key for c in first + second] == ["doaj_delisting:2", "doaj_delisting:4"]


@pytest.mark.parametrize(
    "current_fields",
    [
        {"in_doaj": False, "journal_source_id": "S999", "journal_source_type": "journal"},  # journal changed
        {"in_doaj": False, "journal_source_id": "S49861241", "journal_source_type": "repository"},
        {"in_doaj": False, "journal_source_id": "S999", "journal_source_type": "repository"},  # moved to PubMed
        {"in_doaj": None},
        {"in_doaj": True},
    ],
)
def test_other_in_doaj_flips_are_not_delistings(current_fields):
    assert find_changes(snap(1), snap(2, **current_fields)) == []


def test_no_delisting_when_the_previous_journal_is_unknown():
    previous = snap(1, journal_source_id=None)
    current = snap(2, in_doaj=False, journal_source_id=None)

    assert find_changes(previous, current) == []


def test_in_doaj_is_not_compared_unless_openalex_was_ok_in_both():
    previous = snap(1, source_status={"crossref": "ok", "openalex": "not_found"})

    assert find_changes(previous, snap(2, in_doaj=False)) == []


# --- several at once, and Updating's real snapshot shape ---


def test_every_kind_of_change_in_one_poll():
    after = [
        notice("10.1/r", "retraction"),
        notice("10.1/c", "correction"),
        notice("10.1/w", "withdrawal"),
    ]

    changes = find_changes(snap(1), snap(2, is_retracted=True, crossref_updates=after, in_doaj=False))

    assert kinds_and_keys(changes) == [
        (ChangeType.RETRACTION, "retraction"),
        (ChangeType.CORRECTION, "correction:10.1/c"),
        (ChangeType.OTHER, "other:withdrawal:10.1/w"),
        (ChangeType.DOAJ_DELISTING, "doaj_delisting:2"),
    ]
    assert {change.detected_at for change in changes} == {snap(2).fetched_at}


def stored_by_updating(name: str, doi: str, snapshot_id: int) -> dict:
    """The snapshot Updating builds from the recorded Crossref/OpenAlex fixtures, as Storage
    Management would return it."""
    crossref = CrossrefWork.model_validate(load_fixture("crossref", name)["message"])
    openalex = OpenAlexWork.model_validate(load_fixture("openalex", name))
    built = build_snapshot(Doi(doi), datetime(2026, 9, 25, 12, 0, tzinfo=UTC), crossref, openalex)
    return {"snapshot_id": snapshot_id, "paper_id": "00000000-0000-0000-0000-000000000001",
            **built.model_dump(mode="json")}


def test_ijaa_as_updating_stores_it_is_one_retraction_against_a_pre_retraction_baseline():
    current = stored_by_updating("ijaa", "10.1016/j.ijantimicag.2020.105949", 2)
    baseline = {**current, "snapshot_id": 1, "is_retracted": False, "crossref_updates": []}

    changes = find_changes(Snapshot.model_validate(baseline), Snapshot.model_validate(current))

    assert kinds_and_keys(changes) == [(ChangeType.RETRACTION, "retraction")]
    assert changes[0].notice_doi == "10.1016/j.ijantimicag.2024.107416"
    assert changes[0].detected_at == datetime(2026, 9, 25, 12, 0, tzinfo=UTC)


def test_lancet_as_updating_stores_it_against_an_empty_baseline():
    current = stored_by_updating("lancet", "10.1016/s0140-6736(20)31180-6", 2)
    baseline = {**current, "snapshot_id": 1, "is_retracted": False, "crossref_updates": []}

    changes = find_changes(Snapshot.model_validate(baseline), Snapshot.model_validate(current))

    # the correction notice 31249-6 is also a publisher "erratum": one alert, as a correction
    assert kinds_and_keys(changes) == [
        (ChangeType.RETRACTION, "retraction"),
        (ChangeType.EXPRESSION_OF_CONCERN, "expression_of_concern:10.1016/s0140-6736(20)31290-3"),
        (ChangeType.CORRECTION, "correction:10.1016/s0140-6736(20)31249-6"),
        (ChangeType.ERRATUM, "erratum:10.1016/s0140-6736(20)31528-2"),
    ]
    notices = [change.notice_doi for change in changes]
    assert len(set(notices)) == len(notices)
