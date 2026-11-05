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


def bool_text(value: bool | None) -> str:
    return "" if value is None else str(value).lower()


def agree(folder: Path, label_a: str, label_b: str) -> None:
    agreed, total, runs, one_only, unmatched = 0, 0, 0, 0, 0
    differing = []
    for run in score.run_paths(folder):
        a, b = grading(run, label_a), grading(run, label_b)
        if a is None or b is None:
            one_only += a is not None or b is not None
            continue
        runs += 1
        # statements are paired by their text, so gradings made before and after a case's facts
        # were edited never compare two different statements
        b_by_fact = {y["fact"]: y for y in b}
        unmatched += len(b_by_fact.keys() - {x["fact"] for x in a})
        for n, x in enumerate(a, 1):
            y = b_by_fact.get(x["fact"])
            if y is None:
                unmatched += 1
                continue
            if x["present"] is None or y["present"] is None:
                continue  # skipped by the judge in one grading: nothing to compare
            total += 1
            if x["present"] == y["present"]:
                agreed += 1
            else:
                differing.append(
                    f"  {run.stem} #{n} ({x['kind']}) {x['fact']}: "
                    f"{label_a}={bool_text(x['present'])}, {label_b}={bool_text(y['present'])}"
                )
    print(f"Agreement between {label_a} and {label_b}: {share(agreed, total)} of the statements "
          f"graded in both ({runs} runs)")
    if one_only:
        print(f"Runs graded under only one of the labels (left out): {one_only}")
    if unmatched:
        print(f"Statements in only one of the gradings (left out; the case's facts changed?): "
              f"{unmatched}")
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
            present = bool_text(item["present"])
            writer.writerow([run, i, item["fact"], item["kind"], excerpt, present, ""])
    print(f"Wrote {len(picked)} of {len(population)} graded statements to {out}")
    print("Fill in human_present with true or false, then run: judge_check.py human <folder> <csv>")


def human(folder: Path, csv_path: Path) -> None:
    # utf-8-sig: a spreadsheet's "CSV UTF-8" export starts with a byte-order mark
    with csv_path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    agreed, marked = 0, 0
    unmarked, disagree = [], []
    for row in rows:
        mark = row["human_present"].strip().lower()
        where = f"{row['run']} #{row['n']}"
        if mark not in ("true", "false"):
            unmarked.append(f"  {where} {row['fact']}" + (f" (value {mark!r})" if mark else ""))
            continue
        # the judge's grade as the folder has it now (what score.py reads), else the CSV's copy:
        # when the grade is gone, the statement at n is another one, or the judge skipped it
        items = grading(folder / f"{row['run']}.json", "default") or []
        n = int(row["n"])
        item = items[n - 1] if 0 < n <= len(items) else None
        if item is not None and item["fact"] == row["fact"] and item["present"] is not None:
            judged = bool_text(item["present"])
        else:
            judged = row["judge_present"].strip().lower()  # a spreadsheet may write TRUE
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
        out = Path(args.out) if args.out else folder / "judge_sample.csv"
        sample(folder, args.n, args.seed, out)
    else:
        human(folder, Path(args.csv))


if __name__ == "__main__":
    main()
