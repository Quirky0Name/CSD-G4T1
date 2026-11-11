"""Checks how score.py and judge.py score claims' passages, with inline fixtures and no LLM calls
(docs/EVALUATION.md, "Evals"). Exits 0 when every check holds.

    uv run --with fpdf2 --with rich python evals/check_passage_scoring.py"""

import copy
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import judge
import score

CASE = {
    "category": "check",
    "paper": {"doi": "10.1000/paper", "title": "Caffeine and memory"},
    "paper_text": "In healthy adults, 200 mg caffeine improved n-back accuracy.",
    "draft_text": "We chose 200 mg because caffeine improves working memory [1].",
    "alerts": [],
    "notice": {"doi": "10.1000/notice", "text": "The effect size in Table 2 was corrected."},
    "documents": [],
    "expected": {"tags": {}, "facts": {"must_include": [], "must_not_include": []}},
}


def passage(source: str, quote: str) -> dict:
    return {"source": source, "quote": quote}


def stored(claim_id: str, text: str, *quotes: str) -> dict:
    """A claim as the evaluation stores it (only what the scoring reads)."""
    return {
        "id": claim_id,
        "kind": claim_id.split("-")[0],
        "text": text,
        "supported": bool(quotes),
        "passages": [{"quote": q, "verified": "verbatim"} for q in quotes],
    }


def run(draft: str = "read") -> dict:
    """A run whose three steps each have one claim with one real, rightly sourced passage."""
    change_claim = {"text": "Table 2 was corrected.", "passages": [passage("10.1000/notice", "The effect size in Table 2 was corrected.")]}
    impact_claim = {"text": "The draft relies on the dose.", "passages": [passage("draft", "We chose 200 mg")]}
    action_claim = {"text": "Re-check the dose.", "passages": [passage("stored paper", "200 mg caffeine improved n-back accuracy")]}
    return {
        "change_summary": "Table 2 was corrected.",
        "change_severity": "medium",
        "impact_level": "medium",
        "evaluation": "The draft relies on the dose.",
        "recommendation": "Re-check the dose.",
        "assessment": {
            "placeholder": False,
            "draft": draft,
            "change": {"alerts": [], "claims": [change_claim]},
            "impact": {"uses": [], "claims": [impact_claim]},
            "actions": {"actions": [], "claims": [action_claim]},
        },
        "claims": [
            stored("change-0", change_claim["text"], change_claim["passages"][0]["quote"]),
            stored("impact-0", impact_claim["text"], impact_claim["passages"][0]["quote"]),
            stored("action-0", action_claim["text"], action_claim["passages"][0]["quote"]),
        ],
    }


def scored(ev: dict, grade: dict | None = None) -> dict:
    """The verdict of one run (and its judge file, when given), scored as score.py scores it."""
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "check__run1.json"
        path.write_text(json.dumps(ev))
        if grade is not None:
            path.with_suffix(".judge.json").write_text(json.dumps({"items": [], **grade}))
        r = score.score_run(path, CASE)
        return {**r, "verdict": score.verdict(r, score.load_thresholds())}


def all_pass_grade(ev: dict) -> dict:
    """A judge's answer that every passage backs its claim and every statement is covered."""
    numbered = judge.numbered_passages(ev)
    return judge.passage_grade(
        ev,
        judge.PassageVerdicts(
            passages=[judge.PassageVerdict(n=n, reason="", backs=True) for n in range(1, len(numbered) + 1)],
            statements=[judge.StatementVerdict(field="evaluation", statement="The draft relies on the dose.", covered_by=[2])],
            absences=[],
        ),
    )


def without_passages(ev: dict, step: str, index: int) -> dict:
    ev = copy.deepcopy(ev)
    ev["claims"][index]["passages"] = []
    ev["claims"][index]["supported"] = False
    ev["assessment"][step]["claims"][0]["passages"] = []
    return ev


CHECKS = []


def check(fn):
    CHECKS.append(fn)
    return fn


@check
def a_clean_run_passes():
    ev = run()
    r = scored(ev, all_pass_grade(ev))
    assert (r["unlinked"], r["unbacked"], r["uncovered"]) == (0, 0, 0), r
    assert r["verdict"] == {"result": "pass", "failures": []}, r["verdict"]


