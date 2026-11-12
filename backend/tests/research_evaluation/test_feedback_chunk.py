"""A report's feedback as one chunk (research_evaluation.chunks.feedback_piece) and storing it
(research_evaluation.feedback.store_feedback_chunk) against the stub Storage Management."""

import asyncio
import logging
from datetime import UTC, datetime
from uuid import uuid4

import httpx
import pytest
from research_evaluation_support import FeedbackEmbedder as FakeEmbedder
from research_evaluation_support import feedback_report as stub_report

from research_evaluation.chunks import PAGE_LIMIT, feedback_piece
from research_evaluation.feedback import store_feedback_chunk
from research_evaluation.storage import AlertInReport, Feedback, PaperDetails, Report

NOTE = "only use their dataset"


def alert(alert_id: int, change_type: str) -> AlertInReport:
    return AlertInReport(
        id=alert_id,
        change_type=change_type,
        change_key=f"key:{alert_id}",
        alert_severity="high",
        description="Never in the chunk.",
        detected_at=datetime(2026, 10, 1, 8, tzinfo=UTC),
    )


def assessed_report(**fields) -> Report:
    values = {
        "id": 12,
        "paper_id": uuid4(),
        "status": "assessed",
        "created_at": datetime(2026, 10, 2, 8, tzinfo=UTC),
        "alerts": [alert(1, "correction"), alert(2, "expression_of_concern"), alert(3, "correction")],
        "documents": [],
        "change_summary": "Table 2 was corrected.",
        "change_severity": "high",
        "impact_level": "medium",
        "evaluation": "Your Discussion relies on Table 2.",
        "recommendation": "Recheck Table 2.",
        "evaluated_at": datetime(2026, 10, 3, 8, tzinfo=UTC),
        "feedback": Feedback(verdict="overstated", note=NOTE, updated_at=datetime(2026, 10, 4, tzinfo=UTC)),
    }
    values.update(fields)
    return Report(**values)


PAPER = PaperDetails(doi="10.1/paper", title="Hydroxychloroquine and azithromycin")


def test_the_chunk_has_its_lines_in_order():
    piece = feedback_piece(assessed_report(), PAPER)

    assert piece.page is None
    assert piece.content == "\n".join([
        'feedback on report of 2026-10-02: "Hydroxychloroquine and azithromycin"',
        "changes: correction, expression_of_concern",
        "change summary: Table 2 was corrected.",
        "change severity: high",
        "impact level: medium",
        "evaluation: Your Discussion relies on Table 2.",
        "recommendation: Recheck Table 2.",
        "researcher's verdict: overstated",
        f"researcher's note: {NOTE}",
    ])


def test_empty_fields_and_a_missing_note_are_left_out():
    report = assessed_report(
        change_severity="none", impact_level=None, evaluation=None, recommendation=None,
        feedback=Feedback(verdict="fair", note=None, updated_at=datetime(2026, 10, 4, tzinfo=UTC)),
    )

    assert feedback_piece(report, PaperDetails(doi="10.1/paper")).content.splitlines() == [
        'feedback on report of 2026-10-02: "10.1/paper"',
        "changes: correction, expression_of_concern",
        "change summary: Table 2 was corrected.",
        "change severity: none",
        "researcher's verdict: fair",
    ]


def test_a_long_evaluation_is_cut_and_the_title_verdict_and_note_kept():
    report = assessed_report(evaluation="e" * 20_000)

    content = feedback_piece(report, PAPER).content

    assert len(content) <= PAGE_LIMIT
    assert content.startswith('feedback on report of 2026-10-02: "Hydroxychloroquine and azithromycin"\n')
    assert content.endswith(f"\nresearcher's verdict: overstated\nresearcher's note: {NOTE}")
    assert "evaluation: eee" in content


async def stored(sm, report_id: int) -> dict:
    response = await sm.client.get(f"/dev/chunks/feedback/{report_id}")
    response.raise_for_status()
    return response.json()


