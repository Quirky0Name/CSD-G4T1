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

Register or sign in at `/register` and `/signin`. The token User Management
returns is kept in `localStorage` (24 hours) and sent as `Authorization:
Bearer` on every call to Storage Management and Updating, so a refresh
stays signed in. A `401` from either signs you out.


```sh
npm install
npm run dev
```

## Scripts

- `npm run dev` — Vite dev server with the proxy above.
- `npm run build` — typecheck (`tsc -b`) then build.
- `npm run lint` — ESLint.
- `npm test` — Vitest unit tests (`src/**/*.test.ts`, type-checked by `tsconfig.test.json`).

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
