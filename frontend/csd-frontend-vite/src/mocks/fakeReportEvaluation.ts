// The evaluation fields dev mode's fake report leaves out on this branch, copied word for word from the
// example's evaluation (storage/src/main/resources/dev/fake-report/report.json). Keep them in step with it.
import type { AssessmentLevel } from '../api'

export const FAKE_CHANGE_SUMMARY: string = "The paper was first flagged with an expression of concern about its dose-response calculation, then retracted and replaced by a corrected version after the editors' investigation. The error is confined to the main effect size in Table 3 and section 3.3: the slope drops from 0.45 to 0.28 points per 10 mg, and its confidence interval from 0.30 to 0.60 to 0.15 to 0.41. The rest of the paper, including the iron status and adverse event results and the conclusion that fatigue reduction increases with dose, reads the same in the corrected version, and the notice says the finding that iron reduces fatigue is unchanged. This is serious for anyone quoting the size of the effect, who should cite the corrected version, especially as the copy at the original DOI still shows the old number with no retraction mark; the general direction of the finding can still be relied on."
export const FAKE_CHANGE_SEVERITY: AssessmentLevel = "medium"
export const FAKE_IMPACT_LEVEL: AssessmentLevel = "low"
