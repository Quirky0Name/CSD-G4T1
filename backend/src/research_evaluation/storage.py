"""
handle communitcation with SM
- GET snapshot history
- GET change keys already stored (the changes evaluated on earlier nudges)
- POST alerts
- open a report, POST its documents, mark it investigated (investigation)

handle HTTP codes
"""

from typing import Any
from uuid import UUID

import httpx
from fastapi import Request
from pydantic import BaseModel, ConfigDict

from research_evaluation.changes import Snapshot

SERVICE_SUBJECT = "svc:research-evaluation"


class PaperGone(Exception):
    """Storage Management doesn't know the paper (it was never tracked, or was deleted)."""


class _History(BaseModel):
    """insert snapshots as Snapshot type and ignore the rest"""
    model_config = ConfigDict(extra="ignore")

    snapshots: list[Snapshot]


class _ChangeKeys(BaseModel):
    model_config = ConfigDict(extra="ignore")

    change_keys: list[str]


class ReportAlert(BaseModel):
    model_config = ConfigDict(extra="ignore")

    change_key: str


class OpenedReport(BaseModel):
    """A report Storage Management just opened: its id and the alerts it grouped."""

    model_config = ConfigDict(extra="ignore")

    id: int
    alerts: list[ReportAlert]


def sm_client(request: Request) -> httpx.AsyncClient:
    """return SM client that was created at startup"""
    # request.app returns the app handling the request 
    return request.app.state.sm


async def snapshot_history(http: httpx.AsyncClient, paper_id: UUID, last: int) -> list[Snapshot]:
    """get the newest last N snapshots of the paper (oldest first) from SM
    last = snapshot window"""
    response = await http.get(f"/internal/papers/{paper_id}/background-info/history", params={"last": last})
    _raise_for_status(response, paper_id)
    # turn json body into python objects (BaseModel wrapper)
    history = _History.model_validate(response.json())
    return sorted(history.snapshots, key=lambda snapshot: snapshot.snapshot_id)


async def stored_change_keys(http: httpx.AsyncClient, paper_id: UUID) -> set[str]:
    """the change keys SM already has an alert for: changes evaluated on an earlier nudge"""
    response = await http.get(f"/internal/papers/{paper_id}/alerts/change-keys")
    _raise_for_status(response, paper_id)
    return set(_ChangeKeys.model_validate(response.json()).change_keys)


async def store_alert(http: httpx.AsyncClient, paper_id: UUID, alert: dict[str, Any]) -> bool:
    """Stores alert
    True if it was new (201)
    False if Storage Management already had an alert with that change key (200)."""
    response = await http.post(f"/internal/papers/{paper_id}/alerts", json=alert)
    _raise_for_status(response, paper_id)
    if response.status_code not in (200, 201):
        raise ValueError(f"unexpected status {response.status_code} storing an alert")
    return response.status_code == 201


async def open_report(http: httpx.AsyncClient, paper_id: UUID) -> OpenedReport | None:
    """Opens a report grouping the paper's alerts that aren't in one yet (201), or None when
    there are none (204)."""
    response = await http.post(f"/internal/papers/{paper_id}/reports")
    _raise_for_status(response, paper_id)
    if response.status_code == 204:
        return None
    if response.status_code != 201:
        raise ValueError(f"unexpected status {response.status_code} opening a report")
    return OpenedReport.model_validate(response.json())


async def store_document(http: httpx.AsyncClient, body: dict[str, Any], timeout: float) -> dict[str, Any]:
    """Stores one document of a report and returns the row as Storage Management stored it
    (201 new, 200 already there). Storage Management downloads a new version's or current
    copy's PDF before answering, hence the caller's longer timeout."""
    response = await http.post("/internal/documents", json=body, timeout=timeout)
    response.raise_for_status()
    if response.status_code not in (200, 201):
        raise ValueError(f"unexpected status {response.status_code} storing a document")
    return response.json()


async def mark_investigated(http: httpx.AsyncClient, paper_id: UUID, report_id: int) -> None:
    response = await http.patch(
        f"/internal/papers/{paper_id}/reports/{report_id}", json={"status": "investigated"}
    )
    _raise_for_status(response, paper_id)


def _raise_for_status(response: httpx.Response, paper_id: UUID) -> None:
    """custom raise_for_status to handle our specific error codes
    default to httpx's implementation for other http error codes"""
    if response.status_code == 404 and _detail(response) == f"No paper {paper_id}":
        raise PaperGone(str(paper_id))
    # use httpx library to raise error for certain error codes
    response.raise_for_status()


def _detail(response: httpx.Response) -> str | None:
    try:
        body = response.json()
    except ValueError:
        return None
    return body.get("detail") if isinstance(body, dict) else None
