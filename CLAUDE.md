# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

LinguaFlow is an AI-assisted language-learning platform (German first), built as a Spring Boot backend with a planned React + TypeScript + Tailwind frontend. The repo is at an early stage. INF-01 and INF-02 are done:
- `backend/` is a bootstrapped Spring Boot app: Boot 4.1.2, Java 21, base package `com.linguaflow`, with one package per domain module and a temporary `common.config.SecurityConfig`.
- `frontend/`, `docs/database/` and `docs/technical-specification/` are still placeholders.
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
./mvnw spring-boot:run           # run against a real DB: DB_URL / DB_USERNAME / DB_PASSWORD (defaults: localhost:5432/linguaflow, linguaflow/linguaflow)
```

How the app and its tests are set up:
- Full-context tests `@Import(TestcontainersConfiguration.class)`. That class's `@ServiceConnection` wires the datasource to the container.
- `/actuator/health` is the only public endpoint. Every other request returns 403 until the JWT security from AUTH-04 replaces `SecurityConfig`.
- `ddl-auto=validate` is already on. Any JPA entity needs a matching Flyway migration, or the context fails to start.
- Config is still `application.properties`. Moving to `application.yml` with profiles is task INF-05.

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
