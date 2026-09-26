"""Constants and helpers shared by the test modules."""

import base64
import json
from pathlib import Path

FIXTURES = Path(__file__).parent / "fixtures"

# a throwaway 32-byte secret, base64 like the real JWT_SECRET
TEST_JWT_SECRET = base64.b64encode(b"cg42-test-secret-32-bytes-long!!").decode()
TEST_JWT_KEY = base64.b64decode(TEST_JWT_SECRET)


def load_fixture(source: str, name: str) -> dict:
    return json.loads((FIXTURES / source / f"{name}.json").read_text(encoding="utf-8"))
