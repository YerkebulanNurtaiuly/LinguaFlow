# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

LinguaFlow is an AI-assisted language-learning platform (German first), built as a Spring Boot backend and a React + TypeScript + Tailwind frontend. The repo is at an early stage. INF-01 to INF-03 and INF-05 to INF-09 are done. INF-04 (Docker Compose) is parked, half-finished, on branch `inf-04-docker-compose`:
- `backend/` is a bootstrapped Spring Boot app: Boot 4.1.2, Java 21, base package `com.linguaflow`, with one package per domain module and a temporary `common.config.SecurityConfig`.
- `frontend/` is a bootstrapped Vite app (see Frontend below) with only a home page that shows backend health and a 404 page.
- `docs/database/` and `docs/technical-specification/` are still placeholders.
- There are no Flyway migrations, entities, Docker Compose or CI yet.

- `README.md` describes the product vision, features, and the target architecture.
- `TASKS.md` is the full implementation backlog. Task IDs look like `INF-02`, `VOC-05`, and so on, and each has deps and a "Done when" check. It also gives the milestones and the critical path. When the user refers to a task ID, look it up there and treat its "Done when" line as the acceptance criteria.

### Spring Boot 4 notes

The project uses **Spring Boot 4.x**, not the 3.x some older planning text assumed. Boot 4 split auto-configuration into modules, so things work differently from most Boot 3 examples:
- **Starters:** each tech has its own starter and a matching `-test` starter (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, `spring-boot-starter-webmvc-test`, and so on).
- **Test package names:** test auto-config annotations live in tech-specific packages, for example `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- **Testcontainers 2.x:** artifacts are `testcontainers-postgresql` and `testcontainers-junit-jupiter`, and the container class is `org.testcontainers.postgresql.PostgreSQLContainer`, which isn't generic.
- **springdoc:** must be the 3.x line. Its version is pinned in `backend/pom.xml` as `springdoc.version`.

## Commands

Run Maven from `backend/` with the wrapper. In WSL, call `./mvnw`; on Windows, use `mvnw.cmd`. You need:
- `JAVA_HOME` pointing at a JDK 21. WSL has no system JDK, so set one up or build from Windows/IntelliJ.
- Docker running. `@SpringBootTest` tests start PostgreSQL 16 through Testcontainers.

```bash
./mvnw clean verify                                                  # build + all tests (needs Docker)
./mvnw test -Dtest=LinguaFlowApplicationTests                        # single test class
./mvnw test -Dtest=LinguaFlowApplicationTests#healthEndpointIsPublicAndUp   # single test method
./mvnw spring-boot:test-run      # run the app against a throwaway Testcontainers PostgreSQL (no local DB needed)
./mvnw spring-boot:run           # dev profile against a real DB (defaults: localhost:5432/linguaflow, linguaflow/linguaflow; override via env or backend/.env)
```

How the app and its tests are set up:
- Full-context tests `@Import(TestcontainersConfiguration.class)`. That class's `@ServiceConnection` wires the datasource to the container.
- `/actuator/health` is the only public endpoint. Every other request returns 401 until the JWT security from AUTH-04 replaces `SecurityConfig`. CSRF protection is still on, so POST tests need `.with(csrf())`.
- `ddl-auto=validate` is already on. Any JPA entity needs a matching Flyway migration, or the context fails to start.
- **Config files:** `application.yml` holds the shared settings, and `application-{dev,test,prod}.yml` override it.
  - `dev` is the default profile. It has local defaults and also reads `backend/.env` through `spring.config.import`. See `backend/.env.example`.
  - `test` must be activated with `@ActiveProfiles("test")` on full-context tests.
  - `prod` has no defaults. It needs `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `JWT_SECRET`, and it turns off springdoc.
- **App settings:** custom settings live under `linguaflow.*` and bind to the validated record `common.config.LinguaFlowProperties`. A missing or short JWT secret fails at startup. Add new settings there, as nested records, rather than using `@Value`.
- **Secrets:** never put real secrets in the yml files. Reference them as `${ENV_VAR}`, and only give a default in `application-dev.yml` or `application-test.yml`.

Lombok runs through explicit `annotationProcessorPaths` in `maven-compiler-plugin` for both compile and testCompile.

