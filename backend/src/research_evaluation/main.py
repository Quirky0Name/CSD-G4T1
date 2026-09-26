"""

Serves `POST /evaluate/changes`, Updating's nudge: it evaluates the changed papers and
stores the alerts in Storage Management before answering (docs/CONTRACTS.md). The other
endpoints in CONTRACTS.md land in later stories.

takes in POST /evaluate/changes for Updatigns nudge"""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from common.service_token import ServiceTokenAuth
from research_evaluation.auth import require_service_token
from research_evaluation.config import ResearchEvaluationSettings
from research_evaluation.evaluate import EvaluationResult, evaluate_papers
from research_evaluation.storage import SERVICE_SUBJECT, sm_client

# Updating waits for the evaluation, within its own 20 s timeout
HTTP_TIMEOUT_SECONDS = 10


class ChangesNudge(BaseModel):
    paper_ids: list[UUID]


router = APIRouter()


@router.post("/evaluate/changes", status_code=202, dependencies=[Depends(require_service_token)])
async def evaluate_changes(
    nudge: ChangesNudge, sm: Annotated[httpx.AsyncClient, Depends(sm_client)]
) -> EvaluationResult:
    """202 once every paper's alerts are stored
    503 if any paper failed, so Updating keeps
    its `nudge_pending` flag and re-sends the ids on its next poll."""
    result = await evaluate_papers(sm, nudge.paper_ids)
    if result.failed_paper_ids:
        return JSONResponse(
            status_code=503,
            content={"detail": "Evaluation failed; the nudge was not accepted", **result.model_dump(mode="json")},
        )
    return result


def configure_logging() -> None:
    # uvicorn configures its own loggers only, so without this our INFO logs go nowhere
    logging.basicConfig(level=logging.INFO)
    logging.getLogger("httpx").setLevel(logging.WARNING)


def create_app(settings: ResearchEvaluationSettings | None = None) -> FastAPI:
    configure_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        # use their config or just use .env configs 
        # missing values (mainly JWT_SECRET) -> fail
        config = settings or ResearchEvaluationSettings()
        key = config.jwt_secret.get_secret_value()
        async with httpx.AsyncClient(
            base_url=config.sm_base_url, auth=ServiceTokenAuth(key, SERVICE_SUBJECT), timeout=HTTP_TIMEOUT_SECONDS
        ) as sm:
            app.state.jwt_key = key
            app.state.sm = sm
            yield

    app = FastAPI(title="Research Evaluation", lifespan=lifespan)
    app.include_router(router)
    return app


app = create_app()
