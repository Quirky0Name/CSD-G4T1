from uuid import uuid4

import httpx
import pytest

from dev.stub_research_evaluation import create_app


@pytest.fixture
async def client():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app()), base_url="http://stub") as client:
        yield client


async def test_a_nudge_is_accepted_with_202_and_recorded(client):
    first, second = str(uuid4()), str(uuid4())

    response = await client.post("/evaluate/changes", json={"paper_ids": [first, second]})

    assert response.status_code == 202
    assert (await client.get("/dev/received")).json() == [{"paper_ids": [first, second]}]


async def test_while_failing_nothing_is_recorded_and_the_answer_is_503(client):
    assert (await client.post("/dev/fail")).status_code == 204

    response = await client.post("/evaluate/changes", json={"paper_ids": [str(uuid4())]})

    assert response.status_code == 503
    assert (await client.get("/dev/received")).json() == []


async def test_failing_can_be_turned_off_again(client):
    await client.post("/dev/fail")
    await client.post("/dev/fail", params={"on": False})

    response = await client.post("/evaluate/changes", json={"paper_ids": [str(uuid4())]})

    assert response.status_code == 202


async def test_a_malformed_body_is_422(client):
    response = await client.post("/evaluate/changes", json={"paper_ids": ["not-a-uuid"]})

    assert response.status_code == 422
