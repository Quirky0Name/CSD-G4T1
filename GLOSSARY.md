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

**Alert severity**:
How serious a change of that type usually is. Fixed by the change type alone.
_Avoid_: Severity (on its own)

**Change severity**:
How serious one particular change really is for anyone relying on the paper.
_Avoid_: Severity (on its own)
