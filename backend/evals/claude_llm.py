"""Claude as the evals' tested model or judge (docs/EVALUATION.md, "Evals"). Dev only: no service
imports it, and production stays on Gemini.

`ClaudeLlm` runs each call through Claude Code (`claude -p`) on the user's subscription login,
never an API key: no tools, no MCP servers, no slash commands, no user or project settings, and
an empty temp folder as its working directory, so the model gets only the prompt `assess` built.
`ClaudeApiLlm` calls the Claude API with an API key, for paid runs.

Both implement `research_evaluation.impact.llm.Llm`. No call falls back to another model: a
failure is `AllModelsFailed` with one `ModelFailure`, so `assess` saves the run as a placeholder
and `run.py --resume` re-runs it. Hitting the usage limit is `ClaudeLimitReached`, which `assess`
doesn't catch, so the run is not saved at all."""

import asyncio
import json
import os
import shutil
import subprocess
import tempfile
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel, ValidationError

from research_evaluation.impact.llm import (
    AllModelsFailed,
    Answer,
    ModelFailure,
    Part,
    failure_cause,
    to_blocks,
)

# never passed to Claude Code, so it can only use the subscription login
API_KEY_VARIABLES = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")

INSTALL_HINT = (
    "Claude Code (`claude`) isn't on PATH: install it (https://claude.com/claude-code) and log in "
    "with `claude auth login` on your own Claude account"
)


class ClaudeLimitReached(RuntimeError):
    """The usage limit (subscription) or rate limit (API) was hit; `resets_at` is when it
    lifts, in Unix seconds, when known."""

    def __init__(self, message: str, resets_at: int | None = None):
        super().__init__(message)
        self.resets_at = resets_at


class NotOnSubscription(RuntimeError):
    """Claude Code isn't installed, isn't logged in to a claude.ai account, or would use an
    API key."""


@dataclass(frozen=True)
class Usage:
    """One call's tokens and, for Claude Code, the subscription's 5-hour and 7-day
    utilization (0 to 1) when the CLI reported them."""

    input_tokens: int | None = None
    cache_write_tokens: int | None = None
    cache_read_tokens: int | None = None
    output_tokens: int | None = None
    five_hour: float | None = None
    seven_day: float | None = None
    request_id: str | None = None
    failed: bool = False


type Report = Callable[[Usage], None]


def subprocess_env() -> dict[str, str]:
    return {k: v for k, v in os.environ.items() if k not in API_KEY_VARIABLES}


def resolve(command: tuple[str, ...]) -> tuple[str, ...]:
    """The command with its program's full path (an npm install's claude.cmd on Windows too);
    NotOnSubscription when it isn't found."""
    found = shutil.which(command[0])
    if found is None:
        raise NotOnSubscription(INSTALL_HINT)
    return (found, *command[1:])


