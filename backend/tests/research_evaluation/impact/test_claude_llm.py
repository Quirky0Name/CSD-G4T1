"""impact/llm.py's ClaudeLlm against a fake Anthropic client: what one call sends, and how its
answer is read."""

import base64
from types import SimpleNamespace

from pydantic import BaseModel

from research_evaluation.impact.llm import ClaudeLlm, Pdf


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
