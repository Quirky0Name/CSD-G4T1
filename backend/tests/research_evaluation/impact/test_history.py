"""The paper's past in impact (research_evaluation.impact.history): what is searched, and the
PAST TIMELINE section steps 1 and 2 get."""

from impact_prompt_support import CHANGE, DRAFT, sample_inputs, saved, serialise

from research_evaluation.impact.prompts import change_prompt, impact_prompt


def test_no_history_unchanged():
    inputs = sample_inputs()

    assert serialise(change_prompt(inputs).parts) == saved("step1")
    assert serialise(impact_prompt(inputs.paper, CHANGE, DRAFT)) == saved("step2")
