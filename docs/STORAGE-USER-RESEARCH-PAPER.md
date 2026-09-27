# The researcher's own paper, one per project

**Status:** the upload is built. The researcher's GET and DELETE, and
Research Evaluation's read (`GET /internal/papers/{id}/research-paper`),
are next on `feat/storage-user-research-paper`.

Storage Management keeps the researcher's own paper (the draft they're
writing) for each of their projects, so Research Evaluation can judge what a
change in a tracked paper means for what the researcher is writing. It's a
PDF, stored the same way as a tracked paper's: the file on local disk, its
key in Postgres. The contract is in CONTRACTS.md ("Folders and projects",
`POST /research-paper`) and the reasoning in DECISIONS.md ("2026-09-27 — The
researcher's own paper, one per project").

## Folders and projects

- **A project is one folder**, and everything a user keeps outside folders
  is one more project of theirs: the "no folder" project.
- **`folder_id` null means no folder**, everywhere: in `papers`, in
  `research_papers` and in every response. There's no sentinel id. As
  input, leaving `folder_id` out means no folder, and so does `""` (Spring
  turns an empty string into a null `UUID`).
- **Storage keys a project by (owner, `folder_id`)**, not by `folder_id`
  alone. Folders belong to User Management, which doesn't exist yet, so
  Storage can't check who owns a folder id: any client can send any UUID.
  With the owner in the key, one user's upload can never replace or reach
  another user's research paper, even if they send the same folder id.
- **A tracked paper is in exactly one project:** its `papers.owner_id`
  plus its `papers.folder_id`. A user can't track the same DOI twice
  (`uq_papers_owner_doi`), so they can't have it in two folders either.
  Several users tracking one DOI each have their own paper row, snapshots
  and alerts, and Research Evaluation evaluates each user's row on its own.

## Where the code lives

All in `storage/`:

| What | Where |
|---|---|
| Table | `src/main/resources/db/migration/V6__create_research_papers.sql` |
| Entity | `src/main/java/com/g4t1/storage/research/ResearchPaper.java` |
| Repository | `research/ResearchPaperRepository.java` |
| Upload, replace | `research/ResearchPaperService.java` |
| `POST /research-paper` | `research/ResearchPaperController.java` |
| Response body | `research/ResearchPaperResponse.java` |
| PDF files on disk | `file/LocalFileStore.java` (shared with tracked papers) |
| PDF check | `file/Pdfs.java` (shared) |
| Tests | `src/test/java/com/g4t1/storage/research/` |

It's a package of its own, next to `paper/` and `alert/`, because a research
paper isn't a tracked paper: it has no DOI, no snapshots and no alerts, and
Updating never polls it.

## The `research_papers` table

| Column | Meaning |
|---|---|
| `id` | uuid, the research paper. Stays the same when the file is replaced |
| `owner_id` | the user, from the JWT's `sub` |
| `folder_id` | User Management's folder, a bare reference (no FK). null = the "no folder" project |
| `file_key` | the file's name in the upload folder (`<uuid>.pdf`) |
| `filename` | the name the file was uploaded with, for display only |
| `uploaded_at` | when the current file was uploaded |

`unique nulls not distinct (owner_id, folder_id)` is what makes it one per
project. `nulls not distinct` matters: a plain unique constraint lets any
number of `(owner, null)` rows through, so the "no folder" project wouldn't
be unique.

## How an upload runs

`POST /research-paper` → `ResearchPaperService.upload`:

1. Reject a file over `storage.max-pdf-size` (`413`) or one that doesn't
   start with `%PDF-` (`400`). Nothing has been stored yet.
2. Save the bytes with `LocalFileStore.save`, which returns a new random key.
3. In one transaction, look the project's row up with `SELECT ... FOR
   UPDATE` (`findForUpdateByOwnerIdAndFolderId`; a null `folder_id` becomes
   `folder_id is null`):
   - **no row:** insert one, and answer `201`;
   - **a row:** point it at the new key, filename and time (the id stays),
     and answer `200`.
4. After the commit, delete the file the row used to point at.

If step 3 fails, the new file is deleted and the error goes up; the
project keeps the research paper it had. The old file is only deleted once
the row points at the new one, so a failed write never loses the current
paper.

**Two uploads at once.** If the project has a row, the second upload waits
on the row lock and then replaces the first one's file, so the newest one
wins and no file is left behind. If neither had a row yet, both insert, the
unique constraint rejects one, and that one retries once in a new
transaction, where it finds the winner's row and replaces its file.

No GROBID or CrossRef call is made: a draft usually has no DOI, and nothing
reads its metadata yet.

## Gotchas

- **Needs Postgres 15 or later** for `unique nulls not distinct`. The local
  `postgres:17` in LOCAL_STORAGE_DB.md and Supabase both are. The tests run
  on H2 2.4, which supports it too.
- **Research papers share the upload folder** with tracked papers'
  PDFs, and the same rule applies: if you reset the database, empty the
  folder too, or rows will point at missing files (LOCAL_STORAGE_DB.md).
- **A file can be left behind** if the process dies between saving the new
  file and committing the row, or if deleting the old file fails (that's
  logged as a warning). A stray file only costs disk space; nothing points
  at it.
- **Don't call `upload` inside another transaction.** It opens its own
  transactions (`TransactionTemplate`), and the race retry needs a fresh one.
  Inside an outer transaction the retry would join the one already marked
  for rollback. The controller calls it without one.
- **`filename` is stored as the client sent it**, with no path stripping.
  It's display text only; never build a path from it (the file's path comes
  from `file_key`).
- **Folder ids aren't checked.** Any UUID is accepted, as for
  `POST /papers`. That's safe because the owner is part of the key, but a
  typo in a folder id silently makes a new project.

## TODO for other owners

- **User Management / frontend:** once folders exist, send their real
  ids. The upload is `multipart/form-data` with `file` and an optional
  `folder_id`; leave `folder_id` out for the "no folder" project.
- **Later, if a tracked paper should be in several folders:** that needs a
  papers ↔ folders join table in place of `papers.folder_id`, a change to
  `POST /papers`, its response and the one-DOI-per-user rule, and a decision
  on whether alerts become per project (DECISIONS.md, 2026-09-27, Rejected).
