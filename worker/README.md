# ZPlanner Cloudflare Worker API

The Android client calls this Worker over HTTPS. Worker owns authentication and task access; D1 stores users, sessions, and tasks. The API never accepts `ownerId` from a client. Protected task queries derive the owner from a verified session token.

## Local development

1. Install Node.js 20 or newer.
2. From `worker/`, run `npm install`.
3. Copy `.dev.vars.example` to `.dev.vars` and replace `AUTH_SECRET` with a random value of at least 32 characters. `.dev.vars` is ignored by Git.
   To test password reset email, add a Brevo API key and a verified sender email address. Brevo must approve the account for sending. Never commit the API key.
4. Apply the local D1 migration: `npm run db:apply:local`.
5. Start the Worker: `npm run dev`.
6. From the repository root, build and install the app on a running Android emulator with the local API URL:

   ```powershell
   .\gradlew.bat :app:installDebug "-PAPI_BASE_URL=http://10.0.2.2:8787"
   ```

   Use `http://127.0.0.1:8787` to reach the local Worker from the host. Sign out when switching between local and deployed APIs so you can sign in again against the selected API.

Local Wrangler D1 data is separate from Cloudflare production data. Do not use `--remote` until the Cloudflare database has been created and its generated ID has replaced the placeholder in `wrangler.jsonc`.

## API

| Method | Path | Auth | Purpose |
|---|---|---:|---|
| GET | `/health` | No | Worker and D1 health |
| POST | `/auth/register` | No | Register with `{ "email", "password" }` |
| POST | `/auth/login` | No | Login; returns a 7-day bearer token |
| POST | `/auth/password-reset/request` | No | Send an 8-digit code for a password account; always responds generically |
| POST | `/auth/password-reset/confirm` | No | Verify `{ "email", "code", "newPassword" }` and revoke old sessions |
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
   Before enabling password reset, verify a sender address and activate transactional email in Brevo, then set `BREVO_API_KEY` and `RESET_EMAIL_FROM` with `npx wrangler secret put`. Apply `0004_password_reset.sql` to remote D1 before deploying. Do not put the API key in `wrangler.jsonc` or Android.
4. Deploy with `npm run deploy` and check the returned HTTPS `workers.dev` URL at `/health`.
5. If the deployment URL changes, pass it to Gradle with `-PAPI_BASE_URL=https://your-worker.workers.dev` when building the Android app.

The production database and public Worker are not created by local setup. Review Cloudflare account and usage limits before deploying.

## Build a demo APK

Set `JAVA_HOME` to a JDK 17 installation and install Android SDK Platform 35 through Android Studio's SDK Manager. From the repository root, run:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug-signed APK is written to `app/build/outputs/apk/debug/app-debug.apk`. By default it uses `https://zplanner-api.lucbao045.workers.dev/`, so the demo can run on a device or emulator with internet access. `API_BASE_URL` is set at build time. A command-line `-PAPI_BASE_URL=...` override applies to that build, and a trailing slash is optional.

On macOS or Linux, use `./gradlew` in place of `.\gradlew.bat`.
