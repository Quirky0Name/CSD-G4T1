# Research Assistant

A tool that watches the papers a researcher cites, detects meaningful changes to them, and judges what each change means for the researcher's own work.

## Language

### Projects and papers

**Project**:
One piece of research a researcher is working on: their draft and the papers they track for it.
_Avoid_: Folder

**Default project**:
The project a researcher's papers and draft belong to when they aren't put in any other project.
_Avoid_: No-folder project, no project

**Draft**:
The researcher's own paper in progress for a project, the work that impact is judged against. A project has at most one.
_Avoid_: Research paper, own paper, user paper, your paper

**Tracked paper**:
A published paper a researcher follows for changes, in one project. "Paper" on its own means the same.
_Avoid_: Cited source, citation source, source, work

### Polling

**Poll**:
One pass that re-checks tracked papers against their outside sources, either on the schedule or started by hand.
_Avoid_: Check, run, poll run, refresh, update

**Snapshot**:
What the outside sources said about a tracked paper at one poll. A paper's snapshots in order are its snapshot history.
_Avoid_: Background info, background metadata, background-info history

### Changes

**Notice**:
A statement a journal publishes about a tracked paper, such as a retraction or a correction. Crossref calls it an update.
_Avoid_: Update (on its own)

**Related document**:
A document investigation reached by following a typed link from a notice or new version, not from the paper's own notices.

**Citation jump**:
A rise in a tracked paper's citation count between two polls large enough to alert on: at least 10 new citations and at least 20 % more than before.
_Avoid_: Citation spike, citation change

**Alert severity**:
How serious a change of that type usually is. Fixed by the change type alone.
_Avoid_: Severity (on its own)

**Change severity**:
How serious one particular change really is for anyone relying on the paper.
_Avoid_: Severity (on its own)

### Researchers

**Profile**:
A researcher's answers to the sign-up questionnaire: occupation, research fields and interests, type of work, and how they like reports written. A researcher has at most one.
_Avoid_: Preferences (for the whole), user settings, questionnaire (for the saved answers)

**Profile block**:
The `<document kind="researcher_profile">` block that gives a profile to impact's steps 2 and 3.
_Avoid_: Profile prompt, user context

**Feedback**:
A researcher's verdict (fair, overstated, understated) and optional note on one assessed report.
_Avoid_: Rating, review, comment

**Feedback block**:
The `<document kind="researcher_feedback">` block that gives one piece of feedback to impact's steps 2 and 3.

### Search over the past

**Chunk**:
A piece of an alert's, report's, report document's or report feedback's text, stored with its embedding for search.

**Past timeline**:
A tracked paper's earlier alerts, reports and report documents, found by vector search, that impact's steps 1 and 2 read beside the current report.

### Dev mode

**Dev mode**:
A setting (`DEV_MODE`, off unless set to `true`) that gives the stack an admin account and the admin endpoints, for demos and testing.

**Admin**:
The account `admin`, which exists only in dev mode. It reads and changes any researcher's paper data and is not a researcher: it has no papers or projects.

**Fake report**:
A report, with its alerts and documents, copied onto a paper by the admin from one saved example instead of coming from a poll.
