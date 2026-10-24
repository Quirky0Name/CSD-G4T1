# CSD-G4T1 frontend

React + TypeScript + Vite, talking to Storage Management and Updating.
See [docs/CONTRACTS.md](../../docs/CONTRACTS.md) for the full API and
its "Frontend ↔ Storage Management" table for the
endpoint-by-feature list this frontend was built against.

## Running against the services

Neither service sends CORS headers, so in dev this app talks to them
through Vite's proxy (`vite.config.ts`): `/api` forwards to Storage
Management, `/updating` to Updating. Both need to be running:

- Storage Management on `localhost:8081` — see
  [docs/SETUP.md](../../docs/SETUP.md), "Running everything in
  Docker" or "Storage Management without Docker".
- Updating on `localhost:8001` — see the repo root
  [SETUP.md](../../docs/SETUP.md), pointed at that same Storage
  Management (`SM_BASE_URL=http://localhost:8081`).
- Both need the same `JWT_SECRET`.

Copy `.env.example` to `.env.local` (gitignored) and set
`VITE_DEMO_TOKEN` to a user token for that `JWT_SECRET`. There's no login
yet (docs/CONTRACTS.md, "Auth"), so every request in this app uses that
one demo user.

On Linux/macOS, from `backend/`, with `JWT_SECRET` set to the same value
Storage Management is running with:

```sh
uv run python -c "
import base64, os, time
import jwt
print(jwt.encode(
    {'sub': '11111111-1111-1111-1111-111111111111', 'exp': int(time.time()) + 30 * 86400},
    base64.b64decode(os.environ['JWT_SECRET']),
    algorithm='HS256',
))
"
```

(On Windows, use the PowerShell snippet in
[docs/SETUP.md](../../docs/SETUP.md), "A demo user
token".) The token lasts 30 days; change `sub` for a different demo
user. Updating's `POST /run-poll` takes no token in sprint 1.

Then:

```sh
npm install
npm run dev
```

## Scripts

- `npm run dev` — Vite dev server with the proxy above.
- `npm run build` — typecheck (`tsc -b`) then build.
- `npm run lint` — ESLint.
