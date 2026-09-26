import pytest

from dev.scenarios import fixture_snapshot
from updating.compare import nudge_reasons
from updating.snapshot import CrossrefUpdate, SourceStatuses
from updating.sources import SourceStatus

OK, NOT_FOUND = SourceStatus.OK, SourceStatus.NOT_FOUND
ALERT_TYPES = ["retraction", "correction", "erratum", "expression_of_concern"]


def snap(**changes):
    """A paper with nothing to report: not retracted, no Crossref notices, in DOAJ."""
    defaults = {"is_retracted": False, "crossref_updates": [], "in_doaj": True}
    return fixture_snapshot("jbc", **{**defaults, **changes})


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


@pytest.mark.parametrize("type_", ALERT_TYPES)
def test_a_new_notice_of_an_alerting_type_nudges(type_):
    reasons = nudge_reasons(snap(), snap(crossref_updates=[notice("10.1/n", type_)]))

    assert reasons == [f"new {type_} notice 10.1/n"]


@pytest.mark.parametrize("type_", ALERT_TYPES)
def test_the_same_notice_from_two_sources_is_one_nudge_reason(type_):
    both = [
        notice("10.1/n", type_, source="publisher"),
        notice("10.1/n", type_, source="retraction-watch"),
    ]

    assert nudge_reasons(snap(), snap(crossref_updates=both)) == [f"new {type_} notice 10.1/n"]


@pytest.mark.parametrize("type_", ALERT_TYPES)
def test_a_known_pair_from_a_new_source_does_not_nudge(type_):
    known = snap(crossref_updates=[notice("10.1/n", type_, source="publisher")])
    another_source = snap(
        crossref_updates=[
            notice("10.1/n", type_, source="publisher"),
            notice("10.1/n", type_, source="retraction-watch"),
        ]
    )

    assert nudge_reasons(known, another_source) == []


def test_the_same_notice_under_another_type_is_a_new_entry():
    """Lancet's notice 10.1016/s0140-6736(20)31324-6 is listed as a retraction and as an erratum."""
    retraction_only = snap(crossref_updates=[notice("10.1/n", "retraction")])
    also_erratum = snap(crossref_updates=[notice("10.1/n", "retraction"), notice("10.1/n", "erratum")])

    assert nudge_reasons(retraction_only, also_erratum) == ["new erratum notice 10.1/n"]


def test_an_identical_pair_does_not_nudge():
    same = [notice("10.1/n")]

    assert nudge_reasons(snap(crossref_updates=same), snap(crossref_updates=same)) == []


@pytest.mark.parametrize("type_", ["withdrawal", "removal", "partial_retraction"])
def test_types_we_do_not_alert_on_do_not_nudge(type_):
    assert nudge_reasons(snap(), snap(crossref_updates=[notice("10.1/n", type_)])) == []


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
        ("openalex", {"in_doaj": True}, {"in_doaj": False}),
        ("crossref", {"crossref_updates": []}, {"crossref_updates": [notice("10.1/n")]}),
    ],
    ids=["is_retracted", "in_doaj", "crossref_updates"],
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


def test_leaving_doaj_nudges():
    assert nudge_reasons(snap(in_doaj=True), snap(in_doaj=False)) == ["in_doaj true -> false"]


@pytest.mark.parametrize(
    ("previous", "new"),
    [(False, True), (True, True), (False, False), (None, False), (True, None)],
)
def test_any_other_in_doaj_change_does_not_nudge(previous, new):
    assert nudge_reasons(snap(in_doaj=previous), snap(in_doaj=new)) == []


def test_a_switch_from_the_journal_to_a_repository_still_nudges():
    """Updating doesn't classify: telling this apart from a delisting is Research Evaluation's
    job (CONTRACTS, "Snapshot fields")."""
    repository = {
        "in_doaj": False,
        "journal_source_id": "S4306401300",
        "journal_source_type": "repository",
        "journal": "ORBi (University of Liège)",
    }

    assert nudge_reasons(snap(), snap(**repository)) == ["in_doaj true -> false"]
