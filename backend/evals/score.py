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

import case_text

from research_evaluation.impact.prompts import alerts_block, paper_block
from research_evaluation.impact.quotes import norm, quoted_in
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


def quote_sources(case: dict) -> dict[str, str]:
    """Each label the model may give as an evidence quote's source, and the (normalised) text
    behind it, as the prompt showed it. "draft" is for impact's uses, which have no label."""
    texts: dict[str, list[str]] = defaultdict(list)
    paper = PaperDetails.model_validate(case["paper"])
    texts["stored paper"] += [paper_block(paper), case_text.paper_text(case)]
    if paper.doi:
        texts[paper.doi.lower()] += texts["stored paper"]
    texts["draft"].append(case_text.draft_text(case) or "")
    docs = ([{"kind": "notice", **case["notice"]}] if case.get("notice") else []) + list(case.get("documents") or [])
    for d in docs:
        # a rich PDF's running head, banner and watermark belong to the document it is stored for
        text = " ".join(t for t in (d.get("text"), d.get("title"), case_text.pdf_text(d)) if t)
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
        tag = 0.0 if expected["tags"] else None
        return {
            **out,
            "failed": True,
            "tag": tag,
            "hard": [],
            **check_quotes(ev, case),
            "detail": {},
            "judged": False,
            "failed_facts": [],
            "failed_kinds": [],
        }
    detail = {t: score_tag(spec, resolve(ev, t)) for t, spec in expected["tags"].items()}
    judge_path = path.with_suffix(".judge.json")
    text = None
    failed = []
    if judge_path.exists():
        items = json.loads(judge_path.read_text())["items"]
        text = sum(i["pass"] for i in items) / len(items) if items else None
        failed = [i for i in items if not i["pass"]]
    return {
        **out,
        "failed": False,
        "tag": mean(list(detail.values())),
        "hard": hard_fails(ev, expected.get("hard_fail_if", [])),
        **check_quotes(ev, case),
        "text": text,
        "detail": detail,
        "judged": judge_path.exists(),
        "failed_facts": [i["fact"] for i in failed],
        "failed_kinds": [i["kind"] for i in failed],
    }


def load_thresholds() -> dict:
    """The pass/fail thresholds in evals/thresholds.json, fixed before a run
    (docs/EVALUATION.md)."""
    return json.loads((ROOT / "thresholds.json").read_text())


def verdict(r: dict, thresholds: dict) -> dict:
    """A scored run's verdict against the thresholds: pass, fail (with its failure types),
    unjudged (no .judge.json yet; lists the structured failures found so far) or error (a
    PLACEHOLDER run). A run with no tag score or no text score skips that threshold."""
    if r["failed"]:
        return {"result": "error", "failures": []}
    failures = []
    if len(r["hard"]) > thresholds["max_hard_fails"]:
        failures.append("hard_fail")
    if r["tag"] is not None and r["tag"] < thresholds["tag"]:
        failures.append("wrong_tag")
    if r["text"] is not None and r["text"] < thresholds["text"]:
        # the text score is too low: which kinds of statement the judge's grade failed
        if "must_include" in r["failed_kinds"]:
            failures.append("missed_fact")
        if "must_not_include" in r["failed_kinds"]:
            failures.append("wrong_fact")
    if r["invented"] > thresholds["max_invented"]:
        failures.append("invented_quote")
    if r["misattributed"] > thresholds["max_misattributed"]:
        failures.append("misattributed_quote")
    if not r["judged"]:
        return {"result": "unjudged", "failures": failures}
    return {"result": "fail" if failures else "pass", "failures": failures}


def verdict_cell(v: dict) -> str:
    """A verdict for the grid: pass green, fail red with its failure types, the others dim."""
    if v["result"] == "pass":
        return "[green]pass[/]"
    if v["result"] == "fail":
        return f"[red]fail {', '.join(v['failures'])}[/]"
    return f"[dim]{v['result']}[/]"


FAILURE_TYPES = (
    "hard_fail", "wrong_tag", "missed_fact", "wrong_fact", "invented_quote", "misattributed_quote"
)


def pass_rate(runs: list[dict], thresholds: dict) -> dict:
    """Passed out of the counted runs (pass or fail); unjudged and error runs are counted apart.
    rate is None when no run is counted."""
    results = Counter(verdict(r, thresholds)["result"] for r in runs)
    counted = results["pass"] + results["fail"]
    return {
        "passed": results["pass"],
        "counted": counted,
        "rate": results["pass"] / counted if counted else None,
        "unjudged": results["unjudged"],
        "error": results["error"],
    }


def format_rate(rate: dict) -> str:
    return f"{rate['passed']}/{rate['counted']}" if rate["counted"] else "-"


def rate_line(rate: dict) -> str:
    """A pass rate with its share, and the runs left out of it."""
    share = f" ({rate['rate']:.0%})" if rate["counted"] else ""
    left_out = f"not counted: {rate['unjudged']} unjudged, {rate['error']} error"
    return f"{format_rate(rate)}{share}, {left_out}"


