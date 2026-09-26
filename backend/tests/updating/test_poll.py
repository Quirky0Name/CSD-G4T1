import asyncio
from datetime import UTC, datetime
from uuid import uuid4

import httpx
import pytest
from support import load_fixture
from updating_support import poll_runs, tracked_rows

from dev.scenarios import DOIS, fixture_snapshot
from updating import poll as poll_module
from updating.models import PollTrigger, RunStatus
from updating.poll import PollFailed, UnknownPaper, run_poll


async def poll(deps):
    return await run_poll(deps, PollTrigger.SCHEDULED)


async def test_stores_one_snapshot_per_paper_and_records_it(deps, sm, sources):
    sources.serve("lancet")
    sources.serve("jbc")
    lancet = await sm.add_paper(DOIS["lancet"])
    jbc = await sm.add_paper(DOIS["jbc"])

    summary = await poll(deps)

    assert [s.paper_id for s in summary.stored] == [lancet, jbc]
    for paper_id, name in [(lancet, "lancet"), (jbc, "jbc")]:
        [row] = await sm.history(paper_id)
        expected = fixture_snapshot(name).model_dump(mode="json")
        stored = {k: v for k, v in row.items() if k not in {"snapshot_id", "paper_id"}}
        assert stored | {"fetched_at": None} == expected | {"fetched_at": None}
        assert datetime.fromisoformat(row["fetched_at"]).microsecond == 0
        assert (await tracked_rows(deps))[paper_id].last_snapshot_id == row["snapshot_id"]

    [run] = await poll_runs(deps)
    assert run.status is RunStatus.SUCCEEDED
    assert run.trigger is PollTrigger.SCHEDULED
    assert run.finished_at is not None and run.error is None
    assert run.summary == summary.model_dump(mode="json")


async def test_papers_sharing_a_doi_are_fetched_once_and_each_get_a_snapshot(deps, sm, sources):
    sources.serve("lancet")
    first = await sm.add_paper(DOIS["lancet"])
    second = await sm.add_paper(DOIS["lancet"])

    await poll(deps)

    assert sources.routes[("lancet", "crossref")].call_count == 1
    assert sources.routes[("lancet", "openalex")].call_count == 1
    assert sources.routes[("lancet", "openalex_authors")].call_count == 1
    assert len(await sm.history(first)) == len(await sm.history(second)) == 1


async def test_papers_without_a_doi_are_skipped_and_listed(deps, sm, sources):
    sources.serve("jbc")
    no_doi = await sm.add_paper(None)
    with_doi = await sm.add_paper(DOIS["jbc"])

    summary = await poll(deps)

    assert summary.skipped_no_doi == [no_doi]
    assert list(await tracked_rows(deps)) == [with_doi]
    assert await sm.history(no_doi) == []


async def test_papers_storage_management_no_longer_returns_are_dropped(deps, sm, sources):
    sources.serve("jbc")
    sources.serve("lancet")
    keep = await sm.add_paper(DOIS["jbc"])
    drop = await sm.add_paper(DOIS["lancet"])
    await poll(deps)
    assert set(await tracked_rows(deps)) == {keep, drop}

    await sm.reset()
    await sm.add_paper(DOIS["jbc"], paper_id=keep)
    await poll(deps)

    assert list(await tracked_rows(deps)) == [keep]


@pytest.mark.parametrize("failing", ["crossref", "openalex"])
async def test_a_source_error_stores_nothing_and_is_listed(deps, sm, sources, failing):
    """The outage gate: an `error` from either source means no snapshot for that DOI's papers."""
    sources.serve("lancet", **{failing: 500})
    sources.serve("jbc")
    first = await sm.add_paper(DOIS["lancet"])
    second = await sm.add_paper(DOIS["lancet"])
    healthy = await sm.add_paper(DOIS["jbc"])

    summary = await poll(deps)

    assert await sm.history(first) == await sm.history(second) == []
    rows = await tracked_rows(deps)
    assert rows[first].last_snapshot_id is None and rows[second].last_snapshot_id is None
    expected = [
        {
            "paper_id": str(paper_id),
            "doi": DOIS["lancet"],
            "crossref": "error" if failing == "crossref" else "ok",
            "openalex": "error" if failing == "openalex" else "ok",
        }
        for paper_id in (first, second)
    ]
    assert summary.model_dump(mode="json")["source_errors"] == expected
    [run] = await poll_runs(deps)
    assert run.summary["source_errors"] == expected
    # the outage doesn't hold up other papers
    assert [s.paper_id for s in summary.stored] == [healthy]
    assert len(await sm.history(healthy)) == 1
    # a DOI that gets no snapshot doesn't cost an author lookup either
    assert sources.routes[("lancet", "openalex_authors")].call_count == 0


