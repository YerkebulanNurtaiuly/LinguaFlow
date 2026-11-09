# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

LinguaFlow is an AI-assisted language-learning platform (German first), built as a Spring Boot backend with a planned React + TypeScript + Tailwind frontend. The repo is at the **initial scaffold** stage. The monorepo layout exists (INF-01): `backend/` holds a Spring Initializr skeleton (`backend/src/main/java/LinguaFlow/Application.java` and a `contextLoads` test), while `frontend/`, `docs/database/` and `docs/technical-specification/` are placeholders. There is no frontend app, Flyway, Docker, or CI yet.

- `README.md` describes the product vision, features, and the target architecture.
- `TASKS.md` is the full implementation backlog. Task IDs look like `INF-02`, `VOC-05`, and so on, and each has deps and a "Done when" check. It also gives the milestones and the critical path. When the user refers to a task ID, look it up there and treat its "Done when" line as the acceptance criteria.

### Where the scaffold differs from the plan

Don't silently "fix" these. Ask first, or do it as part of the task that covers it (INF-02):

| Aspect | Current scaffold | Planned (TASKS.md / README) |
|---|---|---|
| Spring Boot | 4.1.2 (`spring-boot-starter-webmvc` etc.) | "3.x" |
| Java | 17 | 21 |
| Base package | `LinguaFlow` | `com.linguaflow.{auth,user,vocabulary,grammar,ai,flashcard,exercise,learning,progress,common}` |
| Artifact / app name | `demo` | — |
| Config | `application.properties` | `application.yml` with `dev`/`test`/`prod` profiles, secrets from env vars |

## Commands

Run Maven from `backend/` with the wrapper. In WSL, call `./mvnw`; on Windows, use `mvnw.cmd`. The wrapper needs `JAVA_HOME` pointing at a JDK; WSL currently has none, so builds may only work from Windows/IntelliJ.

```bash
./mvnw clean verify                                  # build + all tests
./mvnw test -Dtest=ApplicationTests                  # single test class
./mvnw test -Dtest=ApplicationTests#contextLoads     # single test method
./mvnw spring-boot:run                               # run the app
```

The JPA and PostgreSQL dependencies are on the classpath, but no datasource is configured. That means `spring-boot:run` and `@SpringBootTest` (`contextLoads`) won't start until a datasource is set up. The planned fix is Testcontainers for tests (QA-01) and Docker Compose for local dev (INF-04).

Lombok runs through explicit `annotationProcessorPaths` in `maven-compiler-plugin` for both compile and testCompile.

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
