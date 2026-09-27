"""
handle communitcation with SM
- GET snapshot history
- GET change keys already stored (the changes evaluated on earlier nudges)
- POST alerts
- open a report, POST its documents, mark it investigated (investigation)
- read a report by id, the paper's details and PDFs, the draft, and store the evaluation (impact)

handle HTTP codes
"""

from datetime import datetime
from typing import Any
from uuid import UUID

import httpx
from fastapi import Request
from pydantic import BaseModel, ConfigDict, field_validator

from research_evaluation.changes import Snapshot

SERVICE_SUBJECT = "svc:research-evaluation"


class PaperGone(Exception):
    """Storage Management doesn't know the paper (it was never tracked, or was deleted)."""


class ReportGone(Exception):
    """Storage Management doesn't know the report (it never existed, or went with its paper)."""


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


class AlertInReport(BaseModel):
    """An alert as a report shows it: impact reads what the rules said about each change."""

    model_config = ConfigDict(extra="ignore")

    id: int
    change_type: str
    change_key: str
    severity: str
    description: str
    notice_doi: str | None = None
    detected_at: datetime


class DocumentInReport(BaseModel):
    """What investigation stored for one DOI of a report, as Storage Management returns it."""

    model_config = ConfigDict(extra="ignore")

    id: int
    kind: str
    doi: str
    crossref_status: str
    crossref_record: dict[str, Any] | None = None
    update_to_includes_paper: bool | None = None
    text_status: str
    text: str | None = None
    text_truncated: bool = False
    pdf_status: str
    pdf_source_url: str | None = None
    pdf_fetched_at: datetime | None = None


class Report(BaseModel):
    """A report read by its id (impact's handoff): its paper, status, alerts and documents."""

    model_config = ConfigDict(extra="ignore")

    id: int
    paper_id: UUID
    status: str
    alerts: list[AlertInReport]
    documents: list[DocumentInReport]


class PaperDetails(BaseModel):
    """Who and what the tracked paper is, from its newest snapshot: enough to find it in a
    reference list. Kept apart from detection's Snapshot, which doesn't need these."""

    model_config = ConfigDict(extra="ignore")

    doi: str | None = None
    title: str | None = None
    publication_year: int | None = None
    journal: str | None = None
    authors: list[str] = []

    @field_validator("authors", mode="before")
    @classmethod
    def _names(cls, value: Any) -> list[str]:
        """Snapshots hold authors as objects ({name, ...}) or null; only the names are kept."""
        if not value:
            return []
        return [a["name"] for a in value if isinstance(a, dict) and a.get("name")]


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


async def read_report(http: httpx.AsyncClient, report_id: int) -> Report:
    """The report by its id alone (`GET /internal/reports/{id}`); ReportGone when Storage
    Management doesn't know it, including a report that went with its deleted paper."""
    response = await http.get(f"/internal/reports/{report_id}")
    _raise_for_report(response, report_id)
    return Report.model_validate(response.json())


async def paper_details(http: httpx.AsyncClient, paper_id: UUID) -> PaperDetails:
    """The paper's DOI, title, year, journal and authors from its newest snapshot; all empty when
    it has none yet."""
    response = await http.get(
        f"/internal/papers/{paper_id}/background-info/history", params={"last": 1}
    )
    _raise_for_status(response, paper_id)
    snapshots = response.json().get("snapshots") or []
    if not snapshots:
        return PaperDetails()
    newest = max(snapshots, key=lambda snapshot: snapshot["snapshot_id"])
    return PaperDetails.model_validate(newest)


async def paper_pdf(http: httpx.AsyncClient, paper_id: UUID) -> bytes | None:
    """The tracked paper's stored PDF, or None when it has none (no file, or missing from disk)."""
    return await _pdf(http, f"/internal/papers/{paper_id}/pdf", paper_id)


async def draft_pdf(http: httpx.AsyncClient, paper_id: UUID) -> bytes | None:
    """The researcher's own paper for the tracked paper's project, or None when the project has
    none (most don't) or its file is missing from disk. Only `No paper <id>` is the paper gone."""
    return await _pdf(http, f"/internal/papers/{paper_id}/research-paper", paper_id)


async def document_pdf(http: httpx.AsyncClient, document_id: int) -> bytes | None:
    """A report document's stored PDF, or None when it has none (a notice, pending, not found,
    or missing from disk)."""
    response = await http.get(f"/internal/documents/{document_id}/pdf")
    if response.status_code == 404:
        return None
    response.raise_for_status()
    return response.content


async def store_evaluation(
    http: httpx.AsyncClient, report_id: int, body: dict[str, Any]
) -> dict[str, Any]:
    """Stores impact's evaluation (`PUT /internal/reports/{id}/evaluation`), which marks the
    report assessed, and returns the report. A 409 (not investigated, or already assessed) is
    raised like any other error status."""
    response = await http.put(f"/internal/reports/{report_id}/evaluation", json=body)
    _raise_for_report(response, report_id)
    return response.json()


async def _pdf(http: httpx.AsyncClient, path: str, paper_id: UUID) -> bytes | None:
    response = await http.get(path)
    if response.status_code == 404 and _detail(response) != f"No paper {paper_id}":
        return None  # the paper is there, the file isn't
    _raise_for_status(response, paper_id)  # PaperGone for `No paper <id>`, raises other errors
    if response.status_code != 200:
        raise ValueError(f"unexpected status {response.status_code} reading a PDF")
    return response.content


def _raise_for_report(response: httpx.Response, report_id: int) -> None:
    if response.status_code == 404 and _detail(response) == f"No report {report_id}":
        raise ReportGone(str(report_id))
    response.raise_for_status()


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
