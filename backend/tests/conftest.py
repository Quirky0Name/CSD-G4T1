import pytest

SETTINGS_ENV_VARS = (
    "JWT_SECRET",
    "DATABASE_URL",
    "SM_BASE_URL",
    "OPENALEX_API_KEY",
    "CROSSREF_MAILTO",
    "POLL_INTERVAL_HOURS",
)


@pytest.fixture
def clean_settings_env(monkeypatch):
    """Keeps the developer's real environment out of settings tests."""
    for var in SETTINGS_ENV_VARS:
        monkeypatch.delenv(var, raising=False)