### Backend conventions (common module, INF-07)

- **Errors:** every error response is RFC 7807 `application/problem+json`. Besides the standard fields it has `code` (a `common.error.ErrorCode` name), `correlationId` and `timestamp`, plus `errors: [{field, message}]` for `VALIDATION_FAILED`. `GlobalExceptionHandler` is the single exit point, and Spring Security's 401/403 errors are routed into it as well.
  - Signal business errors by throwing `NotFoundException`, `ConflictException` or `ApiException(code, detail)`. Don't build error responses in controllers.
  - When a resource belongs to another user, return `NotFoundException`.
  - The 500 response body never contains the exception message; it's logged on the server instead.
- **Pagination:** controllers take a `Pageable` (`page` is zero-based, `size` defaults to 20 and is capped at 100, `sort=field,dir`) and return `PageResponse.from(page, mapper)`. Never return a Spring `Page` directly.
- **Entities:** they extend `common.persistence.AuditableEntity`, which fills `created_at` and `updated_at` (`timestamptz not null`) from the injected `Clock`.
- **Time:** inject `java.time.Clock` instead of calling `Instant.now()`.
- **Test-only JPA entities:** they live outside `com.linguaflow` (see `testsupport.auditing`). Otherwise the app's entity scan picks them up and schema validation fails.
- **Correlation IDs:** `CorrelationIdFilter` reads or creates `X-Correlation-Id`, puts it in the MDC, which adds it to every log line, and logs one line per request.
- **CORS:** applies to `/api/**`, with origins from `linguaflow.cors.allowed-origins` (env `CORS_ALLOWED_ORIGINS`; dev defaults to `:5173` and `:3000`).
- **Error schema in the docs:** `common.error.ApiProblem` exists only to document the error body. `OpenApiConfig` adds 400, 401, 403, 404 and 500 `ApiProblem` responses to every `/api` operation. If `GlobalExceptionHandler` changes, keep `ApiProblem` in sync with it.
- **API docs (springdoc):** enabled only in the dev profile, at `/swagger-ui.html`. The full spec is `/v3/api-docs/all`, and there's one group per module (`/v3/api-docs/{module}`), configured under `springdoc.group-configs` in `application.yml`.
  - Every operation requires the `bearerAuth` JWT scheme by default. Public endpoints opt out with an empty `@SecurityRequirements`.
  - `SecurityConfig` makes the docs paths public only when `springdoc.api-docs.enabled=true`.
  - When you add a module package, add its group as well.
- **Web slice tests:** `@WebMvcTest` doesn't load `LinguaFlowProperties`, so `@Import` `SecurityConfig` and `CorsConfig` plus an `@EnableConfigurationProperties(LinguaFlowProperties.class)` test config. See `ErrorHandlingTests`.

## Frontend

Run npm from `frontend/`. It needs Node >= 24 (`.nvmrc` says 26). WSL has no system Node, so set one up or use Windows.

```bash
npm install
npm run dev                  # Vite dev server on :5173; proxies /api and /actuator to the backend (BACKEND_URL, default http://localhost:8080)
npm run build                # tsc -b + vite build
npm run lint                 # ESLint (flat config, Prettier-compatible)
npm run format               # Prettier write (format:check to verify)
npm test                     # Vitest, run once (test:watch for watch mode)
npx vitest run src/app/routes.test.tsx        # single test file
npx vitest run -t "not-found page"            # tests matching a name
```

The stack:
- **Core:** React 19, React Router 8 (imported from `react-router`, data router via `createBrowserRouter`), TanStack Query 5, Axios, Zod 4, React Hook Form.
- **Styling:** Tailwind CSS 4, set up through `@tailwindcss/vite` and a single `@import 'tailwindcss'` in `src/index.css`. There's no `tailwind.config.js`.
- **Testing:** Vitest and Testing Library in jsdom.

