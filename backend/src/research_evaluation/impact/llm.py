"""Gemini client for impact: one async call that returns the model's answer parsed into a
pydantic model (Gemini's structured output), so callers never parse free text."""

from google import genai
from google.genai import types
from pydantic import BaseModel

from research_evaluation.config import ResearchEvaluationSettings


class GeminiNotConfiguredError(RuntimeError):
    """GEMINI_API_KEY is missing from the environment / .env."""


def make_client(settings: ResearchEvaluationSettings) -> genai.Client:
    if settings.gemini_api_key is None or not settings.gemini_api_key.get_secret_value():
        raise GeminiNotConfiguredError("GEMINI_API_KEY is not set")
    return genai.Client(api_key=settings.gemini_api_key.get_secret_value())


async def generate[T: BaseModel](
    client: genai.Client,
    model: str,
    *,
    system: str,
    prompt: str,
    schema: type[T],
) -> T:
    """Ask `model` for an answer matching `schema`; raises pydantic.ValidationError if the
    model's JSON doesn't fit it."""
    response = await client.aio.models.generate_content(
        model=model,
        contents=prompt,
        config=types.GenerateContentConfig(
            system_instruction=system,
            response_mime_type="application/json",
            response_schema=schema,
            # impact gives the model no tools
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        ),
    )
    return schema.model_validate_json(response.text)
