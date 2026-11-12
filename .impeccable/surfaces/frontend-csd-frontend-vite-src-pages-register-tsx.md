---
version: 1
slug: "frontend-csd-frontend-vite-src-pages-register-tsx"
primary_target: "frontend/csd-frontend-vite/src/pages/Register.tsx"
related_targets: ["frontend/csd-frontend-vite/src/components/ProfileQuestions.tsx"]
---

# Sign-up (/register)

Scope: the researcher sign-up flow only; /signin and the app shell are untouched. Mode: Operate (the visitor creates an account and, optionally, answers five profile questions). Build path: code-led.

Audience and job: a researcher creating an account, then optionally telling the app about their work so impact reports are tailored. Every question is optional; one register request at the end creates the account with its profile (docs/CONTRACTS.md). Constraints: the exact question wording, placeholder and Gemini notice from the spec; keyboard and screen-reader paths; no request before Create account.

Pinned by the user: the component style of their reference (a two-panel card, illustration left, form right, pill buttons, one question per screen with a counter and outlined answer tiles, animated steps), in the app's existing dark palette.

## Direction contract

THESIS: sign-up reads as a short guided interview, one question at a time, not a long form; it refuses the stacked single-column form with every field visible at once.

OWN-WORLD: the app's own ground, gray-950, with a gray-900 card and gray-800 panel, white/10 hairlines, indigo-500 as the one accent for progress, selection and the primary pill. Rounded-2xl card, fully rounded pill buttons, outlined tiles that fill indigo-tinted when chosen. One authored flat illustration (a researcher with a tall paper sheet and a status badge) whose badge changes per step.

STORY: the visitor sees they are two minutes from an account, answers or skips each question with an obvious next step, reviews what they gave, and creates the account knowing every answer is optional and editable later.

FIRST VIEWPORT: centred card, about 960px wide on desktop. Left 45%: the illustration on a gray-800 panel with a one-line caption and progress dots under it. Right 55%: the app name top-left, "Already have an account? Sign in" pill top-right, heading "Create your account", the email, password and retype fields, and a full-width-on-mobile indigo pill "Continue". On a phone the illustration panel collapses to a short band above the form.

FORM: pinned direction from the user's reference, not a rolled one; the signature move is the step change, where the question pane slides with a short blur-to-sharp settle in the direction of travel while the illustration's badge swaps, and the counter ("2 / 5") ticks.
seed:dfe3b93a3d3043ba

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
