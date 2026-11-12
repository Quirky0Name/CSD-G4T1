"""POST /embed/feedback: Storage Management's nudge after feedback is saved or deleted, against
the stub. ASGITransport finishes the background task before the response comes back."""

import pytest
from research_evaluation_support import FeedbackEmbedder as FakeEmbedder
from research_evaluation_support import feedback_report as stub_report
from research_evaluation_support import updating_token, user_token

from research_evaluation.impact.run import ImpactContext

FEEDBACK = {"verdict": "understated", "note": None, "updated_at": "2026-10-04T08:00:00Z"}


@pytest.fixture
def embedder(impact) -> FakeEmbedder:
    fake = FakeEmbedder()
    impact.context = ImpactContext(llm=None, embedder=fake)
    return fake


async def embed(client, body, token: str | None = None):
    headers = {"Authorization": f"Bearer {token}"} if token is not None else {}
    return await client.post("/embed/feedback", json=body, headers=headers)


async def test_a_service_token_is_accepted_and_the_chunk_stored(client, sm, embedder):
    report_id = await stub_report(sm, FEEDBACK)

    response = await embed(client, {"report_id": report_id}, updating_token())

    assert response.status_code == 202
    assert response.json() == {"report_id": report_id}
    [chunk] = (await sm.client.get(f"/dev/chunks/feedback/{report_id}")).json()["chunks"]
    assert chunk["content"].endswith("researcher's verdict: understated")


async def test_it_needs_a_service_token(client, sm, embedder):
    report_id = await stub_report(sm, FEEDBACK)

    assert (await embed(client, {"report_id": report_id})).status_code == 401
    assert (await embed(client, {"report_id": report_id}, "nope")).status_code == 401
    assert (await embed(client, {"report_id": report_id}, user_token())).status_code == 403
    assert embedder.calls == []


@pytest.mark.parametrize("body", [{"report_id": "12"}, {"report_id": 12.0}, {}, {"report_id": None}])
async def test_a_bad_body_is_422(client, embedder, body):
    assert (await embed(client, body, updating_token())).status_code == 422


async def test_without_a_gemini_key_it_is_503_and_nothing_runs(client, sm, impact):
    report_id = await stub_report(sm, FEEDBACK)

    response = await embed(client, {"report_id": report_id}, updating_token())

    assert response.status_code == 503
    assert response.json()["detail"] == "Gemini isn't configured"
    assert (await sm.client.get(f"/dev/chunks/feedback/{report_id}")).status_code == 404
