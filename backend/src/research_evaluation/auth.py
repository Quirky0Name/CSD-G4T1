"""Checking the service token on Research Evaluation's endpoints (docs/CONTRACTS.md, "Auth").

`POST /evaluate/changes` is called by Updating with a service token it mints itself
(`sub=svc:updating`, `role=service`). Outcomes match Storage Management's internal
endpoints: 401 for a missing, bad or expired token, 403 for a valid user token."""

from typing import Annotated
from uuid import UUID

import jwt
from fastapi import Depends, Header, HTTPException, Request


def jwt_key(request: Request) -> bytes:
    """The decoded JWT_SECRET, set on the app at startup (tests override this)."""
    return request.app.state.jwt_key


def require_service_token(
    key: Annotated[bytes, Depends(jwt_key)],
    authorization: Annotated[str | None, Header()] = None,
) -> str:
    """Returns the calling service's subject, e.g. `svc:updating`."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing bearer token")
    try:
        claims = jwt.decode(
            authorization.removeprefix("Bearer "), key, algorithms=["HS256"], options={"require": ["exp", "sub"]}
        )
    except jwt.PyJWTError:
        raise HTTPException(401, "Invalid token") from None
    if claims.get("role") == "service":
        return str(claims["sub"])
    try:
        UUID(str(claims["sub"]))
    except ValueError:
        raise HTTPException(401, "Invalid token") from None  # not a user token either
    raise HTTPException(403, "Service token required")
