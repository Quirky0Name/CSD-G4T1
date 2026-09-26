"""Updating service (docs/ARCHITECTURE.md, Section 4).

Runs the scheduled poll from the FastAPI lifespan. Single process only: one uvicorn
worker, one replica. `POST /admin/run-poll` lands in PR 4."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime

import httpx
from fastapi import FastAPI

from updating.config import UpdatingSettings
from updating.db import init_db, make_engine, make_sessions
from updating.poll import PollDeps
from updating.scheduler import build_scheduler, first_run_time, last_scheduled_start
from updating.storage import ServiceTokenAuth

HTTP_TIMEOUT_SECONDS = 20


def configure_logging() -> None:
    # uvicorn configures its own loggers only, so without this our INFO logs go nowhere
    logging.basicConfig(level=logging.INFO)
    # httpx logs every request URL at INFO, and OpenAlex's key is in the query string
    logging.getLogger("httpx").setLevel(logging.WARNING)


def create_app(settings: UpdatingSettings | None = None) -> FastAPI:
    configure_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        config = settings or UpdatingSettings()  # a missing or invalid variable fails here, at startup
        engine = make_engine(config.database_url)
        try:
            await init_db(engine)
            sessions = make_sessions(engine)
            next_run = first_run_time(
                await last_scheduled_start(sessions), config.poll_interval_hours, datetime.now(UTC)
            )
            sm_auth = ServiceTokenAuth(config.jwt_secret.get_secret_value())
            async with (
                httpx.AsyncClient(base_url=config.sm_base_url, auth=sm_auth, timeout=HTTP_TIMEOUT_SECONDS) as sm,
                httpx.AsyncClient(base_url=config.re_base_url, timeout=HTTP_TIMEOUT_SECONDS) as re,
                httpx.AsyncClient(timeout=HTTP_TIMEOUT_SECONDS) as sources,
            ):
                deps = PollDeps(
                    sm=sm,
                    sources=sources,
                    re=re,
                    sessions=sessions,
                    crossref_mailto=config.crossref_mailto,
                    openalex_api_key=config.openalex_api_key,
                )
                app.state.scheduler = build_scheduler(deps, config.poll_interval_hours, next_run)
                app.state.scheduler.start()
                try:
                    yield
                finally:
                    app.state.scheduler.shutdown(wait=False)
        finally:
            await engine.dispose()

    return FastAPI(title="Updating", lifespan=lifespan)


app = create_app()
