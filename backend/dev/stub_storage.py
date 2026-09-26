"""In-memory stand-in for Storage Management, for developing and demoing Updating
(and later Research Evaluation) before the real endpoints exist (CG-68).

Implements the `/internal/**` contract in docs/CONTRACTS.md and checks the service
token the way Storage Management's JwtAuthFilter does. Three stub-only helpers seed it:
`POST /dev/papers`, `POST /dev/reset` and `POST /dev/seed?scenario=`, which adds a paper
with a synthetic earlier snapshot (dev/scenarios.py) so the next poll has a change to find.
It is a development aid and should never be pointed at from a deployed environment.

Run it with:
    uv run --env-file .env uvicorn dev.stub_storage:create_app --factory --port 8081
"""

import os
from dataclasses import dataclass, field
from typing import Annotated, Any
from uuid import UUID, uuid4

import jwt
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field

from common.doi import normalize_doi
from common.service_token import decode_jwt_secret
from dev.scenarios import Scenario, before_snapshot


class StubPaper(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    owner_id: UUID = Field(default_factory=uuid4)
    doi: str | None = None
    issn: str | None = None


@dataclass
class Store:
    papers: dict[UUID, StubPaper] = field(default_factory=dict)
    snapshots: dict[UUID, list[dict[str, Any]]] = field(default_factory=dict)
    last_snapshot_id: int = 0

    def add_snapshot(self, paper_id: UUID, snapshot: dict[str, Any]) -> dict[str, Any]:
        self.last_snapshot_id += 1
        stored = {"snapshot_id": self.last_snapshot_id, "paper_id": str(paper_id), **snapshot}
        self.snapshots.setdefault(paper_id, []).append(stored)
        return stored

    def clear(self) -> None:
        self.papers.clear()
        self.snapshots.clear()
        self.last_snapshot_id = 0


def create_app(jwt_key: bytes | None = None) -> FastAPI:
    key = jwt_key if jwt_key is not None else decode_jwt_secret(os.environ["JWT_SECRET"])
    store = Store()

    def require_service_token(authorization: Annotated[str | None, Header()] = None) -> None:
        """Same outcomes as Storage Management: 401 for a bad token, 403 for a user token."""
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401)
        try:
            claims = jwt.decode(authorization.removeprefix("Bearer "), key, algorithms=["HS256"])
        except jwt.PyJWTError:
            raise HTTPException(401) from None
        if claims.get("role") == "service":
            return
        try:
            UUID(str(claims.get("sub")))
        except ValueError:
            raise HTTPException(401) from None  # not a user token either
        raise HTTPException(403)

    def find_paper(paper_id: UUID) -> StubPaper:
        if paper_id not in store.papers:
            raise HTTPException(404, f"No paper {paper_id}")
        return store.papers[paper_id]

    internal = APIRouter(prefix="/internal", dependencies=[Depends(require_service_token)])

    @internal.get("/papers")
    def list_papers() -> list[StubPaper]:
        return list(store.papers.values())

    @internal.post("/papers/{paper_id}/background-info", status_code=201)
    def store_snapshot(paper_id: UUID, snapshot: dict[str, Any]) -> dict[str, Any]:
        find_paper(paper_id)
        return store.add_snapshot(paper_id, snapshot)

    @internal.get("/papers/{paper_id}/background-info/history")
    def history(
        paper_id: UUID,
        after_id: int | None = None,
        limit: Annotated[int | None, Query(ge=1)] = None,
    ) -> dict[str, list[dict[str, Any]]]:
        find_paper(paper_id)
        rows = store.snapshots.get(paper_id, [])
        if after_id is not None:
            rows = [row for row in rows if row["snapshot_id"] > after_id]
        return {"snapshots": rows[:limit]}

    dev = APIRouter(prefix="/dev")

    @dev.post("/papers", status_code=201)
    def add_paper(paper: StubPaper) -> StubPaper:
        paper.doi = normalize_doi(paper.doi)
        store.papers[paper.id] = paper
        return paper

    @dev.post("/seed", status_code=201)
    def seed(scenario: Scenario) -> StubPaper:
        """Add a paper whose earlier snapshot differs from what the recorded APIs say now."""
        before = before_snapshot(scenario)
        paper = StubPaper(doi=before.doi if before else None)
        store.papers[paper.id] = paper
        if before:
            store.add_snapshot(paper.id, before.model_dump(mode="json"))
        return paper

    @dev.post("/reset", status_code=204)
    def reset() -> None:
        store.clear()

    app = FastAPI(title="Storage Management (stub)")
    app.include_router(internal)
    app.include_router(dev)
    return app
