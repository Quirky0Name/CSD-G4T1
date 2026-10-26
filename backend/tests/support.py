"""Constants and helpers shared by the test modules."""

import base64
import json
import time
from pathlib import Path
from uuid import UUID, uuid4

import jwt

FIXTURES = Path(__file__).parent / "fixtures"

# a throwaway 32-byte secret, base64 like the real JWT_SECRET
TEST_JWT_SECRET = base64.b64encode(b"cg42-test-secret-32-bytes-long!!").decode()
TEST_JWT_KEY = base64.b64decode(TEST_JWT_SECRET)

# the user who owns the papers the Updating tests seed, and who triggers its manual polls
TEST_USER = uuid4()


def user_token(user: UUID = TEST_USER) -> str:
    """A token as User Management issues it: `sub` is the user's id, no `role`."""
    now = int(time.time())
    return jwt.encode({"sub": str(user), "iat": now, "exp": now + 3600}, TEST_JWT_KEY, algorithm="HS256")


def load_fixture(source: str, name: str) -> dict:
    return json.loads((FIXTURES / source / f"{name}.json").read_text(encoding="utf-8"))


def load_xml_fixture(source: str, name: str) -> bytes:
    """Raw bytes, as the API sent them (the XML declares its own encoding)."""
    return (FIXTURES / source / f"{name}.xml").read_bytes()
