# Developer setup guide (DOC-02)

How to run LinguaFlow on your machine, test it, and make the common kinds of change. For
how the system works, see the [technical specification](../technical-specification/README.md).

## 1. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Git | any recent | everything |
| Docker Desktop (or Docker Engine with Compose v2) | current | the full stack, backend tests (Testcontainers), end-to-end tests |
| JDK | **21** (newer JDKs build too; CI uses 21) | running the backend outside Docker |
| Node.js | **24 or newer** (`frontend/.nvmrc` says 26) | running the frontend outside Docker |

Maven isn't needed: use the wrapper in `backend/` (`./mvnw` on macOS/Linux/WSL,
`mvnw.cmd` on Windows). WSL has no JDK or Node by default; install them there or work from
Windows.

```bash
git clone https://github.com/yerkebulannurtaiuly/linguaflow.git
cd linguaflow
```

## 2. Quick start: everything in Docker

```bash
docker compose up --build
```

Open http://localhost:3000 and sign in as the demo learner **demo@linguaflow.local** /
**demo-password-123** (53 German words, 10 grammar topics, 2 lessons). This runs:

| Service | URL | Notes |
|---|---|---|
| frontend (nginx) | http://localhost:3000 | serves the app, proxies `/api` to the backend |
| backend | http://localhost:8080 | dev profile; Swagger UI at http://localhost:8080/swagger-ui.html |
| PostgreSQL 16 | localhost:5432 | database `linguaflow`, user and password `linguaflow` |

Optional extras:

```bash
docker compose --profile tools up          # + pgAdmin on http://localhost:5050
docker compose --profile observability up  # + Prometheus :9090 and Grafana :3001
docker compose down                        # stop
docker compose down -v                     # stop and delete the database
```

Ports already taken? Override them: `DB_PORT=5434 BACKEND_PORT=8082 FRONTEND_PORT=3002
docker compose up`. Everything listens on 127.0.0.1 only.

## 3. Backend and frontend on their own

For day-to-day work, run the part you change outside Docker for fast reloads.

### Backend

From `backend/`, pick one:

```bash
./mvnw spring-boot:test-run   # throwaway PostgreSQL in Docker (Testcontainers); nothing to set up
./mvnw spring-boot:run        # against a real database, by default localhost:5432/linguaflow
```

For `spring-boot:run`, start just the database with `docker compose up -d db`. In IntelliJ,
run `LinguaFlowApplication` (or `TestLinguaFlowApplication` for the Testcontainers database)
with `backend/` as the working directory.

The dev profile (the default) loads the demo data, serves the API docs, logs emails with
their links instead of sending them, and reads `backend/.env`.

### Frontend

From `frontend/`:

```bash
npm install
npm run dev      # http://localhost:5173, proxies /api and /actuator to the backend
```

The dev server proxies to `BACKEND_URL` (default `http://localhost:8080`), e.g.
`BACKEND_URL=http://localhost:8082 npm run dev` for a backend on another port. The
`/design-system` page shows every UI component (dev only).

## 4. Configuration

### Backend: `backend/.env`

Copy `backend/.env.example` to `backend/.env` for local settings; it is git-ignored and
excluded from Docker builds, so keys there never reach the repository or an image. Real
environment variables win over the file.

| Variable | Dev default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`, or `prod` (needs every value below set explicitly) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/linguaflow`, `linguaflow`, `linguaflow` | the database |
| `DATABASE_URL` | — | alternatively a `postgresql://user:pass@host:port/db` URL (as Render gives it); explicit `DB_*` win |
| `JWT_SECRET` | a dev-only value | token signing key, at least 32 characters; `openssl rand -base64 48` |
| `AI_PROVIDER` | `auto` | `auto` (Anthropic with a key, else the fake), `fake`, `scripted`, `anthropic` |
| `AI_API_KEY` | empty | Anthropic API key; see §6 |
| `AI_MODEL` | `claude-opus-5-5` | default model for every use case |
| `FRONTEND_URL` | `http://localhost:5173` | where email links point |
| `CORS_ALLOWED_ORIGINS` | `:5173` and `:3000` | browser origins allowed to call the API |
| `SENTRY_DSN`, `METRICS_TOKEN` | empty | observability; off when empty ([observability.md](../operations/observability.md)) |

Any `linguaflow.*` setting can also be set as an environment variable, e.g.
`LINGUAFLOW_RATE_LIMIT_ENABLED=false` or `LINGUAFLOW_AI_QUOTA_DAILY_REQUESTS=1000`. The
settings are the validated record `common.config.LinguaFlowProperties`; a bad value fails
at startup with the setting's name.

### Frontend

