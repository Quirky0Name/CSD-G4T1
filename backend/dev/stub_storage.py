"""In-memory stand-in for Storage Management, for developing and demoing Updating
and Research Evaluation before the real endpoints exist (CG-68).

Implements the `/internal/**` contract in docs/CONTRACTS.md and checks the service
token the way Storage Management's JwtAuthFilter does. Three stub-only helpers seed it:
`POST /dev/papers`, `POST /dev/reset` and `POST /dev/seed?scenario=`, which adds a paper
with a synthetic earlier snapshot (dev/scenarios.py) so the next poll has a change to find.
It is a development aid and should never be pointed at from a deployed environment.

Run it with:
    uv run --env-file .env uvicorn dev.stub_storage:create_app --factory --port 8081
"""

import hashlib
import os
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Annotated, Any, Literal
from uuid import UUID, uuid4

import jwt
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, model_validator

from common.doi import normalize_doi
from common.service_token import decode_jwt_secret
from dev.scenarios import Scenario, before_snapshot


class StubPaper(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    owner_id: UUID = Field(default_factory=uuid4)
    doi: str | None = None
    issn: str | None = None


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


class NewDocument(BaseModel):
    """The body of `POST /internal/documents`; the real service answers 400 where this
    stub's validation answers 422."""

    report_id: int
    kind: Literal["notice", "new_version", "current_version"]
    doi: str = Field(min_length=1, max_length=255, pattern=r"\S")
    crossref_status: Literal["ok", "not_found", "error"]
    crossref_record: Any = None
    update_to_includes_paper: bool | None = None
    text_status: Literal["ok", "not_indexed", "not_open_access", "error"]
    text: str | None = None
    text_truncated: bool


class ReportStatusChange(BaseModel):
    status: Literal["investigating", "investigated", "assessed"]


Level = Literal["none", "low", "medium", "high"]


class ReportEvaluation(BaseModel):
    """The body of `PUT /internal/reports/{id}/evaluation`: impact's result. With change_severity
    none only the summary goes in; otherwise impact_level, evaluation and recommendation are
    required. The real service answers 400 where this stub's validation answers 422."""

    change_summary: str = Field(min_length=1, pattern=r"\S")
    change_severity: Level
    impact_level: Level | None = None
    evaluation: str | None = None
    recommendation: str | None = None
    assessment: Any = None

    @model_validator(mode="after")
    def _shape(self) -> "ReportEvaluation":
        rest = (self.impact_level, self.evaluation, self.recommendation)
        if self.change_severity == "none":
            if any(value is not None for value in rest):
                raise ValueError("impact_level, evaluation and recommendation must be null when change_severity is none")
        elif self.impact_level is None or any(not (text and text.strip()) for text in rest[1:]):
            raise ValueError("impact_level, evaluation and recommendation are required unless change_severity is none")
        return self


class DownloadablePdf(BaseModel):
    """Stub-only: a DOI whose PDF the stub's scripted download finds."""

    doi: str
    source_url: str


def stub_pdf(doi: str) -> bytes:
    """The bytes the stub "downloads" for a DOI."""
    return f"%PDF-1.7 stub copy of {doi}".encode()


# stored but, as in Storage Management, never returned by the alert API
INTERNAL_ALERT_FIELDS = ("change_key", "snapshot_id", "previous_snapshot_id", "report_id")
# how a report shows its alerts: with the change key, which Research Evaluation matches on
REPORT_ALERT_FIELDS = (
    "id",
    "change_type",
    "change_key",
    "severity",
    "description",
    "recommendation",
    "notice_doi",
    "detected_at",
    "status",
)


@dataclass
class Store:
    papers: dict[UUID, StubPaper] = field(default_factory=dict)
    snapshots: dict[UUID, list[dict[str, Any]]] = field(default_factory=dict)
    last_snapshot_id: int = 0
    # per paper, keyed by change_key: one alert per change, like the real unique constraint
    alerts: dict[UUID, dict[str, dict[str, Any]]] = field(default_factory=dict)
    last_alert_id: int = 0
    reports: dict[int, dict[str, Any]] = field(default_factory=dict)
    last_report_id: int = 0
    # keyed by id; one per (report_id, doi), like the real unique constraint
    documents: dict[int, dict[str, Any]] = field(default_factory=dict)
    last_document_id: int = 0
    # the stub's scripted PDF downloads: DOI -> the link it "comes from"; any other DOI isn't found
    downloadable_pdfs: dict[str, str] = field(default_factory=dict)
    pdf_downloads: list[str] = field(default_factory=list)
    pdf_files: dict[int, bytes] = field(default_factory=dict)

    def add_snapshot(self, paper_id: UUID, snapshot: dict[str, Any]) -> dict[str, Any]:
        self.last_snapshot_id += 1
        stored = {"snapshot_id": self.last_snapshot_id, "paper_id": str(paper_id), **snapshot}
        self.snapshots.setdefault(paper_id, []).append(stored)
        return stored

    def clear(self) -> None:
        self.papers.clear()
        self.snapshots.clear()
        self.last_snapshot_id = 0
        self.alerts.clear()
        self.last_alert_id = 0
        self.reports.clear()
        self.last_report_id = 0
        self.documents.clear()
        self.last_document_id = 0
        self.downloadable_pdfs.clear()
        self.pdf_downloads.clear()
        self.pdf_files.clear()


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
        last: Annotated[int | None, Query(ge=1)] = None,
    ) -> dict[str, list[dict[str, Any]]]:
        """Oldest first. `last=N` keeps only the newest N (still oldest first)."""
        find_paper(paper_id)
        rows = store.snapshots.get(paper_id, [])
        if after_id is not None:
            rows = [row for row in rows if row["snapshot_id"] > after_id]
        if last is not None:
            rows = rows[-last:]
        return {"snapshots": rows[:limit]}

    @internal.post("/papers/{paper_id}/alerts")
    def store_alert(paper_id: UUID, alert: NewAlert, response: Response) -> dict[str, Any]:
        """Idempotent on (paper_id, change_key): 201 for a new alert, 200 with the stored one
        unchanged. The exception, as in Storage Management: a retraction notice for a
        retraction alert stored without one replaces it and makes it new (201), keeping its
        id and leaving its report."""
        find_paper(paper_id)
        stored = store.alerts.setdefault(paper_id, {})
        existing = stored.get(alert.change_key)
        if (
            existing is not None
            and existing["change_type"] == "retraction"
            and existing["notice_doi"] is None
            and alert.change_type == "retraction"
            and alert.notice_doi
        ):
            replacement = alert.model_dump(mode="json")
            existing.update(
                {
                    field: replacement[field]
                    for field in (
                        "severity",
                        "description",
                        "recommendation",
                        "notice_doi",
                        "detected_at",
                        "snapshot_id",
                        "previous_snapshot_id",
                    )
                },
                status="new",
                status_changed_at=None,
                report_id=None,
            )
            response.status_code = 201
        elif existing is not None:
            response.status_code = 200
        else:
            store.last_alert_id += 1
            stored[alert.change_key] = {
                "id": store.last_alert_id,
                "paper_id": str(paper_id),
                **alert.model_dump(mode="json"),
                "status": "new",
                "status_changed_at": None,
                "report_id": None,
            }
            response.status_code = 201
        row = stored[alert.change_key]
        return {key: value for key, value in row.items() if key not in INTERNAL_ALERT_FIELDS}

    @internal.get("/papers/{paper_id}/alerts/change-keys")
    def change_keys(paper_id: UUID) -> dict[str, list[str]]:
        """Every change key stored for the paper, sorted, as in Storage Management."""
        find_paper(paper_id)
        return {"change_keys": sorted(store.alerts.get(paper_id, {}))}

    def report_view(report: dict[str, Any]) -> dict[str, Any]:
        """A report with its alerts and documents, oldest first, as Storage Management returns it."""
        paper_alerts = store.alerts.get(UUID(report["paper_id"]), {}).values()
        grouped = sorted(
            (a for a in paper_alerts if a["report_id"] == report["id"]), key=lambda a: a["id"]
        )
        documents = sorted(
            (d for d in store.documents.values() if d["report_id"] == report["id"]),
            key=lambda d: d["id"],
        )
        return {
            **report,
            "alerts": [{key: a[key] for key in REPORT_ALERT_FIELDS} for a in grouped],
            "documents": documents,
        }

    def find_report(paper_id: UUID, report_id: int) -> dict[str, Any]:
        find_paper(paper_id)
        report = store.reports.get(report_id)
        if report is None or report["paper_id"] != str(paper_id):
            raise HTTPException(404, f"No report {report_id}")
        return report

    @internal.post("/papers/{paper_id}/reports")
    def open_report(paper_id: UUID) -> Response:
        """Groups every alert of the paper not in a report yet: 201 with the report, or 204
        when there are none."""
        find_paper(paper_id)
        unreported = [a for a in store.alerts.get(paper_id, {}).values() if a["report_id"] is None]
        if not unreported:
            return Response(status_code=204)
        store.last_report_id += 1
        report = {
            "id": store.last_report_id,
            "paper_id": str(paper_id),
            "status": "investigating",
            "created_at": datetime.now(UTC).isoformat(),
            "investigated_at": None,
            "evaluation": None,
            "recommendation": None,
            "evaluated_at": None,
            # impact's fields (docs/EVALUATION-IMPACT.md), null until the report is assessed
            "change_summary": None,
            "change_severity": None,
            "impact_level": None,
            "assessment": None,
        }
        store.reports[report["id"]] = report
        for alert in unreported:
            alert["report_id"] = report["id"]
        return JSONResponse(report_view(report), status_code=201)

    @internal.get("/papers/{paper_id}/reports/{report_id}")
    def read_report(paper_id: UUID, report_id: int) -> dict[str, Any]:
        return report_view(find_report(paper_id, report_id))

    @internal.patch("/papers/{paper_id}/reports/{report_id}")
    def change_report_status(
        paper_id: UUID, report_id: int, change: ReportStatusChange
    ) -> dict[str, Any]:
        """Only "investigated" can be set; setting it again keeps the first time."""
        if change.status != "investigated":
            raise HTTPException(400, "status must be investigated")
        report = find_report(paper_id, report_id)
        if report["status"] == "assessed":
            raise HTTPException(409, f"Report {report_id} is already assessed")
        if report["status"] == "investigating":
            report["status"] = "investigated"
            report["investigated_at"] = datetime.now(UTC).isoformat()
        return report_view(report)

    def find_report_by_id(report_id: int) -> dict[str, Any]:
        """A report by its id alone; a deleted paper's reports went with it, as in Storage Management."""
        report = store.reports.get(report_id)
        if report is None or UUID(report["paper_id"]) not in store.papers:
            raise HTTPException(404, f"No report {report_id}")
        return report

    @internal.get("/reports/{report_id}")
    def read_report_by_id(report_id: int) -> dict[str, Any]:
        return report_view(find_report_by_id(report_id))

    @internal.put("/reports/{report_id}/evaluation")
    def record_evaluation(report_id: int, evaluation: ReportEvaluation) -> dict[str, Any]:
        """Stores impact's evaluation once, only on an investigated report, and marks it assessed."""
        report = find_report_by_id(report_id)
        if report["status"] == "investigating":
            raise HTTPException(409, f"Report {report_id} is not investigated yet")
        if report["status"] == "assessed":
            raise HTTPException(409, f"Report {report_id} is already assessed")
        report.update(
            evaluation.model_dump(mode="json"),
            status="assessed",
            evaluated_at=datetime.now(UTC).isoformat(),
        )
        return report_view(report)

    @internal.post("/documents")
    def store_document(document: NewDocument, response: Response) -> dict[str, Any]:
        """Idempotent on (report_id, doi): 201 with the new row, or 200 with the stored one,
        unchanged whatever the body says and downloading nothing. A new new_version or
        current_version row gets its PDF "downloaded" first: the scripted `/dev/pdfs` result
        for its DOI, or not_found."""
        if document.report_id not in store.reports:
            raise HTTPException(404, f"No report {document.report_id}")
        for stored in store.documents.values():
            if stored["report_id"] == document.report_id and stored["doi"] == document.doi:
                response.status_code = 200
                return stored
        store.last_document_id += 1
        row = {
            "id": store.last_document_id,
            **document.model_dump(mode="json"),
            "pdf_status": "skipped" if document.kind == "notice" else "pending",
            "file_key": None,
            "sha256": None,
            "pdf_source_url": None,
            "created_at": datetime.now(UTC).isoformat(),
            "pdf_fetched_at": None,
        }
        store.documents[row["id"]] = row
        if row["pdf_status"] == "pending":
            download_pdf(row)
        response.status_code = 201
        return row

    def download_pdf(row: dict[str, Any]) -> None:
        store.pdf_downloads.append(row["doi"])
        source_url = store.downloadable_pdfs.get(row["doi"])
        if source_url is None:
            row["pdf_status"] = "not_found"
            return
        content = stub_pdf(row["doi"])
        store.pdf_files[row["id"]] = content
        row.update(
            pdf_status="ok",
            file_key=f"stub-{row['id']}.pdf",
            sha256=hashlib.sha256(content).hexdigest(),
            pdf_source_url=source_url,
            pdf_fetched_at=datetime.now(UTC).isoformat(),
        )

    @internal.get("/documents/{document_id}/pdf")
    def document_pdf(document_id: int) -> Response:
        """The stored PDF; only reads, never downloads."""
        row = store.documents.get(document_id)
        if row is None:
            raise HTTPException(404, f"No document {document_id}")
        if row["pdf_status"] != "ok":
            raise HTTPException(404, f"Document {document_id} has no stored PDF")
        return Response(store.pdf_files[document_id], media_type="application/pdf")

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

    @dev.post("/seed", status_code=201)
    def seed(scenario: Scenario) -> StubPaper:
        """Add a paper whose earlier snapshot differs from what the recorded APIs say now."""
        before = before_snapshot(scenario)
        paper = StubPaper(doi=before.doi if before else None)
        store.papers[paper.id] = paper
        if before:
            store.add_snapshot(paper.id, before.model_dump(mode="json"))
        return paper

    @dev.post("/pdfs", status_code=204)
    def make_pdf_downloadable(pdf: DownloadablePdf) -> None:
        """Stub-only: from now on, a new document with this DOI "downloads" a PDF from source_url."""
        store.downloadable_pdfs[pdf.doi] = pdf.source_url

    @dev.get("/pdf-downloads")
    def pdf_downloads() -> list[str]:
        """Stub-only: the DOI of every download attempted, in order."""
        return store.pdf_downloads

    @dev.post("/reset", status_code=204)
    def reset() -> None:
        store.clear()

    app = FastAPI(title="Storage Management (stub)")
    app.include_router(internal)
    app.include_router(dev)
    return app
