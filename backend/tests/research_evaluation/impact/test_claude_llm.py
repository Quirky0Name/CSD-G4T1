"""impact/llm.py's ClaudeLlm against a fake Anthropic client: what one call sends, and how its
answer is read."""

import base64
import logging
from types import SimpleNamespace

import anthropic
import httpx2
import pytest
from pydantic import BaseModel

from research_evaluation.impact.llm import (
    AllModelsFailed,
    ClaudeLlm,
    FallbackLlm,
    GeminiLlm,
    ModelFailure,
    Pdf,
)


class Verdict(BaseModel):
    severity: str
    summary: str


class FakeMessages:
    def __init__(self, parsed_output=None, stop_reason="end_turn", model="claude-sonnet-5-5"):
        self.response = SimpleNamespace(
            parsed_output=parsed_output, stop_reason=stop_reason, model=model
        )
        self.calls: list[dict] = []

    async def parse(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


def fake_client(messages: FakeMessages):
    return SimpleNamespace(messages=messages)


async def test_a_call_sends_the_system_the_text_and_pdfs_in_order_and_the_schema_at_low_effort():
    messages = FakeMessages(Verdict(severity="high", summary="Retracted."))
    llm = ClaudeLlm(client=fake_client(messages), model="claude-sonnet-5-5")

    answer = await llm.generate(
        system="the rules",
        parts=["some text", Pdf(b"%PDF-1.7 paper"), "the task"],
        schema=Verdict,
    )

    assert answer.value == Verdict(severity="high", summary="Retracted.")
    assert answer.model_version == "claude-sonnet-5-5"
    assert answer.model == "claude-sonnet-5-5"
    [call] = messages.calls
    assert call["model"] == "claude-sonnet-5-5"
    assert call["system"] == "the rules"
    assert call["messages"] == [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "some text"},
                {
                    "type": "document",
                    "source": {
                        "type": "base64",
                        "media_type": "application/pdf",
                        "data": base64.standard_b64encode(b"%PDF-1.7 paper").decode(),
                    },
                },
                {"type": "text", "text": "the task"},
            ],
        }
    ]
    assert call["output_format"] is Verdict
    assert call["output_config"] == {"effort": "low"}
    assert "tools" not in call


async def test_a_refusal_raises_so_the_next_model_is_tried():
    messages = FakeMessages(parsed_output=None, stop_reason="refusal")
    llm = ClaudeLlm(client=fake_client(messages), model="claude-sonnet-5-5")

    with pytest.raises(ValueError, match="refused"):
        await llm.generate(system="s", parts=["p"], schema=Verdict)


async def test_an_answer_with_nothing_parsed_raises():
    messages = FakeMessages(parsed_output=None, stop_reason="end_turn")
    llm = ClaudeLlm(client=fake_client(messages), model="claude-sonnet-5-5")

    with pytest.raises(ValueError, match="no text"):
        await llm.generate(system="s", parts=["p"], schema=Verdict)


CLAUDE_URL = "https://api.anthropic.com/v1/messages"


def overloaded() -> anthropic.APIStatusError:
    response = httpx2.Response(529, request=httpx2.Request("POST", CLAUDE_URL))
    return anthropic.APIStatusError("Overloaded", response=response, body=None)


def timed_out() -> anthropic.APITimeoutError:
    return anthropic.APITimeoutError(httpx2.Request("POST", CLAUDE_URL))


class FailingMessages:
    def __init__(self, failure: Exception):
        self.failure = failure

    async def parse(self, **kwargs):
        raise self.failure


class GeminiModels:
    async def generate_content(self, **kwargs):
        return SimpleNamespace(
            text='{"severity": "low", "summary": "A typo."}', model_version="gemini-3-flash-001"
        )


def gemini() -> GeminiLlm:
    return GeminiLlm(SimpleNamespace(aio=SimpleNamespace(models=GeminiModels())), "gemini-flash-latest")


@pytest.mark.parametrize(
    "messages",
    [
        FailingMessages(overloaded()),
        FailingMessages(timed_out()),
        FakeMessages(parsed_output=None, stop_reason="refusal"),
    ],
    ids=["529", "timeout", "refusal"],
)
async def test_a_sonnet_failure_falls_back_to_gemini(messages):
    sonnet = ClaudeLlm(client=fake_client(messages), model="claude-sonnet-5-5")

    answer = await FallbackLlm((sonnet, gemini())).generate(system="s", parts=["p"], schema=Verdict)

    assert answer.model == "gemini-flash-latest"
    assert answer.value == Verdict(severity="low", summary="A typo.")


async def test_each_sonnet_failure_is_recorded_and_logged_as_claudes_with_no_prompt_text(caplog):
    llm = FallbackLlm(
        (
            ClaudeLlm(fake_client(FailingMessages(overloaded())), "sonnet-a"),
            ClaudeLlm(fake_client(FailingMessages(timed_out())), "sonnet-b"),
            ClaudeLlm(fake_client(FakeMessages(None, stop_reason="refusal")), "sonnet-c"),
        )
    )

    with (
        caplog.at_level(logging.WARNING, logger="research_evaluation.impact.llm"),
        pytest.raises(AllModelsFailed) as raised,
    ):
        await llm.generate(system="s", parts=["the secret draft text"], schema=Verdict)

    assert raised.value.failures == [
        ModelFailure("sonnet-a", "Claude answered 529"),
        ModelFailure("sonnet-b", "timed out (APITimeoutError)"),
        ModelFailure("sonnet-c", "the model refused"),
    ]
    assert caplog.records[0].getMessage() == "model sonnet-a failed (Claude answered 529)"
    assert "secret draft" not in caplog.text
