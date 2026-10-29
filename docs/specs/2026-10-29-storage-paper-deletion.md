# Storage: paper deletion vs. concurrent writes, PDFs removed after commit

**Status:** draft
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

**Commands** (from `storage/`):
- Full test suite: `./mvnw test` (H2 in Postgres mode)
- Single test file: `./mvnw test -Dtest=<ClassName>`
- Typecheck: compiled by `./mvnw test`
- Lint: none

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |
| S2 | none |

Run order: S1, S2.

External blockers: OPEN Q1.

Git state: on `main`, working tree clean. Branch: `fix/storage-paper-deletion`.

## Open questions

1. **ASSUMED: answer to the losing write.** A snapshot, alert or report
   stored for a paper that a concurrent delete removed gets
   `404 No paper <id>`, the same as for a missing paper, not `500`.
   Affects S1.

## Suggested splits

No split. S1 and S2 both change `PaperDeletion.java`.

## Out of scope

- Writes made with the token of an account that was just deleted, and
  papers posted under a deleted or unknown `project_id`. Only the account
  holder has the token, and has no reason to use it after deleting the
  account (user) (`transient`).
- Changing the `DEV_TOOLS` default. It stays on, as DECISIONS.md
  2026-10-27 records (user: "no change") (`transient`).

## Subtasks

### S1: Lock the paper before deleting it

Fixes this review finding: `snapshots.paper_id` has no
`ON DELETE CASCADE` (V4). A snapshot stored between
`delete from snapshots` and `delete from papers` breaks the foreign key,
and the delete answers `500` (`transient`).

- **Goal:** Deleting a paper, clearing its history, clearing a project and
  clearing an account never fail because a snapshot, alert or report was
  stored at the same time.
- **Blocked by:** none
- **Files:**
  - `storage/src/main/java/com/g4t1/storage/paper/PaperDeletion.java`
  - `SnapshotService.java`, `AlertService.java`, `ReportService.java` and
    `ReportDocumentService.java`, for the `404` on a losing write
  - new test `storage/src/test/java/com/g4t1/storage/paper/PaperDeletionRaceTest.java`
- **Seams:**
  - `DELETE /papers/{id}`
  - `DELETE /internal/projects/{id}`
  - `DELETE /internal/users/{id}`
  - the internal snapshot, alert and report store endpoints
  - the public methods `PaperDeletion.delete` and
    `PaperDeletion.clearHistory` (service-level, like `AlertServiceRaceTest`)
- **Decisions:**
  - `delete` and `clearHistory` lock the paper row
    (`select ... for update`) before they delete anything. A concurrent
    store either commits first and is deleted with the paper, or waits
    for the lock and then fails (`transient`).
  - ASSUMED (Q1): the losing store answers `404 No paper <id>`.
- **Acceptance criteria:**
  - [ ] `PaperDeletionRaceTest`: another thread stores a snapshot while a
        delete holds the lock. Expected: the delete succeeds, and the paper
        and all its rows are gone.
  - [ ] `PaperDeletionRaceTest`: a snapshot store that loses the race gets
        `404`, not `500`.
  - [ ] `./mvnw test` passes.
- **Doc deltas:**
  - `docs/STORAGE.md` (deletion section): "Deleting a paper locks it
    first. A snapshot, alert or report stored at the same time is either
    deleted with the paper or refused with `404`."
  - `docs/CONTRACTS.md`: add the `404` for a paper deleted at the same
    time to the internal store endpoints, where it isn't listed yet.

### S2: Remove PDFs only after the delete commits

Fixes this review finding: PDFs are deleted inside the `@Transactional`
method before the commit. A failed commit rolls the rows back, but their
PDFs are already gone (`transient`).

- **Goal:** A delete that rolls back leaves every PDF on disk. A delete
  that commits removes them.
- **Blocked by:** none
- **Files:**
  - `storage/src/main/java/com/g4t1/storage/paper/PaperDeletion.java`
  - `storage/src/test/java/com/g4t1/storage/paper/PaperDeletionRaceTest.java`
    (or a new `PaperDeletionFilesTest.java`)
- **Seams:** `PaperDeletion.delete`, `PaperDeletion.clearHistory`,
  `DELETE /papers/{id}`.
- **Decisions:**
  - The file deletes are registered with
    `TransactionSynchronization.afterCommit` (`transient`).
- **Acceptance criteria:**
  - [ ] Test: the delete runs inside an outer transaction that is then
        rolled back. Expected: the paper's PDF and its report documents'
        PDFs are still on disk.
  - [ ] `PaperDeleteTest`: after a successful delete, the PDFs are gone.
  - [ ] `./mvnw test` passes.
- **Doc deltas:** `docs/STORAGE.md` (deletion section): "PDFs are removed
  from disk only once the delete has committed."

## Human checks

none
