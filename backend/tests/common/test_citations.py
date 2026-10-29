import pytest

from common.citations import is_citation_jump


@pytest.mark.parametrize(("previous", "current"), [(0, 10), (100, 120), (50, 60), (952, 1252)])
def test_a_rise_of_at_least_10_and_20_percent_is_a_citation_jump(previous, current):
    assert is_citation_jump(previous, current)


@pytest.mark.parametrize(
    ("previous", "current"),
    [(100, 119), (1, 9), (0, 9), (10_000, 10_050), (1247, 1252), (5, 5), (120, 100), (1252, 0)],
)
def test_a_small_rise_no_change_or_a_drop_is_not_a_citation_jump(previous, current):
    assert not is_citation_jump(previous, current)
