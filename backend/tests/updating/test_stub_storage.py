import time
from uuid import uuid4

import httpx
import jwt
import pytest
from support import TEST_JWT_KEY

from common.service_token import mint_service_token
from dev.stub_storage import create_app

PAPER_ID = "6f1c1f0e-3b7d-4a5e-9d55-0c1e6a1a0001"


@pytest.fixture
async def client():
    transport = httpx.ASGITransport(app=create_app(TEST_JWT_KEY))
    async with httpx.AsyncClient(transport=transport, base_url="http://stub") as client:
        yield client


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def service_headers() -> dict[str, str]:
    return bearer(mint_service_token(TEST_JWT_KEY, "svc:updating"))


def user_token(sub: str, key: bytes = TEST_JWT_KEY, **claims) -> str:
    return jwt.encode({"sub": sub, **claims}, key, algorithm="HS256")


async def add_paper(client, **fields) -> dict:
    response = await client.post("/dev/papers", json={"id": PAPER_ID, **fields})
    assert response.status_code == 201
    return response.json()


async def test_missing_token_is_401(client):
    assert (await client.get("/internal/papers")).status_code == 401


@pytest.mark.parametrize(
    "headers",
    [
        bearer(user_token(str(uuid4()), key=b"another-key-that-is-32-bytes-long!") + "x"),
        bearer(mint_service_token(b"another-key-that-is-32-bytes-long!", "svc:updating")),
        bearer(mint_service_token(TEST_JWT_KEY, "svc:updating")[:-3]),
        bearer(jwt.encode({"sub": "svc:updating", "role": "service", "exp": int(time.time()) - 60},
                          TEST_JWT_KEY, algorithm="HS256")),
        {"Authorization": "Token abc"},
    ],
    ids=["tampered", "wrong key", "truncated", "expired", "not bearer"],
)
async def test_invalid_tokens_are_401(client, headers):
    assert (await client.get("/internal/papers", headers=headers)).status_code == 401


async def test_user_token_is_403_on_internal_endpoints(client):
    response = await client.get("/internal/papers", headers=bearer(user_token(str(uuid4()))))

    assert response.status_code == 403


async def test_non_service_token_with_a_non_uuid_subject_is_401(client):
    response = await client.get("/internal/papers", headers=bearer(user_token("svc:updating")))

    assert response.status_code == 401


async def test_service_token_lists_papers_with_a_normalised_doi(client):
    await add_paper(client, doi="HTTPS://doi.org/10.1/ABC", issn="0000-0000")

    response = await client.get("/internal/papers", headers=service_headers())

    assert response.status_code == 200
    [paper] = response.json()
    assert paper["id"] == PAPER_ID
    assert paper["doi"] == "10.1/abc"
    assert paper["issn"] == "0000-0000"
    assert paper["file_available"] is True
    assert set(paper) == {"id", "owner_id", "doi", "issn", "file_available"}


async def test_posting_snapshots_returns_201_with_ascending_ids(client):
    await add_paper(client)

    ids = []
    for n in range(3):
        response = await client.post(
            f"/internal/papers/{PAPER_ID}/background-info",
            json={"doi": "10.1/abc", "cited_by_count": n},
            headers=service_headers(),
        )
        assert response.status_code == 201
        body = response.json()
        assert body["paper_id"] == PAPER_ID
        assert body["cited_by_count"] == n
        ids.append(body["snapshot_id"])

    assert ids == sorted(ids) and len(set(ids)) == 3


async def test_unknown_paper_is_404(client):
    headers = service_headers()

    post = await client.post(f"/internal/papers/{uuid4()}/background-info", json={}, headers=headers)
    history = await client.get(f"/internal/papers/{uuid4()}/background-info/history", headers=headers)

    assert post.status_code == history.status_code == 404


async def test_history_is_ascending_with_exclusive_after_id_and_limit(client):
    await add_paper(client)
    url = f"/internal/papers/{PAPER_ID}/background-info"
    for n in range(4):
        await client.post(url, json={"n": n}, headers=service_headers())

    everything = (await client.get(url + "/history", headers=service_headers())).json()["snapshots"]
    ids = [row["snapshot_id"] for row in everything]
    assert [row["n"] for row in everything] == [0, 1, 2, 3]

    after = await client.get(url + f"/history?after_id={ids[1]}", headers=service_headers())
    assert [row["n"] for row in after.json()["snapshots"]] == [2, 3]

    limited = await client.get(url + f"/history?after_id={ids[0]}&limit=2", headers=service_headers())
    assert [row["n"] for row in limited.json()["snapshots"]] == [1, 2]


async def test_history_only_returns_that_papers_snapshots(client):
    await add_paper(client)
    other = uuid4()
    await client.post("/dev/papers", json={"id": str(other)})
    await client.post(f"/internal/papers/{other}/background-info", json={"n": 1}, headers=service_headers())

    response = await client.get(f"/internal/papers/{PAPER_ID}/background-info/history",
                                headers=service_headers())

    assert response.json() == {"snapshots": []}


async def test_reset_forgets_papers_and_snapshots(client):
    await add_paper(client)
    await client.post(f"/internal/papers/{PAPER_ID}/background-info", json={}, headers=service_headers())

    assert (await client.post("/dev/reset")).status_code == 204

    assert (await client.get("/internal/papers", headers=service_headers())).json() == []
    await add_paper(client)
    first = await client.post(f"/internal/papers/{PAPER_ID}/background-info", json={},
                              headers=service_headers())
    assert first.json()["snapshot_id"] == 1
