"""In-memory stand-in for Storage Management, for developing and demoing Updating
and Research Evaluation before the real endpoints exist (CG-68).

Implements the `/internal/**` contract in docs/CONTRACTS.md and checks the service
token the way Storage Management's JwtAuthFilter does. Stub-only helpers, which take no
token: `POST /dev/papers` and `POST /dev/reset` seed it, and
`GET /dev/papers/{id}/alerts` shows the alerts Research Evaluation stored. It is a
development aid and should never be pointed at from a deployed environment.

Run it with:
    uv run --env-file .env uvicorn dev.stub_storage:create_app --factory --port 8081
"""

import os
from dataclasses import dataclass, field
from datetime import datetime
from typing import Annotated, Any, Literal
from uuid import UUID, uuid4

import jwt
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, Response
from pydantic import BaseModel, Field

from common.doi import normalize_doi
from common.service_token import decode_jwt_secret


class StubPaper(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    owner_id: UUID = Field(default_factory=uuid4)
    doi: str | None = None
    issn: str | None = None
    file_available: bool = True


class NewAlert(BaseModel):
    """The body of `POST /internal/papers/{id}/alerts`; the real service answers 400 where
    this stub's validation answers 422."""

    change_type: Literal["retraction", "correction", "erratum", "expression_of_concern", "doaj_delisting", "other"]
    change_key: str = Field(min_length=1, max_length=512)
    severity: Literal["high", "medium", "low"]
    description: str = Field(min_length=1)
    recommendation: str = Field(min_length=1)
    notice_doi: str | None = None
    detected_at: datetime
    snapshot_id: int
    previous_snapshot_id: int


# stored but, as in Storage Management, never returned by the API
INTERNAL_ALERT_FIELDS = ("change_key", "snapshot_id", "previous_snapshot_id")


@dataclass
class Store:
    papers: dict[UUID, StubPaper] = field(default_factory=dict)
    snapshots: dict[UUID, list[dict[str, Any]]] = field(default_factory=dict)
    last_snapshot_id: int = 0
    # per paper, keyed by change_key: one alert per change, like the real unique constraint
    alerts: dict[UUID, dict[str, dict[str, Any]]] = field(default_factory=dict)
    last_alert_id: int = 0

    def clear(self) -> None:
        self.papers.clear()
        self.snapshots.clear()
        self.last_snapshot_id = 0
        self.alerts.clear()
        self.last_alert_id = 0


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
        store.last_snapshot_id += 1
        stored = {"snapshot_id": store.last_snapshot_id, "paper_id": str(paper_id), **snapshot}
        store.snapshots.setdefault(paper_id, []).append(stored)
        return stored

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

    @internal.post("/papers/{paper_id}/alerts")
    def store_alert(paper_id: UUID, alert: NewAlert, response: Response) -> dict[str, Any]:
        """Idempotent on (paper_id, change_key): 201 for a new alert, 200 with the stored one
        unchanged."""
        find_paper(paper_id)
        stored = store.alerts.setdefault(paper_id, {})
        if alert.change_key in stored:
            response.status_code = 200
        else:
            store.last_alert_id += 1
            stored[alert.change_key] = {
                "id": store.last_alert_id,
                "paper_id": str(paper_id),
                **alert.model_dump(mode="json"),
                "status": "new",
                "status_changed_at": None,
            }
            response.status_code = 201
        row = stored[alert.change_key]
        return {key: value for key, value in row.items() if key not in INTERNAL_ALERT_FIELDS}

    dev = APIRouter(prefix="/dev")

    @dev.get("/papers/{paper_id}/alerts")
    def stored_alerts(paper_id: UUID) -> list[dict[str, Any]]:
        """Stub-only: every stored alert of a paper, internal fields included, oldest first."""
        return list(store.alerts.get(paper_id, {}).values())

    @dev.post("/papers", status_code=201)
    def add_paper(paper: StubPaper) -> StubPaper:
        paper.doi = normalize_doi(paper.doi)
        store.papers[paper.id] = paper
        return paper

    @dev.post("/reset", status_code=204)
    def reset() -> None:
        store.clear()

    app = FastAPI(title="Storage Management (stub)")
    app.include_router(internal)
    app.include_router(dev)
    return app
