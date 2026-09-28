"""In-memory stand-in for Research Evaluation, for developing and demoing Updating's
nudge before the real service exists.

Implements `POST /evaluate/changes` from docs/CONTRACTS.md: it checks the service token the
way Research Evaluation does, records the paper ids and answers 202. Two stub-only helpers,
which take no token: `GET /dev/received` shows what arrived, and `POST /dev/fail` makes it
act as if Research Evaluation were down. It is a development aid and should never be pointed
at from a deployed environment.

Run it with (it reads JWT_SECRET from the environment):
    uv run --env-file .env uvicorn dev.stub_research_evaluation:create_app --factory --port 8000
"""

import os
from typing import Annotated
from uuid import UUID

import jwt
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from common.service_token import decode_jwt_secret


class ChangesRequest(BaseModel):
    paper_ids: list[UUID]


def create_app(jwt_key: bytes | None = None) -> FastAPI:
    key = jwt_key if jwt_key is not None else decode_jwt_secret(os.environ["JWT_SECRET"])
    received: list[ChangesRequest] = []
    failing = False

    def require_service_token(authorization: Annotated[str | None, Header()] = None) -> None:
        """Same outcomes as Research Evaluation: 401 for a missing or bad token, 403 for a
        user token."""
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "Missing bearer token")
        try:
            claims = jwt.decode(
                authorization.removeprefix("Bearer "),
                key,
                algorithms=["HS256"],
                options={"require": ["exp", "sub"]},
            )
        except jwt.PyJWTError:
            raise HTTPException(401, "Invalid token") from None
        if claims.get("role") == "service":
            return
        try:
            UUID(str(claims["sub"]))
        except ValueError:
            raise HTTPException(401, "Invalid token") from None  # not a user token either
        raise HTTPException(403, "Service token required")

    evaluate = APIRouter(prefix="/evaluate", dependencies=[Depends(require_service_token)])

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
