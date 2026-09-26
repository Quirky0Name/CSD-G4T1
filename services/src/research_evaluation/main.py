"""Research Evaluation service (Section 3).

Round 1 stub: boots FastAPI with no routes so the container/Swagger UI
can be verified end to end before any external-API code is written.
Routers get mounted here as they land — see the plan's Build order.
"""

from fastapi import FastAPI

app = FastAPI(title="Research Evaluation")
