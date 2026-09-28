"""impact/llm.py against a fake Gemini client: what one call sends, and how its answer is read."""

import logging
from types import SimpleNamespace

import httpx
import pytest
from google.genai import errors as genai_errors
from google.genai import types
from pydantic import BaseModel, ValidationError

from research_evaluation.impact.llm import (
    PDF_MIME_TYPE,
    AllModelsFailed,
    FallbackLlm,
    GeminiLlm,
    ModelFailure,
    Pdf,
    generate,
    to_parts,
)


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
    assert answer.model == "gemini-flash-latest"
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


class ScriptedModels:
    """generate_content answers per model name: an exception to raise, or the text to return."""

    def __init__(self, script: dict[str, Exception | str | None]):
        self.script = script
        self.calls: list[str] = []

    async def generate_content(self, **kwargs):
        model = kwargs["model"]
        self.calls.append(model)
        outcome = self.script[model]
        if isinstance(outcome, Exception):
            raise outcome
        return SimpleNamespace(text=outcome, model_version=f"{model}-001")


OK = '{"severity": "low", "summary": "A typo."}'


def overloaded() -> genai_errors.APIError:
    return genai_errors.ServerError(
        503, {"error": {"code": 503, "status": "UNAVAILABLE", "message": "high demand"}}
    )


async def ask(llm: FallbackLlm):
    return await llm.generate(system="s", parts=["the secret draft text"], schema=Verdict)


async def test_the_first_model_answering_means_one_call():
    models = ScriptedModels({"a": OK, "b": OK})

    answer = await ask(FallbackLlm(fake_client(models), ("a", "b")))

    assert models.calls == ["a"]
    assert answer.model == "a" and answer.model_version == "a-001"


@pytest.mark.parametrize(
    "failure",
    [
        overloaded(),
        httpx.ReadTimeout("timed out"),
        httpx.ConnectError("refused"),
        '{"severity": "high"}',  # off-schema
        "",  # no text
        None,  # no text
    ],
    ids=["503", "timeout", "connect", "off-schema", "empty", "none"],
)
async def test_each_kind_of_failure_moves_on_to_the_next_model(failure):
    models = ScriptedModels({"a": failure, "b": OK})

    answer = await ask(FallbackLlm(fake_client(models), ("a", "b")))

    assert models.calls == ["a", "b"]
    assert answer.model == "b"
    assert answer.value == Verdict(severity="low", summary="A typo.")


async def test_models_are_tried_in_order_until_one_answers():
    models = ScriptedModels({"a": overloaded(), "b": overloaded(), "c": OK, "d": OK})

    answer = await ask(FallbackLlm(fake_client(models), ("a", "b", "c", "d")))

    assert models.calls == ["a", "b", "c"]
    assert answer.model == "c"


async def test_every_model_failing_raises_with_each_model_and_its_cause():
    models = ScriptedModels(
        {"a": overloaded(), "b": httpx.ReadTimeout("t"), "c": '{"x": 1}', "d": None}
    )

    with pytest.raises(AllModelsFailed) as raised:
        await ask(FallbackLlm(fake_client(models), ("a", "b", "c", "d")))

    assert models.calls == ["a", "b", "c", "d"]
    assert raised.value.failures == [
        ModelFailure("a", "Gemini answered 503 (UNAVAILABLE)"),
        ModelFailure("b", "timed out (ReadTimeout)"),
        ModelFailure("c", "answer didn't fit its schema (2 errors)"),
        ModelFailure("d", "no text in the answer"),
    ]


async def test_another_value_error_moves_on_and_is_recorded_by_its_class_only():
    class SdkComplaint(ValueError):
        pass

    models = ScriptedModels({"a": SdkComplaint("the model said: secret"), "b": None})

    with pytest.raises(AllModelsFailed) as raised:
        await ask(FallbackLlm(fake_client(models), ("a", "b")))

    assert raised.value.failures == [
        ModelFailure("a", "SdkComplaint"),
        ModelFailure("b", "no text in the answer"),
    ]


async def test_each_call_starts_again_from_the_first_model():
    models = ScriptedModels({"a": overloaded(), "b": OK})
    llm = FallbackLlm(fake_client(models), ("a", "b"))

    await ask(llm)
    models.script["a"] = OK
    answer = await ask(llm)

    assert models.calls == ["a", "b", "a"]
    assert answer.model == "a"


async def test_an_error_that_isnt_a_model_failure_is_raised_straight_away():
    models = ScriptedModels({"a": KeyError("bug"), "b": OK})

    with pytest.raises(KeyError):
        await ask(FallbackLlm(fake_client(models), ("a", "b")))

    assert models.calls == ["a"]


async def test_a_fallback_logs_the_model_and_cause_but_no_prompt_text(caplog):
    models = ScriptedModels({"a": overloaded(), "b": OK})

    with caplog.at_level(logging.WARNING, logger="research_evaluation.impact.llm"):
        await ask(FallbackLlm(fake_client(models), ("a", "b")))

    [record] = caplog.records
    assert record.getMessage() == "Gemini model a failed (Gemini answered 503 (UNAVAILABLE))"
    assert "secret draft" not in caplog.text


def test_the_fallback_llm_is_named_after_its_first_model_and_needs_one():
    assert FallbackLlm(fake_client(ScriptedModels({})), ("a", "b")).model == "a"
    with pytest.raises(ValueError, match="at least one model"):
        FallbackLlm(fake_client(ScriptedModels({})), ())


def test_text_and_pdfs_become_their_own_parts():
    text, pdf = to_parts(["hello", Pdf(b"%PDF")])
    assert text.text == "hello" and text.inline_data is None
    assert pdf.inline_data.data == b"%PDF" and pdf.text is None
