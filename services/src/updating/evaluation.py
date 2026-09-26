"""Client for Research Evaluation's `POST /evaluate/changes` (docs/CONTRACTS.md).

A nudge carries only paper ids. Only a 202 means it was accepted: any other status raises
`httpx.HTTPStatusError` and an unreachable service raises `httpx.HTTPError`, and the poll
job keeps the papers pending and re-sends them next poll."""

import httpx

from updating.storage import PaperId


async def send_changes(http: httpx.AsyncClient, paper_ids: list[PaperId]) -> None:
    response = await http.post("/evaluate/changes", json={"paper_ids": [str(i) for i in paper_ids]})
    if response.status_code != httpx.codes.ACCEPTED:
        raise httpx.HTTPStatusError(
            f"expected 202, got {response.status_code}", request=response.request, response=response
        )
