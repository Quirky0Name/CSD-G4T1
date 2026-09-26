"""In-memory stand-in for Research Evaluation, for developing and demoing Updating's
nudge before the real service exists.

Implements `POST /evaluate/changes` from docs/CONTRACTS.md: it records the paper ids and
answers 202. Two stub-only helpers: `GET /dev/received` shows what arrived, and
`POST /dev/fail` makes it act as if Research Evaluation were down. It is a development
aid and should never be pointed at from a deployed environment.

Run it with:
    uv run uvicorn dev.stub_research_evaluation:create_app --factory --port 8000
"""

from uuid import UUID

from fastapi import APIRouter, FastAPI, HTTPException
from pydantic import BaseModel


class ChangesRequest(BaseModel):
    paper_ids: list[UUID]


def create_app() -> FastAPI:
    received: list[ChangesRequest] = []
    failing = False

    evaluate = APIRouter(prefix="/evaluate")

    @evaluate.post("/changes", status_code=202)
    def changes(request: ChangesRequest) -> None:
        if failing:
            raise HTTPException(503, "Research Evaluation is down (stub)")
        received.append(request)

    dev = APIRouter(prefix="/dev")

    @dev.get("/received")
    def get_received() -> list[ChangesRequest]:
        return received

    @dev.post("/fail", status_code=204)
    def fail(on: bool = True) -> None:
        nonlocal failing
        failing = on

    app = FastAPI(title="Research Evaluation (stub)")
    app.include_router(evaluate)
    app.include_router(dev)
    return app