@check
def a_change_claim_with_no_passages_is_an_unlinked_claim():
    ev = without_passages(run(), "change", 0)
    r = scored(ev, all_pass_grade(ev))
    assert r["unlinked"] == 1, r
    assert "unlinked_claim" in r["verdict"]["failures"], r["verdict"]


@check
def an_impact_claim_with_no_passages_and_no_draft_is_not_unlinked():
    ev = without_passages(run(draft="none"), "impact", 1)
    r = scored(ev, all_pass_grade(ev))
    assert r["unlinked"] == 0, r
    assert "unlinked_claim" not in r["verdict"]["failures"], r["verdict"]


@check
def an_impact_claim_with_no_passages_and_a_draft_read_is_unlinked():
    ev = without_passages(run(), "impact", 1)
    assert scored(ev, all_pass_grade(ev))["unlinked"] == 1


@check
def a_passage_judged_not_to_back_its_claim_is_an_unbacked_passage():
    ev = run()
    grade = judge.passage_grade(
        ev,
        judge.PassageVerdicts(
            passages=[
                judge.PassageVerdict(n=1, reason="", backs=True),
                judge.PassageVerdict(n=2, reason="it is about the dose, not memory", backs=False),
                judge.PassageVerdict(n=3, reason="", backs=True),
            ],
            statements=[],
            absences=[],
        ),
    )
    assert [p["pass"] for p in grade["passages"]] == [True, False, True], grade
    r = scored(ev, grade)
    assert r["unbacked"] == 1, r
    assert r["verdict"]["result"] == "fail" and "unbacked_passage" in r["verdict"]["failures"], r["verdict"]


@check
def a_passage_the_judge_skipped_counts_as_unbacked():
    ev = run()
    grade = judge.passage_grade(
        ev,
        judge.PassageVerdicts(
            passages=[judge.PassageVerdict(n=1, reason="", backs=True), judge.PassageVerdict(n=3, reason="", backs=True)],
            statements=[],
            absences=[],
        ),
    )
    assert scored(ev, grade)["unbacked"] == 1


@check
def a_statement_no_claim_covers_is_an_uncovered_statement():
    ev = run()
    grade = judge.passage_grade(
        ev,
        judge.PassageVerdicts(
            passages=[judge.PassageVerdict(n=n, reason="", backs=True) for n in (1, 2, 3)],
            statements=[
                judge.StatementVerdict(field="change_summary", statement="Table 2 was corrected.", covered_by=[1]),
                judge.StatementVerdict(field="recommendation", statement="Ask the authors.", covered_by=[]),
                # a claim number that doesn't exist covers nothing
                judge.StatementVerdict(field="evaluation", statement="The dose is high.", covered_by=[9]),
            ],
            absences=[],
        ),
    )
    assert [s["pass"] for s in grade["statements"]] == [True, False, False], grade
    r = scored(ev, grade)
    assert r["uncovered"] == 2, r
    assert "uncovered_statement" in r["verdict"]["failures"], r["verdict"]


@check
def an_invented_claim_quote_counts_as_invented():
    ev = run()
    ev["assessment"]["actions"]["claims"][0]["passages"] = [passage("stored paper", "caffeine abolished memory")]
    r = scored(ev)
    assert (r["quotes"], r["invented"]) == (3, 1), r
    assert "invented_quote" in r["verdict"]["failures"], r["verdict"]


@check
def a_claim_quote_naming_the_wrong_source_counts_as_misattributed():
    ev = run()
    ev["assessment"]["change"]["claims"][0]["passages"] = [passage("draft", "The effect size in Table 2 was corrected.")]
    assert scored(ev)["misattributed"] == 1


@check
def a_quote_from_a_notices_crossref_record_is_not_invented():
    case = copy.deepcopy(CASE)
    case["notice"]["crossref_record"] = {"title": ["Correction to: Caffeine and memory"]}
    ev = run()
    ev["assessment"]["change"]["claims"][0]["passages"] = [passage("10.1000/notice", "Correction to: Caffeine and memory")]
    found = score.check_quotes(ev, case)
    assert (found["invented"], found["misattributed"]) == (0, 0), found


