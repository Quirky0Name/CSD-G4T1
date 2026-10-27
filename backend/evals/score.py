"""Eval scorer: compares saved runner results with each case's expected answers. No LLM calls.

    python evals/score.py                 # newest results folder
    python evals/score.py results/<stamp> # a specific one

Per run it scores the fixed-choice tags (exact match; one level off on an ordered level is half
credit), flags hard failures, and checks that quoted evidence appears in the case's own text.
If judge.py has been run on the folder, the free-text score is added."""

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).parent
LEVELS = ["none", "low", "medium", "high"]


def resolve(ev: dict, path: str) -> list:
    """The model's value(s) for a tag path. A list because a draft can have several uses.

    change.severity | change.has_relations | alert.<field> (first alert) | alert#<id>.<field>
    impact.<field> | impact.use.<field> (any use) | impact.use@<section text>.<field>"""
    a = ev["assessment"]
    change, impact = a.get("change") or {}, a.get("impact")
    if path == "change.severity":
        return [ev["change_severity"]]
    if path == "change.has_relations":
        return [change.get("relations") is not None]
    if path.startswith("alert"):
        head, field = path.split(".", 1)
        alerts = change.get("alerts") or []
        if "#" in head:
            alerts = [x for x in alerts if x.get("alert_id") == int(head.split("#")[1])]
        return [alerts[0].get(field)] if alerts else [None]
    if path.startswith("impact.use"):
        head, field = path.rsplit(".", 1)
        uses = (impact or {}).get("uses") or []
        if "@" in head:
            wanted = head.split("@", 1)[1].lower()
            uses = [u for u in uses if wanted in u.get("section", "").lower()]
        return [u.get(field) for u in uses] or [None]
    if path.startswith("impact."):
        return [impact.get(path.split(".", 1)[1]) if impact else None]
    raise ValueError(f"unknown tag path {path}")


def score_tag(spec: dict, values: list) -> float:
    accept = spec["accept"]
    best = 0.0
    for got in values:
        if got in accept:
            return 1.0
        if spec.get("ordered") and got in LEVELS:
            near = [abs(LEVELS.index(got) - LEVELS.index(a)) for a in accept if a in LEVELS]
            if near and min(near) == 1:
                best = 0.5
    return best


def hard_fails(ev: dict, rules: list[str]) -> list[str]:
    hit = []
    for rule in rules:
        path, _, value = rule.partition(" == ")
        if any(("null" if got is None else str(got).lower()) == value.lower() for got in resolve(ev, path)):
            hit.append(rule)
    return hit


def norm(text: str) -> str:
    text = text.replace("\u2019", "'").replace("\u201c", '"').replace("\u201d", '"')
    return re.sub(r"\s+", " ", text).strip().lower()


def ungrounded_quotes(ev: dict, case: dict) -> tuple[int, int]:
    docs = ([case["notice"]] if case.get("notice") else []) + list(case.get("documents") or [])
    doc_text = [t or "" for d in docs for t in (d.get("text"), d.get("title"), d.get("pdf_text"))]
    # the model also quotes what the prompt shows besides the texts: the alert lines and the
    # notice's Crossref title
    alert_lines = [
        f"{a['change_type']}, {'notice ' + a['notice_doi'] if a.get('notice_doi') else 'no notice'}, "
        f"detected {a['detected_at'][:10]}"
        for a in case["alerts"]
    ]
    source = norm(
        " ".join(
            [case["paper_text"], case.get("draft_text") or "", *doc_text, *alert_lines]
        )
    )
    a = ev["assessment"]
    quotes = [e["quote"] for al in (a.get("change") or {}).get("alerts", []) for e in al["evidence"]]
    quotes += [u["quote"] for u in (a.get("impact") or {}).get("uses", [])]
    bad = sum(1 for q in quotes if norm(q) not in source)
    return bad, len(quotes)


def score_run(path: Path, case: dict) -> dict:
    ev = json.loads(path.read_text())
    expected = case["expected"]
    out = {"case": path.name.split("__")[0], "category": case["category"], "path": path}
    if ev["assessment"]["placeholder"]:
        return {**out, "failed": True, "tag": 0.0, "hard": [], "bad_quotes": 0, "quotes": 0, "detail": {}}
    detail = {t: score_tag(spec, resolve(ev, t)) for t, spec in expected["tags"].items()}
    bad, total = ungrounded_quotes(ev, case)
    judge_path = path.with_suffix(".judge.json")
    text = None
    if judge_path.exists():
        items = json.loads(judge_path.read_text())["items"]
        text = sum(i["pass"] for i in items) / len(items) if items else None
    return {
        **out,
        "failed": False,
        "tag": sum(detail.values()) / len(detail),
        "hard": hard_fails(ev, expected.get("hard_fail_if", [])),
        "bad_quotes": bad,
        "quotes": total,
        "text": text,
        "detail": detail,
    }


def main() -> None:
    folders = sorted((ROOT / "results").glob("*"))
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else folders[-1]
    cases = {p.stem: json.loads(p.read_text()) for p in (ROOT / "cases").glob("*.json")}
    runs = [
        score_run(p, cases[p.name.split("__")[0]])
        for p in sorted(folder.glob("*__run*.json"))
        if not p.name.endswith(".judge.json")
    ]
    print(f"results: {folder}\n")
    for r in runs:
        flags = []
        if r["failed"]:
            flags.append("PLACEHOLDER (failed run)")
        if r["hard"]:
            flags.append("HARD FAIL: " + ", ".join(r["hard"]))
        if r["bad_quotes"]:
            flags.append(f"{r['bad_quotes']}/{r['quotes']} quotes not in input")
        miss = [f"{t}={s}" for t, s in r["detail"].items() if s < 1]
        text = f" text={r['text']:.2f}" if r.get("text") is not None else ""
        print(f"{Path(r['path']).stem:<48} tag={r['tag']:.2f}{text}  {'; '.join(flags + miss)}")

    by_cat = defaultdict(list)
    for r in runs:
        by_cat[r["category"]].append(r)
    print(f"\n{'category':<30}{'runs':>5}{'tag':>7}{'text':>7}{'hard':>6}{'fail':>6}{'badquote':>9}")
    for cat, rs in sorted(by_cat.items()):
        print(row(cat, rs))
    print(row("OVERALL", runs))


def row(name: str, rs: list[dict]) -> str:
    texts = [r["text"] for r in rs if r.get("text") is not None]
    text = f"{sum(texts) / len(texts):.2f}" if texts else "-"
    tag = sum(r["tag"] for r in rs) / len(rs)
    return (
        f"{name:<30}{len(rs):>5}{tag:>7.2f}{text:>7}{sum(bool(r['hard']) for r in rs):>6}"
        f"{sum(r['failed'] for r in rs):>6}{sum(r['bad_quotes'] for r in rs):>9}"
    )


if __name__ == "__main__":
    main()