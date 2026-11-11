"""Eval judge: a second LLM grades the free-text answers against each case's fact list.

    python evals/judge.py                  # newest results folder
    python evals/judge.py results/<stamp>
    python evals/judge.py results/<stamp> --label b   # a second grading, for judge_check.py
    python evals/judge.py results/<stamp> --provider claude-code

The fact grading reads only two JSON documents: the model's three free-text fields and the
case's statements, numbered, without saying which must or must not be included. It never sees
the case's paper, notices or draft: whether an answer is true of them is checked by the
statements, the tags and the quote check. It reports per statement whether the answer says it,
with a quote, and the code decides pass or fail. It writes <run>.judge.json next to each
result; score.py then adds the text score.

A run with `claims` (PROMPT_VERSION 5 on) gets a second grading in the same file: whether each
passage backs its claim (`passages`), and each factual statement of the three free-text fields
with the claims that cover it (`statements`). The judge reports; the code decides what passes. A
run graded before claims were judged gets only this grading, without --redo. With --label <label> it writes
<run>.judge-<label>.json instead, which score.py never reads. Calls the judge model with
temperature 0 on Gemini; Claude has none.

Every case's `expected.facts` is checked against `Rubric` before the first call. A judge model
equal to a run's tested model (`assessment.model`) is a usage error. A call that fails leaves
its run ungraded; the run is listed at the end, and running judge.py again grades only those.
Hitting the Claude usage limit stops it; runs already graded are kept.

Settings (environment or backend/.env):
  --provider     gemini (default), claude-code (the Claude Code login, no key) or claude-api
  JUDGE_API_KEY  a Gemini API key for the judge (falls back to GEMINI_API_KEY); gemini only
  ANTHROPIC_API_KEY  claude-api only
  JUDGE_MODEL    default gemini-flash-lite-latest, haiku (claude-code) or claude-haiku-5-5
                 (claude-api); it may not be the model being tested"""

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path
from typing import Annotated

import httpx
from google import genai
from google.genai import errors, types
from pydantic import BaseModel, ConfigDict, StringConstraints, ValidationError, model_validator

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT.parent / "src"))

import claude_llm
from claude_llm import ApiStopped, ClaudeLimitReached, NotOnSubscription

from research_evaluation.impact.llm import AllModelsFailed
from research_evaluation.impact.schemas import Claim, Sentence, kept

PROVIDERS = ("gemini", "claude-code", "claude-api")
DEFAULT_MODELS = {
    "gemini": "gemini-flash-lite-latest",
    "claude-code": "haiku",
    "claude-api": "claude-haiku-5-5",
}
EFFORT = "medium"

SYSTEM = """You check an AI assistant's written answer against a numbered list of statements. \
You get two JSON documents: first the ANSWER (its change_summary, evaluation and recommendation; \
null where it gave none), then the STATEMENTS, each with its number `n` and `text`. Use only the \
ANSWER; do not use outside knowledge, and do not decide whether the answer is true. For each \
statement, decide whether the ANSWER says it or does it (paraphrase counts; silence does not). \
First quote the words of the ANSWER that show it (empty if there are none), then give `present`: \
true if the answer says or does what the statement describes, false if it doesn't. Return one \
item per statement number. You are only reporting what the answer contains, not whether that is \
good or bad. Do not reward length or style."""

Statement = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class Rubric(BaseModel):
    """A case's `expected.facts` (docs/EVALUATION.md, "A case"): both lists, nothing else, each
    item one non-empty fact checkable from the answer alone, and at least one in all."""

    model_config = ConfigDict(extra="forbid")

    must_include: list[Statement]
    must_not_include: list[Statement]

    @model_validator(mode="after")
    def has_statements(self) -> "Rubric":
        if not (self.must_include or self.must_not_include):
            raise ValueError("the rubric has no statements")
        return self


class Verdict(BaseModel):
    n: int
    evidence: str
    present: bool


class Verdicts(BaseModel):
    items: list[Verdict]


PASSAGE_SYSTEM = """You check an AI assistant's claims and the passages it quoted for them. \
Use only what is given; do not use outside knowledge. For each numbered PASSAGE, give a short \
reason, then `backs`: true if the quoted words, read on their own and knowing where they come \
from, show what the claim states, false if they don't (they are about something else, say less, \
or contradict it). A quote from the researcher's draft can show what the draft says. Then list \
each factual statement in the ANSWER's change_summary, evaluation and recommendation, with its \
field, and `covered_by`: the numbers of the CLAIMS that state it (empty if no claim does). A \
factual statement is one the source documents could show true or false. Advice, instructions and \
opinions on what to do are not factual statements: do not list them. A field given as a list of \
sentences marks each one [fact] or [advice]: list statements only from its [fact] sentences, and \
skip its [advice] sentences whole. A field shown as (none) \
has no statements. Then give `absences`: the numbers of the CLAIMS marked (no passages) that \
state only that something is missing from the material (a notice, its text, a banner); a claim \
stating anything else is not one. You are only reporting, not judging whether the answer is right. Do not \
reward length or style."""