def preflight(command: tuple[str, ...] = ("claude",)) -> None:
    """Before the first call: Claude Code is installed and logged in to a claude.ai account
    (`claude auth status`), seen with the same environment the calls get."""
    try:
        done = subprocess.run(
            [*resolve(command), "auth", "status"],
            capture_output=True,
            encoding="utf-8",
            errors="replace",
            env=subprocess_env(),
            timeout=60,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise NotOnSubscription(f"`claude auth status` failed ({type(exc).__name__})") from None
    if done.returncode != 0:
        raise NotOnSubscription(
            f"`claude auth status` exited {done.returncode}: log in with `claude auth login`"
        )
    try:
        method = json.loads(done.stdout).get("authMethod")
    except (json.JSONDecodeError, AttributeError):
        method = None
    if method != "claude.ai":
        raise NotOnSubscription(
            f"Claude Code is logged in by {method!r}, not a claude.ai subscription: "
            "log in with `claude auth login`"
        )


def failed(model: str, cause: str) -> AllModelsFailed:
    return AllModelsFailed([ModelFailure(model, cause)])


def events(stdout: str) -> list[dict[str, Any]]:
    out = []
    for line in stdout.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            out.append(event)
    return out


@dataclass(frozen=True)
class ClaudeLlm:
    """One `claude -p` process per call, on the subscription login."""

    model_name: str
    command: tuple[str, ...] = ("claude",)
    timeout_seconds: float = 300
    effort: str = "medium"
    # called after each call that got an answer or a result event, with its usage
    report: Report | None = None

    @property
    def model(self) -> str:
        return self.model_name

    @property
    def models(self) -> tuple[str, ...]:
        return (self.model_name,)

    def argv(self, system: str, schema: type[BaseModel]) -> list[str]:
        return [
            *resolve(self.command),
            "-p",
            "--verbose",
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--model", self.model_name,
            "--effort", self.effort,
            "--system-prompt", system,
            "--tools", "",
            "--strict-mcp-config",
            "--disable-slash-commands",
            "--no-session-persistence",
            "--setting-sources=",
            "--json-schema", json.dumps(schema.model_json_schema(), separators=(",", ":")),
        ]

    async def generate[T: BaseModel](
        self, *, system: str, parts: list[Part], schema: type[T]
    ) -> Answer[T]:
        argv = self.argv(system, schema)
        message = {"type": "user", "message": {"role": "user", "content": to_blocks(parts)}}
        stdin = (json.dumps(message) + "\n").encode("utf-8")
        cwd = tempfile.mkdtemp(prefix="eval-claude-")
        try:
            try:
                process = await asyncio.create_subprocess_exec(
                    *argv,
                    cwd=cwd,
                    env=subprocess_env(),
                    stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            except OSError as exc:
                raise failed(self.model_name, f"claude didn't start ({type(exc).__name__})") from None
            try:
                stdout, _ = await asyncio.wait_for(
                    process.communicate(stdin), timeout=self.timeout_seconds
                )
            except TimeoutError:
                process.kill()
                await process.wait()
                raise failed(self.model_name, f"timed out after {self.timeout_seconds:g} s") from None
        finally:
            shutil.rmtree(cwd, ignore_errors=True)
        return self.answer(events(stdout.decode("utf-8", errors="replace")), process.returncode, schema)

    def answer[T: BaseModel](
        self, seen: list[dict[str, Any]], returncode: int | None, schema: type[T]
    ) -> Answer[T]:
        """The answer in the CLI's events; the limit and a non-subscription login stop the run,
        anything else fails the call with a cause that holds no response text."""
        init = next((e for e in seen if e.get("type") == "system" and e.get("subtype") == "init"), None)
        if init is not None and init.get("apiKeySource", "none") != "none":
            raise NotOnSubscription(
                f"Claude Code used an API key ({init.get('apiKeySource')}), not the subscription"
            )
        limits = [e.get("rate_limit_info") or {} for e in seen if e.get("type") == "rate_limit_event"]
        result = next((e for e in seen if e.get("type") == "result"), None)
        resets_at = next((i.get("resetsAt") for i in reversed(limits) if i.get("resetsAt")), None)
        if any(i.get("status") == "rejected" for i in limits):
            raise ClaudeLimitReached("the Claude usage limit was reached", resets_at)
        if result is not None and result.get("is_error") and "limit" in str(result.get("result", "")).lower():
            raise ClaudeLimitReached("the Claude usage limit was reached", resets_at)
        if result is not None and self.report is not None:
            self.report(cli_usage(result, limits[-1] if limits else {}))
        if returncode != 0:
            raise failed(self.model_name, f"claude exited {returncode}")
        if result is None:
            raise failed(self.model_name, "no result event")
        if result.get("is_error"):
            raise failed(self.model_name, f"claude reported an error ({result.get('subtype')})")
        if result.get("structured_output") is None:
            raise failed(self.model_name, "no structured output")
        try:
            value = schema.model_validate(result["structured_output"])
        except ValidationError as exc:
            raise failed(self.model_name, failure_cause(exc)) from None
        return Answer(value, init.get("model") if init else None, self.model_name)


def cli_usage(result: dict[str, Any], limit: dict[str, Any]) -> Usage:
    usage = result.get("usage") or {}
    windows = limit.get("unifiedWindows") or {}
    return Usage(
        input_tokens=usage.get("input_tokens"),
        cache_write_tokens=usage.get("cache_creation_input_tokens"),
        cache_read_tokens=usage.get("cache_read_input_tokens"),
        output_tokens=usage.get("output_tokens"),
        five_hour=(windows.get("five_hour") or {}).get("utilization"),
        seven_day=(windows.get("seven_day") or {}).get("utilization"),
        failed=bool(result.get("is_error")),
    )


class ApiStopped(RuntimeError):
    """A Claude API error that would repeat on every run (key, permission, model, request):
    the run stops instead of saving placeholders."""


# the API provider's request settings (docs/EVALUATION.md, "Evals")
API_MAX_TOKENS = 16000
API_TIMEOUT_SECONDS = 300


def api_client(api_key: str | None = None):
    """An `anthropic.AsyncAnthropic` on ANTHROPIC_API_KEY only (no other credential source),
    with ClaudeLlm's 300 s timeout. `anthropic` is imported only here, so the other
    providers don't need it (`uv run --with anthropic`)."""
    import anthropic

    return anthropic.AsyncAnthropic(
        api_key=api_key or os.environ["ANTHROPIC_API_KEY"], timeout=API_TIMEOUT_SECONDS
    )


@dataclass(frozen=True)
class ClaudeApiLlm:
    """One `messages.parse` call per step on the Claude API, billed to the key. No sampling
    settings and no `thinking` (adaptive by default), effort always sent, no fallbacks."""

    client: Any  # anthropic.AsyncAnthropic
    model_name: str
    effort: str = "medium"
    report: Report | None = None

    @property
    def model(self) -> str:
        return self.model_name

    @property
    def models(self) -> tuple[str, ...]:
        return (self.model_name,)

    async def generate[T: BaseModel](
        self, *, system: str, parts: list[Part], schema: type[T]
    ) -> Answer[T]:
        import anthropic

        try:
            response = await self.client.messages.parse(
                model=self.model_name,
                max_tokens=API_MAX_TOKENS,
                system=system,
                messages=[{"role": "user", "content": to_blocks(parts)}],
                output_format=schema,
                output_config={"effort": self.effort},
                # top-level automatic caching; parse() has no cache_control argument
                extra_body={"cache_control": {"type": "ephemeral"}},
            )
        except anthropic.RateLimitError as exc:
            self.failed_call(exc)
            retry = exc.response.headers.get("retry-after") if exc.response is not None else None
            raise ClaudeLimitReached(
                f"the Claude API rate limit was hit (retry after {retry or '?'} s)"
            ) from None
        except anthropic.APIStatusError as exc:
            self.failed_call(exc)
            if exc.status_code >= 500:
                raise failed(self.model_name, f"Claude API answered {exc.status_code}") from None
            # 400, 401, 403, 404 and any other 4xx: the same on every run
            raise ApiStopped(
                f"Claude API answered {exc.status_code} ({type(exc).__name__}, "
                f"request {getattr(exc, 'request_id', None)})"
            ) from None
        except anthropic.APIConnectionError as exc:  # timeouts too
            self.failed_call(exc)
            raise failed(self.model_name, f"request failed ({type(exc).__name__})") from None
        except ValidationError as exc:
            self.failed_call(exc)
            raise failed(self.model_name, failure_cause(exc)) from None
        unusable = response.stop_reason in ("refusal", "max_tokens") or response.parsed_output is None
        if self.report is not None:
            u = response.usage
            self.report(
                Usage(
                    input_tokens=u.input_tokens,
                    cache_write_tokens=u.cache_creation_input_tokens,
                    cache_read_tokens=u.cache_read_input_tokens,
                    output_tokens=u.output_tokens,
                    # the request id only for a failed call, to quote to Anthropic
                    request_id=response._request_id if unusable else None,
                    failed=unusable,
                )
            )
        if response.stop_reason in ("refusal", "max_tokens"):
            raise failed(self.model_name, f"stop reason {response.stop_reason}")
        if response.parsed_output is None:
            raise failed(self.model_name, "no parsed output")
        try:
            value = schema.model_validate(response.parsed_output)
        except ValidationError as exc:
            raise failed(self.model_name, failure_cause(exc)) from None
        return Answer(value, response.model, self.model_name)

    def failed_call(self, exc: Exception) -> None:
        if self.report is not None:
            self.report(Usage(request_id=getattr(exc, "request_id", None), failed=True))
