# Trivial review fixes: remove DebugJson, one ownership check for a paper

**Status:** draft
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

**Commands:**
- Full test suite: `./mvnw test` from `storage/`. The frontend has no test
  runner.
- Single test file: `./mvnw test -Dtest=<ClassName>` from `storage/`
- Typecheck: `npm run build` from `frontend/csd-frontend-vite/`. Java is
  compiled by `./mvnw test`.
- Lint: `npm run lint` from `frontend/csd-frontend-vite/`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | none |

Run order: S1, S2.

External blockers: none.

Git state: on `main`, working tree clean. Branch: `fix/trivial-review-fixes`.

## Suggested splits

S1 (frontend) and S2 (storage) share no dependency and no files. They are
kept together because the user grouped the trivial fixes into one spec.

## Out of scope

none

## Subtasks

### S1: Remove the DebugJson panel

Fixes this review finding: the "TEMPORARY DEBUG, REMOVE BEFORE MERGING"
panel was merged to `main`. It is rendered on the Alerts page and in
ReportList (`transient`).

- **Goal:** No debug panel on the Alerts page or in ReportList.
- **Blocked by:** none
- **Files:**
  - delete `frontend/csd-frontend-vite/src/components/DebugJson.tsx`
  - `frontend/csd-frontend-vite/src/components/AlertList.tsx`
  - `frontend/csd-frontend-vite/src/components/ReportList.tsx`
- **Seams:** the build.
- **Decisions:** none.
- **Acceptance criteria:**
  - [ ] `grep -rn "DebugJson" frontend/csd-frontend-vite/src` returns nothing.
  - [ ] `npm run build` passes.
  - [ ] `npm run lint` passes.
- **Doc deltas:** none.

### S2: One ownership check for a paper (prefactor)

Fixes this review finding: `PaperService.delete` repeats the
"someone else's paper gets the same 404" lookup that
`DevService.requireOwnPaper` already does (`transient`).

This changes no behaviour.

- **Goal:** `PaperService.delete` and `DevService` share one method that
  returns the owner's paper or answers `404 No paper <id>`.
- **Blocked by:** none
- **Files:**
  - `storage/src/main/java/com/g4t1/storage/paper/PaperService.java`
  - `storage/src/main/java/com/g4t1/storage/dev/DevService.java`
- **Seams:** `DELETE /papers/{id}`, and the `/dev` endpoints.
- **Decisions:**
  - The check moves to `PaperService` as the public method
    `Paper requireOwn(UUID ownerId, UUID paperId)`. `DevService` calls it,
    and its private `requireOwnPaper` is removed (`transient`).
- **Acceptance criteria:**
  - [ ] The existing tests pass unchanged: `./mvnw test`, which includes
        `PaperDeleteTest` and `DevToolsTest`.
  - [ ] `grep -rn "requireOwnPaper" storage/src/main` returns nothing.
- **Doc deltas:** none.

## Human checks

- [ ] **S1:** Open the Alerts page and a project's reports. Expected: no
      red debug block.
