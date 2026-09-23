"""Typed settings shared by both apps, loaded from env vars.

Deliberately minimal for now (round 1: scaffolding only, no external
calls). Fields for the third-party API keys, JWT secret, DB URL etc. get
added here as each build-order step that needs them lands — see
../../ARCHITECTURE.md and ../../CONTRACTS.md. A required field left unset
should fail at startup, not mid-request.
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
