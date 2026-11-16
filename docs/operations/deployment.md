# Deploying LinguaFlow on Render (OPS-02)

LinguaFlow runs on [Render](https://render.com) as two environments, **staging** and
**production**, defined in [`render.yaml`](../../render.yaml) (a Render Blueprint). This
guide covers the first deploy, the settings each environment needs, and day-to-day
operation.

## What runs where

```
browser ──HTTPS──▶ linguaflow-web-*   (public web service: nginx + the built app)
                        │  /api, /actuator/health over Render's private network
                        ▼
                   linguaflow-api-*   (private service: Spring Boot, one instance)
                        │
                        ▼
                   linguaflow-db-*    (managed PostgreSQL 16, private network only)
```

- **HTTPS** is terminated by Render. nginx passes the browser's scheme, host and port on
  (`X-Forwarded-Proto`, `Host`, `X-Forwarded-Port`), so the backend sees requests as
  same-origin. It sends HSTS only over HTTPS.
- **The backend** is a private service, so it has no public URL. nginx resolves its
  internal address on every request, because Render gives the backend a new address on
  each deploy.
- **One backend instance.** The login rate limits and the AI job worker live in its
  memory, so don't scale it out before the rate limits move to a shared store (see
  CLAUDE.md, AUTH-06).
- **Database:** PostgreSQL 16, the version the tests run against, with no access from
  outside Render (`ipAllowList: []`). Its connection string reaches the backend as
  `DATABASE_URL`, which `DatabaseUrlEnvironmentPostProcessor` turns into the JDBC settings.

| | Staging | Production |
|---|---|---|
| Deploys | Automatically: the Deliver workflow deploys each commit that passes CI on `main` | By promotion: the Promote workflow deploys staging-tested images after an approval |
| AI | Scripted answers (`AI_PROVIDER=scripted`): no key, no cost | Anthropic, with `AI_API_KEY` |
| Protection | None | Environment protection on |
| Database | `0.1c-256mb`, 5 GB | `0.5c-1g`, 10 GB |
| Backend | `1c-2g` | `1c-2g` |
| Frontend | `0.5c-512mb` | `0.5c-512mb` |

The plans are a starting point; check current prices on Render's pricing page. The free
plans aren't used: a free database has no backups or recovery, and the JVM needs more
than 512 MB.

## How a change reaches production (OPS-03)

Images are built **once**, by GitHub Actions, and the exact images tested on staging are
the ones production runs. Render never builds anything.

1. **CI** (`ci.yml`) passes for a push to `main`.
2. **Deliver** (`deliver.yml`):
   - builds the backend and frontend images with the commit recorded in them, and pushes
     them to GitHub's registry as `ghcr.io/<owner>/linguaflow-{backend,frontend}`, tagged
     with the commit and `staging`;
   - deploys them **by digest** to staging through Render's deploy hooks, backend first;
   - with `RENDER_API_KEY`, follows each deploy until it's live;
   - checks that `<staging>/version.txt` names the commit and `/actuator/health` is UP.
3. **Promote to production** (`promote.yml`): run it by hand from GitHub → Actions. It
   promotes the commit given, or else whatever staging serves now. It waits for an
   approval on the `production` environment, tags that commit's images `production`
   **without rebuilding**, deploys them by digest and runs the same check against
   production.

