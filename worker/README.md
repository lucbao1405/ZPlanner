# ZPlanner Cloudflare Worker API

The Android client calls this Worker over HTTPS. Worker owns authentication and task access; D1 stores users, sessions, and tasks. The API never accepts `ownerId` from a client. Protected task queries derive the owner from a verified session token.

## Local development

1. Install Node.js 20 or newer.
2. From `worker/`, run `npm install`.
3. Copy `.dev.vars.example` to `.dev.vars` and replace `AUTH_SECRET` with a random value of at least 32 characters. `.dev.vars` is ignored by Git.
4. Apply the local D1 migration: `npm run db:apply:local`.
5. Start the Worker: `npm run dev`.
6. Use `http://10.0.2.2:8787` as the API base URL from the Android emulator; use `http://127.0.0.1:8787` from the host.

Local Wrangler D1 data is separate from Cloudflare production data. Do not use `--remote` until the Cloudflare database has been created and its generated ID has replaced the placeholder in `wrangler.jsonc`.

## API

| Method | Path | Auth | Purpose |
|---|---|---:|---|
| GET | `/health` | No | Worker and D1 health |
| POST | `/auth/register` | No | Register with `{ "email", "password" }` |
| POST | `/auth/login` | No | Login; returns a 7-day bearer token |
| GET | `/auth/me` | Yes | Current user |
| POST | `/auth/logout` | Yes | Revoke current session |
| GET | `/tasks?date=YYYY-MM-DD` | Yes | List tasks for one local calendar date |
| GET | `/tasks?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&status=todo` | Yes | Filter by inclusive date range and optional status |
| POST | `/tasks` | Yes | Create task |
| PUT | `/tasks/:id` | Yes | Replace task fields |
| DELETE | `/tasks/:id` | Yes | Delete task |

Task JSON fields: `title`, `description`, `date`, `startTime`, `endTime`, `priority` (`low|medium|high`), `status` (`todo|in_progress|done`). Responses use `{ "task": ... }` or `{ "tasks": [...] }`; errors use `{ "error": { "code", "message" } }`.

## Cloudflare deployment

1. Create a D1 database named `zplanner` with Wrangler and copy the returned database ID into `wrangler.jsonc`.
2. Apply migrations to that database with `npx wrangler d1 migrations apply zplanner --remote`.
3. Set the production signing key with `npx wrangler secret put AUTH_SECRET` (never commit it or put it in the APK).
4. Deploy with `npm run deploy` and check the returned HTTPS `workers.dev` URL at `/health`.
5. Add that HTTPS URL as the Android `API_BASE_URL` build configuration. Workers provide a public HTTPS endpoint, not a dedicated static IPv4 address.

The production database and public Worker are not created by local setup. Review Cloudflare account and usage limits before deploying.
