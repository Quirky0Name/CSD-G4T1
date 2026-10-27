"""Eval viewer: the saved results as tables in the terminal. Reads files only, no LLM calls.

    python evals/view.py                     # grid of every case x tag, newest results
    python evals/view.py <case>              # one case: expected vs got, free text, judge facts
    python evals/view.py <case> --folder evals/results/<stamp>

Needs rich (pip install rich; not a project dependency)."""


## Just to read it easily since json is harder to read teh more test cases we have
import argparse
import json
from pathlib import Path

from rich.console import Console
from rich.panel import Panel
from rich.table import Table

import score

ROOT = Path(__file__).parent
console = Console()


def colour(s: float) -> str:
    return "green" if s == 1 else "yellow" if s > 0 else "red"


def short(value) -> str:
    return "null" if value is None else str(value).lower() if isinstance(value, bool) else str(value)


def grid(folder: Path, cases: dict) -> None:
    counts: dict[str, int] = {}
    for c in cases.values():
        for t in c["expected"]["tags"]:
            counts[t] = counts.get(t, 0) + 1
    # only tags most cases share are columns; the rest show up in "misses" when they fail
    tags = [t for t, n in counts.items() if n >= 5]
    table = Table(title=f"{folder.name}  (green = match, yellow = one level off, red = wrong)")
    table.add_column("case", no_wrap=True, min_width=30)
    table.add_column("model", style="dim")
    for t in tags:
        table.add_column(t.replace("impact.", "i.").replace("alert.", "a.").replace("change.", "c."), justify="center")
    table.add_column("tag", justify="right")
    table.add_column("text", justify="right")
    table.add_column("flags", style="red")
    table.add_column("misses (tag: got)", overflow="fold")
    for path in sorted(folder.glob("*__run?.json")):
        name = path.name.split("__")[0]
        case = cases[name]
        ev = json.loads(path.read_text())
        r = score.score_run(path, case)
        row = [path.name.split("__")[0][:30], (ev["assessment"]["answered_by"]["change"] or "?").replace("gemini-", "").replace("-latest", "")]
        for t in tags:
            if t not in r["detail"]:
                row.append("[dim]-[/]")
                continue
            got = "/".join(dict.fromkeys(short(v) for v in score.resolve(ev, t)))
            row.append(f"[{colour(r['detail'][t])}]{got}[/]")
        flags = ["PLACEHOLDER"] * r["failed"] + [f"HARD: {h}" for h in r["hard"]]
        if r["bad_quotes"]:
            flags.append(f"{r['bad_quotes']} bad quote")
        misses = [
            f"{t}: {'/'.join(dict.fromkeys(short(v) for v in score.resolve(ev, t)))}"
            for t, sc in r["detail"].items()
            if sc < 1 and t not in tags
        ]
        row += [
            f"{r['tag']:.2f}",
            f"{r['text']:.2f}" if r.get("text") is not None else "-",
            "; ".join(flags),
            "; ".join(misses),
        ]
        table.add_row(*row)
    console.print(table)
    console.print("Open one with: python evals/view.py <case name>")


def detail(folder: Path, name: str, cases: dict) -> None:
    case = cases[name]
    for path in sorted(folder.glob(f"{name}__run?.json")):
        ev = json.loads(path.read_text())
        r = score.score_run(path, case)
        console.rule(f"{path.stem}  ({case['category']})")
        table = Table(title="Tags: expected vs got")
        for col in ("tag", "expected", "got", "score"):
            table.add_column(col)
        for t, spec in case["expected"]["tags"].items():
            got = " / ".join(dict.fromkeys(short(v) for v in score.resolve(ev, t)))
            s = r["detail"].get(t, 0.0)
            table.add_row(t, " | ".join(short(a) for a in spec["accept"]), f"[{colour(s)}]{got}[/]", f"{s}")
        console.print(table)
        if r["hard"]:
            console.print(f"[bold red]HARD FAIL: {', '.join(r['hard'])}[/]")
        for label, key in (("change_summary", "change_summary"), ("evaluation", "evaluation"), ("recommendation", "recommendation")):
            console.print(Panel(str(ev[key]), title=label, title_align="left"))
        judge = path.with_suffix(".judge.json")
        if judge.exists():
            facts = Table(title="Judge: facts")
            for col in ("", "fact", "judge's evidence"):
                facts.add_column(col, overflow="fold")
            for i in json.loads(judge.read_text())["items"]:
                facts.add_row("[green]PASS[/]" if i["pass"] else "[red]FAIL[/]", i["fact"], i["evidence"])
            console.print(facts)
        else:
            console.print("[dim]not judged yet (run evals/judge.py)[/]")
        console.print(f"[dim]why this answer is expected: {case['expected']['why']}[/]")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("case", nargs="?", help="case name (file stem); omit for the grid")
    parser.add_argument("--folder", help="results folder; default is the newest")
    args = parser.parse_args()
    folder = Path(args.folder) if args.folder else sorted((ROOT / "results").glob("*"))[-1]
    cases = {p.stem: json.loads(p.read_text()) for p in (ROOT / "cases").glob("*.json")}
    if args.case:
        detail(folder, args.case, cases)
    else:
        grid(folder, cases)


if __name__ == "__main__":
    main()
