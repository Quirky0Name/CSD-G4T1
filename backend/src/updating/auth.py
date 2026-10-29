"""Checking the user's JWT on `POST /run-poll` (docs/CONTRACTS.md, "Auth").

The token is the one User Management issues: HS256, `sub` = the user's id, no `role`.
401 for a missing, bad or expired token; 403 for a service token."""

from typing import Annotated
from uuid import UUID

import jwt
from fastapi import Depends, Header, HTTPException, Request


def jwt_key(request: Request) -> bytes:
    """The decoded JWT_SECRET, set on the app at startup."""
    return request.app.state.jwt_key


def require_user(
    key: Annotated[bytes, Depends(jwt_key)],
    authorization: Annotated[str | None, Header()] = None,
) -> UUID:
    """Returns the calling user's id."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing bearer token")
    try:
        claims = jwt.decode(
            authorization.removeprefix("Bearer "), key, algorithms=["HS256"], options={"require": ["exp", "sub"]}
        )
    except jwt.PyJWTError:
        raise HTTPException(401, "Invalid token") from None
    if claims.get("role") == "service":
        raise HTTPException(403, "User token required")
    try:
        return UUID(str(claims["sub"]))
    except ValueError:
        raise HTTPException(401, "Invalid token") from None
