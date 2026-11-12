"""Snapshot builders and request helpers shared by the Research Evaluation tests."""

import time
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import uuid4

import httpx
import jwt
from support import TEST_JWT_KEY

from common.service_token import mint_service_token

BASE_TIME = datetime(2026, 9, 1, 12, 0, tzinfo=UTC)


def snapshot_dict(snapshot_id: int, **fields: Any) -> dict[str, Any]:
    """A snapshot as Storage Management returns it: nothing changed yet, every source ok.
    Each snapshot id is fetched a day after the previous one."""
    snapshot = {
        "snapshot_id": snapshot_id,
        "paper_id": "00000000-0000-0000-0000-000000000001",
        "doi": "10.1016/j.ijantimicag.2020.105949",
        "fetched_at": (BASE_TIME + timedelta(days=snapshot_id)).isoformat(),
        "openalex_id": "W3027680906",
        "title": "Hydroxychloroquine and azithromycin as a treatment of COVID-19",
        "publication_year": 2020,
        "is_retracted": False,
        "crossref_updates": [],
        "in_doaj": True,
        "journal_source_id": "S49861241",
        "journal_source_type": "journal",
        "journal": "International Journal of Antimicrobial Agents",
        "issn_l": "0924-8579",
        "publisher": "Elsevier BV",
        "authors": None,
        "cited_by_count": 1252,
        "source_status": {"crossref": "ok", "openalex": "ok", "openalex_authors": None},
    }
    snapshot.update(fields)
    return snapshot


def notice(notice_doi: str | None, type_: str | None, source: str = "publisher", **fields: Any) -> dict[str, Any]:
    entry = {
        "notice_doi": notice_doi,
        "type": type_,
        "label": type_.replace("_", " ").capitalize() if type_ else None,
        "source": source,
        "date": "2020-07-01",
        "record_id": None,
    }
    entry.update(fields)
    return entry


def updating_token() -> str:
    return mint_service_token(TEST_JWT_KEY, "svc:updating")


def user_token() -> str:
    now = int(time.time())
    return jwt.encode({"sub": str(uuid4()), "iat": now, "exp": now + 300}, TEST_JWT_KEY, algorithm="HS256")


async def nudge(client: httpx.AsyncClient, paper_ids: list, token: str | None = None) -> httpx.Response:
    return await client.post(
        "/evaluate/changes",
        json={"paper_ids": [str(paper_id) for paper_id in paper_ids]},
        headers={"Authorization": f"Bearer {token or updating_token()}"},
    )


class FeedbackEmbedder:
    """Embeds every text as the same vector and records each call; `fail` makes every call raise."""

    model = "gemini-embedding-001"

    def __init__(self, fail: bool = False):
        self.fail = fail
        self.calls: list[tuple[list[str], str]] = []

    async def embed(self, texts, task):
        self.calls.append((texts, task))
        if self.fail:
            raise RuntimeError(f"embedding failed for {texts[0]}")
        return [[0.5] * 1536 for _ in texts]


async def feedback_report(sm, feedback: dict | None) -> int:
    """An assessed report of a new paper with one alert and a snapshot titled, with that feedback."""
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1, title="Hydroxychloroquine and azithromycin")
    await sm.client.post(
        f"/internal/papers/{paper}/alerts",
        json={
            "change_type": "retraction", "change_key": "retraction", "alert_severity": "high",
            "description": "d", "recommendation": "r", "notice_doi": None,
            "detected_at": "2026-09-20T12:00:00Z", "snapshot_id": 2, "previous_snapshot_id": 1,
        },
    )
    report_id = (await sm.client.post(f"/internal/papers/{paper}/reports")).json()["id"]
    await sm.client.patch(f"/internal/papers/{paper}/reports/{report_id}", json={"status": "investigated"})
    await sm.client.put(
        f"/internal/reports/{report_id}/evaluation",
        json={"change_summary": "Retracted.", "change_severity": "high", "impact_level": "high",
              "evaluation": "Your Methods use it.", "recommendation": "Remove it."},
    )
    await sm.client.put(f"/dev/reports/{report_id}/feedback", json=feedback)
    return report_id
