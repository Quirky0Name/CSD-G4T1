"""The mock harness (CG-53): each nudge rule proven end to end through `POST /run-poll`.

Every test runs the real Updating app (`/run-poll`, poll job, a fresh SQLite database) over
the ASGI transport against the in-process stub Storage Management and Research Evaluation, so
it never touches live data and can be re-run from scratch. Crossref and OpenAlex answer from
the recorded fixtures via respx; `pytest -m live` runs the same scenarios against the live APIs.

Nudge reasons are only logged, so the log line is what proves the intended rule fired."""

import logging
from enum import StrEnum
from uuid import UUID

import pytest
from updating_support import trigger_poll

from dev.scenarios import Scenario

RULE_SCENARIOS = [scenario for scenario in Scenario if scenario is not Scenario.NO_DOI]

# The reasons `nudge_reasons` gives when the recorded APIs are polled against each seeded "before".
EXPECTED_REASONS = {
    Scenario.OPENALEX_RETRACTION: ["is_retracted false -> true"],
    Scenario.CROSSREF_RETRACTION: [
        "new retraction notice 10.1016/j.ijantimicag.2024.107416",
        "new retraction notice 10.1016/j.ijantimicag.2020.105949",
    ],
    Scenario.CORRECTIONS: [
        "new expression_of_concern notice 10.1016/s0140-6736(20)31290-3",
        "new correction notice 10.1016/s0140-6736(20)31249-6",
        "new erratum notice 10.1016/s0140-6736(20)31324-6",
        "new erratum notice 10.1016/s0140-6736(20)31528-2",
        "new erratum notice 10.1016/s0140-6736(20)31249-6",
    ],
    Scenario.DOAJ_DELISTING: ["in_doaj true -> false"],
    Scenario.NO_CHANGE: [],
}


class Apis(StrEnum):
    RECORDED = "recorded"
    LIVE = "live"


@pytest.fixture(params=[Apis.RECORDED, pytest.param(Apis.LIVE, marks=pytest.mark.live)])
def apis(request):
    if request.param is Apis.RECORDED:
        sources = request.getfixturevalue("sources")
        for name in ("ijaa", "lancet", "jbc"):
            sources.serve(name)
    return request.param


def logged_reasons(caplog, paper: UUID) -> list[str]:
    """The reasons Updating logged for a paper's change (none when it logged no change)."""
    for record in caplog.records:
        about_paper = record.name == "updating.poll" and record.args and record.args[0] == paper
        if about_paper and "changed since" in record.msg:
            return record.args[-1].split("; ")
    return []


@pytest.mark.parametrize("scenario", RULE_SCENARIOS)
async def test_the_scenarios_rule_fires_and_a_second_trigger_does_not_nudge_again(
    apis, updating_client, sm, re, caplog, scenario
):
    caplog.set_level(logging.INFO, logger="updating.poll")
    paper = await sm.seed_scenario(scenario)
    expected = EXPECTED_REASONS[scenario]

    summary = await trigger_poll(updating_client, paper)

    assert [s["paper_id"] for s in summary["stored"]] == [str(paper)]
    assert summary["source_errors"] == [] and summary["store_errors"] == []
    reasons = logged_reasons(caplog, paper)
    if apis is Apis.RECORDED:
        assert reasons == expected
        assert summary["nudged"] == ([str(paper)] if expected else [])
        assert await re.received() == ([[paper]] if expected else [])
    elif expected:  # live data may have drifted (new notices), so the intended reasons must be present
        assert set(expected) <= set(reasons)
        assert str(paper) in summary["nudged"]
    # live NO_CHANGE only proves that a poll fetches and stores against the live APIs

    caplog.clear()
    second = await trigger_poll(updating_client, paper)

    assert [s["paper_id"] for s in second["stored"]] == [str(paper)]
    assert second["nudged"] == [] and logged_reasons(caplog, paper) == []


async def test_a_paper_with_no_doi_is_skipped_without_calling_any_source(updating_client, sm, re, sources):
    paper = await sm.seed_scenario(Scenario.NO_DOI)  # `sources` has no routes, so any fetch would fail

    summary = await trigger_poll(updating_client, paper)

    assert summary["skipped_no_doi"] == [str(paper)]
    assert summary["stored"] == [] and summary["nudged"] == []
    assert await re.received() == []
