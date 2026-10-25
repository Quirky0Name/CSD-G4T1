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
