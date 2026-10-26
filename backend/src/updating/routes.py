"""`POST /run-poll` (docs/CONTRACTS.md): run a poll now, for the demo and for testing.

Takes the user's JWT and polls only that user's papers; the scheduled poll covers everyone's."""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request, status

from updating.auth import require_user
from updating.poll import PollAlreadyRunning, PollFailed, PollSummary, UnknownPaper, run_manual_poll
from updating.storage import PaperId

router = APIRouter()


@router.post("/run-poll")
async def run_poll_now(
    request: Request, user_id: Annotated[UUID, Depends(require_user)], paper_id: UUID | None = None
) -> PollSummary:
    only_paper = None if paper_id is None else PaperId(paper_id)
    try:
        return await run_manual_poll(request.app.state.poll_deps, only_paper, user_id)
    except PollAlreadyRunning:
        raise HTTPException(status.HTTP_409_CONFLICT, "A poll is already running") from None
    except UnknownPaper as exc:  # before PollFailed, its base class
        raise HTTPException(status.HTTP_404_NOT_FOUND, str(exc)) from None
    except PollFailed as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, str(exc)) from None