# how the claims grading names a passage's source (a stored passage's `target`)
PASSAGE_SOURCES: dict[str | None, str] = {
    "document": "a fetched document",
    "paper": "the tracked paper",
    "draft": "the researcher's draft",
    "alert": "an alert line",
}


class PassageVerdict(BaseModel):
    n: int
    reason: str
    backs: bool


class StatementVerdict(BaseModel):
    field: str
    statement: str
    covered_by: list[int]


class PassageVerdicts(BaseModel):
    passages: list[PassageVerdict]
    statements: list[StatementVerdict]
    absences: list[int]


def load_env(keys: tuple[str, ...]) -> None:
    env = ROOT.parent / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            key, sep, value = line.partition("=")
            if sep and key.strip() in keys:
                os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


def statements(case: dict) -> list[str]:
    facts = case["expected"]["facts"]
    return facts["must_include"] + facts["must_not_include"]


def prompt(case: dict, ev: dict) -> str:
    """The fact grading's prompt: the answer JSON, then the statements JSON, and nothing else
    (docs/EVALUATION.md, "A case")."""
    answer_doc = {
        "change_summary": ev["change_summary"],
        "evaluation": ev["evaluation"],
        "recommendation": ev["recommendation"],
    }
    statements_doc = {
        "statements": [{"n": n, "text": text} for n, text in enumerate(statements(case), 1)]
    }
    return (
        json.dumps(answer_doc, indent=2, ensure_ascii=False)
        + "\n\n"
        + json.dumps(statements_doc, indent=2, ensure_ascii=False)
    )


# each free-text field, the step it comes from and that step's sentence list (PROMPT_VERSION 11 on)
FIELD_SENTENCES = (
    ("change_summary", "change", "summary"),
    ("evaluation", "impact", "explanation"),
    ("recommendation", "actions", "recommendation"),
)


def field_text(ev: dict, field: str, step: str, key: str) -> str:
    """One free-text field as the judge sees it: the sentences the stored text kept, each marked
    [fact] or [advice], when the run has its step's sentence list; else the stored text."""
    if not ev[field]:
        return "(none)"
    answer = ev["assessment"].get(step) or {}
    if not isinstance(answer.get(key), list):
        return ev[field]
    sentences = [Sentence.model_validate(s) for s in answer[key]]
    claims = [Claim.model_validate(c) for c in answer.get("claims") or []]
    return "".join(f"\n  - [{s.kind}] {s.text.strip()}" for s in kept(sentences, claims))


def answer(ev: dict) -> str:
    """The run's three free-text fields, as the judge sees them."""
    return "\n".join(
        f"{field}: {field_text(ev, field, step, key)}" for field, step, key in FIELD_SENTENCES
    )


def numbered_passages(ev: dict) -> list[tuple[int, dict]]:
    """Every passage of the run's claims, in order, with the index of its claim; passage n of
    the prompt is entry n - 1."""
    return [(i, p) for i, claim in enumerate(ev["claims"]) for p in claim["passages"]]


def passage_prompt(ev: dict) -> str:
    claims = [
        f"{i}. {c['text']}" + ("" if c["passages"] else " (no passages)")
        for i, c in enumerate(ev["claims"], 1)
    ]
    passages = [
        f"{n}. for claim {i + 1}, from {PASSAGE_SOURCES.get(p.get('target'), 'an unknown source')}: \"{p['quote']}\""
        for n, (i, p) in enumerate(numbered_passages(ev), 1)
    ]
    return (
        f"ANSWER\n{answer(ev)}\n\n"
        "CLAIMS\n" + "\n".join(claims) + "\n\n"
        "PASSAGES (return one item per number)\n" + ("\n".join(passages) or "(none)")
    )