| Variable | Where | Default | Purpose |
|---|---|---|---|
| `VITE_API_BASE_URL` | `frontend/.env.local` (see `.env.example`) | `/api` | the API's base URL; keep `/api` with the dev proxy |
| `BACKEND_URL` | the shell's environment (`vite.config.ts` reads it; `.env` files don't set it) | `http://localhost:8080` | where `npm run dev` proxies `/api` and `/actuator` |

### Docker Compose

`FRONTEND_PORT` (3000), `BACKEND_PORT` (8080), `DB_PORT` (5432), `PGADMIN_PORT` (5050),
`PROMETHEUS_PORT` (9090), `GRAFANA_PORT` (3001). The backend container reads
`backend/.env` too; Compose's own database and URL settings override that file, so a `.env`
written for running outside Docker still works.

## 5. Accounts and data

- **Demo learner:** `demo@linguaflow.local` / `demo-password-123`, from the dev-only seed
  `backend/src/main/resources/db/dev/R__dev_seed.sql`. Test and prod never load it.
- **Your own account:** register in the app. Email links (verification, password reset) are
  written to the backend log in dev; copy them from there.
- **Admin:** set a user's role in the database:
  `update users set role = 'ADMIN' where email = 'you@example.com';` then sign in again.
- **Changing the demo data:** edit `backend/tools/generate_dev_seed.py` and regenerate the
  SQL rather than editing it; `LemmaNormalizerTests` checks every seeded word.
- **Starting over:** `docker compose down -v`, or drop and recreate the local database.

## 6. AI in development

The app works without an API key:

| `AI_PROVIDER` | Behaviour | Cost |
|---|---|---|
| `fake` (and `auto` without a key) | minimal schema-shaped answers that mostly fail the content checks: new words become "AI failed", and AI features don't produce useful content | none |
| `scripted` | canned, valid answers for a small dictionary (Hund, Katze, laufen, …) and exercise banks; the whole app works | none |
| `anthropic` (and `auto` with a key) | the real model | per call |

For realistic local data without spending money, use `AI_PROVIDER=scripted`. To use the real
model, put `AI_API_KEY=…` in `backend/.env` (never anywhere else) and keep an eye on
`GET /api/ai/usage`; each learner has a daily quota.

## 7. Tests

### Backend

From `backend/`, with Docker running (tests start PostgreSQL through Testcontainers):

```bash
./mvnw verify                                    # all tests, Checkstyle and the coverage gates (as CI)
./mvnw test -Dtest=VocabularyApiTests            # one class
./mvnw test -Dtest=LinguaFlowApplicationTests#healthEndpointIsPublicAndUp   # one method
./mvnw checkstyle:check@checkstyle               # Checkstyle alone
```

- **Full-context tests** use `@IntegrationTest`; test material is built with `TestData`,
  users and tokens with `TestUsers`, and the AI is the `FakeLlmClient` you can inject into a
  test method. See CLAUDE.md, "Test foundation".
- **Coverage gates:** every `*Service` keeps ≥ 80% line coverage, the whole backend ≥ 90% lines
  and ≥ 80% branches. The report is `target/site/jacoco/index.html`.
- **Prompt changes:** `PromptVersionTests` and `PromptGoldenTests` pin templates and their
  rendering. After a deliberate change, re-record and review the diff:
  `./mvnw test -Dtest=PromptVersionTests -Dprompts.update=true` and
  `./mvnw test -Dtest=PromptGoldenTests -Dgolden.update=true`.
- **Live AI tests** (`*LiveTests`, `*EvalTests`) call the real model and cost money; they run
  only with `-Dai.live=true` and `AI_API_KEY` set.

### Frontend

From `frontend/`:

```bash
npm test                                  # Vitest once (npm run test:watch to watch)
npx vitest run src/app/routes.test.tsx    # one file
npx vitest run -t "not-found page"        # by name
npm run lint && npm run typecheck && npm run format:check && npm run build
```

Tests never touch the network: `fakeApi.on(method, url, reply)` answers requests, and
`renderRoute(path)` renders a page with a signed-in German learner.

### End-to-end (Playwright)

```bash
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up --build -d
cd e2e && npm ci && npm run install-browser && npm test
```

The e2e override answers AI calls with the scripted provider and turns off the auth rate
limit. To keep your own stack's data, run it as another project:
`DB_PORT=5435 BACKEND_PORT=8085 FRONTEND_PORT=3005 docker compose -p linguaflow-e2e -f docker-compose.yml -f docker-compose.e2e.yml up --build -d`
and `E2E_BASE_URL=http://localhost:3005 npm test`.

### Restore drill

`scripts/restore-drill.sh` backs up, restores and checks a throwaway database
([backup-restore.md](../operations/backup-restore.md)).

## 8. Changing the API

The frontend's API types are generated, never written by hand:

1. Change the controller or DTOs in the backend.
2. Refresh the OpenAPI snapshot (from `backend/`):
   `./mvnw test -Dtest=OpenApiDocsTests -Dopenapi.update=true` → `frontend/openapi.json`.
