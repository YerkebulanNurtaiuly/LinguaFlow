# Deploying LinguaFlow (free tier: Render + Supabase)

LinguaFlow runs as **one free environment** on [Render](https://render.com), with the
database on a free external Postgres ([Supabase](https://supabase.com) or
[Neon](https://neon.tech)), defined in [`render.yaml`](../../render.yaml) (a Render
Blueprint). This guide covers the first deploy, the settings it needs, the free-tier
caveats, and day-to-day operation.

> This is a public-MVP setup chosen for **zero cost**, not for speed or high availability.
> See [Free-tier caveats](#free-tier-caveats) for what you are trading away.

## What runs where

```
browser ──HTTPS──▶ linguaflow-web   (public web service: nginx + the built app)
                        │  proxies /api and /actuator/health to the backend
                        ▼
                   linguaflow-api    (public web service: Spring Boot, one instance)
                        │
                        ▼
                   Supabase / Neon   (external PostgreSQL 16, reached over TLS)
```

- **One origin for the browser.** nginx serves the app and proxies `/api` to the backend,
  so the browser only ever talks to `linguaflow-web`. That keeps the `SameSite=Strict`
  refresh cookie, CORS and the strict CSP working unchanged — nothing is cross-origin.
- **The backend is public** (free plans have no private services), but every endpoint
  except health and the auth routes needs a JWT, and `/actuator/prometheus` needs the
  metrics token. nginx reaches it over Render's private network (`fromService`); if a free
  instance can't be reached that way, set `BACKEND_URL` to the backend's public URL instead
  (see [Environment variables](#environment-variables)).
- **One backend instance.** The login rate limits and the AI job worker live in its memory,
  so don't scale it out (see CLAUDE.md, AUTH-06).
- **Database:** an external free Postgres 16, reached through `DB_URL` / `DB_USERNAME` /
  `DB_PASSWORD` (a single libpq `DATABASE_URL` also works when `DB_URL` is unset).

## Free-tier caveats

| | What happens | What to do |
|---|---|---|
| **Cold starts** | A free Render service spins down after ~15 min idle. The next visitor waits ~1–2 min while the backend boots and runs Flyway. The AI job worker doesn't run while asleep. | Accept it, or keep the service warm with an external pinger (the Uptime workflow hits it every 10 min, which helps). |
| **Memory** | The backend gets **512 MB**. The JVM is tuned down for it (`JAVA_TOOL_OPTIONS` in `render.yaml`: 300 MB heap, serial GC, capped metaspace). It should boot, but it is tight. | If it gets OOM-killed (Render shows "Out of memory"), lower `-Xmx` further or move to a paid 1 GB+ plan. |
| **Supabase pause** | A free Supabase project is paused after ~1 week with no activity; the backend then can't connect. | Resume it from the Supabase dashboard. Neon auto-resumes on the next connection instead. |
| **No staging** | Pushes to `main` deploy straight to the one live environment. | Test locally with `docker compose up` before pushing. Roll back with the Redeploy workflow. |

## The database connection (important)

Flyway and Hibernate open real sessions and use advisory locks, so the connection must be a
**direct or session-mode** connection, **not** a transaction-mode pooler.

The Render Blueprint sets the database as **three separate variables** —
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — rather than one `DATABASE_URL`. A managed database
password frequently contains characters (`@ : / ?`) that would have to be percent-encoded
inside a libpq URL; passing the password as its own variable avoids that trap entirely, so
you paste the **raw** password unescaped. (A libpq `DATABASE_URL` is still accepted when
`DB_URL` is unset — `DatabaseUrlEnvironmentPostProcessor` splits it — but `DB_*` always wins.)

### Supabase

In the Supabase dashboard: **Connect → Direct → Session pooler** (or **Settings → Database**).
Read these from the **Session pooler** connection parameters:

- **host** `aws-0-<region>.pooler.supabase.com`, **port 5432**, **database** `postgres`,
  **user** `postgres.<project-ref>`. It is IPv4 and supports everything the app needs.
- **Do not** use the **Transaction pooler** (port **6543**) — PgBouncer in transaction mode
  breaks Flyway migrations and prepared statements.
- The direct connection (`db.<ref>.supabase.co:5432`) is IPv6-only and Render can't reach
  it, so the session pooler is the one to use.

Then set the three Dashboard values (TLS is required, hence `sslmode=require`):

- `DB_URL` = `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`
- `DB_USERNAME` = `postgres.<project-ref>`
- `DB_PASSWORD` = the raw database password (no escaping)

### Neon

Use the **pooled** host from the Neon dashboard: `DB_URL` =
`jdbc:postgresql://<pooled-host>/<db>?sslmode=require`, `DB_USERNAME` and `DB_PASSWORD` the
role and its password. Neon resumes a paused database automatically on the next connection.

## How a change reaches production (OPS-03)

Images are built **once**, by GitHub Actions, and deployed by digest; Render never builds
anything (it only pulls images, so it uses no free build minutes).

1. **CI** (`ci.yml`) passes for a push to `main`.
2. **Deliver** (`deliver.yml`):
   - builds the backend and frontend images with the commit recorded in them, and pushes
     them to GitHub's registry as `ghcr.io/<owner>/linguaflow-{backend,frontend}`, tagged
     with the commit and `production`;
   - deploys them **by digest** through Render's deploy hooks, backend first;
   - with `RENDER_API_KEY`, follows each deploy until it's live;
   - checks that `/version.txt` names the commit and `/actuator/health` is UP.

Flyway runs when the backend starts, so the database is migrated as part of each deploy.

## First deploy

You need a Render account, a Supabase (or Neon) account, and admin access to the GitHub
repository. The order matters: Render can only create a service once its image exists.

1. **Create the database.** In Supabase, create a project; note its password. Read the
   session-pooler `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` as
   [above](#the-database-connection-important).
2. **Build the first images.** Push to `main` (or re-run the latest CI). Once CI passes,
   **Deliver** pushes `:production` images and then stops at the missing deploy hooks; that's
   expected the first time.
3. **Give Render read access to the images.** The GHCR packages are private.
   - On GitHub, create a classic personal access token with only `read:packages` (or a
     fine-grained token with read access to packages).
   - In Render, go to **Settings → Registry Credentials** and add it as a GitHub credential
     named `ghcr-linguaflow`, the name `render.yaml` uses.
4. **Create the Blueprint.** In the Render Dashboard, choose **New → Blueprint**, connect
   the repository and pick `main`. Render reads `render.yaml` and lists the two services.
5. **Fill in the values Render asks for** (the `sync: false` variables):
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — the session-pooler values from step 1.
   - `AI_API_KEY` — your Anthropic API key (from your free token credit at
     [console.anthropic.com](https://console.anthropic.com)). It stays in Render's secret
     store; never commit it.
   - `FRONTEND_URL` — the public URL of `linguaflow-web`, e.g.
     `https://linguaflow-web.onrender.com`. Used for email links and as the allowed CORS
     origin. Correct it afterwards if Render assigned a different name.
6. **Apply.** Render pulls the images and starts the services. The backend runs the Flyway
   migrations at startup; `JWT_SECRET` and `METRICS_TOKEN` are generated by Render and stay
   stable across deploys.
7. **Connect GitHub Actions to Render** (GitHub → Settings → Secrets and variables →
   Actions):
   - **Secrets:** both **Deploy Hook** URLs, from each service's Settings in Render:
     `RENDER_DEPLOY_HOOK_PRODUCTION_API` and `RENDER_DEPLOY_HOOK_PRODUCTION_WEB`.
   - **Optionally** `RENDER_API_KEY` (Render → Account Settings → API Keys), so the workflow
     follows each deploy until it's live.
   - **Variables:** `PRODUCTION_URL`, the public web URL.
   - A deploy hook lets anyone redeploy that service; if one leaks, choose **Regenerate
     Hook** in Render and update the secret.
8. **Deploy for real.** Push a commit (or re-run Deliver). It now deploys both services.
9. **Check it:**
   - `https://<web-url>/healthz` answers `ok` (nginx);
   - `/version.txt` names the commit;
   - `/actuator/health` answers `{"status":"UP"}` (backend and database);
   - register a learner, add a word, and review it (filled in by Anthropic).
10. **Custom domain (optional):** add it under the web service's **Settings → Custom
    Domains**; Render issues the certificate. Then update `FRONTEND_URL` and `PRODUCTION_URL`.
11. **Turn on Dependabot security updates** under the repository's Settings → Code security
    (SEC-01).

## Environment variables

| Variable | Service | Where it comes from |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | backend | `render.yaml` |
| `PORT=8080` | both | `render.yaml`; the app and nginx bind it |
| `DB_URL` | backend | entered in the Dashboard; JDBC URL of the Supabase/Neon session pooler + `sslmode=require` |
| `DB_USERNAME` | backend | entered in the Dashboard; `postgres.<project-ref>` for Supabase |
| `DB_PASSWORD` | backend | entered in the Dashboard; the raw database password (no escaping) |
| `JWT_SECRET` | backend | generated by Render |
| `AI_API_KEY` | backend | entered in the Dashboard |
| `AI_MODEL=claude-opus-4-8` | backend | `render.yaml`; the Claude model the app runs on |
| `FRONTEND_URL` | backend | entered in the Dashboard |
| `JAVA_TOOL_OPTIONS` | backend | `render.yaml`; the 512 MB JVM tuning |
| `BACKEND_URL` | frontend | the backend's internal `host:port` (`fromService`); override with its public URL if private networking isn't available on free |
| `SENTRY_DSN` | backend | entered in the Dashboard; empty turns Sentry off (OPS-04) |
| `SENTRY_ENVIRONMENT=production` | backend | `render.yaml` |
| `METRICS_TOKEN` | backend | generated by Render; the bearer token for `/actuator/prometheus` |

Optional backend settings, any `linguaflow.*` property as an environment variable:
- `AI_MODEL` — set to `claude-opus-4-8` in `render.yaml`; change it (e.g. `claude-haiku-5-5` to
  cut cost on the free tier) by editing the Blueprint or overriding it in the Dashboard;
- `LINGUAFLOW_AI_QUOTA_DAILY_REQUESTS` — the daily AI calls per learner;
- `CORS_ALLOWED_ORIGINS` — to allow more origins than `FRONTEND_URL`.

## Deploying changes and rolling back

- **Deploys** follow `main` through **Deliver**. Every commit that passes CI is built and
  deployed; the run's summary lists the image digests.
- **Rolling back:** run **Redeploy to production** (Actions → Redeploy to production → Run
  workflow) with the previous good commit's SHA. Its images are still in GHCR, so nothing is
  rebuilt. Render's own **Rollback** button works too. Neither undoes database migrations, so
  a migration that needs undoing gets a new migration.
- **Backward-compatible migrations:** Render starts the new instance before stopping the old
  one, and Flyway runs before the backend listens, so a migration must be compatible with the
  version still running: add columns and tables first, remove old ones in a later release.
- **Never deploy by hand in the Dashboard** ("Deploy latest" would redeploy the moving
  `:production` tag). Use the workflows, so what runs is always a known commit.

## Backups

Free Postgres plans don't include point-in-time recovery, so take your own backups:

- **Supabase** can schedule daily backups on paid plans; on free, use the backup scripts in
  this repo (`scripts/db-backup.sh` with `SOURCE_DATABASE_URL` set to your session-pooler
  URL). The runbook is [backup-restore.md](backup-restore.md) (OPS-05).
- Keep at least one dump off-platform. Backups hold personal data — never commit them.

## Known gaps before launch

- **Email:** there's no SMTP mailer yet (`linguaflow.mail.mode=disabled` in prod), so
  verification and password-reset emails aren't sent.
- **Privacy page:** it needs the operator's name and contact details
  (`docs/security/privacy.md`).
- **Observability (OPS-04):** a Sentry DSN, a metrics scraper and the uptime variable still
  have to be set up; see [observability.md](observability.md).
- **Backups:** set up a recurring dump off-platform, since the free database has no recovery.
