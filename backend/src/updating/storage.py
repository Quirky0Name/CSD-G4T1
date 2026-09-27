"""Client for Storage Management's `/internal/**` endpoints (docs/CONTRACTS.md).

The `httpx.AsyncClient` passed in carries the base URL and the shared `ServiceTokenAuth`
(common/service_token.py) with `SERVICE_SUBJECT`. Calls
raise `httpx.HTTPStatusError` on a non-2xx and `ValueError` on a body that doesn't
parse; the poll job decides what each means."""

from typing import NewType
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, TypeAdapter

from common.doi import Doi
from updating.snapshot import Snapshot

PaperId = NewType("PaperId", UUID)
SnapshotId = NewType("SnapshotId", int)

# Storage Management may cap a history request that has no `limit`, so we page explicitly
HISTORY_PAGE_SIZE = 100


# the `sub` of Updating's service tokens, to Storage Management and to Research Evaluation
SERVICE_SUBJECT = "svc:updating"


class SmPaper(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: PaperId
    doi: Doi | None  # already normalised by Storage Management


class StoredSnapshot(BaseModel):
    model_config = ConfigDict(extra="ignore")

    snapshot_id: SnapshotId


class HistoryRow(Snapshot):
    """A stored snapshot as the history endpoint returns it."""

    snapshot_id: SnapshotId


class _HistoryPage(BaseModel):
    snapshots: list[HistoryRow]


_PAPERS = TypeAdapter(list[SmPaper])


async def list_papers(http: httpx.AsyncClient) -> list[SmPaper]:
    response = await http.get("/internal/papers")
    response.raise_for_status()
    return _PAPERS.validate_python(response.json())


async def post_snapshot(
    http: httpx.AsyncClient, paper_id: PaperId, snapshot: Snapshot
) -> SnapshotId:
    response = await http.post(
        f"/internal/papers/{paper_id}/background-info", json=snapshot.model_dump(mode="json")
    )
    response.raise_for_status()
    return StoredSnapshot.model_validate(response.json()).snapshot_id


async def latest_snapshot(
    http: httpx.AsyncClient, paper_id: PaperId, after_id: SnapshotId | None
) -> HistoryRow | None:
    """The newest snapshot with an id above `after_id` (any snapshot when it's None), or None."""
    latest: HistoryRow | None = None
    while True:
        params: dict[str, int] = {"limit": HISTORY_PAGE_SIZE}
        if after_id is not None:
            params["after_id"] = after_id
        response = await http.get(f"/internal/papers/{paper_id}/background-info/history", params=params)
        response.raise_for_status()
        rows = _HistoryPage.model_validate(response.json()).snapshots
        if rows:
            latest = rows[-1]
            after_id = latest.snapshot_id
        if len(rows) < HISTORY_PAGE_SIZE:
            return latest