def target(rate: dict, thresholds: dict) -> str:
    """Whether a case meets case_pass_rate: "meets", "misses", or "-" with no counted run. The
    rate is rounded to two places first, so 2 of 3 (0.667) meets 0.67."""
    if rate["rate"] is None:
        return "-"
    return "meets" if round(rate["rate"], 2) >= thresholds["case_pass_rate"] else "misses"


def failure_counts(runs: list[dict], thresholds: dict) -> dict[str, int]:
    """How many failed runs had each failure type."""
    counts = Counter(
        f for r in runs if (v := verdict(r, thresholds))["result"] == "fail" for f in v["failures"]
    )
    return {f: counts[f] for f in FAILURE_TYPES}


def failure_line(runs: list[dict], thresholds: dict) -> str:
    return "Failure types (in failed runs): " + ", ".join(
        f"{f} {n}" for f, n in failure_counts(runs, thresholds).items()
    )


def colour(s: float) -> str:
    return "green" if s == 1 else "yellow" if s > 0 else "red"


def short(value) -> str:
    return "null" if value is None else str(value).lower() if isinstance(value, bool) else str(value)


def format_score(val: float | None) -> str:
    return "-" if val is None else f"{val:.2f}"


def mean(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def group_by(runs: list[dict], key: str) -> defaultdict[str, list[dict]]:
    """Scored runs by one of their fields ("case", "category"), in the runs' order."""
    groups = defaultdict(list)
    for r in runs:
        groups[r[key]].append(r)
    return groups


def overall_mean(runs: list[dict], key: str) -> float | None:
    """The mean tag or text score over runs, PLACEHOLDER runs and missing scores left out."""
    return mean([r[key] for r in runs if not r["failed"] and r.get(key) is not None])


def tag_by_run(runs: list[dict]) -> dict[int, float]:
    """The overall tag score of each run number (runs from all.load_runs carry "run"), in run
    order; a PLACEHOLDER run's tag of 0 counts."""
    by_run = defaultdict(list)
    for r in runs:
        if r["tag"] is not None:
            by_run[r["run"]].append(r["tag"])
    return {k: mean(v) for k, v in sorted(by_run.items())}


def noise_floor(runs: list[dict]) -> float | None:
    """The spread between the run numbers' overall tag scores; None with a single run number."""
    by_run = tag_by_run(runs)
    return max(by_run.values()) - min(by_run.values()) if len(by_run) > 1 else None


def run_paths(folder: Path) -> list[Path]:
    """The saved runs in a results folder, without the grades: every file with ".judge" in its
    name (<run>.judge.json, and <run>.judge-<label>.json from judge.py --label)."""
    return [p for p in sorted(folder.glob("*__run*.json")) if ".judge" not in p.name]


def most_common_value(runs: list[dict], tag: str) -> str | None:
    """The value a tag path most often had across a case's runs (PLACEHOLDER runs left out), as
    the grid shows it; None when no run is left. Values tied for most common are all given,
    sorted and joined with " | ", so the result doesn't depend on the order of the runs."""
    values = Counter(
        "/".join(dict.fromkeys(short(v) for v in resolve(json.loads(r["path"].read_text()), tag)))
        for r in runs
        if not r["failed"]
    )
    if not values:
        return None
    top = max(values.values())
    return " | ".join(sorted(v for v, n in values.items() if n == top))


def metamorphic_groups(runs: list[dict], cases: dict[str, dict], thresholds: dict) -> list[dict]:
    """Each original case with metamorphic variants (`variant_of`), when at least two of the
    group have runs: each member's pass rate and most common value per tag, the tags whose most
    common value isn't the same for every member (a member with no value, only PLACEHOLDER runs,
    makes the tag inconsistent), and whether every member passes at the same rate (None when a
    member has no counted run, so there is nothing to compare)."""
    variants = defaultdict(list)
    for stem, c in cases.items():
        if c.get("variant_of"):
            variants[c["variant_of"]].append(stem)
    by_case = group_by(runs, "case")
    groups = []
    for original, names in sorted(variants.items()):
        present = [n for n in (original, *sorted(names)) if by_case[n]]
        if original not in cases or len(present) < 2:
            continue  # an unknown original has no expected tags to compare
        tags = list(cases[original]["expected"]["tags"])
        members = [
            {
                "case": n,
                "rate": pass_rate(by_case[n], thresholds),
                "values": {t: most_common_value(by_case[n], t) for t in tags},
            }
            for n in present
        ]
        rates = {m["rate"]["rate"] for m in members}
        groups.append({
            "original": original,
            "members": members,
            "inconsistent": [
                t for t in tags
                if None in (values := {m["values"][t] for m in members}) or len(values) > 1
            ],
            "same_pass_rate": None if None in rates else len(rates) == 1,
        })
    return groups


def main() -> None:
    folders = sorted((ROOT / "results").glob("*"))
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else folders[-1]
    cases = {p.stem: json.loads(p.read_text()) for p in (ROOT / "cases").glob("*.json")}

    runs = [score_run(p, cases[p.name.split("__")[0]]) for p in run_paths(folder)]
    thresholds = load_thresholds()

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
    grid_table.add_column("verdict", no_wrap=True)
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
            format_score(r["tag"]),
            format_score(r.get("text")),
            verdict_cell(verdict(r, thresholds)),
            "; ".join(flags),
            "; ".join(misses),
        ]
        grid_table.add_row(*row_cells)
    
    console.print(grid_table)

    # --- 2. PASS RATES BY CASE ---
    by_case = group_by(runs, "case")
    case_target = f"target: {thresholds['case_pass_rate']:.0%} of them pass"
    case_table = Table(title=f"Pass Rates by Case (counted runs only; {case_target})")
    for col in ("Case", "Runs", "Pass", "Target", "Unjudged", "Error"):
        case_table.add_column(col, justify="left" if col == "Case" else "right")
    for name, rs in sorted(by_case.items()):
        rate = pass_rate(rs, thresholds)
        met = target(rate, thresholds)
        style = {"meets": "green", "misses": "red"}.get(met, "dim")
        case_table.add_row(
            name, str(len(rs)), format_rate(rate), f"[{style}]{met}[/]",
            str(rate["unjudged"]), str(rate["error"]),
        )
    console.print(case_table)

    # --- 3. CATEGORY SUMMARY TABLE ---
    by_cat = group_by(runs, "category")

    cat_table = Table(title="Performance Summary by Category")
    for col in ("Category", "Runs", "Pass", "Unjudged", "Error", "Avg Tag", "Avg Text", "Hard Fails", "Placeholders", "Invented", "Misattributed", "Unknown Source"):
        cat_table.add_column(col, justify="right" if col != "Category" else "left")

    for cat, rs in sorted(by_cat.items()):
        rate = pass_rate(rs, thresholds)
        cat_table.add_row(
            cat,
            str(len(rs)),
            format_rate(rate),
            str(rate["unjudged"]),
            str(rate["error"]),
            format_score(overall_mean(rs, "tag")),
            format_score(overall_mean(rs, "text")),
            str(sum(bool(r['hard']) for r in rs)),
            str(sum(r['failed'] for r in rs)),
            *(str(sum(r[k] for r in rs)) for k in ("invented", "misattributed", "unknown_source")),
        )
    
    valid_runs = [r for r in runs if not r["failed"]]
    overall_text_avg = overall_mean(runs, "text")
    overall_tag_avg = overall_mean(runs, "tag")
    overall_rate = pass_rate(runs, thresholds)
    cat_table.add_row(
        "OVERALL",
        str(len(runs)),
        f"[bold]{format_rate(overall_rate)}[/bold]",
        str(overall_rate["unjudged"]),
        str(overall_rate["error"]),
        format_score(overall_tag_avg),
        format_score(overall_text_avg),
        str(sum(bool(r['hard']) for r in runs)),
        str(sum(r['failed'] for r in runs)),
        *(f"[bold]{sum(r[k] for r in runs)}[/bold]" for k in ("invented", "misattributed", "unknown_source")),
    )
    console.print(cat_table)
    console.print(failure_line(runs, thresholds))

    # --- 4. METAMORPHIC GROUPS: an original and its reworded variants should answer alike ---
    groups = metamorphic_groups(runs, cases, thresholds)
    if groups:
        group_table = Table(
            title="Metamorphic Groups (same facts, other wording: same answer expected)"
        )
        for col in ("Group", "Member", "Pass", "Most common tag values"):
            group_table.add_column(col, overflow="fold")
        for g in groups:
            for m in g["members"]:
                values = "; ".join(f"{t}={'-' if v is None else v}" for t, v in m["values"].items())
                group_table.add_row(g["original"], m["case"], format_rate(m["rate"]), values)
            same = {True: "[green]same[/]", False: "[red]differ[/]", None: "[dim]-[/]"}[
                g["same_pass_rate"]
            ]
            flags = (
                f"[red]inconsistent: {', '.join(g['inconsistent'])}[/]"
                if g["inconsistent"]
                else "[green]all tags consistent[/]"
            )
            group_table.add_row(g["original"], "[bold](group)[/]", same, flags, end_section=True)
        console.print(group_table)

    # --- 5. ADVANCED ACTIONABLE DIAGNOSTICS ---
    total_runs = len(runs)
    if total_runs > 0:
        tag_line = (
            f"{overall_tag_avg:.3f} / 1.000"
            if overall_tag_avg is not None
            else "N/A (no tag scores)"
        )
        text_line = (
            f"{overall_text_avg:.3f} / 1.000"
            if overall_text_avg is not None
            else "N/A (run judge.py first)"
        )
        diag_panel_lines = [
            f"[bold cyan]Total Test Runs Executed:[/] {total_runs}",
            f"[bold cyan]Successful Runs Rate:[/]    {len(valid_runs)}/{total_runs} ({len(valid_runs)/total_runs*100:.1f}%)",
            f"[bold cyan]Overall Pass Rate:[/]       {rate_line(overall_rate)}",
            f"[bold cyan]Average Structured Tag Score:[/] {tag_line}",
            f"[bold cyan]Average LLM Judge Text Score:[/] {text_line}",
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