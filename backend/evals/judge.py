"""Eval judge: a second LLM grades the free-text answers against each case's fact list.

    python evals/judge.py                  # newest results folder
    python evals/judge.py results/<stamp>

It never sees a gold paragraph: only the case's own texts, the model's three free-text fields and
the facts, and answers pass/fail per fact with a quote. It writes <run>.judge.json next to each
result; score.py then adds the text score. Calls the judge model with temperature 0.

Settings (environment or backend/.env):
  JUDGE_API_KEY  a Gemini API key for the judge (falls back to GEMINI_API_KEY)
  JUDGE_MODEL    default gemini-flash-lite-latest; use a different model from the one being tested"""

import asyncio
import json
import os
import sys
from pathlib import Path

from google import genai
from google.genai import errors, types
from pydantic import BaseModel

ROOT = Path(__file__).parent

SYSTEM = """You check an AI assistant's written answer against a numbered list of statements. \
Use only the CASE MATERIAL and the ANSWER given; do not use outside knowledge. For each \
statement, decide whether the ANSWER says it or does it (paraphrase counts; silence does not). \
First quote the words of the ANSWER that show it (empty if there are none), then give `present`: \
true if the answer says or does what the statement describes, false if it doesn't. You are only \
reporting what the answer contains, not whether that is good or bad. Do not reward length or \
style."""


class Verdict(BaseModel):
    n: int
    evidence: str
    present: bool


class Verdicts(BaseModel):
    items: list[Verdict]


def load_env() -> None:
    env = ROOT.parent / ".env"
    if env.exists():
        for line in env.read_text().splitlines():
            key, sep, value = line.partition("=")
            if sep and key.strip() in ("JUDGE_API_KEY", "JUDGE_MODEL", "GEMINI_API_KEY"):
                os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


def statements(case: dict) -> list[str]:
    facts = case["expected"]["facts"]
    return facts["must_include"] + facts["must_not_include"]


def prompt(case: dict, ev: dict) -> str:
    facts = case["expected"]["facts"]
    lines = [f"{n}. {f}" for n, f in enumerate(statements(case), 1)]
    docs = ([case["notice"]] if case.get("notice") else []) + list(case.get("documents") or [])
    notice = "\n\n".join(
        f"[{d.get('kind', 'notice')} {d['doi']}]\n" + (d.get("text") or d.get("pdf_text") or "(no text)")
        for d in docs
    ) or "(no notice)"
    return (
        "CASE MATERIAL\n"
        f"Tracked paper:\n{case['paper_text']}\n\nChange notice(s) and related documents:\n{notice}\n\n"
        f"Researcher's draft:\n{case.get('draft_text') or '(no draft uploaded)'}\n\n"
        "ANSWER\n"
        f"change_summary: {ev['change_summary']}\n"
        f"evaluation: {ev['evaluation'] or '(none: the change was not meaningful, so impact was not assessed)'}\n"
        f"recommendation: {ev['recommendation'] or '(none)'}\n\n"
        "STATEMENTS (return one item per number)\n"
        + "\n".join(lines)
    )


async def main() -> None:
    load_env()
    key = os.environ.get("JUDGE_API_KEY") or os.environ.get("GEMINI_API_KEY")
    if not key:
        sys.exit("set JUDGE_API_KEY (or GEMINI_API_KEY)")
    model = os.environ.get("JUDGE_MODEL", "gemini-flash-lite-latest")
    client = genai.Client(api_key=key)

    folders = sorted((ROOT / "results").glob("*"))
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    folder = Path(args[0]) if args else folders[-1]
    cases = {p.stem: json.loads(p.read_text()) for p in (ROOT / "cases").glob("*.json")}
    for path in sorted(folder.glob("*__run?.json")):
        ev = json.loads(path.read_text())
        if ev["assessment"]["placeholder"]:
            continue  # nothing to judge: a failed run
        if path.with_suffix(".judge.json").exists() and "--redo" not in sys.argv:
            continue  # already graded; --redo grades again
        case = cases[path.name.split("__")[0]]
        for attempt in range(4):
            try:
                response = await client.aio.models.generate_content(
                    model=model,
                    contents=prompt(case, ev),
                    config=types.GenerateContentConfig(
                        system_instruction=SYSTEM,
                        temperature=0,
                        response_mime_type="application/json",
                        response_schema=Verdicts,
                    ),
                )
                break
            except errors.APIError as exc:
                if exc.code != 429 or attempt == 3:
                    raise
                print("rate limited, waiting 45 s")
                await asyncio.sleep(45)
        said = {i.n: i for i in Verdicts.model_validate_json(response.text).items}
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
        out = {"judge_model": model, "items": items}
        path.with_suffix(".judge.json").write_text(json.dumps(out, indent=2))
        print(f"{path.stem}: {sum(i['pass'] for i in items)}/{len(items)} facts met")


if __name__ == "__main__":
    asyncio.run(main())
