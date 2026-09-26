from datetime import UTC, datetime

import pytest

from research_evaluation.changes import Change, ChangeType
from research_evaluation.rules import Severity, assess

DETECTED = datetime(2026, 9, 2, 12, 0, tzinfo=UTC)


def change(kind: ChangeType, **fields) -> Change:
    return Change(change_type=kind, change_key=f"{kind}:x", detected_at=DETECTED, snapshot_id=2,
                  previous_snapshot_id=1, **fields)


@pytest.mark.parametrize(
    ("kind", "severity"),
    [
        (ChangeType.RETRACTION, Severity.HIGH),
        (ChangeType.EXPRESSION_OF_CONCERN, Severity.MEDIUM),
        (ChangeType.CORRECTION, Severity.MEDIUM),
        (ChangeType.ERRATUM, Severity.LOW),
        (ChangeType.DOAJ_DELISTING, Severity.LOW),
        (ChangeType.OTHER, Severity.MEDIUM),
    ],
)
def test_each_kind_of_change_has_its_severity(kind, severity):
    assert assess(change(kind)).severity is severity


@pytest.mark.parametrize("kind", list(ChangeType))
def test_every_kind_gets_readable_text_even_with_no_details(kind):
    assessment = assess(change(kind))

    assert assessment.description.strip()
    assert assessment.recommendation.strip()
    assert "None" not in assessment.description + assessment.recommendation
    assert "()" not in assessment.description


@pytest.mark.parametrize(
    "kind",
    [ChangeType.RETRACTION, ChangeType.EXPRESSION_OF_CONCERN, ChangeType.CORRECTION, ChangeType.ERRATUM,
     ChangeType.OTHER],
)
def test_the_notice_doi_and_date_are_in_the_description(kind):
    description = assess(change(kind, notice_doi="10.1/notice", notice_date="2024-12-16")).description

    assert "(notice 10.1/notice, 2024-12-16)" in description


def test_a_notice_with_only_a_date_says_just_the_date():
    assert "(2024-12-16)" in assess(change(ChangeType.CORRECTION, notice_date="2024-12-16")).description


def test_a_retraction_says_the_paper_was_retracted():
    assessment = assess(change(ChangeType.RETRACTION))

    assert "retracted" in assessment.description
    assert "retraction notice" in assessment.recommendation


def test_a_delisting_names_the_journal():
    named = assess(change(ChangeType.DOAJ_DELISTING, journal="Some Journal")).description
    unnamed = assess(change(ChangeType.DOAJ_DELISTING)).description

    assert named.startswith("Some Journal was removed from the Directory of Open Access Journals")
    assert unnamed.startswith("The journal this paper was published in was removed")


def test_an_other_notice_is_named_by_its_label_then_its_type():
    by_label = assess(change(ChangeType.OTHER, notice_type="withdrawal", notice_label="Withdrawal")).description
    by_type = assess(change(ChangeType.OTHER, notice_type="withdrawal")).description

    assert "a 'Withdrawal' notice" in by_label
    assert "a 'withdrawal' notice" in by_type
    assert "isn't a type of change we assess yet" in by_label
