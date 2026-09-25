import base64

import pytest
from pydantic import ValidationError
from support import TEST_JWT_KEY, TEST_JWT_SECRET

from updating.config import UpdatingSettings

pytestmark = pytest.mark.usefixtures("clean_settings_env")


def make(**values):
    values.setdefault("jwt_secret", TEST_JWT_SECRET)
    values.setdefault("database_url", "sqlite+aiosqlite://")
    return UpdatingSettings(_env_file=None, **values)


def test_defaults_and_decoded_secret():
    settings = make()

    assert settings.jwt_secret.get_secret_value() == TEST_JWT_KEY
    assert settings.sm_base_url == "http://localhost:8081"
    assert settings.crossref_mailto == ""
    assert settings.openalex_api_key.get_secret_value() == ""
    assert settings.poll_interval_hours == 24


def test_short_secret_fails_with_hint_and_without_echoing_the_secret():
    short = base64.b64encode(b"tooshort").decode()

    with pytest.raises(ValidationError) as excinfo:
        make(jwt_secret=short)

    assert "openssl rand -base64 32" in str(excinfo.value)
    assert short not in str(excinfo.value)


def test_missing_required_settings_fail():
    with pytest.raises(ValidationError) as excinfo:
        UpdatingSettings(_env_file=None)

    missing = {error["loc"][0] for error in excinfo.value.errors()}
    assert missing == {"jwt_secret", "database_url"}


def test_poll_interval_must_be_positive():
    with pytest.raises(ValidationError):
        make(poll_interval_hours=0)


def test_reads_env_vars(monkeypatch):
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("DATABASE_URL", "sqlite+aiosqlite:///x.db")
    monkeypatch.setenv("POLL_INTERVAL_HOURS", "0.5")

    settings = UpdatingSettings(_env_file=None)

    assert settings.database_url == "sqlite+aiosqlite:///x.db"
    assert settings.poll_interval_hours == 0.5