def passage_grade(ev: dict, verdicts: PassageVerdicts) -> dict:
    """The code's decision on the judge's report: a passage passes when the judge says it backs
    its claim (one the judge skipped fails); a statement passes when at least one claim that
    exists covers it. An absence counts only for a claim that exists and has no passage."""
    said = {v.n: v for v in verdicts.passages}
    claims = ev["claims"]
    passages = []
    for n, (i, p) in enumerate(numbered_passages(ev), 1):
        verdict = said.get(n)
        passages.append(
            {
                "claim_id": claims[i]["id"],
                "quote": p["quote"],
                "backs": verdict.backs if verdict else None,
                "pass": verdict is not None and verdict.backs,
                "reason": verdict.reason if verdict else "",
            }
        )
    statements = []
    for s in verdicts.statements:
        # the judge sometimes lists an empty field's "(none)" as a statement; it has none
        if not ev.get(s.field) or s.statement.strip() == "(none)":
            continue
        covered = [claims[k - 1]["id"] for k in s.covered_by if 1 <= k <= len(claims)]
        statements.append(
            {"field": s.field, "statement": s.statement, "covered_by": covered, "pass": bool(covered)}
        )
    absent = [
        claims[k - 1]["id"]
        for k in verdicts.absences
        if 1 <= k <= len(claims) and not claims[k - 1]["passages"]
    ]
    return {"passages": passages, "statements": statements, "absent_claims": absent}


async def ask(client, model: str, system: str, contents: str, schema: type[BaseModel]) -> str:
    """One Gemini judge call at temperature 0, waiting out a rate limit up to three times."""
    for attempt in range(4):
        try:
            response = await client.aio.models.generate_content(
                model=model,
                contents=contents,
                config=types.GenerateContentConfig(
                    system_instruction=system,
                    temperature=0,
                    response_mime_type="application/json",
                    response_schema=schema,
                ),
            )
            return response.text
        except errors.APIError as exc:
            if exc.code != 429 or attempt == 3:
                raise
            print("rate limited, waiting 45 s")
            await asyncio.sleep(45)
    raise AssertionError("unreachable")


class Judge:
    """The judge model on one provider: `ask` returns the answer as JSON text."""

    def __init__(self, provider: str, model: str, client=None):
        self.provider, self.model, self.client = provider, model, client

    async def ask(self, system: str, contents: str, schema: type[BaseModel]) -> str:
        if self.provider == "gemini":
            return await ask(self.client, self.model, system, contents, schema)
        answer = await self.client.generate(system=system, parts=[contents], schema=schema)
        return answer.value.model_dump_json()


# a judge call that failed for its run only: the run stays ungraded and is listed
CALL_FAILURES = (AllModelsFailed, ValidationError, errors.APIError, httpx.TransportError, ValueError)


def failure_text(exc: Exception) -> str:
    """The cause of a failed judge call, without the model's text."""
    match exc:
        case AllModelsFailed():
            return "; ".join(f.cause for f in exc.failures)
        case ValidationError():
            return f"answer didn't fit its schema ({exc.error_count()} errors)"
        case errors.APIError():
            return f"Gemini answered {exc.code} ({exc.status})"
        case _:
            return type(exc).__name__


def make_judge(provider: str, model: str) -> Judge:
    if provider == "claude-code":
        claude_llm.preflight()
        return Judge(provider, model, claude_llm.ClaudeLlm(model, effort=EFFORT))
    if provider == "claude-api":
        load_env(("ANTHROPIC_API_KEY",))
        if not os.environ.get("ANTHROPIC_API_KEY"):
            sys.exit("ANTHROPIC_API_KEY is not set")
        return Judge(provider, model, claude_llm.ClaudeApiLlm(claude_llm.api_client(), model, effort=EFFORT))
    key = os.environ.get("JUDGE_API_KEY") or os.environ.get("GEMINI_API_KEY")
    if not key:
        sys.exit("set JUDGE_API_KEY (or GEMINI_API_KEY)")
    return Judge(provider, model, genai.Client(api_key=key))


def check_rubrics(cases: dict[str, dict]) -> list[str]:
    """Every case whose `expected.facts` breaks the rubric contract, with why."""
    bad = []
    for name, case in sorted(cases.items()):
        try:
            Rubric.model_validate((case.get("expected") or {}).get("facts"))
        except ValidationError as exc:
            bad.append(f"{name}: {exc.error_count()} error(s): " + "; ".join(
                f"{'.'.join(map(str, e['loc'])) or 'facts'}: {e['msg']}" for e in exc.errors()
            ))
    return bad


def grade_path(run: Path, label: str | None = None) -> Path:
    """Where a run's grade goes: <run>.judge.json, or <run>.judge-<label>.json for a labelled
    grading. Every file with ".judge" in its name is a grade, not a run."""
    return run.with_suffix(f".judge-{label}.json" if label else ".judge.json")


