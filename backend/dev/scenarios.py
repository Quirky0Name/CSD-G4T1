"""Synthetic "before" snapshots for the mock harness and the stub Storage Management's
`POST /dev/seed`.

Each scenario is the recorded snapshot of a real paper with only the field its nudge rule
watches changed, so polling the recorded (or live) data afterwards fires that one rule and no
other. The snapshot must have the full shape: Updating parses history rows into `Snapshot`,
where every field is required, and a partial row fails the history read, so the paper would
land in `store_errors` instead of nudging.

The recorded API responses are read from `tests/fixtures/` as plain data, and nothing here
imports from tests/."""

import json
from datetime import UTC, datetime
from enum import StrEnum, auto
from pathlib import Path
from typing import assert_never

from common.doi import Doi
from updating.snapshot import Snapshot, build_snapshot
from updating.sources import CrossrefWork, OpenAlexAuthor, OpenAlexWork, SourceStatus

FIXTURES = Path(__file__).parent.parent / "tests" / "fixtures"

DOIS = {
    "lancet": "10.1016/s0140-6736(20)31180-6",
    "ijaa": "10.1016/j.ijantimicag.2020.105949",
    "jbc": "10.1016/s0021-9258(19)52451-6",
    "arxiv": "10.48550/arxiv.1201.0490",  # a DataCite DOI: Crossref has never heard of it
}

CORRECTION_TYPES = frozenset({"correction", "erratum", "expression_of_concern"})


class Scenario(StrEnum):
    OPENALEX_RETRACTION = auto()  # IJAA: OpenAlex hasn't flagged the retraction yet
    CROSSREF_RETRACTION = auto()  # IJAA: Crossref lists no retraction notice yet
    CORRECTIONS = auto()  # Lancet: no correction, erratum or expression of concern yet
    DOAJ_DELISTING = auto()  # Lancet: its journal was still in DOAJ
    NO_CHANGE = auto()  # JBC: nothing has changed since
    NO_DOI = auto()  # a paper Updating can't poll


def load_fixture(source: str, name: str) -> dict:
    return json.loads((FIXTURES / source / f"{name}.json").read_text())


def author_batch(name: str) -> list[OpenAlexAuthor]:
    """A recorded `/authors` batch, in the order OpenAlex returned it."""
    rows = load_fixture("openalex_authors", name)["results"]
    return [OpenAlexAuthor.model_validate(row) for row in rows]


def fixture_snapshot(name: str, **changes) -> Snapshot:
    """What a poll builds from the recorded fixtures, with `changes` applied on top, for
    seeding a "before" state in Storage Management."""
    crossref = (
        SourceStatus.NOT_FOUND  # a DataCite DOI: Crossref has never heard of it
        if name == "arxiv"
        else CrossrefWork.model_validate(load_fixture("crossref", name)["message"])
    )
    snapshot = build_snapshot(
        Doi(DOIS[name]),
        datetime.now(UTC).replace(microsecond=0),
        crossref,
        OpenAlexWork.model_validate(load_fixture("openalex", name)),
        author_batch(name),
    )
    return snapshot.model_copy(update=changes)


def _without_updates(name: str, drop: frozenset[str] | set[str]) -> Snapshot:
    kept = [u for u in fixture_snapshot(name).crossref_updates or [] if u.type not in drop]
    return fixture_snapshot(name, crossref_updates=kept)


def before_snapshot(scenario: Scenario) -> Snapshot | None:
    """The snapshot Storage Management holds from an earlier poll; None for a paper with no DOI."""
    match scenario:
        case Scenario.OPENALEX_RETRACTION:
            return fixture_snapshot("ijaa", is_retracted=False)
        case Scenario.CROSSREF_RETRACTION:
            return _without_updates("ijaa", {"retraction"})
        case Scenario.CORRECTIONS:
            return _without_updates("lancet", CORRECTION_TYPES)
        case Scenario.DOAJ_DELISTING:
            return fixture_snapshot("lancet", in_doaj=True)
        case Scenario.NO_CHANGE:
            return fixture_snapshot("jbc")
        case Scenario.NO_DOI:
            return None
        case _:
            assert_never(scenario)
