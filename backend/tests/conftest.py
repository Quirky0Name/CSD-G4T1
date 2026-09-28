import pytest

SETTINGS_ENV_VARS = (
    "JWT_SECRET",
    "DATABASE_URL",
    "SM_BASE_URL",
    "RE_BASE_URL",
    "OPENALEX_API_KEY",
    "CROSSREF_MAILTO",
    "POLL_INTERVAL_HOURS",
    "EVALUATION_SNAPSHOT_WINDOW",
    "INVESTIGATION_PDF_TIMEOUT_SECONDS",
    "GEMINI_API_KEY",
    "GEMINI_MODEL",
    "IMPACT_LLM_TIMEOUT_SECONDS",
    "TELEGRAM_BOT_TOKEN",
    "NOTIFY_TELEGRAM_CHAT_ID",
)


@pytest.fixture
def clean_settings_env(monkeypatch):
    """Keeps the developer's real environment out of settings tests."""
    for var in SETTINGS_ENV_VARS:
        monkeypatch.delenv(var, raising=False)