async def test_not_found_is_stored(deps, sm, sources):
    sources.serve("arxiv")  # Crossref answers 404, OpenAlex knows the paper
    paper = await sm.add_paper(DOIS["arxiv"])

    summary = await poll(deps)

    assert summary.source_errors == []
    [row] = await sm.history(paper)
    assert row["crossref_updates"] is None
    assert row["source_status"] == {"crossref": "not_found", "openalex": "ok", "openalex_authors": "ok"}
    assert row["journal_source_type"] == "repository"


async def test_a_failed_author_batch_is_stored_with_null_stats_not_gated(deps, sm, sources):
    sources.serve("lancet", openalex_authors=500)
    paper = await sm.add_paper(DOIS["lancet"])

    summary = await poll(deps)

    assert summary.source_errors == []
    assert [s.paper_id for s in summary.stored] == [paper]
    [row] = await sm.history(paper)
    assert row["source_status"] == {"crossref": "ok", "openalex": "ok", "openalex_authors": "error"}
    assert [a["name"] for a in row["authors"]] == [
        "Mandeep R. Mehra", "Sapan S. Desai", "Frank T. Ruschitzka", "Amit N. Patel",
    ]  # fmt: skip
    assert all(a["h_index"] is None and a["works_count"] is None for a in row["authors"])
    assert row["is_retracted"] is True  # the rest of the snapshot is unaffected


async def test_a_work_with_no_authors_is_stored_without_an_author_lookup(deps, sm, sources):
    sources.serve("jbc")
    no_authors = {**load_fixture("openalex", "jbc"), "authorships": []}
    sources.routes[("jbc", "openalex")].return_value = httpx.Response(200, json=no_authors)
    paper = await sm.add_paper(DOIS["jbc"])

    await poll(deps)

    [row] = await sm.history(paper)
    assert row["authors"] == []
    assert row["source_status"]["openalex_authors"] == "ok"
    assert sources.routes[("jbc", "openalex_authors")].call_count == 0


async def test_a_skipped_paper_is_stored_on_the_next_poll_once_the_source_is_back(deps, sm, sources):
    sources.serve("lancet", crossref=httpx.ConnectError("down"))
    paper = await sm.add_paper(DOIS["lancet"])
    await poll(deps)
    assert await sm.history(paper) == []

    sources.set("lancet", "crossref", None)
    summary = await poll(deps)

    assert [s.paper_id for s in summary.stored] == [paper]
    assert summary.source_errors == []
    assert len(await sm.history(paper)) == 1


async def test_every_poll_stores_a_snapshot_even_when_nothing_changed(deps, sm, sources):
    sources.serve("jbc")
    paper = await sm.add_paper(DOIS["jbc"])

    await poll(deps)
    await poll(deps)

    first, second = await sm.history(paper)
    assert first["snapshot_id"] < second["snapshot_id"]
    assert (await tracked_rows(deps))[paper].last_snapshot_id == second["snapshot_id"]
    assert len(await poll_runs(deps)) == 2


