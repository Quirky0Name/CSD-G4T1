"""Research Evaluation's client for User Management: reading the researcher's profile, which
impact gives to steps 2 and 3, and posting the events User Management tells the researcher about
(docs/CONTRACTS.md, "Research Evaluation ↔ User Management").
The client carries Research Evaluation's service token; User Management's /internal is reachable
only on the compose network."""

import logging
from typing import Any
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict

from research_evaluation.storage import PaperDetails, _detail

log = logging.getLogger(__name__)

# User Management sends the message before it answers, and an email can be slow
NOTIFY_TIMEOUT_SECONDS = 30


class ResearcherProfile(BaseModel):
    model_config = ConfigDict(extra="ignore")

    occupation: str | None = None
    occupation_other: str | None = None
    research_fields: list[str] = []
    research_interests: list[str] = []
    work_types: list[str] = []
    response_preferences: str | None = None


async def researcher_profile(http: httpx.AsyncClient, user_id: UUID) -> ResearcherProfile | None:
    """The researcher's profile, or None when they have none (User Management's `No profile`
    404) or cleared every answer. Any other answer raises: an HTTP error, or a ValidationError
    for a body that doesn't fit."""
    response = await http.get(f"/internal/users/{user_id}/profile")
    if response.status_code == 404 and _detail(response) == f"No profile {user_id}":
        return None
    response.raise_for_status()
    profile = ResearcherProfile.model_validate(response.json())
    return profile if any(profile.model_dump().values()) else None  # every answer cleared


async def notify(http: httpx.AsyncClient, user_id: UUID, body: dict[str, Any]) -> None:
    """Posts one event; raises on any non-2xx answer or transport error."""
    response = await http.post(
        f"/internal/users/{user_id}/notifications", json=body, timeout=NOTIFY_TIMEOUT_SECONDS
    )
    response.raise_for_status()


def paper_body(paper_id: UUID, details: PaperDetails) -> dict[str, Any]:
    """The `paper` object every event carries."""
    return {"id": str(paper_id), "title": details.title, "doi": details.doi}


async def notify_quietly(
    http: httpx.AsyncClient, user_id: UUID, report_id: int, body: dict[str, Any]
) -> None:
    """Posts one event for a report; a failure is logged, never raised: telling the researcher
    must not change what investigation or impact did. The log names the status code or the
    exception class only, never a response body."""
    try:
        await notify(http, user_id, body)
    except Exception as exc:  # noqa: BLE001
        log.warning("notifying user %s about report %s failed: %s", user_id, report_id, _cause(exc))


def _cause(exc: Exception) -> str:
    """The status code or the exception class only, never a response body."""
    match exc:
        case httpx.HTTPStatusError():
            return f"User Management answered {exc.response.status_code}"
        case _:
            return type(exc).__name__
