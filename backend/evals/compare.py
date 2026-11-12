"""Eval regression check: compares two results folders. No LLM calls.

    python evals/compare.py evals/results/<baseline> evals/results/<new>

For the cases in both folders it prints each case's pass rate before and after (better, worse,
same), and the change in overall pass rate, mean tag and mean text, each labelled "within noise"
when it is no bigger than the larger of the two folders' noise floors (all.py's spread of the
per-run overall tag score, score.noise_floor). A case whose file changed between the runs
(meta.json's case_sha256) is listed as "case changed"; cases in only one folder are listed
apart. Neither counts toward the totals. A different prompt_version, models, prompt_sha256 (the
prompt texts, in each folder's prompts.json) or provider is a header line, not an error; a folder
made before prompt_sha256 and provider were recorded is compared without them. Across providers
it shows the model change, not a regression."""

import argparse
import json
from pathlib import Path

import all as orchestrator
import score


def load(folder: Path) -> tuple[dict[str, list[dict]], dict | None]:
    """The folder's scored runs by case, and its meta.json (None for a folder made before it)."""
    by_case = score.group_by(orchestrator.load_runs(folder), "case")
    meta_path = folder / "meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else None
    return dict(by_case), meta


def meta_values(meta: dict, field: str) -> str:
    """The distinct values of one meta.json field across a folder's runs, as text; runs that
    don't record it are left out ("" when none does)."""
    seen = []
    for entry in meta["runs"].values():
        if field not in entry:
            continue
        value = entry[field]
        text = ", ".join(value) if isinstance(value, list) else str(value)
        if text not in seen:
            seen.append(text)
    return "; ".join(seen)


def case_changed(
    base_meta: dict, base_runs: list[dict], new_meta: dict, new_runs: list[dict]
) -> bool:
    """The case file's hash differs between the folders' runs; a case whose runs have no
    meta.json entry in one folder is not checked."""
    before, after = case_hashes(base_meta, base_runs), case_hashes(new_meta, new_runs)
    return bool(before and after) and before != after


def case_hashes(meta: dict, runs: list[dict]) -> set[str]:
    return {
        meta["runs"][r["path"].stem]["case_sha256"]
        for r in runs
        if r["path"].stem in meta["runs"]
    }


def noise_label(change: float | None, floor: float | None) -> str:
    if change is None:
        return ""
    if floor is None:
        return "no noise floor (1 run)"
    return "within noise" if abs(change) <= floor else "beyond noise"


def direction(before: float | None, after: float | None) -> str:
    if before is None or after is None:
        return "not comparable"
    return "better" if after > before else "worse" if after < before else "same"


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare two eval results folders")
    parser.add_argument("baseline")
    parser.add_argument("new")
    args = parser.parse_args()
    base_folder, new_folder = Path(args.baseline), Path(args.new)
    thresholds = score.load_thresholds()
    base, base_meta = load(base_folder)
    new, new_meta = load(new_folder)

    print(f"Baseline: {base_folder}")
    print(f"New:      {new_folder}")
    if base_meta and new_meta:
        for field in ("prompt_version", "models", "prompt_sha256", "provider"):
            before, after = meta_values(base_meta, field), meta_values(new_meta, field)
            if field in ("prompt_sha256", "provider") and not (before and after):
                continue  # a folder made before the field: compared without it
            if before == after:
                print(f"{field}: {before}")
            else:
                print(f"{field} differs: baseline {before} | new {after}")
    else:
        folders = ((base_folder, base_meta), (new_folder, new_meta))
        missing = [str(f) for f, m in folders if m is None]
        print(f"Case hashes not checked: no meta.json in {' and '.join(missing)}")

    base_floor = score.noise_floor([r for rs in base.values() for r in rs])
    new_floor = score.noise_floor([r for rs in new.values() for r in rs])
    floors = [f for f in (base_floor, new_floor) if f is not None]
    floor = max(floors) if floors else None
    print(
        f"Noise floor: {score.format_score(floor) if floor is not None else 'none (1 run)'} "
        f"(baseline {score.format_score(base_floor)}, new {score.format_score(new_floor)})"
    )

    shared = []
    print()
    print(f"{'Case':<40}{'Baseline':>10}{'New':>8}  Change")
    for name in sorted(base.keys() & new.keys()):
        if base_meta and new_meta and case_changed(base_meta, base[name], new_meta, new[name]):
            print(f"{name:<40}{'':>10}{'':>8}  case changed (left out of the totals)")
            continue
        shared.append(name)
        before = score.pass_rate(base[name], thresholds)
        after = score.pass_rate(new[name], thresholds)
        print(
            f"{name:<40}{score.format_rate(before):>10}{score.format_rate(after):>8}  "
            f"{direction(before['rate'], after['rate'])}"
        )
    only = (
        ("Only in baseline", base.keys() - new.keys()),
        ("Only in new", new.keys() - base.keys()),
    )
    for label, names in only:
        if names:
            print(f"{label} (left out of the totals): {', '.join(sorted(names))}")

    base_runs = [r for n in shared for r in base[n]]
    new_runs = [r for n in shared for r in new[n]]
    before, after = score.pass_rate(base_runs, thresholds), score.pass_rate(new_runs, thresholds)
    print()
    print(f"Totals over {len(shared)} case(s) in both:")
    rate_change = None
    if before["rate"] is not None and after["rate"] is not None:
        rate_change = after["rate"] - before["rate"]
        if before["counted"] == after["counted"]:
            passed = after["passed"] - before["passed"]
            moved = f"{passed:+d}/{after['counted']}, {rate_change:+.0%}"
        else:
            moved = f"{rate_change:+.0%}"
    else:
        moved = "not comparable"
    print(
        f"  pass rate: {score.format_rate(before)} -> {score.format_rate(after)} "
        f"({moved}) {noise_label(rate_change, floor)}".rstrip()
    )
    for key in ("tag", "text"):
        b, a = score.overall_mean(base_runs, key), score.overall_mean(new_runs, key)
        change = a - b if a is not None and b is not None else None
        moved = f"{change:+.2f}" if change is not None else "not comparable"
        print(
            f"  mean {key}: {score.format_score(b)} -> {score.format_score(a)} "
            f"({moved}) {noise_label(change, floor)}".rstrip()
        )


if __name__ == "__main__":
    main()