3. Regenerate the types and the docs' endpoint list (from `frontend/`):
   `npm run api:generate` → `src/api/schema.ts`, and `npm run docs:api` →
   `docs/technical-specification/api.md`.
4. Add an alias in `src/api/types.ts` for each new DTO you use.
5. Commit all of them. CI fails when the snapshot, the types or the endpoint list are stale.

API rules to keep (details in [api.md](../technical-specification/api.md)):
- errors are thrown (`NotFoundException`, `ConflictException`, `ApiException`,
  `InvalidFieldException`), never built in controllers; a new error the frontend reacts to
  gets its own `ErrorCode`;
- the caller is `@CurrentUserId UUID userId`, and every query is scoped by it;
- lists take a `Pageable` with a sort whitelist and return `PageResponse`;
- every operation declares its success response, or `OpenApiDocsTests` fails;
- a new module package needs a springdoc group in `application.yml`.

## 9. Adding a database migration

The schema comes only from Flyway migrations; Hibernate only validates it.

1. **Design first.** Update [docs/database/schema.md](../database/schema.md) in the same change:
   the table, its constraints, and the migration plan.
2. **Add the next file:** `backend/src/main/resources/db/migration/V9__short_description.sql`.
   Never edit a migration that has been applied anywhere; write a new one.
3. **Write it carefully:**
   - name every constraint `<table>_<what>_{pk,fk,uq,ck,ix}`, so a violation can be mapped
     to an API error;
   - user-owned tables carry `user_id` (and `language_code` with the composite foreign key to
     `user_languages` for learning data) with `on delete cascade`;
   - enum-like columns get a `CHECK` list matching the Java enum;
   - keep it **backward-compatible** with the running version (add, don't rename or drop in
     the same release), because deploys start the new backend against the migrated database
     ([deployment.md](../operations/deployment.md)).
4. **Map it:** entities follow the conventions (UUIDv7 IDs, `AuditableEntity`, `@Version` where
   edited concurrently, `OwnedEntity` with an `OwnedRepository`, enums as strings, `jsonb` as
   records). `ddl-auto=validate` fails startup on any mismatch.
5. **Test it:**
   - add constraint checks to `common.persistence.MigrationTests` (at most one violation per
     test, as its last statement);
   - round-trip new entities in a `@DataJpaTest`, and add an `OwnedRepositoryContract`
     test for each owned repository;
   - add the table to `DataExportService` (`TABLES` or `NOT_EXPORTED` with a reason), or
     `DataExportTests` fails.
6. **Run** `./mvnw verify`, then start the app against your existing database to see the
   migration apply on real data.

## 10. Code style and CI

- **Backend:** Checkstyle (imports and likely bugs, plus project rules: inject `Clock`
  instead of `Instant.now()`, no `System.out`, no Lombok `@Data`). Format as the surrounding
  code does.
- **Frontend:** Prettier (no semicolons, single quotes) and strict type-checked ESLint. Run
  `npm run format` before committing.
- **CI** (`.github/workflows/ci.yml`) runs on every pull request and push to `main`: the
  backend build with all gates, the frontend checks (including `npm audit`), and the Docker
  stack with the end-to-end tests. Run the same commands locally first.
- **Secrets:** never commit keys, `.env` files, database dumps or exports. They are
  git-ignored; check `git status` before committing anyway.

## 11. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `./mvnw`: "The JAVA_HOME environment variable is not defined correctly" | point `JAVA_HOME` at a JDK 21+ (e.g. one IntelliJ downloaded to `~/.jdks/`) |
| Backend tests fail with "Could not find a valid Docker environment" | Docker isn't running; start Docker Desktop |
| `Port is already allocated` on `docker compose up` | another stack or service uses the port; set `DB_PORT`, `BACKEND_PORT`, `FRONTEND_PORT` |
| Every POST from the browser is 403 | the origin isn't allowed; set `CORS_ALLOWED_ORIGINS`, or use the same origin through nginx or the Vite proxy |
| Startup fails with "Schema-validation" | an entity doesn't match the migrations; add or fix the migration |
| Startup fails with "Detected applied migration not resolved locally" | your database ran a migration this branch doesn't have, or the dev seed ran under the dev profile and you started `prod`; use a fresh database |
| New words stay "AI failed" in dev | no API key with `AI_PROVIDER=auto`; use `scripted` or add a key |
| Login answers 429 | the auth rate limit (10 a minute per IP); wait a minute, or `LINGUAFLOW_RATE_LIMIT_ENABLED=false` locally |
| Coverage gate reports 0% | the test JVM was killed before writing coverage data; rerun, and see the surefire note in CLAUDE.md |
| CI fails on `schema.ts`, `openapi.json` or `api.md` | regenerate them (§8) |
