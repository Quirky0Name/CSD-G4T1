# CSD-G4T1 frontend

React + TypeScript + Vite, talking to Storage Management and Updating.
See [docs/CONTRACTS.md](../../docs/CONTRACTS.md) for the full API and
its "Frontend ↔ Storage Management" table for the
endpoint-by-feature list this frontend was built against.

## Running against the services

None of the services send CORS headers, so in dev this app talks to them
through Vite's proxy (`vite.config.ts`): `/auth` forwards to User
Management, `/api` to Storage Management, `/updating` to Updating. They
need to be running:

- User Management on `localhost:8080` — see
  [docs/SETUP.md](../../docs/SETUP.md), "User Management".
- Storage Management on `localhost:8081` — see
  [docs/SETUP.md](../../docs/SETUP.md), "Running everything in
  Docker" or "Storage Management without Docker".
- Updating on `localhost:8001` — see the repo root
  [SETUP.md](../../docs/SETUP.md), pointed at that same Storage
  Management (`SM_BASE_URL=http://localhost:8081`).
- All need the same `JWT_SECRET`.

Register or sign in at `/register` and `/signin`. The access token User
Management returns is kept in `localStorage` (1 hour) and sent as
`Authorization: Bearer` on every call to Storage Management, Updating and
User Management. A `401` triggers one refresh (`POST /auth/refresh`, through
the shared `refreshSession()`) and one retry of the request with the new
token; a failed refresh, or a second `401`, signs you out. The refresh token
(1 week, sliding) is an `HttpOnly` cookie the page can't read, so
`loadSession` keeps a session whose access token has expired. Tabs share one
refresh at a time through `navigator.locks`; where the browser has none (the
app opened over plain HTTP on a LAN address) each tab refreshes by itself.
**Sign out** sends `POST /auth/logout` and clears the session at once,
without waiting for the answer. The token and `401` handling is in
`src/authRetry.ts` and `src/refresh.ts`.


```sh
npm install
npm run dev
```

## Scripts

- `npm run dev` — Vite dev server with the proxy above.
- `npm run build` — typecheck (`tsc -b`) then build.
- `npm run lint` — ESLint.
- `npm test` — Vitest unit tests (`src/**/*.test.ts`, type-checked by `tsconfig.test.json`).
  Component tests (`*.test.tsx`, also type-checked by `tsconfig.test.json`; `tsconfig.app.json` excludes them) use `@testing-library/react`, `@testing-library/dom` and `jsdom`. A component test opts into jsdom with a `// @vitest-environment jsdom` first line, so the other tests stay in node. `ReportContext.test.tsx` mocks `PdfPane`, which needs a real canvas.

## Projects

`/` lists the default project and the researcher's own, which can be created,
renamed and deleted there (User Management's `/projects`). A project opens at
`/project/:projectId`, and the default project at `/project`, because the dev
proxy and Caddy send `/projects` to User Management. Each has its own papers and
draft. Poll now still polls all of the researcher's papers.

## Report page and claims

`/alerts` and `/reports` are separate pages. `/reports/:paperId/:reportId`
shows one group of a report's claims at a time, under tabs, and highlights each
passage: the draft sentence above the cited source (pdf.js,
`src/highlight/`). The selected claim is kept in `?claim=`. The page is
lazy-loaded, so pdf.js downloads only when a report is opened.

Storage on this branch doesn't send `claims` yet. `listReports` normalises
every report to the contract (`toUserReport` in `src/api.ts`), and a dev
mode fake report at `assessed` gets the example's claims from
`src/mocks/fakeReportClaims.ts`. Claims the server sends always replace the
mock. `src/mocks/fakeReportClaims.test.ts` checks every quote against the
example PDFs in `storage/src/main/resources/dev/fake-report/`, so change
the fixture and those files together. Where each passage is shown is
decided in one place, `src/highlight/sources.ts`: when Storage gains the
researcher PDF endpoints, only it changes.

## Sign-up questionnaire and profile

Sign-up has two screens: the account, then the questionnaire
(`ProfileQuestions`), and one register request creates the account with its
profile. Every answer is optional. `/profile` (a `Your profile` entry in the
sidebar's account menu, researchers only; the admin is sent to `/dev`) shows
the same questions to view, edit and clear the saved profile
(`GET`/`PUT /auth/me/profile`). An existing account without a profile is
prompted only by that page's empty state, not elsewhere.

The questions' lists are bundled, with no runtime fetch and no server-side
search; search runs in the browser. The occupations, work types and research
fields are in `src/profile/options.ts` and also in User Management's
`users/src/main/resources/profile/options.json`, because the two are built
from separate Docker contexts. `options.test.ts` reads the JSON and fails if
the two differ, so change both together. The interest suggestions (252
OpenAlex subfield names, frontend only, since the server accepts any valid
interest) are in `src/profile/interestSuggestions.ts`. The form, tag and
two-screen logic is pure and tested in `src/profile/` (the Vitest setup has
no DOM); `TagInput` is the shared, accessible tag input. The limits mirror
the server's (docs/CONTRACTS.md, `PUT /auth/me/profile`).
