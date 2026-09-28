"""
create app
handle POST /evaluate/changes for Updatigns nudge
handle POST /evaluate/reports, assessing reports by id on request"""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

import httpx
from fastapi import APIRouter, BackgroundTasks, Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, StrictInt

from common.service_token import ServiceTokenAuth
from research_evaluation.auth import require_service_token
from research_evaluation.config import ResearchEvaluationSettings
from research_evaluation.evaluate import EvaluationResult, evaluate_papers
from research_evaluation.impact.llm import GeminiLlm, gemini_configured, make_client
from research_evaluation.impact.run import ImpactContext, assess_reports
from research_evaluation.investigation.run import (
    InvestigationContext,
    PaperChanges,
    investigate_papers,
)
from research_evaluation.notify import TelegramNotifier, telegram_configured
from research_evaluation.storage import SERVICE_SUBJECT, sm_client

# Updating waits for the evaluation, within its own 20 s timeout
HTTP_TIMEOUT_SECONDS = 10


class ChangesNudge(BaseModel):
    paper_ids: list[UUID]


class ReportsRequest(BaseModel):
    # strict: "5" or 5.0 is a bad request, not report 5
    report_ids: list[StrictInt] = Field(min_length=1)


class AcceptedReports(BaseModel):
    report_ids: list[int]


router = APIRouter()
log = logging.getLogger(__name__)


def snapshot_window(request: Request) -> int:
    """EVALUATION_SNAPSHOT_WINDOW, stored at startup (tests override this)
    number of snapshots to look back on
        for when RE fails after nudge -> check again in next nudge"""
    return request.app.state.snapshot_window


def investigation_context(request: Request) -> InvestigationContext:
    """The external client and settings investigation runs with, made at startup (tests override this)."""
    return request.app.state.investigation


def impact_context(request: Request) -> ImpactContext | None:
    """The model impact runs with, made at startup; None when GEMINI_API_KEY isn't set (tests
    override this)."""
    return request.app.state.impact


async def investigate_then_assess(
    sm: httpx.AsyncClient,
    investigation: InvestigationContext,
    impact: ImpactContext | None,
    papers: list[PaperChanges],
) -> None:
    """The background task after a nudge: investigation, then impact on the reports it
    finished (the handoff is their ids). Without a Gemini key the reports stay investigated."""
    report_ids = await investigate_papers(sm, investigation, papers)
    if not report_ids:
        return
    if impact is None:
        log.info("GEMINI_API_KEY is not set: reports %s stay investigated", report_ids)
        return
    await assess_reports(sm, impact, report_ids)


@router.post("/evaluate/changes", status_code=202, dependencies=[Depends(require_service_token)])
async def evaluate_changes(
    nudge: ChangesNudge,
    sm: Annotated[httpx.AsyncClient, Depends(sm_client)],
    window: Annotated[int, Depends(snapshot_window)],
    investigation: Annotated[InvestigationContext, Depends(investigation_context)],
    impact: Annotated[ImpactContext | None, Depends(impact_context)],
    background: BackgroundTasks,
) -> EvaluationResult:
    """202 once every paper's alerts are stored
    503 if any paper failed, so Updating keeps
    its `nudge_pending` flag and re-sends the ids on its next poll.
    Either way, the papers evaluated without failure are investigated after the reply is sent,
    then their finished reports assessed, so Updating never waits for either."""
    evaluation = await evaluate_papers(sm, nudge.paper_ids, window)
    if evaluation.to_investigate:
        background.add_task(
            investigate_then_assess, sm, investigation, impact, evaluation.to_investigate
        )
    result = evaluation.result
    if result.failed_paper_ids:
        return JSONResponse(
            status_code=503,
            content={"detail": "Evaluation failed; the nudge was not accepted", **result.model_dump(mode="json")},
        )
    return result


@router.post(
    "/evaluate/reports", status_code=202, dependencies=[Depends(require_service_token)]
)
async def evaluate_reports(
    request: ReportsRequest,
    sm: Annotated[httpx.AsyncClient, Depends(sm_client)],
    impact: Annotated[ImpactContext | None, Depends(impact_context)],
    background: BackgroundTasks,
) -> AcceptedReports:
    """Assesses reports on request, by id: to re-run a report whose impact failed, or to assess
    the demo's reports ahead of time. 202 with the ids accepted (each once, in order) and impact
    runs in the background; only `investigated` reports are assessed, the rest are skipped.
    503 when GEMINI_API_KEY isn't set, and nothing runs."""
    if impact is None:
        raise HTTPException(503, "Gemini isn't configured")
    report_ids = list(dict.fromkeys(request.report_ids))
    background.add_task(assess_reports, sm, impact, report_ids)
    return AcceptedReports(report_ids=report_ids)


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
        async with (
            httpx.AsyncClient(
                base_url=config.sm_base_url, auth=ServiceTokenAuth(key, SERVICE_SUBJECT), timeout=HTTP_TIMEOUT_SECONDS
            ) as sm,
            # Crossref and Europe PMC only: no base URL, no auth, never the service token
            httpx.AsyncClient(timeout=HTTP_TIMEOUT_SECONDS) as external,
            # Telegram only, the same way: the bot token is in each request's URL
            httpx.AsyncClient(timeout=HTTP_TIMEOUT_SECONDS) as telegram,
        ):
            app.state.jwt_key = key
            app.state.sm = sm
            app.state.snapshot_window = config.evaluation_snapshot_window
            app.state.investigation = InvestigationContext(
                external=external,
                crossref_mailto=config.crossref_mailto,
                pdf_timeout=config.investigation_pdf_timeout_seconds,
            )
            notifier = (
                TelegramNotifier(
                    telegram,
                    config.telegram_bot_token.get_secret_value().strip(),
                    config.notify_telegram_chat_id.strip(),
                )
                if telegram_configured(config)
                else None
            )
            app.state.impact = (
                ImpactContext(GeminiLlm(make_client(config), config.gemini_model), notifier)
                if gemini_configured(config)
                else None
            )
            yield

    app = FastAPI(title="Research Evaluation", lifespan=lifespan)
    app.include_router(router)
    return app


app = create_app()
