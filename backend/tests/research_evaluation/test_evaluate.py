import logging
from uuid import uuid4

import httpx
import pytest
from research_evaluation_support import BASE_TIME, notice, nudge

from research_evaluation import llm
from research_evaluation.rules import assess as real_assess


def day(n: int) -> str:
    """The fetched_at of a snapshot stored on day n, as the alerts carry it."""
    return (BASE_TIME.replace(day=BASE_TIME.day + n)).isoformat().replace("+00:00", "Z")


# --- from nudge to stored alert ---


async def test_a_retraction_becomes_a_stored_alert(client, sm):
    paper = await sm.add_paper()
    before = await sm.add_snapshot(paper, 1)
    after = await sm.add_snapshot(paper, 2, is_retracted=True,
                                  crossref_updates=[notice("10.1/notice", "retraction", date="2024-12-16")])

    response = await nudge(client, [paper])

    assert response.status_code == 202
    assert response.json() == {"alerts_created": 1, "skipped_paper_ids": [], "failed_paper_ids": []}
    [alert] = await sm.alerts(paper)
    assert alert["change_type"] == "retraction"
    assert alert["change_key"] == "retraction"
    assert alert["severity"] == "high"
    assert "(notice 10.1/notice, 2024-12-16)" in alert["description"]
    assert alert["recommendation"]
    assert alert["notice_doi"] == "10.1/notice"
    assert alert["detected_at"] == day(2)
    assert (alert["snapshot_id"], alert["previous_snapshot_id"]) == (after["snapshot_id"], before["snapshot_id"])
    assert alert["status"] == "new"


@pytest.mark.parametrize(
    ("fields", "change_type", "severity"),
    [
        ({"is_retracted": True}, "retraction", "high"),
        ({"crossref_updates": [notice("10.1/n", "expression_of_concern")]}, "expression_of_concern", "medium"),
        ({"crossref_updates": [notice("10.1/n", "correction")]}, "correction", "medium"),
        ({"crossref_updates": [notice("10.1/n", "erratum")]}, "erratum", "low"),
        ({"in_doaj": False}, "doaj_delisting", "low"),
        ({"crossref_updates": [notice("10.1/n", "withdrawal")]}, "other", "medium"),
    ],
)
async def test_each_kind_of_change_is_stored_with_its_severity(client, sm, fields, change_type, severity):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, **fields)

    assert (await nudge(client, [paper])).status_code == 202

    [alert] = await sm.alerts(paper)
    assert (alert["change_type"], alert["severity"]) == (change_type, severity)


async def test_stage_3_is_called_for_other_changes_only_and_keeps_the_stage_2_assessment(client, sm, monkeypatch):
    calls = []
    placeholder = llm.investigate

    def spy(change, context, assessment):
        calls.append((change.change_type, context.paper_id, context.previous.snapshot_id, context.current.snapshot_id))
        result = placeholder(change, context, assessment)
        assert result == assessment  # the placeholder keeps the stage-2 assessment
        return result

    monkeypatch.setattr("research_evaluation.evaluate.llm.investigate", spy)
    paper = await sm.add_paper()
    before = await sm.add_snapshot(paper, 1)
    after = await sm.add_snapshot(paper, 2, crossref_updates=[notice("10.1/w", "withdrawal", label="Withdrawal"),
                                                              notice("10.1/c", "correction")])

    assert (await nudge(client, [paper])).status_code == 202

    assert calls == [("other", paper, before["snapshot_id"], after["snapshot_id"])]
    other = next(alert for alert in await sm.alerts(paper) if alert["change_type"] == "other")
    assert other["change_key"] == "other:withdrawal:10.1/w"
    assert "a 'Withdrawal' notice" in other["description"]


async def test_the_stage_3_result_is_what_gets_stored(client, sm, monkeypatch):
    def rewrite(change, context, assessment):
        return assessment.model_copy(update={"description": "Investigated by stage 3"})

    monkeypatch.setattr("research_evaluation.evaluate.llm.investigate", rewrite)
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, crossref_updates=[notice("10.1/w", "withdrawal")])

    await nudge(client, [paper])

    assert [alert["description"] for alert in await sm.alerts(paper)] == ["Investigated by stage 3"]


