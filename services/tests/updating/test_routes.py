"""POST /run-poll over HTTP: what the endpoint adds to `run_poll` (its status codes)."""

from uuid import uuid4

from updating_support import RUN_POLL, poll_runs, trigger_poll

from dev.scenarios import DOIS
from updating.models import PollTrigger


async def test_a_paper_id_scopes_the_run_to_that_paper(updating_client, updating_app, sm, sources):
    sources.serve("lancet")
    sources.serve("jbc")
    lancet = await sm.add_paper(DOIS["lancet"])
    jbc = await sm.add_paper(DOIS["jbc"])

    summary = await trigger_poll(updating_client, lancet)

    assert summary["paper_id"] == str(lancet) and summary["trigger"] == "manual"
    assert [s["paper_id"] for s in summary["stored"]] == [str(lancet)]
    assert await sm.history(jbc) == []
    manual_runs = [r for r in await poll_runs(updating_app.state.poll_deps) if r.trigger is PollTrigger.MANUAL]
    assert len(manual_runs) == 1


async def test_without_a_paper_id_every_paper_is_polled(updating_client, sm, sources):
    sources.serve("lancet")
    sources.serve("jbc")
    await sm.add_paper(DOIS["lancet"])
    await sm.add_paper(DOIS["jbc"])

    summary = await trigger_poll(updating_client)

    assert summary["paper_id"] is None and len(summary["stored"]) == 2


async def test_an_unknown_paper_is_404(updating_client, sm):
    await sm.add_paper(DOIS["jbc"])

    response = await updating_client.post(RUN_POLL, params={"paper_id": str(uuid4())})

    assert response.status_code == 404
    assert "no paper" in response.json()["detail"]


async def test_a_poll_already_running_is_409(updating_client, updating_app):
    await updating_app.state.poll_deps.lock.acquire()

    response = await updating_client.post(RUN_POLL)

    updating_app.state.poll_deps.lock.release()
    assert response.status_code == 409


async def test_an_unavailable_paper_list_is_502(updating_client, sm):
    sm.transport.faults["/internal/papers"] = 503

    response = await updating_client.post(RUN_POLL)

    assert response.status_code == 502
    assert "Storage Management" in response.json()["detail"]


async def test_a_bad_paper_id_is_422(updating_client):
    response = await updating_client.post(RUN_POLL, params={"paper_id": "nope"})

    assert response.status_code == 422
