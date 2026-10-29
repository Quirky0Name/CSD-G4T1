"""Eval scorer: compares saved runner results with expected answers. No LLM calls.

    python evals/score.py                 # newest results folder
    python evals/score.py results/<stamp> # a specific one

Per run it scores tags, flags hard failures, checks quotes, and displays a rich terminal grid
alongside advanced actionable diagnostics for prompt engineering."""

import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from rich.console import Console
from rich.panel import Panel
from rich.table import Table

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT.parent / "src"))

from research_evaluation.impact.prompts import alerts_block, paper_block
from research_evaluation.storage import AlertInReport, PaperDetails

LEVELS = ["none", "low", "medium", "high"]
# the fixed source names the step 1 prompt allows besides a notice's DOI, by document kind
KIND_LABELS = {"current_version": "current copy", "new_version": "new version"}
DOI = re.compile(r"10\.\d{4,9}/\S+")
ALERT = re.compile(r"alert\s*#?\s*(\d+)")
console = Console()


def resolve(ev: dict, path: str) -> list:
    """The model's value(s) for a tag path."""
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


def quote_sources(case: dict) -> dict[str, str]:
    """Each label the model may give as an evidence quote's source, and the (normalised) text
    behind it, as the prompt showed it. "draft" is for impact's uses, which have no label."""
    texts: dict[str, list[str]] = defaultdict(list)
    paper = PaperDetails.model_validate(case["paper"])
    texts["stored paper"] += [paper_block(paper), case["paper_text"]]
    if paper.doi:
        texts[paper.doi.lower()] += texts["stored paper"]
    texts["draft"].append(case.get("draft_text") or "")
    docs = ([{"kind": "notice", **case["notice"]}] if case.get("notice") else []) + list(case.get("documents") or [])
    for d in docs:
        text = " ".join(t for t in (d.get("text"), d.get("title"), d.get("pdf_text")) if t)
        texts[d["doi"].lower()].append(text)
        if d["kind"] in KIND_LABELS:
            texts[KIND_LABELS[d["kind"]]].append(text)
    for alert in case["alerts"]:
        # the alert's own line, without alerts_block's header, exactly as the prompt has it
        line = alerts_block([AlertInReport.model_validate(alert)]).split("\n", 1)[1]
        texts[f"alert {alert['id']}"].append(line)
    return {label: norm(" ".join(parts)) for label, parts in texts.items()}


def source_label(source: str, sources: dict[str, str]) -> str | None:
    """The quote_sources key a model's source names, or None when it names none of them."""
    source = norm(source)
    doi = DOI.search(source)
    if doi and doi.group().rstrip(".,;)") in sources:
        return doi.group().rstrip(".,;)")
    alert = ALERT.search(source)
    if alert and f"alert {alert.group(1)}" in sources:
        return f"alert {alert.group(1)}"
    return next((name for name in ("stored paper", "current copy", "new version") if name in source and name in sources), None)


def quoted_in(quote: str, text: str) -> bool:
    """A normalised quote is in a normalised text; "..." or "…" in the quote skips words, so each
    piece must be there, in order."""
    start = 0
    for piece in filter(None, (p.strip() for p in re.split(r"\.\.\.|…", quote))):
        found = text.find(piece, start)
        if found < 0:
            return False
        start = found + len(piece)
    return True


def check_quotes(ev: dict, case: dict) -> dict[str, int]:
    """Each quote is invented (in none of the case's texts), misattributed (in one, but not the one
    its source names; for impact's uses, not in the draft) or of unknown source (real, but its
    source names no text)."""
    sources = quote_sources(case)
    a = ev["assessment"]
    found = {"quotes": 0, "invented": 0, "misattributed": 0, "unknown_source": 0}
    claimed = [
        (e["quote"], source_label(e["source"], sources))
        for al in (a.get("change") or {}).get("alerts", [])
        for e in al["evidence"]
    ]
    claimed += [(u["quote"], "draft") for u in (a.get("impact") or {}).get("uses", [])]
    for quote, label in claimed:
        q = norm(quote)
        found["quotes"] += 1
        if not any(quoted_in(q, text) for text in sources.values()):
            found["invented"] += 1
        elif label is None:
            found["unknown_source"] += 1
        elif not quoted_in(q, sources[label]):
            found["misattributed"] += 1
    return found


def quote_flags(r: dict) -> list[str]:
    return [f"{r[k]} {k.replace('_', ' ')}" for k in ("invented", "misattributed", "unknown_source") if r[k]]


