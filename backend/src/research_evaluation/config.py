"""Research Evaluation's settings, loaded from env vars (see docs/SETUP.md).

RE's individual configs independent of common/config.py whihc is for all backend services
missing/invalid variables -> start up fails
"""

from pydantic import Field, SecretBytes, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from common.service_token import decode_jwt_secret

DEFAULT_SM_BASE_URL = "http://localhost:8081"
DEFAULT_SNAPSHOT_WINDOW = 5


class ResearchEvaluationSettings(BaseSettings):
    # exlcude JWT_SECRET from error msg
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", hide_input_in_errors=True)

    # jwt_secret and sm_base_url inhertied from BaseSettings (incl. constructor)
    # wrapper to hide value unless explicitly requested
    jwt_secret: SecretBytes
    sm_base_url: str = DEFAULT_SM_BASE_URL
    # how many of the newest snapshots each evaluation compares (N snapshots = N - 1 pairs);
    # a change is missed if its nudge keeps failing for more than N - 2 polls in a row
    evaluation_snapshot_window: int = Field(default=DEFAULT_SNAPSHOT_WINDOW, ge=2)

    @field_validator("jwt_secret", mode="before")
    @classmethod
    def _decode_jwt_secret(cls, value: str) -> bytes:
        return decode_jwt_secret(value)