async def main() -> None:
    parser = argparse.ArgumentParser(description="Grade a results folder's free-text answers")
    parser.add_argument("folder", nargs="?", help="results folder; default is the newest")
    parser.add_argument("--redo", action="store_true", help="grade runs already graded again")
    parser.add_argument("--label", help="write <run>.judge-<label>.json, a second grading")
    parser.add_argument("--provider", choices=PROVIDERS, default="gemini")
    args = parser.parse_args()
    if args.label == "default":
        # judge_check.py reads the label `default` as <run>.judge.json, the unlabelled grading
        parser.error("--label default is reserved for <run>.judge.json: leave out --label")
    keys = {"gemini": ("JUDGE_API_KEY", "JUDGE_MODEL", "GEMINI_API_KEY")}.get(args.provider, ("JUDGE_MODEL",))
    load_env(keys)
    model = os.environ.get("JUDGE_MODEL") or DEFAULT_MODELS[args.provider]
    folder = Path(args.folder) if args.folder else max((ROOT / "results").glob("*"))
    cases = {p.stem: json.loads(p.read_text()) for p in (ROOT / "cases").glob("*.json")}
    if bad := check_rubrics(cases):
        sys.exit("cases break the rubric contract (docs/EVALUATION.md, \"A case\"):\n  " + "\n  ".join(bad))
    runs = []
    for path in sorted(folder.glob("*__run*.json")):
        if ".judge" in path.name:
            continue  # a grade, not a run (same run list as score.run_paths)
        runs.append((path, json.loads(path.read_text())))
    if same := [p.stem for p, ev in runs if ev["assessment"].get("model") == model]:
        parser.error(
            f"the judge model {model!r} is the tested model of {', '.join(same)}: "
            "set JUDGE_MODEL to another model"
        )
    try:
        judge = make_judge(args.provider, model)
    except NotOnSubscription as exc:
        sys.exit(f"stopped before any call: {exc}")
    failed: list[tuple[str, str]] = []
    try:
        for path, ev in runs:
            if ev["assessment"]["placeholder"]:
                continue  # nothing to judge: a failed run
            try:
                await judge_run(judge, cases, path, ev, args)
            except CALL_FAILURES as exc:
                failed.append((path.stem, failure_text(exc)))
                print(f"{path.stem}: not graded ({failed[-1][1]})")
    except ClaudeLimitReached as exc:
        sys.exit(
            f"\nstopped: {exc}; runs already graded are kept. Run judge.py on {folder} again "
            "to grade the rest."
        )
    except (NotOnSubscription, ApiStopped) as exc:
        sys.exit(f"\nstopped: {exc}")
    if failed:
        print(f"\n{len(failed)} run(s) not graded:")
        for name, cause in failed:
            print(f"  {name}: {cause}")
        print(f"grade them by running judge.py on {folder} again (with the same --provider)")


async def judge_run(judge: Judge, cases: dict, path: Path, ev: dict, args: argparse.Namespace) -> None:
    out_path = grade_path(path, args.label)
    existing = json.loads(out_path.read_text()) if out_path.exists() else None
    # --redo grades again; a run graded before claims were judged gets only that grading
    grade_facts = existing is None or args.redo
    grade_claims = ev.get("claims") is not None and (grade_facts or "passages" not in existing)
    if not (grade_facts or grade_claims):
        return
    out = existing if not grade_facts else await grade(judge, cases, path, ev)
    if grade_claims:
        text = await judge.ask(PASSAGE_SYSTEM, passage_prompt(ev), PassageVerdicts)
        out |= passage_grade(ev, PassageVerdicts.model_validate_json(text))
    out_path.write_text(json.dumps(out, indent=2))
    facts = f"{sum(i['pass'] for i in out['items'])}/{len(out['items'])} facts met"
    if "passages" in out:
        backed = sum(p["pass"] for p in out["passages"])
        covered = sum(s["pass"] for s in out["statements"])
        facts += (
            f", {backed}/{len(out['passages'])} passages back their claim, "
            f"{covered}/{len(out['statements'])} statements covered"
        )
    print(f"{path.stem}: {facts}")


async def grade(judge: Judge, cases: dict, path: Path, ev: dict) -> dict:
    """The fact grading of one run: the judge reports, the code decides."""
    case = cases[path.name.split("__")[0]]
    text = await judge.ask(SYSTEM, prompt(case, ev), Verdicts)
    said = {i.n: i for i in Verdicts.model_validate_json(text).items}
    facts = case["expected"]["facts"]
    wanted = [True] * len(facts["must_include"]) + [False] * len(facts["must_not_include"])
    items = []
    for n, (fact, should_say) in enumerate(zip(statements(case), wanted), 1):
        verdict = said.get(n)
        # the code, not the judge, decides: a must-include is met when present, a must-not
        # when absent; a statement the judge skipped counts as failed
        ok = verdict is not None and verdict.present == should_say
        items.append(
            {
                "fact": fact,
                "kind": "must_include" if should_say else "must_not_include",
                "present": verdict.present if verdict else None,
                "pass": ok,
                "evidence": verdict.evidence if verdict else "",
            }
        )
    return {"judge_model": judge.model, "items": items}


if __name__ == "__main__":
    asyncio.run(main())
