"""Updating's settings, loaded from env vars (see docs/SETUP.md).

Kept out of `common/config.py` so Research Evaluation doesn't fail at startup on
variables only Updating needs. A missing or invalid required variable fails when
the app starts, not mid-poll."""

from pydantic import Field, SecretStr, field_validator
from pydantic.types import SecretBytes
from pydantic_settings import BaseSettings, SettingsConfigDict

from common.service_token import decode_jwt_secret

DEFAULT_SM_BASE_URL = "http://localhost:8081"
DEFAULT_POLL_INTERVAL_HOURS = 24


class UpdatingSettings(BaseSettings):
    # a bad JWT_SECRET must not be echoed back in the validation error
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", hide_input_in_errors=True)

    # decoded once here (base64, 32+ bytes), so nothing downstream handles the raw string
    jwt_secret: SecretBytes
    database_url: str
    sm_base_url: str = DEFAULT_SM_BASE_URL
    openalex_api_key: SecretStr = SecretStr("")
    crossref_mailto: str = ""
    poll_interval_hours: float = Field(default=DEFAULT_POLL_INTERVAL_HOURS, gt=0)

    @field_validator("jwt_secret", mode="before")
    @classmethod
    def _decode_jwt_secret(cls, value: str) -> bytes:
        return decode_jwt_secret(value)
