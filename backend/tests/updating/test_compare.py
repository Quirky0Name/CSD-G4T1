import pytest
from updating_support import fixture_snapshot

from updating.compare import nudge_reasons
from updating.snapshot import CrossrefUpdate, SourceStatuses
from updating.sources import SourceStatus

OK, NOT_FOUND = SourceStatus.OK, SourceStatus.NOT_FOUND


def snap(**changes):
    """A paper with nothing to report: not retracted, no Crossref notices."""
    return fixture_snapshot("jbc", **{"is_retracted": False, "crossref_updates": [], **changes})


def statuses(crossref=OK, openalex=OK):
    return SourceStatuses(crossref=crossref, openalex=openalex, openalex_authors=OK)


def notice(notice_doi, type_="retraction", source="publisher"):
    return CrossrefUpdate(
        notice_doi=notice_doi, type=type_, label=type_.title(), source=source, date=None, record_id=None
    )


def test_identical_snapshots_do_not_nudge():
    assert nudge_reasons(snap(), snap()) == []


def test_is_retracted_false_to_true_nudges():
    assert nudge_reasons(snap(), snap(is_retracted=True)) == ["is_retracted false -> true"]


@pytest.mark.parametrize(
    ("previous", "new"),
    [(True, True), (True, False), (None, True), (False, None), (None, None)],
)
def test_any_other_is_retracted_change_does_not_nudge(previous, new):
    assert nudge_reasons(snap(is_retracted=previous), snap(is_retracted=new)) == []


def test_a_new_retraction_notice_nudges():
    reasons = nudge_reasons(snap(), snap(crossref_updates=[notice("10.1/n")]))

    assert reasons == ["new retraction notice 10.1/n"]


def test_the_same_notice_from_two_sources_is_one_nudge_reason():
    both = [notice("10.1/n", source="publisher"), notice("10.1/n", source="retraction-watch")]

    assert nudge_reasons(snap(), snap(crossref_updates=both)) == ["new retraction notice 10.1/n"]


def test_a_known_pair_from_a_new_source_does_not_nudge():
    known = snap(crossref_updates=[notice("10.1/n", source="publisher")])
    another_source = snap(
        crossref_updates=[notice("10.1/n", source="publisher"), notice("10.1/n", source="retraction-watch")]
    )

    assert nudge_reasons(known, another_source) == []


def test_an_identical_pair_does_not_nudge():
    same = [notice("10.1/n")]

    assert nudge_reasons(snap(crossref_updates=same), snap(crossref_updates=same)) == []


def test_types_we_do_not_alert_on_do_not_nudge():
    assert nudge_reasons(snap(), snap(crossref_updates=[notice("10.1/n", "withdrawal")])) == []


@pytest.mark.parametrize("side", ["previous", "new"])
def test_a_null_notice_list_is_never_compared(side):
    with_notice = snap(crossref_updates=[notice("10.1/n")])
    without_data = snap(crossref_updates=None, source_status=statuses(crossref=NOT_FOUND))
    previous, new = (without_data, with_notice) if side == "previous" else (with_notice, without_data)

    assert nudge_reasons(previous, new) == []


@pytest.mark.parametrize("side", ["previous", "new"])
@pytest.mark.parametrize(
    ("source", "before", "after"),
    [
        ("openalex", {"is_retracted": False}, {"is_retracted": True}),
        ("crossref", {"crossref_updates": []}, {"crossref_updates": [notice("10.1/n")]}),
    ],
    ids=["is_retracted", "crossref_updates"],
)
def test_a_field_whose_source_was_not_ok_is_never_compared(side, source, before, after):
    previous, new = snap(**before), snap(**after)
    assert nudge_reasons(previous, new) != []  # control: with both sources ok, this change nudges

    not_ok = {"source_status": statuses(**{source: NOT_FOUND})}
    if side == "previous":
        previous = previous.model_copy(update=not_ok)
    else:
        new = new.model_copy(update=not_ok)

    assert nudge_reasons(previous, new) == []
