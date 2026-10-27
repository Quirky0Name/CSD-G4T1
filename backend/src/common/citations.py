"""When a rise in a paper's citation count between two polls is worth an alert. Shared, so
Updating's nudge and Research Evaluation's alert can't disagree (docs/DECISIONS.md, 2026-10-26)."""

# both floors must be cleared: the absolute one stops 1 -> 2 counting as +100 %, the relative
# one stops a heavily cited paper's ordinary growth counting as a jump
MIN_CITATION_JUMP = 10
MIN_CITATION_JUMP_PERCENT = 20


def is_citation_jump(previous: int, current: int) -> bool:
    rise = current - previous
    return rise >= MIN_CITATION_JUMP and rise * 100 >= MIN_CITATION_JUMP_PERCENT * previous