def score_run(path: Path, case: dict) -> dict:
    ev = json.loads(path.read_text())
    expected = case["expected"]
    out = {"case": path.name.split("__")[0], "category": case["category"], "path": path}
    if ev["assessment"]["placeholder"]:
        # the steps that finished before every model failed are kept, so their quotes still count
        return {**out, "failed": True, "tag": 0.0, "hard": [], **check_quotes(ev, case), "detail": {}, "failed_facts": []}
    detail = {t: score_tag(spec, resolve(ev, t)) for t, spec in expected["tags"].items()}
    judge_path = path.with_suffix(".judge.json")
    text = None
    failed_facts = []
    if judge_path.exists():
        items = json.loads(judge_path.read_text())["items"]
        text = sum(i["pass"] for i in items) / len(items) if items else None
        failed_facts = [i["fact"] for i in items if not i["pass"]]
    return {
        **out,
        "failed": False,
        "tag": sum(detail.values()) / len(detail),
        "hard": hard_fails(ev, expected.get("hard_fail_if", [])),
        **check_quotes(ev, case),
        "text": text,
        "detail": detail,
        "failed_facts": failed_facts,
    }


def colour(s: float) -> str:
    return "green" if s == 1 else "yellow" if s > 0 else "red"


def short(value) -> str:
    return "null" if value is None else str(value).lower() if isinstance(value, bool) else str(value)


