"""Eval orchestrator: run -> judge -> score, all on the same results folder.

    python evals/all.py                              # prompts user, then runs ALL steps
    python evals/all.py 01_correction_effect_size    # specific case
    python evals/all.py --folder evals/results/<st>  # target specific folder
"""

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from datetime import UTC, datetime
from pathlib import Path

import score  # Assumes this is in the same evals/ folder

ROOT = Path(__file__).parent


def run_step(name: str, *args: str) -> None:
    """Executes a pipeline script via subprocess and halts on failure."""
    print(f"\n{'='*20} {name.upper()} {'='*20}", flush=True)
    result = subprocess.run([sys.executable, str(ROOT / f"{name}.py"), *args], check=False)
    if result.returncode != 0:
        sys.exit(
            f"\n❌ {name}.py failed (exit code {result.returncode}).\n"
            f"Fix the error, then resume grading this folder with:\n"
            f"    python evals/all.py --folder <folder_path> --skip-run"
        )


def format_score(val: float | None) -> str:
    return "-" if val is None else f"{val:.2f}"


def get_span(scores: list[float]) -> str:
    if not scores:
        return "-"
    lo, hi = min(scores), max(scores)
    return f"{lo:.2f}" if lo == hi else f"{lo:.2f}-{hi:.2f}"


def load_runs(folder: Path) -> list[dict]:
    cases = {p.stem: json.loads(p.read_text()) for p in (ROOT / "cases").glob("*.json")}
    runs = []
    for path in sorted(folder.glob("*__run?.json")):
        name, _, number = path.stem.partition("__run")
        if name not in cases:
            continue
        r = score.score_run(path, cases[name])
        r.update({"run": int(number), "tier": cases[name].get("tier", "base")})
        runs.append(r)
    return runs


def generate_summary(folder: Path) -> list[str]:
    runs = load_runs(folder)
    if not runs:
        return [f"No scored runs found in {folder}"]

    by_case = defaultdict(list)
    by_run = defaultdict(list)
    
    for r in runs:
        by_case[r["case"]].append(r)
        if r["tag"] is not None:
            by_run[r["run"]].append(r["tag"])

    header = f"{'Case':<35}{'Runs':>5}{'Tag':>7}{'Spread':>12}{'Text':>7}{'Hard':>6}{'Fail':>6}{'Inv':>5}{'Mis':>5}{'Unk':>5}"
    lines = [f"Results: {folder}\n", header, "-" * len(header)]

    for name, rs in sorted(by_case.items()):
        tags = [r["tag"] for r in rs if r["tag"] is not None]
        texts = [r["text"] for r in rs if r.get("text") is not None]
        avg_tag = sum(tags) / len(tags) if tags else None
        avg_text = sum(texts) / len(texts) if texts else None
        
        row = (
            f"{name[:34]:<35}{len(rs):>5}{format_score(avg_tag):>7}{get_span(tags):>12}"
            f"{format_score(avg_text):>7}{sum(bool(r['hard']) for r in rs):>6}"
            f"{sum(r['failed'] for r in rs):>6}{sum(r['invented'] for r in rs):>5}"
            f"{sum(r['misattributed'] for r in rs):>5}{sum(r['unknown_source'] for r in rs):>5}"
        )
        lines.append(row)

    lines.append("-" * len(header))
    
    # Calculate noise floor if multiple runs exist
    if len(by_run) > 1:
        per_run_avg = {k: (sum(v) / len(v) if v else 0) for k, v in sorted(by_run.items())}
        spread = max(per_run_avg.values()) - min(per_run_avg.values())
        lines.extend([
            "",
            "Overall tag score by run: " + "   ".join(f"run{k}: {v:.2f}" for k, v in per_run_avg.items()),
            f"Baseline spread (Noise Floor): {spread:.2f}",
        ])

    return lines


def main() -> None:
    parser = argparse.ArgumentParser(description="Eval Orchestrator: Run -> Judge -> Score")
    parser.add_argument("cases", nargs="*", help="Specific case file stems to run")
    parser.add_argument("--runs", type=int, default=3, help="Runs per case (default 3)")
    parser.add_argument("--folder", help="Target an existing results folder")
    parser.add_argument("--skip-run", action="store_true", help="Skip run.py; process existing folder")
    args = parser.parse_args()

    # Interactive prompt
    do_run = not args.skip_run
    if do_run:
        choice = input("🚀 Do you want to execute the generation step (run.py)? [Y/n]: ").strip().lower()
        if choice in ('n', 'no'):
            do_run = False
            args.skip_run = True

    # Determine target folder
    if args.folder:
        folder = Path(args.folder)
    elif args.skip_run:
        folders = sorted((ROOT / "results").glob("*"))
        if not folders:
            sys.exit("❌ No results folders exist yet. You must generate runs first.")
        folder = folders[-1]
        print(f"\n📂 Auto-selected newest folder: {folder.name}")
    else:
        folder = ROOT / "results" / datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
        folder.mkdir(parents=True, exist_ok=True)

    print(f"Target Directory: {folder}")

    # Pipeline Execution
    if do_run:
        run_step("run", *args.cases, "--runs", str(args.runs), "--into", str(folder))
    
    run_step("judge", str(folder))
    
    # Generate and save analytics
    summary_lines = generate_summary(folder)
    summary_text = "\n".join(summary_lines)
    
    print(f"\n{'='*20} FINAL SUMMARY {'='*20}\n{summary_text}")
    (folder / "summary.txt").write_text(summary_text + "\n")
    print(f"\n💾 Saved summary to: {folder / 'summary.txt'}")

    # Always run score.py at the end: the grid, the per-category table and the diagnostics
    run_step("score", str(folder))


if __name__ == "__main__":
    main()