async def test_a_second_nudge_for_the_same_papers_stores_nothing_new(client, sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True, in_doaj=False)

    first = await nudge(client, [paper])
    second = await nudge(client, [paper])

    assert first.json()["alerts_created"] == 2
    assert second.status_code == 202
    assert second.json()["alerts_created"] == 0
    assert len(await sm.alerts(paper)) == 2


async def test_every_consecutive_pair_is_compared_and_each_alert_has_its_own_detection_time(client, sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2)
    await sm.add_snapshot(paper, 3, crossref_updates=[notice("10.1/c", "correction")])
    await sm.add_snapshot(paper, 4, crossref_updates=[notice("10.1/c", "correction")], is_retracted=True)
    await sm.add_snapshot(paper, 5, crossref_updates=[notice("10.1/c", "correction")], is_retracted=True)

    assert (await nudge(client, [paper])).json()["alerts_created"] == 2

    detected = {alert["change_type"]: alert["detected_at"] for alert in await sm.alerts(paper)}
    assert detected == {"correction": day(3), "retraction": day(4)}


async def test_a_change_missed_while_research_evaluation_was_down_is_caught_up_later(client, sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    await sm.add_snapshot(paper, 3, is_retracted=True, in_doaj=False)

    # one nudge covers both polls' changes, each with the time it was first seen
    await nudge(client, [paper])

    detected = {alert["change_type"]: alert["detected_at"] for alert in await sm.alerts(paper)}
    assert detected == {"retraction": day(2), "doaj_delisting": day(3)}


async def test_a_baseline_alone_or_no_snapshots_gives_no_alerts(client, sm):
    baseline_only = await sm.add_paper()
    await sm.add_snapshot(baseline_only, 1)
    no_snapshots = await sm.add_paper()

    response = await nudge(client, [baseline_only, no_snapshots])

    assert response.status_code == 202
    assert response.json()["alerts_created"] == 0
    assert await sm.alerts(baseline_only) == []


async def test_a_paper_nudged_twice_in_one_request_is_evaluated_once(client, sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)

    response = await nudge(client, [paper, paper])

    assert response.json()["alerts_created"] == 1


async def test_an_empty_nudge_is_accepted(client):
    response = await nudge(client, [])

    assert response.status_code == 202
    assert response.json() == {"alerts_created": 0, "skipped_paper_ids": [], "failed_paper_ids": []}


# --- papers Storage Management doesn't know ---


async def test_a_paper_storage_management_does_not_know_is_skipped(client, sm, caplog):
    caplog.set_level(logging.INFO)
    unknown = uuid4()

    response = await nudge(client, [unknown])

    assert response.status_code == 202
    assert response.json()["skipped_paper_ids"] == [str(unknown)]
    assert f"skipping paper {unknown}" in caplog.text


async def test_a_404_that_is_not_no_such_paper_is_a_failure(client, sm):
    # e.g. the real Storage Management doesn't have the history endpoint yet
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    sm.transport.faults["/background-info/history"] = 404

    response = await nudge(client, [paper])

    assert response.status_code == 503
    assert response.json()["failed_paper_ids"] == [str(paper)]


async def test_a_paper_deleted_before_its_alert_is_stored_is_skipped(client, sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    sm.transport.faults["/alerts"] = httpx.Response(404, json={"detail": f"No paper {paper}"})

    response = await nudge(client, [paper])

    assert response.status_code == 202
    assert response.json()["skipped_paper_ids"] == [str(paper)]


# --- failures give 503, and a retry recovers ---


@pytest.mark.parametrize(
    ("suffix", "fault", "cause"),
    [
        ("/background-info/history", 500, "Storage Management answered 500"),
        ("/background-info/history", 401, "Storage Management answered 401"),
        ("/background-info/history", 403, "Storage Management answered 403"),
        ("/background-info/history", httpx.ReadTimeout("slow"), "Storage Management timed out"),
        ("/background-info/history", httpx.ConnectError("refused"), "could not reach Storage Management"),
        ("/alerts", 400, "Storage Management answered 400"),
        ("/alerts", 500, "Storage Management answered 500"),
    ],
)
async def test_a_storage_management_failure_gives_503_and_a_retry_stores_the_alerts(
    client, sm, caplog, suffix, fault, cause
):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    sm.transport.faults[suffix] = fault

    failed = await nudge(client, [paper])

    assert failed.status_code == 503
    assert failed.json()["failed_paper_ids"] == [str(paper)]
    assert f"evaluating paper {paper} failed: {cause}" in caplog.text
    assert await sm.alerts(paper) == []

    sm.transport.faults.clear()
    retried = await nudge(client, [paper])

    assert retried.status_code == 202
    assert [alert["change_type"] for alert in await sm.alerts(paper)] == ["retraction"]


async def test_an_unreadable_snapshot_gives_503_not_an_unhandled_error(client, sm, caplog):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, source_status="garbage")

    response = await nudge(client, [paper])

    assert response.status_code == 503
    assert f"evaluating paper {paper} failed: unreadable response from Storage Management" in caplog.text


async def test_an_unexpected_error_gives_503_with_the_traceback_logged(client, sm, caplog, monkeypatch):
    def broken(previous, current):
        raise KeyError("bug")

    monkeypatch.setattr("research_evaluation.evaluate.find_changes", broken)
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2)

    response = await nudge(client, [paper])

    assert response.status_code == 503
    assert f"evaluating paper {paper} failed unexpectedly" in caplog.text
    assert "KeyError: 'bug'" in caplog.text