Flyway runs when the backend starts, so the database is migrated as part of each deploy
(see [Deploying changes](#deploying-changes) for what that means for migrations).

## First deploy

You need a Render account with a payment method, and admin access to the GitHub
repository. Creating accounts, tokens and payment details is up to you. The order
matters: Render can only create a service once its image exists.

1. **Build the first images.** Push to `main` (or re-run the latest CI). Once CI passes,
   **Deliver** pushes `:staging` images. It skips the deploy, since there are no hooks yet.
2. **Tag them for production too.** Run **Promote to production** once with the commit
   Deliver built. It tags the images `:production`, then stops at the missing production
   hooks; that's expected the first time.
3. **Give Render read access to the images.** The packages in GHCR are private.
   - On GitHub, create a classic personal access token with only `read:packages`, or a
     fine-grained one with read access to packages.
   - In Render, go to **Settings → Registry Credentials** and add it as a GitHub
     credential named `ghcr-linguaflow`, the name `render.yaml` uses.
4. **Create the Blueprint.** In the Render Dashboard, choose **New → Blueprint**, connect
   the repository and pick `main`. Render reads `render.yaml` and lists the six resources.
5. **Fill in the values Render asks for.** These are the variables marked `sync: false`:
   - `FRONTEND_URL` for each backend: the public URL of its web service, for example
     `https://linguaflow-web-staging.onrender.com`. It's used for email links and as the
     allowed CORS origin. If Render gives the service a different URL (when the name is
     taken), correct it afterwards under the backend's **Environment** tab.
   - `AI_API_KEY` for `linguaflow-api-production`: the Anthropic API key. It stays in
     Render's secret store; never commit it.
6. **Apply.** Render creates the databases, then pulls the images and starts the services.
   - The backend runs the Flyway migrations at startup.
   - Each `JWT_SECRET` is generated by Render (`generateValue`) and stays stable across deploys.
7. **Connect GitHub Actions to Render** (GitHub → Settings → Secrets and variables →
   Actions):
   - **Secrets:** the four services' **Deploy Hook** URLs, from each service's Settings
     in Render:
     - `RENDER_DEPLOY_HOOK_STAGING_API` and `RENDER_DEPLOY_HOOK_STAGING_WEB`;
     - `RENDER_DEPLOY_HOOK_PRODUCTION_API` and `RENDER_DEPLOY_HOOK_PRODUCTION_WEB`.
   - **Optionally**, `RENDER_API_KEY` (Render → Account Settings → API Keys), so the
     workflows follow each deploy until it's live.
   - **Variables:** `STAGING_URL` and `PRODUCTION_URL`, the two public URLs.
   - Deploy hooks and the API key are secrets: anyone holding a hook can redeploy that
     service. If one leaks, choose **Regenerate Hook** in Render and update the secret.
8. **Require an approval for production.** Go to GitHub → Settings → Environments →
   **production** (created by the first Promote run, or add it) → Required reviewers, and
   add yourself. Every promotion then waits for your approval.
9. **Check each environment:**
   - `https://<web-url>/healthz` answers `ok` (nginx);
   - `/version.txt` names the commit;
   - `/actuator/health` answers `{"status":"UP"}` (backend and database);
   - register a learner, add a word and review it. On staging the word is filled in by the
     scripted AI; in production by Anthropic.
10. **Custom domain (optional):** add it under the web service's **Settings → Custom
    Domains**. Render issues the certificate. Then update `FRONTEND_URL` and the
    `*_URL` variable.
11. **Turn on Dependabot security updates** under the repository's Settings → Code
    security, if you haven't already (SEC-01).

## Environment variables

| Variable | Service | Where it comes from |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | backend | `render.yaml` |
| `DATABASE_URL` | backend | the environment's database (`fromDatabase`) |
| `JWT_SECRET` | backend | generated by Render |
| `AI_PROVIDER=scripted` | staging backend | `render.yaml` |
| `AI_API_KEY` | production backend | entered in the Dashboard |
| `FRONTEND_URL` | backend | entered in the Dashboard |
| `BACKEND_URL` | frontend | the backend's internal `host:port` (`fromService`) |
| `PORT=8080` | frontend | `render.yaml`; nginx listens there |
| `SENTRY_DSN` | backend | entered in the Dashboard; empty turns Sentry off (OPS-04) |
| `SENTRY_ENVIRONMENT` | backend | `render.yaml` (`staging` or `production`) |
| `METRICS_TOKEN` | backend | generated by Render; the bearer token for `/actuator/prometheus` |

Optional backend settings, any `linguaflow.*` property as an environment variable:
- `AI_MODEL`, the model;
- `LINGUAFLOW_AI_QUOTA_DAILY_REQUESTS`, the daily AI calls per learner;
- `CORS_ALLOWED_ORIGINS`, to allow more origins than `FRONTEND_URL`;
- `JAVA_TOOL_OPTIONS`, extra JVM flags.

## Deploying changes

- **Staging** follows `main` through **Deliver**. Every commit that passes CI is built
  and deployed, and the run's summary lists the image digests.
- **Production:** run **Promote to production**, optionally with a commit SHA, and approve
  it. Only commits Deliver built can be promoted. The backend is deployed before the
  frontend.
- **Never deploy by hand in the Dashboard** ("Deploy latest" would redeploy the moving
  `:production` tag). Use the workflows, so what runs is always a known commit.
- **Zero downtime:** Render starts the new instance and switches traffic once it's
  listening. Flyway runs before the backend listens, so migrations must be **backward
  compatible** with the version still running: add columns and tables first, and remove
  old ones in a later release.
- **Rolling back:** run **Promote to production** with the previous good commit. Its
  images are still in GHCR. Render's own **Rollback** works too. Neither undoes database
  migrations, so a migration that needs undoing gets a new migration.

## Backups and the database

- **Recovery:** Render keeps point-in-time recovery for paid databases. That's the last
  3 days on a Hobby workspace and 7 days on Pro or higher. Restoring creates a **new**
  database from a chosen moment; you then point the backend's `DATABASE_URL` at it.
  The full procedure, the backup scripts and the drill record are in
  [backup-restore.md](backup-restore.md) (OPS-05).
- **Exports:** from the database's **Recovery** page you can create a logical export,
  kept for 7 days. Download it to keep it longer.
- **Connecting with `psql`:** the database accepts no outside connections. To connect,
  add your IP under the database's **Access Control** for the session, use its External
  Database URL, and remove the IP afterwards. Never open it to `0.0.0.0/0`.

## Known gaps before launch

- **Email:** there's no SMTP mailer yet (`linguaflow.mail.mode=disabled` in prod), so
  verification and password-reset emails aren't sent.
- **Privacy page:** it needs the operator's name and contact details
  (`docs/security/privacy.md`).
- **Observability (OPS-04):** a Sentry DSN, a metrics scraper and the uptime variables still
  have to be set up; see [observability.md](observability.md).
- **Restore drill on Render:** the automated drill runs on Docker; a point-in-time recovery
  of staging still has to be done once ([backup-restore.md](backup-restore.md#drills)).
