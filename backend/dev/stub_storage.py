"""In-memory stand-in for Storage Management, for developing and
demoing Research Evaluation / Updating before the real service exists.

Round 1 stub: not wired up yet. Once built, this will implement only the
contract endpoints Storage Management owes Research Evaluation/Updating
(see ../../CONTRACTS.md):

  GET  /internal/papers
  POST /papers/{id}/background-info
  GET  /papers/{id}/background-info/history

It is a development aid, not a substitute for the real Storage
Management service, and should never be pointed at from a deployed
environment.
"""

from fastapi import FastAPI

app = FastAPI(title="Storage Management (stub)")
