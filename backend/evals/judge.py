"""Eval judge: a second LLM grades the free-text answers against each case's fact list.

    python evals/judge.py                  # newest results folder
    python evals/judge.py results/<stamp>
    python evals/judge.py results/<stamp> --label b   # a second grading, for judge_check.py

It never sees a gold paragraph: only the case's own texts, the model's three free-text fields and
the facts, and answers pass/fail per fact with a quote. It writes <run>.judge.json next to each
result; score.py then adds the text score.

A run with `claims` (PROMPT_VERSION 5 on) gets a second grading in the same file: whether each
passage backs its claim (`passages`), and each factual statement of the three free-text fields
with the claims that cover it (`statements`). The judge reports; the code decides what passes. A
run graded before claims were judged gets only this grading, without --redo. With --label <label> it writes
<run>.judge-<label>.json instead, which score.py never reads. Calls the judge model with
temperature 0.

Settings (environment or backend/.env):
  JUDGE_API_KEY  a Gemini API key for the judge (falls back to GEMINI_API_KEY)
  JUDGE_MODEL    default gemini-flash-lite-latest; use a different model from the one being tested"""

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

import case_text
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


PASSAGE_SYSTEM = """You check an AI assistant's claims and the passages it quoted for them. \
Use only what is given; do not use outside knowledge. For each numbered PASSAGE, give a short \
reason, then `backs`: true if the quoted words, read on their own, show what the claim states, \
false if they don't (they are about something else, say less, or contradict it). Then list each \
factual statement in the ANSWER's change_summary, evaluation and recommendation, with its field, \
and `covered_by`: the numbers of the CLAIMS that state it (empty if no claim does). You are only \
reporting, not judging whether the answer is right. Do not reward length or style."""


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
    lines = [f"{n}. {f}" for n, f in enumerate(statements(case), 1)]
    docs = ([case["notice"]] if case.get("notice") else []) + list(case.get("documents") or [])
    notice = "\n\n".join(
        f"[{d.get('kind', 'notice')} {d['doi']}]\n" + document_text(d) for d in docs
    ) or "(no notice)"
    return (
        "CASE MATERIAL\n"
        f"Tracked paper:\n{case_text.paper_text(case)}\n\nChange notice(s) and related documents:\n{notice}\n\n"
        f"Researcher's draft:\n{case_text.draft_text(case) or '(no draft uploaded)'}\n\n"
        f"ANSWER\n{answer(ev)}\n\n"
        "STATEMENTS (return one item per number)\n"
        + "\n".join(lines)
    )


def document_text(document: dict) -> str:
    """A document's text and its stored PDF's text, as the quote check reads them: a watermark
    or banner on the PDF is part of what the model saw."""
    text, pdf = document.get("text"), case_text.pdf_text(document)
    if text and pdf and text != pdf:
        return f"{text}\n\nStored PDF:\n{pdf}"
    return text or pdf or "(no text)"


def answer(ev: dict) -> str:
    """The run's three free-text fields, as the judge sees them."""
    return (
        f"change_summary: {ev['change_summary']}\n"
        f"evaluation: {ev['evaluation'] or '(none: the change was not meaningful, so impact was not assessed)'}\n"
        f"recommendation: {ev['recommendation'] or '(none)'}"
    )


def numbered_passages(ev: dict) -> list[tuple[int, dict]]:
    """Every passage of the run's claims, in order, with the index of its claim; passage n of
    the prompt is entry n - 1."""
    return [(i, p) for i, claim in enumerate(ev["claims"]) for p in claim["passages"]]


def passage_prompt(ev: dict) -> str:
    claims = [f"{i}. {c['text']}" for i, c in enumerate(ev["claims"], 1)]
    passages = [
        f"{n}. for claim {i + 1}: \"{p['quote']}\""
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
    exists covers it."""
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
        covered = [claims[k - 1]["id"] for k in s.covered_by if 1 <= k <= len(claims)]
        statements.append(
            {"field": s.field, "statement": s.statement, "covered_by": covered, "pass": bool(covered)}
        )
    return {"passages": passages, "statements": statements}


async def ask(client, model: str, system: str, contents: str, schema: type[BaseModel]) -> str:
    """One judge call at temperature 0, waiting out a rate limit up to three times."""
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


def grade_path(run: Path, label: str | None = None) -> Path:
    """Where a run's grade goes: <run>.judge.json, or <run>.judge-<label>.json for a labelled
    grading. Every file with ".judge" in its name is a grade, not a run."""
    return run.with_suffix(f".judge-{label}.json" if label else ".judge.json")


async def main() -> None:
    load_env()
    key = os.environ.get("JUDGE_API_KEY") or os.environ.get("GEMINI_API_KEY")
    if not key:
        sys.exit("set JUDGE_API_KEY (or GEMINI_API_KEY)")
    model = os.environ.get("JUDGE_MODEL", "gemini-flash-lite-latest")
    client = genai.Client(api_key=key)

    parser = argparse.ArgumentParser(description="Grade a results folder's free-text answers")
    parser.add_argument("folder", nargs="?", help="results folder; default is the newest")
    parser.add_argument("--redo", action="store_true", help="grade runs already graded again")
    parser.add_argument("--label", help="write <run>.judge-<label>.json, a second grading")
    args = parser.parse_args()
    if args.label == "default":
        # judge_check.py reads the label `default` as <run>.judge.json, the unlabelled grading
        parser.error("--label default is reserved for <run>.judge.json: leave out --label")
    folder = Path(args.folder) if args.folder else max((ROOT / "results").glob("*"))
    cases = {p.stem: json.loads(p.read_text()) for p in (ROOT / "cases").glob("*.json")}
    for path in sorted(folder.glob("*__run*.json")):
        if ".judge" in path.name:
            continue  # a grade, not a run (same run list as score.run_paths)
        ev = json.loads(path.read_text())
        if ev["assessment"]["placeholder"]:
            continue  # nothing to judge: a failed run
        out_path = grade_path(path, args.label)
        existing = json.loads(out_path.read_text()) if out_path.exists() else None
        # --redo grades again; a run graded before claims were judged gets only that grading
        grade_facts = existing is None or args.redo
        grade_claims = ev.get("claims") is not None and (grade_facts or "passages" not in existing)
        if not (grade_facts or grade_claims):
            continue
        out = existing if not grade_facts else await grade(client, model, cases, path, ev)
        if grade_claims:
            text = await ask(client, model, PASSAGE_SYSTEM, passage_prompt(ev), PassageVerdicts)
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


async def grade(client, model: str, cases: dict, path: Path, ev: dict) -> dict:
    """The fact grading of one run: the judge reports, the code decides."""
    case = cases[path.name.split("__")[0]]
    text = await ask(client, model, SYSTEM, prompt(case, ev), Verdicts)
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
    return {"judge_model": model, "items": items}


if __name__ == "__main__":
    asyncio.run(main())