async def test_a_failed_store_is_listed_and_does_not_stop_the_other_papers(deps, sm, sources):
    sources.serve("lancet")
    sources.serve("jbc")
    sources.serve("ijaa")
    server_error = await sm.add_paper(DOIS["lancet"])
    unreachable = await sm.add_paper(DOIS["jbc"])
    fine = await sm.add_paper(DOIS["ijaa"])
    sm.transport.faults[f"{server_error}/background-info"] = 500
    sm.transport.faults[f"{unreachable}/background-info"] = httpx.ConnectError("down")

    summary = await poll(deps)

    assert {(e.paper_id, e.reason) for e in summary.store_errors} == {
        (server_error, "HTTP 500"),
        (unreachable, "ConnectError"),
    }
    assert [s.paper_id for s in summary.stored] == [fine]
    rows = await tracked_rows(deps)
    assert rows[server_error].last_snapshot_id is None
    assert rows[unreachable].last_snapshot_id is None
    assert rows[fine].last_snapshot_id is not None
    [run] = await poll_runs(deps)
    assert run.status is RunStatus.SUCCEEDED


async def test_an_unavailable_paper_list_fails_the_run(deps, sm):
    sm.transport.faults["/internal/papers"] = 503

    with pytest.raises(PollFailed, match="Storage Management"):
        await poll(deps)

    [run] = await poll_runs(deps)
    assert run.status is RunStatus.FAILED
    assert "Storage Management" in run.error
    assert run.finished_at is not None and run.summary is None


async def test_an_unexpected_error_marks_the_run_failed_and_propagates(deps, sm, sources, monkeypatch):
    sources.serve("jbc")
    await sm.add_paper(DOIS["jbc"])

    def explode(*args):
        raise RuntimeError("bug")

    monkeypatch.setattr(poll_module, "build_snapshot", explode)

    with pytest.raises(RuntimeError):
        await poll(deps)

    [run] = await poll_runs(deps)
    assert run.status is RunStatus.FAILED and run.error == "RuntimeError"


async def test_a_single_paper_run_stores_only_that_paper_and_keeps_the_other_rows(deps, sm, sources):
    sources.serve("lancet")
    sources.serve("jbc")
    lancet = await sm.add_paper(DOIS["lancet"])
    jbc = await sm.add_paper(DOIS["jbc"])
    await poll(deps)
    jbc_snapshot = (await tracked_rows(deps))[jbc].last_snapshot_id

    summary = await run_poll(deps, PollTrigger.MANUAL, lancet)

    assert summary.paper_id == lancet
    assert [s.paper_id for s in summary.stored] == [lancet]
    assert len(await sm.history(lancet)) == 2 and len(await sm.history(jbc)) == 1
    rows = await tracked_rows(deps)
    assert set(rows) == {lancet, jbc}
    assert rows[jbc].last_snapshot_id == jbc_snapshot
    assert sources.routes[("jbc", "crossref")].call_count == 1  # fetched by the first poll only
    run = (await poll_runs(deps))[-1]
    assert run.trigger is PollTrigger.MANUAL
    assert run.summary["paper_id"] == str(lancet)


async def test_a_single_paper_run_for_a_paper_without_a_doi_lists_it_as_skipped(deps, sm, sources):
    no_doi = await sm.add_paper(None)

    summary = await run_poll(deps, PollTrigger.MANUAL, no_doi)

    assert summary.skipped_no_doi == [no_doi]
    assert summary.stored == [] and await tracked_rows(deps) == {}


async def test_a_single_paper_run_for_an_unknown_paper_fails_the_run(deps, sm, sources):
    await sm.add_paper(DOIS["jbc"])
    unknown = uuid4()

    with pytest.raises(UnknownPaper, match=str(unknown)):
        await run_poll(deps, PollTrigger.MANUAL, unknown)

    [run] = await poll_runs(deps)
    assert run.status is RunStatus.FAILED and str(unknown) in run.error
    assert await tracked_rows(deps) == {}  # rejected before anything was synced


async def test_a_poll_that_comes_due_during_another_waits_for_it(deps, sm, sources):
    sources.serve("jbc")
    await sm.add_paper(DOIS["jbc"])
    await deps.lock.acquire()  # another poll is running
    waiting = asyncio.create_task(poll(deps))
    await asyncio.sleep(0)

    assert not waiting.done()
    assert await poll_runs(deps) == []  # no run is recorded until it has the lock
    released_at = datetime.now(UTC)
    deps.lock.release()
    summary = await waiting

    assert summary.started_at >= released_at
    assert len(summary.stored) == 1