async def test_the_feedback_is_stored_as_one_chunk_of_the_report(sm):
    report_id = await stub_report(
        sm, {"verdict": "overstated", "note": NOTE, "updated_at": "2026-10-04T08:00:00Z"}
    )
    embedder = FakeEmbedder()

    await store_feedback_chunk(sm.client, embedder, report_id)

    body = await stored(sm, report_id)
    assert body["source_type"] == "feedback"
    assert body["source_id"] == str(report_id)
    assert body["embed_model"] == "gemini-embedding-001"
    [chunk] = body["chunks"]
    assert chunk["page"] is None
    assert len(chunk["embedding"]) == 1536
    assert chunk["content"].startswith('feedback on report of ')
    assert '"Hydroxychloroquine and azithromycin"' in chunk["content"]
    assert "evaluation: Your Methods use it." in chunk["content"]
    assert chunk["content"].endswith(f"researcher's verdict: overstated\nresearcher's note: {NOTE}")
    assert embedder.calls == [([chunk["content"]], "document")]


async def test_a_report_with_no_feedback_stores_an_empty_list(sm):
    report_id = await stub_report(sm, None)
    embedder = FakeEmbedder()

    await store_feedback_chunk(sm.client, embedder, report_id)

    assert (await stored(sm, report_id))["chunks"] == []
    assert embedder.calls == []


class HeldEmbedder(FakeEmbedder):
    """Holds its first call until `release` is set; later calls answer at once."""

    def __init__(self):
        super().__init__()
        self.release = asyncio.Event()

    async def embed(self, texts, task):
        first = not self.calls
        vectors = await super().embed(texts, task)
        if first:
            await self.release.wait()
        return vectors


async def test_of_two_quick_saves_the_later_one_is_stored(sm):
    report_id = await stub_report(
        sm, {"verdict": "fair", "note": "first note", "updated_at": "2026-10-04T08:00:00Z"}
    )
    embedder = HeldEmbedder()
    first = asyncio.create_task(store_feedback_chunk(sm.client, embedder, report_id))
    while not embedder.calls:
        await asyncio.sleep(0)
    await sm.client.put(
        f"/dev/reports/{report_id}/feedback",
        json={"verdict": "overstated", "note": "second note", "updated_at": "2026-10-04T08:01:00Z"},
    )
    second = asyncio.create_task(store_feedback_chunk(sm.client, embedder, report_id))
    await asyncio.wait({second}, timeout=0.2)

    embedder.release.set()
    await asyncio.gather(first, second)

    [chunk] = (await stored(sm, report_id))["chunks"]
    assert chunk["content"].endswith("researcher's verdict: overstated\nresearcher's note: second note")


async def test_an_embedder_error_is_logged_without_the_note_and_not_raised(sm, caplog):
    report_id = await stub_report(
        sm, {"verdict": "overstated", "note": NOTE, "updated_at": "2026-10-04T08:00:00Z"}
    )

    with caplog.at_level(logging.WARNING):
        await store_feedback_chunk(sm.client, FakeEmbedder(fail=True), report_id)

    assert (await sm.client.get(f"/dev/chunks/feedback/{report_id}")).status_code == 404
    assert f"report {report_id}" in caplog.text
    assert "RuntimeError" in caplog.text
    assert NOTE not in caplog.text


@pytest.mark.parametrize("fault", [500, httpx.ConnectError("refused")])
async def test_a_storage_error_is_logged_and_not_raised(sm, caplog, fault):
    report_id = await stub_report(
        sm, {"verdict": "fair", "note": NOTE, "updated_at": "2026-10-04T08:00:00Z"}
    )
    sm.transport.faults[("PUT", "/internal/chunks")] = fault

    with caplog.at_level(logging.WARNING):
        await store_feedback_chunk(sm.client, FakeEmbedder(), report_id)

    assert f"report {report_id}" in caplog.text
    assert NOTE not in caplog.text


async def test_an_unknown_report_is_logged_and_not_raised(sm, caplog):
    with caplog.at_level(logging.WARNING):
        await store_feedback_chunk(sm.client, FakeEmbedder(), 999)

    assert "report 999" in caplog.text
