"""Gemini client for impact: one async call that takes text and PDFs and returns the model's
answer parsed into a pydantic model (Gemini's structured output), so callers never parse free
text. The rest of impact talks to the `Llm` protocol, so tests can put a fake in its place."""

from dataclasses import dataclass
from typing import Protocol

from google import genai
from google.genai import types
from pydantic import BaseModel

from research_evaluation.config import ResearchEvaluationSettings

PDF_MIME_TYPE = "application/pdf"


class GeminiNotConfiguredError(RuntimeError):
    """GEMINI_API_KEY is missing from the environment / .env."""


@dataclass(frozen=True)
class Pdf:
    """A PDF part of a prompt, sent to the model as the file itself."""

    data: bytes


type Part = str | Pdf


@dataclass(frozen=True)
class Answer[T: BaseModel]:
    value: T
    # the model that answered, as Gemini reports it (an alias such as gemini-flash-latest moves)
    model_version: str | None


class Llm(Protocol):
    model: str

    async def generate[T: BaseModel](
        self, *, system: str, parts: list[Part], schema: type[T]
    ) -> Answer[T]: ...


def gemini_configured(settings: ResearchEvaluationSettings) -> bool:
    return settings.gemini_api_key is not None and bool(settings.gemini_api_key.get_secret_value())


def make_client(settings: ResearchEvaluationSettings) -> genai.Client:
    """A Gemini client whose calls give up after IMPACT_LLM_TIMEOUT_SECONDS."""
    if not gemini_configured(settings):
        raise GeminiNotConfiguredError("GEMINI_API_KEY is not set")
    return genai.Client(
        api_key=settings.gemini_api_key.get_secret_value(),
        # the SDK takes milliseconds
        http_options=types.HttpOptions(timeout=int(settings.impact_llm_timeout_seconds * 1000)),
    )


def to_parts(parts: list[Part]) -> list[types.Part]:
    return [
        types.Part.from_bytes(data=part.data, mime_type=PDF_MIME_TYPE)
        if isinstance(part, Pdf)
        else types.Part.from_text(text=part)
        for part in parts
    ]


async def generate[T: BaseModel](
    client: genai.Client,
    model: str,
    *,
    system: str,
    parts: list[Part],
    schema: type[T],
) -> Answer[T]:
    """Ask `model` for an answer matching `schema`; raises pydantic.ValidationError if the
    model's JSON doesn't fit it, and ValueError if it gave no text at all."""
    response = await client.aio.models.generate_content(
        model=model,
        contents=[types.Content(role="user", parts=to_parts(parts))],
        config=types.GenerateContentConfig(
            system_instruction=system,
            response_mime_type="application/json",
            response_schema=schema,
            # impact gives the model no tools
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        ),
    )
    if not response.text:
        raise ValueError("the model returned no text")
    return Answer(schema.model_validate_json(response.text), response.model_version)


@dataclass(frozen=True)
class GeminiLlm:
    """The Llm impact runs with: a Gemini client and the model name (GEMINI_MODEL)."""

    client: genai.Client
    model: str

    async def generate[T: BaseModel](
        self, *, system: str, parts: list[Part], schema: type[T]
    ) -> Answer[T]:
        return await generate(self.client, self.model, system=system, parts=parts, schema=schema)