def main() -> None:
    folders = sorted((ROOT / "results").glob("*"))
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else folders[-1]
    cases = {p.stem: json.loads(p.read_text()) for p in (ROOT / "cases").glob("*.json")}
    
    runs = [
        score_run(p, cases[p.name.split("__")[0]])
        for p in sorted(folder.glob("*__run*.json"))
        if not p.name.endswith(".judge.json")
    ]

    # --- 1. RICH GRID ---
    counts: dict[str, int] = {}
    for c in cases.values():
        for t in c["expected"]["tags"]:
            counts[t] = counts.get(t, 0) + 1

    tags = [t for t, n in counts.items() if n >= 5]
    grid_table = Table(title=f"Results Grid: {folder.name}  (green = match, yellow = one level off, red = wrong)")
    grid_table.add_column("case", no_wrap=True, min_width=30)
    for t in tags:
        grid_table.add_column(t.replace("impact.", "i.").replace("alert.", "a.").replace("change.", "c."), justify="center")
    grid_table.add_column("tag", justify="right")
    grid_table.add_column("text", justify="right")
    grid_table.add_column("flags", style="red")
    grid_table.add_column("misses (tag: got)", overflow="fold")

    for r in runs:
        path = r["path"]
        ev = json.loads(path.read_text())
        row_cells = [path.name.split("__")[0][:30]]
        for t in tags:
            if t not in r["detail"]:
                row_cells.append("[dim]-[/]")
                continue
            got = "/".join(dict.fromkeys(short(v) for v in resolve(ev, t)))
            row_cells.append(f"[{colour(r['detail'][t])}]{got}[/]")
        
        flags = ["PLACEHOLDER"] * r["failed"] + [f"HARD: {h}" for h in r["hard"]] + quote_flags(r)
        misses = [
            f"{t}: {'/'.join(dict.fromkeys(short(v) for v in resolve(ev, t)))}"
            for t, sc in r["detail"].items()
            if sc < 1 and t not in tags
        ]
        row_cells += [
            f"{r['tag']:.2f}",
            f"{r['text']:.2f}" if r.get("text") is not None else "-",
            "; ".join(flags),
            "; ".join(misses),
        ]
        grid_table.add_row(*row_cells)
    
    console.print(grid_table)

    # --- 2. CATEGORY SUMMARY TABLE ---
    by_cat = defaultdict(list)
    for r in runs:
        by_cat[r["category"]].append(r)

    cat_table = Table(title="Performance Summary by Category")
    for col in ("Category", "Runs", "Avg Tag", "Avg Text", "Hard Fails", "Placeholders", "Invented", "Misattributed", "Unknown Source"):
        cat_table.add_column(col, justify="right" if col != "Category" else "left")

    for cat, rs in sorted(by_cat.items()):
        c_valid = [r for r in rs if not r["failed"]]
        texts = [r["text"] for r in c_valid if r.get("text") is not None]
        text_avg = f"{sum(texts) / len(texts):.2f}" if texts else "-"
        tag_avg = sum(r["tag"] for r in c_valid) / len(c_valid) if c_valid else 0.0
        cat_table.add_row(
            cat,
            str(len(rs)),
            f"{tag_avg:.2f}",
            text_avg,
            str(sum(bool(r['hard']) for r in rs)),
            str(sum(r['failed'] for r in rs)),
            *(str(sum(r[k] for r in rs)) for k in ("invented", "misattributed", "unknown_source")),
        )
    
    valid_runs = [r for r in runs if not r["failed"]]
    all_texts = [r["text"] for r in valid_runs if r.get("text") is not None]
    overall_text_avg = f"{sum(all_texts) / len(all_texts):.2f}" if all_texts else "-"
    overall_tag_avg = sum(r["tag"] for r in valid_runs) / len(valid_runs) if valid_runs else 0.0
    cat_table.add_row(
        "OVERALL",
        str(len(runs)),
        f"{overall_tag_avg:.2f}",
        overall_text_avg,
        str(sum(bool(r['hard']) for r in runs)),
        str(sum(r['failed'] for r in runs)),
        *(f"[bold]{sum(r[k] for r in runs)}[/bold]" for k in ("invented", "misattributed", "unknown_source")),
    )
    console.print(cat_table)

    # --- 3. ADVANCED ACTIONABLE DIAGNOSTICS ---
    total_runs = len(runs)
    if total_runs > 0:
        diag_panel_lines = [
            f"[bold cyan]Total Test Runs Executed:[/] {total_runs}",
            f"[bold cyan]Successful Runs Rate:[/]    {len(valid_runs)}/{total_runs} ({len(valid_runs)/total_runs*100:.1f}%)",
            f"[bold cyan]Average Structured Tag Score:[/] {overall_tag_avg:.3f} / 1.000",
            f"[bold cyan]Average LLM Judge Text Score:[/] {float(overall_text_avg):.3f} / 1.000" if overall_text_avg != "-" else "[bold cyan]Average LLM Judge Text Score:[/] N/A (run judge.py first)",
        ]

        # Tag weaknesses
        tag_misses = Counter()
        tag_partial = Counter()
        for r in valid_runs:
            for t, s in r["detail"].items():
                if s == 0.0:
                    tag_misses[t] += 1
                elif s == 0.5:
                    tag_partial[t] += 1
                    
        if tag_misses:
            diag_panel_lines.append("\n[bold red]❌ Top Structured Tag Misses (0.0 Score):[/]")
            for t, count in tag_misses.most_common(5):
                diag_panel_lines.append(f"  • {t}: completely failed in {count} runs")
        if tag_partial:
            diag_panel_lines.append("\n[bold yellow]⚠️ Top Partial Tag Matches (0.5 Score - Tune Levels):[/]")
            for t, count in tag_partial.most_common(3):
                diag_panel_lines.append(f"  • {t}: off-by-one level in {count} runs")

        # Fact weaknesses
        fact_misses = Counter()
        for r in valid_runs:
            for fact in r.get("failed_facts", []):
                fact_misses[fact] += 1
        if fact_misses:
            diag_panel_lines.append("\n[bold yellow]📝 Top Missed Free-Text Facts:[/]")
            for f, count in fact_misses.most_common(5):
                diag_panel_lines.append(f"  • (Missed {count}x) {f[:80]}...")

        # Quote metrics
        # every run, placeholders included: a placeholder keeps the quotes of the steps that finished
        total_quotes = sum(r["quotes"] for r in runs)
        if total_quotes > 0:
            invented = sum(r["invented"] for r in runs)
            misattributed = sum(r["misattributed"] for r in runs)
            unknown = sum(r["unknown_source"] for r in runs)
            diag_panel_lines.append(f"\n[bold magenta]💬 Quotes checked:[/] {total_quotes}")
            diag_panel_lines.append(f"  • Invented (in no text of the case): {invented / total_quotes * 100:.1f}% ({invented})")
            diag_panel_lines.append(f"  • Misattributed (real, but not in the source named): {misattributed / total_quotes * 100:.1f}% ({misattributed})")
            diag_panel_lines.append(f"  • Unknown source (real, source names no text): {unknown / total_quotes * 100:.1f}% ({unknown})")
            if invented / total_quotes > 0.1:
                diag_panel_lines.append("  [bold red]Action:[/] High hallucination! Strengthen source extraction constraints in prompts.")
            if misattributed:
                diag_panel_lines.append("  [bold red]Action:[/] Quotes credited to the wrong document: check the cases flagged misattributed.")

        console.print(Panel("\n".join(diag_panel_lines), title="📈 Advanced Actionable Diagnostics & Improvement Metrics", title_align="left"))


if __name__ == "__main__":
    main()