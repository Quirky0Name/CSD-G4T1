"""impact/prompts.py: what step 2 attaches, and what each step's task asks for (claims)."""

from research_evaluation.impact import prompts
from research_evaluation.impact.llm import Pdf
from research_evaluation.impact.prompts import (
    ACTIONS_TASK,
    CHANGE_TASK,
    IMPACT_TASK,
    NO_DRAFT,
    impact_prompt,
    pdfs_record,
)
from research_evaluation.impact.schemas import ChangeAssessment
from research_evaluation.storage import PaperDetails

PAPER = PaperDetails(
    doi="10.1016/j.ijantimicag.2020.105949",
    title="Hydroxychloroquine and azithromycin as a treatment of COVID-19",
    publication_year=2020,
    journal="International Journal of Antimicrobial Agents",
    authors=["Gautret, P."],
)
CHANGE = ChangeAssessment.model_validate(
    {
        "alerts": [],
        "relations": None,
        "severity": "high",
        "summary": "The paper was retracted.",
        "claims": [{"text": "The paper was retracted.", "passages": []}],
    }
)
DRAFT = b"%PDF-1.7 the researcher's draft"
PAPER_PDF = b"%PDF-1.7 the stored paper"


def pdfs(parts) -> list[bytes]:
    return [part.data for part in parts if isinstance(part, Pdf)]


def texts(parts) -> list[str]:
    return [part for part in parts if isinstance(part, str)]


def test_step_two_attaches_the_draft_then_the_stored_paper():
    prompt = impact_prompt(PAPER, CHANGE, DRAFT, PAPER_PDF)

    assert pdfs(prompt.parts) == [DRAFT, PAPER_PDF]
    labels = [t for t in texts(prompt.parts) if t.startswith("ATTACHED PDF:")]
    assert labels[0].startswith("ATTACHED PDF: THE RESEARCHER'S DRAFT")
    assert labels[1].startswith("ATTACHED PDF: STORED PAPER")
    assert prompt.parts[-1] == IMPACT_TASK
    assert pdfs_record(prompt) == {"attached": ["draft", "stored paper"], "left_out": []}


def test_step_two_with_no_draft_attaches_nothing():
    prompt = impact_prompt(PAPER, CHANGE, None, PAPER_PDF)

    assert pdfs(prompt.parts) == []
    assert NO_DRAFT in prompt.parts
    assert prompt.attached == []


def test_step_two_leaves_the_stored_paper_out_when_both_are_over_the_budget(monkeypatch):
    monkeypatch.setattr(prompts, "MAX_INLINE_PDF_BYTES", len(DRAFT) + len(PAPER_PDF) - 1)

    prompt = impact_prompt(PAPER, CHANGE, DRAFT, PAPER_PDF)

    assert pdfs(prompt.parts) == [DRAFT]
    assert pdfs_record(prompt) == {
        "attached": ["draft"],
        "left_out": [{"pdf": "stored paper", "why": "over the size budget"}],
    }


def test_step_two_records_a_stored_paper_that_isnt_there():
    prompt = impact_prompt(PAPER, CHANGE, DRAFT, None)

    assert pdfs(prompt.parts) == [DRAFT]
    assert pdfs_record(prompt)["left_out"] == [{"pdf": "stored paper", "why": "not stored"}]


def test_each_task_asks_for_one_claim_per_factual_statement():
    for task in (CHANGE_TASK, IMPACT_TASK, ACTIONS_TASK):
        assert "one claim per factual statement" in task


def test_step_ones_task_names_an_alerts_line_as_a_source():
    assert '"alert <id>"' in CHANGE_TASK
