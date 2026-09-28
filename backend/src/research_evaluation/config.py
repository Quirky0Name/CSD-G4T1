"""Research Evaluation's settings, loaded from env vars (see docs/SETUP.md).

RE's individual configs independent of common/config.py whihc is for all backend services
missing/invalid variables -> start up fails
"""

from typing import Annotated

from pydantic import Field, SecretBytes, SecretStr, field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

from common.service_token import decode_jwt_secret

DEFAULT_SM_BASE_URL = "http://localhost:8081"
DEFAULT_SNAPSHOT_WINDOW = 5
DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
# not gemini-3.8-flash (what gemini-flash-latest resolves to) nor gemini-2.5-flash (gone for new
# keys): docs/EVAL-GEM-FAILSAFE.md
DEFAULT_GEMINI_FALLBACK_MODELS = ("gemini-flash-lite-latest",)


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
    # sent to Crossref as `mailto` (its polite pool) by investigation; the same variable Updating reads
    crossref_mailto: str = ""
    # how long investigation waits for Storage Management to store a document, which includes
    # downloading its PDF (over 30 s per link); it runs after the nudge's reply
    investigation_pdf_timeout_seconds: float = Field(default=120, gt=0)
    # impact's LLM (aistudio.google.com); optional so the service still starts without it,
    # impact.llm refuses to build a client when it's missing
    gemini_api_key: SecretStr | None = None
    gemini_model: str = DEFAULT_GEMINI_MODEL
    # tried in order after gemini_model when a call to it fails (docs/EVAL-GEM-FAILSAFE.md);
    # comma separated in the env, empty for none
    gemini_fallback_models: Annotated[list[str], NoDecode] = list(DEFAULT_GEMINI_FALLBACK_MODELS)
    # how long impact waits for one Gemini call; it runs after investigation, in the background
    impact_llm_timeout_seconds: float = Field(default=120, gt=0)
    # the demo's notification when a report is assessed (docs/EVALUATION-NOTIF.md): a Telegram
    # bot and the one hard-coded chat it writes to; notifications are off unless both are set
    telegram_bot_token: SecretStr | None = None
    notify_telegram_chat_id: str = ""

    @field_validator("jwt_secret", mode="before")
    @classmethod
    def _decode_jwt_secret(cls, value: str) -> bytes:
        return decode_jwt_secret(value)

    @field_validator("gemini_fallback_models", mode="before")
    @classmethod
    def _split_fallback_models(cls, value: str | list[str]) -> list[str]:
        names = value.split(",") if isinstance(value, str) else value
        return [name.strip() for name in names if name.strip()]
