"""Updating service (Section 4).

Round 1 stub: boots FastAPI with no routes so the container/Swagger UI
can be verified end to end before any DB/scheduler code is written.
Routers and the scheduled poller get wired in here as they land — see
the plan's Build order.
"""

from fastapi import FastAPI

app = FastAPI(title="Updating")
