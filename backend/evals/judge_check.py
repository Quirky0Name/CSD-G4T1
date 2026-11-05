"""Checks the judge (judge.py): is it consistent, and does a person agree with it? No LLM calls.

    python evals/judge_check.py agree <folder> <labelA> <labelB>   # two gradings of the same runs
    python evals/judge_check.py sample <folder> [--n 20] [--seed N] [--out <csv>]
    python evals/judge_check.py human <folder> <csv>               # after filling in human_present

A label names a grading: `default` is <run>.judge.json (the one score.py reads), any other label
is <run>.judge-<label>.json from `judge.py --label <label>`. Agreement is per statement: the
judge's `present`, over the statements graded in both (a statement the judge skipped has none).
`sample` draws statements from the default grading into a CSV for a person to mark;
`human_present` is left blank to fill in with true or false."""

import argparse
import csv
import json
import random
from pathlib import Path

import judge
import score

COLUMNS = ["run", "n", "fact", "kind", "answer_excerpt", "judge_present", "human_present"]


def grading(run: Path, label: str) -> list[dict] | None:
    """A run's graded items under a label, or None when that grading doesn't exist."""
    path = judge.grade_path(run, None if label == "default" else label)
    return json.loads(path.read_text())["items"] if path.exists() else None


def share(agreed: int, total: int) -> str:
    return f"{agreed}/{total} ({agreed / total:.0%})" if total else "-"


def text(value: bool | None) -> str:
    return "" if value is None else str(value).lower()


def agree(folder: Path, label_a: str, label_b: str) -> None:
    agreed, total, runs, one_only = 0, 0, 0, 0
    differing = []
    for run in score.run_paths(folder):
        a, b = grading(run, label_a), grading(run, label_b)
        if a is None or b is None:
            one_only += a is not None or b is not None
            continue
        runs += 1
        for n, (x, y) in enumerate(zip(a, b), 1):
            if x["present"] is None or y["present"] is None:
                continue  # skipped by the judge in one grading: nothing to compare
            total += 1
            if x["present"] == y["present"]:
                agreed += 1
            else:
                differing.append(
                    f"  {run.stem} #{n} ({x['kind']}) {x['fact']}: "
                    f"{label_a}={text(x['present'])}, {label_b}={text(y['present'])}"
                )
    print(f"Agreement between {label_a} and {label_b}: {share(agreed, total)} of the statements "
          f"graded in both ({runs} runs)")
    if one_only:
        print(f"Runs graded under only one of the labels (left out): {one_only}")
    if differing:
        print("Differing statements:")
        print("\n".join(differing))


def sample(folder: Path, n: int, seed: int | None, out: Path) -> None:
    population = []
    for run in score.run_paths(folder):
        items = grading(run, "default")
        if items is None:
            continue
        excerpt = judge.answer(json.loads(run.read_text()))
        population += [
            (run.stem, i, item, excerpt)
            for i, item in enumerate(items, 1)
            if item["present"] is not None
        ]
    picked = random.Random(seed).sample(population, min(n, len(population)))
    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(COLUMNS)
        for run, i, item, excerpt in sorted(picked, key=lambda p: (p[0], p[1])):
            writer.writerow([run, i, item["fact"], item["kind"], excerpt, text(item["present"]), ""])
    print(f"Wrote {len(picked)} of {len(population)} graded statements to {out}")
    print("Fill in human_present with true or false, then run: judge_check.py human <folder> <csv>")


def human(folder: Path, csv_path: Path) -> None:
    with csv_path.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    agreed, marked = 0, 0
    unmarked, disagree = [], []
    for row in rows:
        mark = row["human_present"].strip().lower()
        where = f"{row['run']} #{row['n']}"
        if mark not in ("true", "false"):
            unmarked.append(f"  {where} {row['fact']}" + (f" (value {mark!r})" if mark else ""))
            continue
        # the judge's grade as the folder has it now (what score.py reads), else the CSV's copy
        items = grading(folder / f"{row['run']}.json", "default")
        n = int(row["n"])
        judged = text(items[n - 1]["present"]) if items and n <= len(items) else row["judge_present"]
        marked += 1
        if judged == mark:
            agreed += 1
        else:
            disagree.append(f"  {where} {row['fact']}: judge={judged}, person={mark}")
    print(f"Person agrees with the judge: {share(agreed, marked)} of the marked statements")
    if disagree:
        print("Disagreements:")
        print("\n".join(disagree))
    if unmarked:
        print("Unmarked (not counted):")
        print("\n".join(unmarked))


def main() -> None:
    parser = argparse.ArgumentParser(description="Check the eval judge")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("agree", help="how often two gradings of the same runs agree")
    p.add_argument("folder")
    p.add_argument("label_a")
    p.add_argument("label_b")
    p = sub.add_parser("sample", help="write a random sample of the judge's verdicts to a CSV")
    p.add_argument("folder")
    p.add_argument("--n", type=int, default=20)
    p.add_argument("--seed", type=int)
    p.add_argument("--out", help="CSV path; default <folder>/judge_sample.csv")
    p = sub.add_parser("human", help="how often a person agrees with the judge")
    p.add_argument("folder")
    p.add_argument("csv")
    args = parser.parse_args()

    folder = Path(args.folder)
    if args.command == "agree":
        agree(folder, args.label_a, args.label_b)
    elif args.command == "sample":
        sample(folder, args.n, args.seed, Path(args.out) if args.out else folder / "judge_sample.csv")
    else:
        human(folder, Path(args.csv))


if __name__ == "__main__":
    main()
