# Frontend: track papers only while signed in, fresh for each user

**Status:** approved
**Repo:** `D:\SMU\CS203\project\CSD-G4T1`
**Context file to update:** none named

**Commands** (from `frontend/csd-frontend-vite/`):
- Full test suite: none (the frontend has no test runner)
- Single test file: none
- Typecheck: `npm run build` (runs `tsc -b`)
- Lint: `npm run lint`

## Dependencies and blockers

| Subtask | Blocked by |
|---|---|
| S1 | none |

Run order: S1.

External blockers: none.

Git state: on `main`, working tree clean. Branch: `fix/frontend-tracking-session`.

## Suggested splits

No split: one subtask.

## Out of scope

- Adding a frontend test runner. S1 is proven by build, lint, a grep and
  the Human checks (user) (`transient`).

## Subtasks

### S1: TrackingProvider only inside RequireAuth, keyed by user

Fixes this review finding: `TrackingProvider` wraps `/signin` and
`/register`, so it fetches before login. It also keeps the previous
user's papers, alerts and seen-alert ids after an account switch
(`transient`).

- **Goal:** No paper or alert fetch runs without a session. Each signed-in
  user gets a fresh provider: no papers, alerts or `seenAlertIds` carry
  over from the previous user.
- **Blocked by:** none
- **Files:** `frontend/csd-frontend-vite/src/App.tsx`
- **Seams:** the route tree in `App.tsx`, and the `useTracking` callers.
- **Decisions:**
  - `TrackingProvider` wraps only the `RequireAuth` branch (around
    `AppLayout`). It is keyed by `session.user.id`, so a different user
    remounts it. `/signin` and `/register` are outside it (`transient`).
- **Acceptance criteria:**
  - [ ] `npm run build` passes.
  - [ ] `npm run lint` passes.
  - [ ] `grep -rn "useTracking" frontend/csd-frontend-vite/src/pages/Signin.tsx frontend/csd-frontend-vite/src/pages/Register.tsx`
        returns nothing.
- **Doc deltas:** `docs/ARCHITECTURE.md` (frontend section, where it covers
  the shared refresh): "The shared paper and alert refresh runs only while
  a user is signed in, and starts fresh for each user."

## Human checks

- [ ] **S1:** Run the stack. Open `/signin` with no session. Expected: the
      devtools Network tab shows no `/papers` request.
- [ ] **S1:** Sign in. Expected: the Projects page loads the papers at once,
      without "Please sign in again."
- [ ] **S1:** Sign out, then sign in as a second user who has a `new`
      alert. Expected: the first user's papers never appear, and no toast
      fires for the second user's existing alerts.
