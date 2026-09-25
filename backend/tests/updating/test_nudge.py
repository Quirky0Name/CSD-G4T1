"""The nudge flow end to end: stub Storage Management and Research Evaluation in-process."""

import httpx
import pytest
from support import load_fixture
from updating_support import DOIS, fixture_snapshot, poll_runs, tracked_rows

from updating.models import PollTrigger
from updating.poll import run_poll

NUDGES = "/evaluate/changes"
# what Storage Management holds from an earlier poll, for a paper with nothing to report
NOTHING_YET = {"is_retracted": False, "crossref_updates": []}


async def poll(deps):
    return await run_poll(deps, PollTrigger.SCHEDULED)


async def add_seeded(sm, name, **changes):
    """A tracked paper whose earlier snapshot (a seeded baseline) is already in Storage Management."""
    paper = await sm.add_paper(DOIS[name])
    await sm.seed(paper, fixture_snapshot(name, **{**NOTHING_YET, **changes}))
    return paper


async def test_a_papers_first_snapshot_is_a_baseline_and_does_not_nudge(deps, sm, re, sources):
    sources.serve("lancet")
    paper = await sm.add_paper(DOIS["lancet"])

    summary = await poll(deps)

    assert [s.paper_id for s in summary.stored] == [paper]
    assert summary.nudged == [] and summary.nudge_error is None
    assert await re.received() == []
    assert (await tracked_rows(deps))[paper].nudge_pending is False


async def test_a_retraction_since_the_seeded_baseline_nudges_once(deps, sm, re, sources):
    sources.serve("lancet")
    paper = await add_seeded(sm, "lancet")

    summary = await poll(deps)

    assert summary.nudged == [paper] and summary.nudge_error is None
    assert await re.received() == [[paper]]
    assert (await tracked_rows(deps))[paper].nudge_pending is False
    [run] = await poll_runs(deps)
    assert run.summary["nudged"] == [str(paper)]


async def test_a_rerun_does_not_nudge_again_for_the_same_difference(deps, sm, re, sources):
    sources.serve("lancet")
    paper = await add_seeded(sm, "lancet")
    await poll(deps)

    summary = await poll(deps)

    assert summary.nudged == []
    assert await re.received() == [[paper]]


async def test_a_change_published_during_an_outage_nudges_when_the_source_is_back(deps, sm, re, sources):
    """Mon ok, Tue Crossref down, Wed ok. Tuesday stores nothing, so Wednesday is compared with Monday."""
    crossref = load_fixture("crossref", "lancet")
    monday_crossref = {**crossref, "message": {**crossref["message"], "updated-by": []}}
    monday_openalex = {**load_fixture("openalex", "lancet"), "is_retracted": False}
    sources.serve("lancet", crossref=monday_crossref, openalex=monday_openalex)
    paper = await sm.add_paper(DOIS["lancet"])
    await poll(deps)  # Monday

    sources.set("lancet", "crossref", 500)
    tuesday = await poll(deps)
    assert [e.paper_id for e in tuesday.source_errors] == [paper]
    assert tuesday.nudged == [] and len(await sm.history(paper)) == 1

    sources.set("lancet", "crossref", None)
    sources.set("lancet", "openalex", None)
    wednesday = await poll(deps)

    assert wednesday.nudged == [paper]
    assert await re.received() == [[paper]]
    assert len(await sm.history(paper)) == 2


@pytest.mark.parametrize(("name", "missing"), [("arxiv", "crossref"), ("jbc", "openalex")])
async def test_a_source_that_does_not_know_the_doi_does_not_nudge(deps, sm, re, sources, name, missing):
    sources.serve(name, **{missing: 404})
    paper = await add_seeded(sm, name)

    summary = await poll(deps)

    assert [s.paper_id for s in summary.stored] == [paper]
    assert summary.nudged == [] and await re.received() == []


@pytest.mark.parametrize(
    ("fault", "reason"),
    [(None, "HTTP 503"), (200, "HTTP 200"), (httpx.ConnectError("down"), "ConnectError")],
    ids=["RE down", "not a 202", "unreachable"],
)
async def test_a_failed_nudge_stays_pending_and_is_re_sent_on_the_next_run(deps, sm, re, sources, fault, reason):
    sources.serve("lancet")
    paper = await add_seeded(sm, "lancet")
    if fault is None:
        await re.fail()
    else:
        re.transport.faults[NUDGES] = fault

    failed = await poll(deps)

    assert failed.nudged == []
    assert failed.nudge_error.paper_ids == [paper] and failed.nudge_error.reason == reason
    assert (await tracked_rows(deps))[paper].nudge_pending is True
    [run] = await poll_runs(deps)
    assert run.summary["nudge_error"] == {"paper_ids": [str(paper)], "reason": reason}

    await re.fail(on=False)
    re.transport.faults.clear()
    retried = await poll(deps)  # nothing new changed: the flag alone drives the re-send

    assert retried.nudged == [paper] and retried.nudge_error is None
    assert (await tracked_rows(deps))[paper].nudge_pending is False
    assert await re.received() == [[paper]]
    assert (await poll(deps)).nudged == []
    assert await re.received() == [[paper]]


async def test_every_pending_paper_goes_out_in_one_call(deps, sm, re, sources):
    sources.serve("lancet")
    sources.serve("ijaa")
    lancet = await add_seeded(sm, "lancet")
    ijaa = await add_seeded(sm, "ijaa")

    summary = await poll(deps)

    assert set(summary.nudged) == {lancet, ijaa}
    assert [set(call) for call in await re.received()] == [{lancet, ijaa}]


async def test_a_failed_history_read_stores_nothing_and_the_change_is_caught_next_poll(deps, sm, re, sources):
    sources.serve("lancet")
    paper = await add_seeded(sm, "lancet")
    sm.transport.faults["/background-info/history"] = 503

    failed = await poll(deps)

    assert [(e.paper_id, e.reason) for e in failed.store_errors] == [(paper, "reading history: HTTP 503")]
    assert failed.stored == [] and failed.nudged == []

    sm.transport.faults.clear()
    assert len(await sm.history(paper)) == 1  # only the seeded baseline
    summary = await poll(deps)

    assert summary.nudged == [paper]
