"""Service tokens for calling Storage Management's `/internal/**` endpoints
(see docs/CONTRACTS.md, "Auth"). Shared by Updating and Research Evaluation."""

import base64
import binascii
import time

import jwt

MIN_KEY_BYTES = 32
TOKEN_TTL_SECONDS = 5 * 60


def decode_jwt_secret(b64: str) -> bytes:
    """Decode `JWT_SECRET` the way Storage Management does: base64, at least 32 bytes."""
    try:
        key = base64.b64decode(b64, validate=True)
    except binascii.Error as exc:
        raise ValueError(
            "JWT_SECRET must be base64 (generate one with `openssl rand -base64 32`)"
        ) from exc
    if len(key) < MIN_KEY_BYTES:
        raise ValueError(
            f"JWT_SECRET decodes to {len(key)} bytes, need at least {MIN_KEY_BYTES} "
            "(generate one with `openssl rand -base64 32`)"
        )
    return key


def mint_service_token(key: bytes, subject: str) -> str:
    now = int(time.time())
    claims = {"sub": subject, "role": "service", "iat": now, "exp": now + TOKEN_TTL_SECONDS}
    return jwt.encode(claims, key, algorithm="HS256")