async def test_one_failing_paper_does_not_stop_the_others(client, sm):
    good = await sm.add_paper()
    await sm.add_snapshot(good, 1)
    await sm.add_snapshot(good, 2, is_retracted=True)
    bad = await sm.add_paper()
    await sm.add_snapshot(bad, 1)
    await sm.add_snapshot(bad, 2, source_status="garbage")

    response = await nudge(client, [bad, good])

    assert response.status_code == 503
    assert response.json()["failed_paper_ids"] == [str(bad)]
    assert response.json()["alerts_created"] == 1
    assert [alert["change_type"] for alert in await sm.alerts(good)] == ["retraction"]


# --- only changes not stored yet are evaluated ---


@pytest.fixture
def assessed(monkeypatch):
    """Records the change key of every change stage 2 assesses."""
    keys = []

    def spy(change):
        keys.append(change.change_key)
        return real_assess(change)

    monkeypatch.setattr("research_evaluation.evaluate.assess", spy)
    return keys


@pytest.fixture
def investigated(monkeypatch):
    """Records the change key of every change stage 3 investigates."""
    keys = []
    placeholder = llm.investigate

    def spy(change, context, assessment):
        keys.append(change.change_key)
        return placeholder(change, context, assessment)

    monkeypatch.setattr("research_evaluation.evaluate.llm.investigate", spy)
    return keys


async def test_a_second_nudge_evaluates_and_stores_nothing_already_stored(client, sm, assessed, investigated):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True, crossref_updates=[notice("10.1/w", "withdrawal")])
    await nudge(client, [paper])
    assessed.clear()
    investigated.clear()
    stores_before = sm.calls("POST", "/alerts")

    second = await nudge(client, [paper])

    assert second.status_code == 202
    assert second.json()["alerts_created"] == 0
    assert assessed == []
    assert investigated == []
    assert sm.calls("POST", "/alerts") == stores_before
    assert len(await sm.alerts(paper)) == 2


async def test_only_a_new_change_among_stored_ones_is_evaluated_and_stored(client, sm, assessed):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    await nudge(client, [paper])
    await sm.add_snapshot(paper, 3, is_retracted=True, crossref_updates=[notice("10.1/c", "correction")])
    assessed.clear()
    stores_before = sm.calls("POST", "/alerts")

    response = await nudge(client, [paper])

    assert response.json()["alerts_created"] == 1
    assert assessed == ["correction:10.1/c"]
    assert sm.calls("POST", "/alerts") == stores_before + 1
    assert sorted(alert["change_key"] for alert in await sm.alerts(paper)) == ["correction:10.1/c", "retraction"]


