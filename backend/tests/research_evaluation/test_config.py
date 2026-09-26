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
