import base64

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from support import TEST_JWT_KEY, TEST_JWT_SECRET

from research_evaluation.config import ResearchEvaluationSettings
from research_evaluation.main import create_app

pytestmark = pytest.mark.usefixtures("clean_settings_env")


def test_defaults_and_decoded_secret():
    settings = ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET)

    assert settings.jwt_secret.get_secret_value() == TEST_JWT_KEY
    assert settings.sm_base_url == "http://localhost:8081"
    assert settings.evaluation_snapshot_window == 5


def test_crossref_mailto_defaults_to_empty_and_comes_from_the_environment(monkeypatch):
    assert ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET).crossref_mailto == ""

    monkeypatch.setenv("CROSSREF_MAILTO", "team@example.org")

    settings = ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET)
    assert settings.crossref_mailto == "team@example.org"


def test_the_investigation_pdf_timeout_defaults_to_120_and_comes_from_the_environment(monkeypatch):
    default = ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET)
    assert default.investigation_pdf_timeout_seconds == 120

    monkeypatch.setenv("INVESTIGATION_PDF_TIMEOUT_SECONDS", "45.5")

    settings = ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET)
    assert settings.investigation_pdf_timeout_seconds == 45.5


@pytest.mark.parametrize("timeout", ["0", "-1", "soon"])
def test_an_investigation_pdf_timeout_of_zero_or_less_or_not_a_number_fails(monkeypatch, timeout):
    monkeypatch.setenv("INVESTIGATION_PDF_TIMEOUT_SECONDS", timeout)

    with pytest.raises(ValidationError, match="investigation_pdf_timeout_seconds"):
        ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET)


def test_the_snapshot_window_comes_from_the_environment(monkeypatch):
    monkeypatch.setenv("EVALUATION_SNAPSHOT_WINDOW", "30")

    settings = ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET)

    assert settings.evaluation_snapshot_window == 30


@pytest.mark.parametrize("window", ["1", "0", "-3", "five"])
def test_a_snapshot_window_below_one_pair_or_not_a_number_fails(monkeypatch, window):
    monkeypatch.setenv("EVALUATION_SNAPSHOT_WINDOW", window)

    with pytest.raises(ValidationError, match="evaluation_snapshot_window"):
        ResearchEvaluationSettings(_env_file=None, jwt_secret=TEST_JWT_SECRET)


def test_the_app_refuses_to_start_with_a_window_of_one(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("EVALUATION_SNAPSHOT_WINDOW", "1")

    with pytest.raises(ValidationError, match="evaluation_snapshot_window"), TestClient(create_app()):
        pass


def test_startup_stores_the_snapshot_window(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("EVALUATION_SNAPSHOT_WINDOW", "7")
    app = create_app()

    with TestClient(app):
        assert app.state.snapshot_window == 7


def test_startup_makes_investigation_its_own_client_without_auth(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("CROSSREF_MAILTO", "team@example.org")
    monkeypatch.setenv("INVESTIGATION_PDF_TIMEOUT_SECONDS", "90")
    app = create_app()

    with TestClient(app):
        investigation = app.state.investigation
        # Crossref and Europe PMC only: never the service token, never Storage Management's URL
        assert investigation.external is not app.state.sm
        assert investigation.external.auth is None
        assert str(investigation.external.base_url) == ""
        assert investigation.crossref_mailto == "team@example.org"
        assert investigation.pdf_timeout == 90


def test_settings_come_from_the_environment(monkeypatch):
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("SM_BASE_URL", "http://host.docker.internal:8081")

    settings = ResearchEvaluationSettings(_env_file=None)

    assert settings.jwt_secret.get_secret_value() == TEST_JWT_KEY
    assert settings.sm_base_url == "http://host.docker.internal:8081"


def test_a_short_secret_fails_with_a_hint_and_without_echoing_it():
    short = base64.b64encode(b"tooshort").decode()

    with pytest.raises(ValidationError) as excinfo:
        ResearchEvaluationSettings(_env_file=None, jwt_secret=short)

    assert "openssl rand -base64 32" in str(excinfo.value)
    assert short not in str(excinfo.value)


def test_a_missing_jwt_secret_fails():
    with pytest.raises(ValidationError) as excinfo:
        ResearchEvaluationSettings(_env_file=None)

    assert "jwt_secret" in str(excinfo.value)


def test_the_app_refuses_to_start_without_jwt_secret(tmp_path, monkeypatch):
    # no .env in the working directory, and no JWT_SECRET in the environment
    monkeypatch.chdir(tmp_path)

    with pytest.raises(ValidationError, match="jwt_secret"), TestClient(create_app()):
        pass
