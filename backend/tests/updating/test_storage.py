import pytest
from updating_support import DOIS, fixture_snapshot

from updating import storage
from updating.storage import latest_snapshot


@pytest.mark.parametrize("count", [0, 1, 2, 4, 5])
async def test_latest_snapshot_is_the_last_row_across_pages(sm, monkeypatch, count):
    monkeypatch.setattr(storage, "HISTORY_PAGE_SIZE", 2)  # 4 rows fill two pages exactly
    paper = await sm.add_paper(DOIS["jbc"])
    for n in range(count):
        await sm.seed(paper, fixture_snapshot("jbc", cited_by_count=n))

    latest = await latest_snapshot(sm.client, paper, after_id=None)

    if count == 0:
        assert latest is None
    else:
        assert latest.cited_by_count == count - 1


async def test_latest_snapshot_only_looks_above_after_id(sm):
    paper = await sm.add_paper(DOIS["jbc"])
    ids = [await sm.seed(paper, fixture_snapshot("jbc", cited_by_count=n)) for n in range(3)]

    from_last = await latest_snapshot(sm.client, paper, after_id=ids[-1] - 1)
    past_last = await latest_snapshot(sm.client, paper, after_id=ids[-1])

    assert from_last.snapshot_id == ids[-1]
    assert past_last is None
