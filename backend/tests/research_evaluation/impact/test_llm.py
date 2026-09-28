"""impact/llm.py against a fake Gemini client: what one call sends, and how its answer is read."""

from types import SimpleNamespace

import pytest
from google.genai import types
from pydantic import BaseModel, ValidationError

from research_evaluation.impact.llm import PDF_MIME_TYPE, GeminiLlm, Pdf, generate, to_parts


class Verdict(BaseModel):
    severity: str
    summary: str


class FakeModels:
    def __init__(self, text: str | None, model_version: str | None = "gemini-3-flash-001"):
        self.text = text
        self.model_version = model_version
        self.calls: list[dict] = []

    async def generate_content(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(text=self.text, model_version=self.model_version)


def fake_client(models: FakeModels):
    return SimpleNamespace(aio=SimpleNamespace(models=models))


async def test_a_call_sends_the_system_the_parts_and_the_schema_with_no_tools():
    models = FakeModels('{"severity": "high", "summary": "Retracted."}')

    answer = await generate(
        fake_client(models),
        "gemini-flash-latest",
        system="the rules",
        parts=["some text", Pdf(b"%PDF-1.7 paper"), "the task"],
        schema=Verdict,
    )

    assert answer.value == Verdict(severity="high", summary="Retracted.")
    assert answer.model_version == "gemini-3-flash-001"
    [call] = models.calls
    assert call["model"] == "gemini-flash-latest"
    [content] = call["contents"]
    assert content.role == "user"
    text, pdf, task = content.parts
    assert (text.text, task.text) == ("some text", "the task")
    assert pdf.inline_data.data == b"%PDF-1.7 paper"
    assert pdf.inline_data.mime_type == PDF_MIME_TYPE
    config: types.GenerateContentConfig = call["config"]
    assert config.system_instruction == "the rules"
    assert config.response_mime_type == "application/json"
    assert config.response_schema is Verdict
    assert config.automatic_function_calling.disable is True
    assert not config.tools


async def test_an_answer_that_doesnt_fit_the_schema_raises():
    models = FakeModels('{"severity": "high"}')
    with pytest.raises(ValidationError):
        await generate(fake_client(models), "m", system="s", parts=["p"], schema=Verdict)


@pytest.mark.parametrize("text", [None, ""])
async def test_no_text_at_all_raises(text):
    models = FakeModels(text)
    with pytest.raises(ValueError, match="no text"):
        await generate(fake_client(models), "m", system="s", parts=["p"], schema=Verdict)


async def test_the_gemini_llm_passes_its_model_through():
    models = FakeModels('{"severity": "low", "summary": "A typo."}')
    llm = GeminiLlm(client=fake_client(models), model="gemini-flash-latest")

    answer = await llm.generate(system="s", parts=["p"], schema=Verdict)

    assert answer.value.severity == "low"
    assert models.calls[0]["model"] == "gemini-flash-latest"


def test_text_and_pdfs_become_their_own_parts():
    text, pdf = to_parts(["hello", Pdf(b"%PDF")])
    assert text.text == "hello" and text.inline_data is None
    assert pdf.inline_data.data == b"%PDF" and pdf.text is None