@check
def a_run_with_no_claims_key_has_none_of_the_three_failures():
    ev = without_passages(run(), "change", 0)
    del ev["claims"]
    for step in ("change", "impact", "actions"):
        del ev["assessment"][step]["claims"]
    r = scored(ev, {"passages": [{"pass": False}], "statements": [{"pass": False}]})
    assert (r["unlinked"], r["unbacked"], r["uncovered"]) == (None, None, None), r
    assert not {"unlinked_claim", "unbacked_passage", "uncovered_statement"} & set(r["verdict"]["failures"]), r["verdict"]


@check
def a_run_with_claims_not_yet_judged_has_only_the_unlinked_count():
    r = scored(without_passages(run(), "change", 0), {})
    assert (r["unlinked"], r["unbacked"], r["uncovered"]) == (1, None, None), r


@check
def the_claims_grading_shows_no_statement_for_a_step_that_never_ran():
    ev = {**run(), "evaluation": None, "recommendation": None}
    shown = judge.passage_prompt(ev)
    assert "evaluation: (none)" in shown, shown
    assert "not meaningful" not in shown, shown


@check
def the_claims_grading_says_where_each_passage_comes_from():
    ev = run()
    ev["claims"][0]["passages"][0]["target"] = "document"
    ev["claims"][1]["passages"][0]["target"] = "draft"
    ev["claims"][2]["passages"][0]["target"] = None
    shown = judge.passage_prompt(ev)
    assert 'for claim 1, from a fetched document: "The effect size' in shown, shown
    assert 'for claim 2, from the researcher\'s draft: "We chose' in shown, shown
    assert 'for claim 3, from an unknown source: "200 mg' in shown, shown


@check
def a_claim_the_judge_says_only_states_an_absence_is_not_unlinked():
    ev = without_passages(run(), "change", 0)
    grade = all_pass_grade(ev)
    assert scored(ev, grade)["unlinked"] == 1
    grade = judge.passage_grade(
        ev,
        judge.PassageVerdicts(passages=[], statements=[], absences=[1]),
    )
    assert grade["absent_claims"] == ["change-0"], grade
    assert scored(ev, grade)["unlinked"] == 0


@check
def an_absence_on_a_claim_with_passages_or_no_such_claim_counts_for_nothing():
    grade = judge.passage_grade(run(), judge.PassageVerdicts(passages=[], statements=[], absences=[1, 9]))
    assert grade["absent_claims"] == [], grade


@check
def a_claim_the_model_marks_an_absence_is_not_unlinked():
    ev = without_passages(run(), "change", 0)
    ev["assessment"]["change"]["claims"][0]["absence"] = True
    assert scored(ev, all_pass_grade(ev))["unlinked"] == 0
    assert scored(ev)["unlinked"] == 0


@check
def the_judge_sees_the_kept_sentences_marked_fact_or_advice():
    ev = run()
    ev["assessment"]["actions"]["recommendation"] = [
        {"text": "The dose was corrected.", "kind": "fact", "claims": [1]},
        {"text": "The whole paper is wrong.", "kind": "fact", "claims": []},
        {"text": "Re-check the dose.", "kind": "advice", "claims": []},
    ]
    shown = judge.answer(ev)
    assert "recommendation: \n  - [fact] The dose was corrected.\n  - [advice] Re-check the dose." in shown, shown
    assert "The whole paper is wrong." not in shown, shown
    # a step without a sentence list (before PROMPT_VERSION 11) shows its stored text
    assert "evaluation: The draft relies on the dose." in shown, shown


@check
def a_quote_of_a_notices_update_to_line_is_not_invented():
    case = copy.deepcopy(CASE)
    case["notice"]["update_to_includes_paper"] = True
    ev = run()
    ev["assessment"]["change"]["claims"][0]["passages"] = [passage("10.1000/notice", "update_to_includes_paper: true")]
    found = score.check_quotes(ev, case)
    assert (found["invented"], found["misattributed"]) == (0, 0), found


def main() -> None:
    failed = 0
    for fn in CHECKS:
        try:
            fn()
            print(f"ok    {fn.__name__}")
        except AssertionError as exc:
            failed += 1
            print(f"FAIL  {fn.__name__}: {exc}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