Conventions:
- **Imports:** `@/` points to `src/`.
- **TypeScript:** pinned to `~6.0` because `typescript-eslint` doesn't support 6.1+ or TS 7 yet. Strict mode and `noUncheckedIndexedAccess` are on.
- **HTTP:** API calls go through `src/lib/api.ts`, an Axios instance with base URL `VITE_API_BASE_URL`, default `/api`. Validate responses with Zod schemas.
- **Routes:** defined once in `src/app/routes.tsx`. Tests render them with `renderRoute(path)` from `src/test/renderWithProviders.tsx`, which uses a memory router and a fresh QueryClient.
- **Formatting:** Prettier with no semicolons and single quotes.
- **API types are generated, never hand-written** (INF-09). The pipeline:
  1. The backend's `OpenApiDocsTests` keeps the committed snapshot `frontend/openapi.json` in sync with `/v3/api-docs/all`. The test fails when the snapshot is stale.
  2. `npm run api:generate` (openapi-typescript) writes `src/api/schema.ts`. CI fails if the file is stale.
  3. Import aliases from `src/api/types.ts`. Add an alias there for each new DTO.
  - After changing the backend API, run `./mvnw test -Dtest=OpenApiDocsTests -Dopenapi.update=true` in `backend/`, then `npm run api:generate` in `frontend/`, and commit both files.
- **Error handling:** read API errors with `src/api/errors.ts` (`getApiProblem`, `hasErrorCode`, `getFieldErrors`, `getErrorMessage`), which uses the generated `ApiProblem` type.
- **openapi-typescript and TypeScript 6:** openapi-typescript only declares support for TypeScript 5, so an npm `overrides` entry in `package.json` points it at the project's TypeScript.

## CI

`.github/workflows/ci.yml` runs on every pull request and on every push to `main`. It has two jobs:
- **backend:** `./mvnw -B -ntp verify`, with Testcontainers using the runner's Docker.
- **frontend:** `npm ci`, `format:check`, `lint`, `typecheck`, `test`, `build`.

The frontend job also fails if `src/api/schema.ts` doesn't match `openapi.json`. Run the same commands locally before pushing; unformatted frontend code fails CI. The job that builds Docker images will be added along with INF-04's Dockerfiles.

## Target architecture (from README / TASKS.md)

- **Modular monolith.** The backend is split into one package per domain module (auth, user, vocabulary, grammar, ai, flashcard, exercise, learning, progress, common), and they all share one PostgreSQL database.
- **AI produces content; the backend decides.** All business rules stay deterministic Java code: validation, SRS scheduling, mastery thresholds, progress calculation, and answer evaluation for closed exercise types. The LLM only generates content (vocabulary enrichment, exercises, explanations) and helps grade open-ended answers.
- **AI layer** (AI-01…08):
  - `LlmClient` interface with a real provider implementation and a deterministic `FakeLlmClient`. Use the fake for tests and dev so other work never blocks on the provider.
  - Structured JSON output parsed into Java records and validated, with one retry on invalid output.
  - Versioned prompt templates in `backend/src/main/resources/prompts/`.
  - Async jobs, caching, and an `ai_requests` audit/cost table.
- **Data scoping:**
  - All learning data is scoped by `(user, target language)`.
  - Every user-owned query filters by `user_id`. Accessing another user's resource returns **404**, not 403 (AUTH-05).
  - Vocabulary duplicates are prevented by a unique `(user_id, language_id, normalized_lemma, part_of_speech)` constraint.
- **Knowledge status is earned.** Grammar topics move through `LEARNING → LEARNED → MASTERED` according to configurable accuracy rules (PRG-02). Adding a topic never marks it as mastered.
- **Persistence:**
  - Flyway migrations live in `backend/src/main/resources/db/migration/`.
  - JPA runs with `ddl-auto=validate`; the schema comes only from migrations.
  - Entities share an auditing base with `createdAt` and `updatedAt`.
- **API conventions:**
  - REST under `/api/**`, documented with springdoc/Swagger (every endpoint must appear in it).
  - Errors are returned as RFC 7807 `ProblemDetail`.
  - Auth is stateless JWT: short-lived access tokens plus rotating refresh tokens in an HttpOnly cookie.
- **Testing:**
  - JUnit 5 and Mockito for services.
  - `@WebMvcTest`/MockMvc for controllers, including auth and ownership checks.
  - Testcontainers PostgreSQL for repositories and migrations.
  - Backend tasks are expected to ship with unit and integration tests.
