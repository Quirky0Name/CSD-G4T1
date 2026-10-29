"""POST /run-poll over HTTP: what the endpoint adds to `run_poll` (its status codes)."""

from uuid import uuid4

import jwt
from support import TEST_JWT_KEY, user_token
from updating_support import RUN_POLL, polls, trigger_poll

from common.service_token import mint_service_token
from dev.scenarios import DOIS, Scenario
from updating.models import PollTrigger

AUTH = {"Authorization": f"Bearer {user_token()}"}


async def test_a_paper_id_scopes_the_poll_to_that_paper(updating_client, updating_app, sm, sources):
    sources.serve("lancet")
    sources.serve("jbc")
    lancet = await sm.add_paper(DOIS["lancet"])
    jbc = await sm.add_paper(DOIS["jbc"])

    summary = await trigger_poll(updating_client, lancet)

    assert summary["paper_id"] == str(lancet) and summary["trigger"] == "manual"
    assert [s["paper_id"] for s in summary["stored"]] == [str(lancet)]
    assert await sm.history(jbc) == []
    manual_polls = [r for r in await polls(updating_app.state.poll_deps) if r.trigger is PollTrigger.MANUAL]
    assert len(manual_polls) == 1


async def test_without_a_paper_id_every_paper_is_polled(updating_client, sm, sources):
    sources.serve("lancet")
    sources.serve("jbc")
    await sm.add_paper(DOIS["lancet"])
    await sm.add_paper(DOIS["jbc"])

    summary = await trigger_poll(updating_client)

    assert summary["paper_id"] is None and len(summary["stored"]) == 2


async def test_an_unknown_paper_is_404(updating_client, sm):
    await sm.add_paper(DOIS["jbc"])

    response = await updating_client.post(RUN_POLL, params={"paper_id": str(uuid4())}, headers=AUTH)

    assert response.status_code == 404
    assert "no paper" in response.json()["detail"]


async def test_a_poll_already_running_is_409(updating_client, updating_app):
    await updating_app.state.poll_deps.lock.acquire()

    response = await updating_client.post(RUN_POLL, headers=AUTH)

    updating_app.state.poll_deps.lock.release()
    assert response.status_code == 409


async def test_an_unavailable_paper_list_is_502(updating_client, sm):
    sm.transport.faults["/internal/papers"] = 503

    response = await updating_client.post(RUN_POLL, headers=AUTH)

    assert response.status_code == 502
    assert "Storage Management" in response.json()["detail"]


async def test_a_bad_paper_id_is_422(updating_client):
    response = await updating_client.post(RUN_POLL, params={"paper_id": "nope"}, headers=AUTH)

    assert response.status_code == 422


async def test_the_nudge_carries_updatings_service_token(updating_client, sm, re, sources):
    """The app's own `re` client (built in the lifespan) signs the nudge; the stub rejects any
    request without a valid service token."""
    sources.serve("ijaa")
    paper = await sm.seed_scenario(Scenario.OPENALEX_RETRACTION)

    summary = await trigger_poll(updating_client, paper)

    assert summary["nudged"] == [str(paper)] and summary["nudge_error"] is None
    assert await re.received() == [[paper]]
    [nudge] = re.nudges()
    scheme, token = nudge.headers["Authorization"].split(" ")
    assert scheme == "Bearer"
    claims = jwt.decode(token, TEST_JWT_KEY, algorithms=["HS256"], options={"require": ["exp", "sub"]})
    assert claims["sub"] == "svc:updating"
    assert claims["role"] == "service"
    assert claims["exp"] > claims["iat"]


async def test_every_request_to_either_service_carries_updatings_service_token(
    updating_client, sm, re, sources
):
    sources.serve("ijaa")
    paper = await sm.seed_scenario(Scenario.OPENALEX_RETRACTION)
    sm.transport.sent.clear()  # the test's own seeding

    await trigger_poll(updating_client, paper)

    requests = sm.transport.sent + re.transport.sent
    assert any(r.url.path.startswith("/internal/") for r in requests) and re.nudges()
    for request in requests:
        scheme, token = request.headers["Authorization"].split(" ")
        claims = jwt.decode(token, TEST_JWT_KEY, algorithms=["HS256"], options={"require": ["exp", "sub"]})
        assert (scheme, claims["sub"], claims["role"]) == ("Bearer", "svc:updating", "service")


async def test_a_poll_needs_a_user_token(updating_client):
    assert (await updating_client.post(RUN_POLL)).status_code == 401
    assert (await updating_client.post(RUN_POLL, headers={"Authorization": "Bearer nope"})).status_code == 401
    service = {"Authorization": f"Bearer {mint_service_token(TEST_JWT_KEY, 'svc:updating')}"}
    assert (await updating_client.post(RUN_POLL, headers=service)).status_code == 403


async def test_a_poll_covers_only_the_callers_papers(updating_client, sm, sources):
    sources.serve("ijaa")
    sources.serve("lancet")
    mine = await sm.add_paper(DOIS["ijaa"])
    theirs = await sm.add_paper(DOIS["lancet"], owner=uuid4())

    summary = await trigger_poll(updating_client)

    assert [row["paper_id"] for row in summary["stored"]] == [str(mine)]
    assert str(theirs) not in str(summary)


async def test_a_user_with_no_papers_polls_nothing(updating_client, sm, sources):
    await sm.add_paper(DOIS["ijaa"], owner=uuid4())

    summary = await trigger_poll(updating_client)

    assert summary["stored"] == [] and summary["skipped_no_doi"] == []


async def test_polling_someone_elses_paper_is_404(updating_client, sm, sources):
    sources.serve("ijaa")
    theirs = await sm.add_paper(DOIS["ijaa"], owner=uuid4())

    response = await updating_client.post(
        RUN_POLL, params={"paper_id": str(theirs)}, headers={"Authorization": f"Bearer {user_token()}"}
    )

    assert response.status_code == 404
