"""Runs against the real APIs to catch drift in their response shapes: `pytest -m live`."""

from datetime import UTC, datetime

import httpx
import pytest
from pydantic import SecretStr
from updating_support import DOIS

from common.doi import Doi
from updating.snapshot import author_ids, build_snapshot
from updating.sources import fetch_crossref, fetch_openalex, fetch_openalex_authors

pytestmark = pytest.mark.live


async def test_the_retracted_lancet_paper_builds_a_full_snapshot():
    doi = Doi(DOIS["lancet"])
    async with httpx.AsyncClient(timeout=20) as http:
        crossref = await fetch_crossref(http, doi, mailto="")
        openalex = await fetch_openalex(http, doi, SecretStr(""))
        authors = await fetch_openalex_authors(http, doi, author_ids(openalex), SecretStr(""))

    snap = build_snapshot(doi, datetime.now(UTC), crossref, openalex, authors)

    assert snap.source_status.crossref == "ok" and snap.source_status.openalex == "ok"
    assert snap.source_status.openalex_authors == "ok"
    assert snap.authors[0].name == "Mandeep R. Mehra"
    assert len(snap.authors) == 4
    assert all(a.h_index is not None and a.works_count is not None for a in snap.authors)
    assert snap.is_retracted is True
    assert len(snap.crossref_updates) >= 7
    assert "retraction" in {u.type for u in snap.crossref_updates}
    assert snap.journal_source_type == "journal"
