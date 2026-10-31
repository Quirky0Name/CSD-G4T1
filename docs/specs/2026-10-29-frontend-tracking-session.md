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
- The refetch on every route change. The fetch effect in `tracking.tsx`
  depends on `navigate`, which react-router recreates whenever the path
  changes, so moving between `/`, `/project` and `/alerts` reruns the
  fetch. S1 does not change this.

## Subtasks

### S1: TrackingProvider only inside RequireAuth, keyed by user

Fixes this review finding: `TrackingProvider` wraps `/signin` and
`/register`, so it sends a `/papers` request before login, which gets a
401. It never remounts, so `seenAlertIds` survives sign-out. The next user
to sign in then gets a toast for every existing `new` alert on their first
load (`transient`).

The 401 was never visible. Login changes the path, which reruns the fetch
with the token. The Projects page (`/`), where login lands, does not read
tracking state, so the error is replaced before any page that shows it
renders.
Papers and alerts did not carry over between users either, because the
fetch at `/signin` replaced them with the error state.

- **Goal:** Each signed-in user gets a fresh provider, so `seenAlertIds`
  starts empty and the first load seeds it without toasting. No paper or
  alert fetch runs without a session.
- **Blocked by:** none
- **Files:** `frontend/csd-frontend-vite/src/App.tsx`
- **Seams:** the route tree in `App.tsx`, and the `useTracking` callers.
- **Decisions:**
  - `TrackingProvider` wraps only the `RequireAuth` branch (around
    `AppLayout`). It is keyed by `session.user.id`, so a different user
    remounts it. `/signin` and `/register` are outside it (`transient`).
- **Acceptance criteria:**
  - [x] `npm run build` passes.
  - [x] `npm run lint` passes.
  - [x] `grep -rn "useTracking" frontend/csd-frontend-vite/src/pages/Signin.tsx frontend/csd-frontend-vite/src/pages/Register.tsx`
        returns nothing.
- **Doc deltas:** `docs/ARCHITECTURE.md` (frontend section, where it covers
  the shared refresh): "The shared paper and alert refresh runs only while
  a user is signed in, and starts fresh for each user."
  - Decision: `AppLayout` itself renders `TrackingProvider`, keyed by
    `useAuth().session?.user.id`, instead of a separate wrapper route
    element. Why: `AppLayout` only renders under `RequireAuth`, so one
    component holds both the auth gate placement and the key. Needs no doc
    beyond the line above; the comment on `AppLayout` records it.
  - Decision: the provider wraps `Sidebar` as well as the `Outlet`. Why: the
    whole signed-in shell then shares one provider, so any future tracking
    use in the sidebar works. Needs no doc.

## Human checks

- [ ] **S1:** Run the stack. Open `/signin` with no session. Expected: the
      devtools Network tab shows no `/papers` request.
- [ ] **S1:** Sign out, then sign in as a second user who has a `new`
      alert. Expected: no toast fires for the second user's existing
      alerts. (The old build toasts every one of them.)