async def test_stage_3_is_not_called_again_for_a_stored_other_change(client, sm, investigated):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, crossref_updates=[notice("10.1/w", "withdrawal")])

    await nudge(client, [paper])
    await nudge(client, [paper])

    assert investigated == ["other:withdrawal:10.1/w"]


async def test_the_same_change_in_two_pairs_of_one_history_is_evaluated_once(client, sm, assessed):
    # the OpenAlex flag on day 2, then the Crossref notice on day 3: both are the key `retraction`
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    await sm.add_snapshot(paper, 3, is_retracted=True, crossref_updates=[notice("10.1/r", "retraction")])

    response = await nudge(client, [paper])

    assert response.json()["alerts_created"] == 1
    assert assessed == ["retraction"]
    assert sm.calls("POST", "/alerts") == 1
    [alert] = await sm.alerts(paper)
    assert alert["detected_at"] == day(2)


async def test_the_stored_keys_are_read_once_per_paper(client, sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    await sm.add_snapshot(paper, 3, is_retracted=True, in_doaj=False)

    await nudge(client, [paper])

    assert sm.calls("GET", "/alerts/change-keys") == 1


async def test_the_stored_keys_are_not_read_when_nothing_changed(client, sm):
    baseline_only = await sm.add_paper()
    await sm.add_snapshot(baseline_only, 1)
    unchanged = await sm.add_paper()
    await sm.add_snapshot(unchanged, 1)
    await sm.add_snapshot(unchanged, 2)

    response = await nudge(client, [baseline_only, unchanged])

    assert response.status_code == 202
    assert sm.calls("GET", "/alerts/change-keys") == 0


@pytest.mark.parametrize(
    ("fault", "cause"),
    [
        (500, "Storage Management answered 500"),
        (401, "Storage Management answered 401"),
        (httpx.ReadTimeout("slow"), "Storage Management timed out"),
        (httpx.Response(200, json={"keys": []}), "unreadable response from Storage Management"),
    ],
)
async def test_a_failed_key_lookup_gives_503_and_a_retry_stores_the_alerts(client, sm, caplog, fault, cause):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    sm.transport.faults["/alerts/change-keys"] = fault

    failed = await nudge(client, [paper])

    assert failed.status_code == 503
    assert failed.json()["failed_paper_ids"] == [str(paper)]
    assert f"evaluating paper {paper} failed: {cause}" in caplog.text
    assert await sm.alerts(paper) == []

    sm.transport.faults.clear()
    retried = await nudge(client, [paper])

    assert retried.status_code == 202
    assert [alert["change_type"] for alert in await sm.alerts(paper)] == ["retraction"]


async def test_a_paper_deleted_before_its_keys_are_read_is_skipped(client, sm):
    paper = await sm.add_paper()
    await sm.add_snapshot(paper, 1)
    await sm.add_snapshot(paper, 2, is_retracted=True)
    sm.transport.faults["/alerts/change-keys"] = httpx.Response(404, json={"detail": f"No paper {paper}"})

    response = await nudge(client, [paper])

    assert response.status_code == 202
    assert response.json()["skipped_paper_ids"] == [str(paper)]
    assert sm.calls("POST", "/alerts") == 0


async def test_the_stub_lists_change_keys_like_storage_management(sm):
    paper = await sm.add_paper()
    unknown = uuid4()
    for key in ["retraction", "correction:10.1/c"]:
        alert = {"change_type": key.split(":")[0], "change_key": key, "severity": "high", "description": "d",
                 "recommendation": "r", "detected_at": day(2), "snapshot_id": 2, "previous_snapshot_id": 1}
        assert (await sm.client.post(f"/internal/papers/{paper}/alerts", json=alert)).status_code == 201

    listed = await sm.client.get(f"/internal/papers/{paper}/alerts/change-keys")
    missing = await sm.client.get(f"/internal/papers/{unknown}/alerts/change-keys")

    assert listed.json() == {"change_keys": ["correction:10.1/c", "retraction"]}
    assert (missing.status_code, missing.json()["detail"]) == (404, f"No paper {unknown}")
