"""Impact's models, Claude and Gemini: one async call that takes text and PDFs and returns the
model's answer parsed into a pydantic model (structured output), so callers never parse free
text. The rest of impact talks to the `Llm` protocol, so tests can put a fake in its place."""

import base64
import logging
from dataclasses import dataclass
from typing import TYPE_CHECKING, Protocol

import httpx
from google import genai
from google.genai import errors as genai_errors
from google.genai import types
from pydantic import BaseModel, ValidationError

from research_evaluation.config import ResearchEvaluationSettings

if TYPE_CHECKING:
    from anthropic import AsyncAnthropic

log = logging.getLogger(__name__)

PDF_MIME_TYPE = "application/pdf"
# room for step 3's actions and claims; a non-streaming call this size stays under the SDK's timeout
CLAUDE_MAX_TOKENS = 16000


class GeminiNotConfiguredError(RuntimeError):
    """GEMINI_API_KEY is missing from the environment / .env."""


class NoText(ValueError):
    """The model's response had no text to parse."""


class Refused(ValueError):
    """Claude's safety classifiers declined the request (stop_reason "refusal")."""


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
    # the model name the answer was asked of (FallbackLlm may have moved past the first)
    model: str | None = None


class Llm(Protocol):
    @property
    def model(self) -> str: ...

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
        raise NoText("the model returned no text")
    return Answer(schema.model_validate_json(response.text), response.model_version, model)


@dataclass(frozen=True)
class GeminiLlm:
    """The Llm impact runs with: a Gemini client and the model name (GEMINI_MODEL)."""

    client: genai.Client
    model: str

    async def generate[T: BaseModel](
        self, *, system: str, parts: list[Part], schema: type[T]
    ) -> Answer[T]:
        return await generate(self.client, self.model, system=system, parts=parts, schema=schema)


def to_blocks(parts: list[Part]) -> list[dict]:
    return [
        {
            "type": "document",
            "source": {
                "type": "base64",
                "media_type": PDF_MIME_TYPE,
                "data": base64.standard_b64encode(part.data).decode(),
            },
        }
        if isinstance(part, Pdf)
        else {"type": "text", "text": part}
        for part in parts
    ]


@dataclass(frozen=True)
class ClaudeLlm:
    """The Llm impact tries first (CLAUDE_MODEL). Low effort: a researcher waits on all three
    steps, and the prompts spell out the reasoning they want."""

    client: "AsyncAnthropic"
    model: str

    async def generate[T: BaseModel](
        self, *, system: str, parts: list[Part], schema: type[T]
    ) -> Answer[T]:
        response = await self.client.messages.parse(
            model=self.model,
            max_tokens=CLAUDE_MAX_TOKENS,
            system=system,
            messages=[{"role": "user", "content": to_blocks(parts)}],
            output_format=schema,
            output_config={"effort": "low"},
        )
        if response.stop_reason == "refusal":
            raise Refused("the model refused")
        return Answer(response.parsed_output, response.model, self.model)


# a failure of one model's call, after which the next model is tried; anything else (a bug)
# propagates straight away
MODEL_FAILURES = (genai_errors.APIError, httpx.TransportError, ValidationError, ValueError)


@dataclass(frozen=True)
class ModelFailure:
    model: str
    cause: str


class AllModelsFailed(RuntimeError):
    """Every model in the list failed the same call; `failures` says how, in order."""

    def __init__(self, failures: list[ModelFailure]):
        super().__init__("every Gemini model failed: " + "; ".join(f"{f.model}: {f.cause}" for f in failures))
        self.failures = failures


@dataclass(frozen=True)
class FallbackLlm:
    """The Llm impact runs with (docs/EVALUATION.md, "Impact"): each call tries GEMINI_MODEL, then
    GEMINI_FALLBACK_MODELS in order, until one answers; raises AllModelsFailed when none
    does. Every call starts again from the first model."""

    client: genai.Client
    models: tuple[str, ...]

    def __post_init__(self):
        if not self.models:
            raise ValueError("FallbackLlm needs at least one model")

    @property
    def model(self) -> str:
        return self.models[0]

    async def generate[T: BaseModel](
        self, *, system: str, parts: list[Part], schema: type[T]
    ) -> Answer[T]:
        failures = []
        for model in self.models:
            try:
                return await generate(self.client, model, system=system, parts=parts, schema=schema)
            except MODEL_FAILURES as exc:
                failures.append(ModelFailure(model, failure_cause(exc)))
                log.warning("Gemini model %s failed (%s)", model, failures[-1].cause)
        raise AllModelsFailed(failures)


def failure_cause(exc: Exception) -> str:
    """What went wrong with one model's call, without response bodies or prompt text."""
    match exc:
        case genai_errors.APIError():
            return f"Gemini answered {exc.code} ({exc.status})"
        case httpx.TimeoutException():
            return f"timed out ({type(exc).__name__})"
        case httpx.TransportError():
            return f"request failed ({type(exc).__name__})"
        case ValidationError():
            return f"answer didn't fit its schema ({exc.error_count()} errors)"
        case NoText():
            return "no text in the answer"
        case _:
            # the class only: a message could carry the model's text
            return type(exc).__name__
