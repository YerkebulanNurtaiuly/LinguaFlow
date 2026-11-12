# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

LinguaFlow is an AI-assisted language-learning platform (German first), built as a Spring Boot backend and a React + TypeScript + Tailwind frontend. The repo is at an early stage. INF-01 to INF-09 are done, including INF-04 (Docker Compose):
- `backend/` is a bootstrapped Spring Boot app: Boot 4.1.2, Java 21, base package `com.linguaflow`, with one package per domain module and a temporary `common.config.SecurityConfig`.
- `frontend/` is a Vite app with the app shell and full route map (FE-01). Sections show placeholders until their feature task builds them; Settings (FE-06), Vocabulary (FE-VOC-01 to 03), Grammar (FE-GRM-01, FE-GRM-02), Study sessions (FE-SES-01) and Flashcards review (FE-FC-01) are built.
- `docs/database/schema.md` is the approved schema design from DB-01. Migrations must follow it, and schema changes update it in the same PR. `docs/technical-specification/` is still a placeholder.
- Flyway migrations exist up to `V7__review_state_before.sql` (V4 is AUTH-07, V5 is GRM-03's catalog seed, V6 is FC-01's card directions, V7 is SRS-03's review state). Phase 2 has started: VOC-01 (vocabulary service), VOC-02 (vocabulary API), VOC-03 (tags), VOC-04 (bulk input), VOC-05 (AI enrichment), VOC-06 (regenerate and full editing) and VOC-07 (CSV import/export) are done, which completes the vocabulary epic. GRM-01 (grammar topic API), GRM-02 (status lifecycle), GRM-03 (topic catalog) and GRM-04 (AI explanations) are done, and so are SES-01 (study sessions) and SES-02 (learning timeline). The AI epic has started: AI-01 (LLM client), AI-02 (structured output), AI-03 (prompt templates), AI-04 (job queue), AI-05 (circuit breaker, quota, cost log), AI-06 (shared result cache), AI-07 (prompt safety) and AI-08 (job status API) are done, which completes the AI epic. V1 (DB-02) has identity and languages; V2 (DB-03) has vocabulary, tags, grammar topics and catalog, and study sessions; V3 (DB-04) has flashcards and SRS, exercises and attempts, mistakes, practice sessions, progress summary tables, and the AI job, request and cache tables. V3 tables have no entities yet apart from `ai_jobs` (AI-04), `ai_requests` (AI-05), `ai_cache` (AI-06), `flashcards` and `srs_state` (FC-01), `flashcard_reviews` (SRS-02), and `exercise_sets`, `exercises` and their link tables (EX-01); they come with their feature tasks (EX-05, PRC-02 and so on). The JPA entities for V2 are in `vocabulary`, `grammar` and `learning`. `User` and `UserRepository` are in the `user` module (AUTH-01), which the `auth` module will use.

- `README.md` describes the product vision, features, and the target architecture.
- `TASKS.md` is the full implementation backlog. Task IDs look like `INF-02`, `VOC-05`, and so on, and each has deps and a "Done when" check. It also gives the milestones and the critical path. When the user refers to a task ID, look it up there and treat its "Done when" line as the acceptance criteria.

### Spring Boot 4 notes

The project uses **Spring Boot 4.x**, not the 3.x some older planning text assumed. Boot 4 split auto-configuration into modules, so things work differently from most Boot 3 examples:
- **Starters:** each tech has its own starter and a matching `-test` starter (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, `spring-boot-starter-webmvc-test`, and so on).
- **Test package names:** test auto-config annotations live in tech-specific packages, for example `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- **Testcontainers 2.x:** artifacts are `testcontainers-postgresql` and `testcontainers-junit-jupiter`, and the container class is `org.testcontainers.postgresql.PostgreSQLContainer`, which isn't generic.
- **springdoc:** must be the 3.x line. Its version is pinned in `backend/pom.xml` as `springdoc.version`.

## Commands

### Whole stack in Docker (INF-04)

```bash
docker compose up --build            # PostgreSQL 16 + backend + frontend; app on http://localhost:3000
docker compose --profile tools up    # also pgAdmin on http://localhost:5050
docker compose down -v               # stop and delete the database volume
```

- **Ports:** the frontend's nginx serves the built app and proxies `/api` and `/actuator` to the backend, so the browser sees one origin. Host ports: `FRONTEND_PORT` (3000), `BACKEND_PORT` (8080, Swagger at `/swagger-ui.html`), `DB_PORT` (5432) and `PGADMIN_PORT` (5050). Override them when they're taken, e.g. `DB_PORT=5434 BACKEND_PORT=8082 docker compose up`.
- **Backend:** it runs the `dev` profile (demo data, API docs) against the `db` service. It reads `backend/.env` through `env_file` (optional), so `AI_API_KEY` reaches it. Compose's `environment` overrides that file's `DB_*` and `FRONTEND_URL`, so a `.env` written for running outside Docker still works.
- **Secrets:** `backend/.dockerignore` excludes `.env`, so the key never enters a build context or image. The backend image runs as a non-root `app` user, with the heap sized from the container (`MaxRAMPercentage=75`).
- **Uploads:** nginx allows 3 MB bodies (`client_max_body_size`), above CSV import's 2 MB limit, so too-large files get the backend's `PAYLOAD_TOO_LARGE` problem rather than an nginx page.
- **Images:** `backend/Dockerfile` builds with the Maven wrapper and runs on a JRE. `frontend/Dockerfile` builds with Node 26 and serves `dist` from nginx with `frontend/nginx.conf`, which adds an SPA fallback and long caching for `/assets`.

### Backend and frontend on their own

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
- **Security (AUTH-04):** `common.config.SecurityConfig` is a stateless JWT resource server.
  - **Public routes:** health, `POST /api/auth/{register,login,refresh,logout,verify-email,password-reset/request,password-reset}`, and the API docs in dev. Everything else needs `Authorization: Bearer <access token>`.
  - **Roles:** `/api/admin/**` requires `ROLE_ADMIN`, and `@EnableMethodSecurity` is on, so `@PreAuthorize("hasRole('ADMIN')")` works.
  - **Errors:** a missing or invalid token gets 401 with `WWW-Authenticate: Bearer` (`error="invalid_token"` for a rejected token); a wrong role gets 403. Both use the standard ProblemDetail body.
  - **CSRF is disabled on purpose.** The API authenticates with a header, and the only cookie (the refresh token) is `SameSite=Strict` and scoped to `/api/auth`. Don't add cookie-based authentication elsewhere without revisiting this.
  - **Current user:** controllers get the caller with `@CurrentUserId UUID userId` (`common.security`); scope every user-owned query by it.
  - **Tests:** full-context tests send a real token from `AccessTokenService.issue(user)` (see `SecurityTests`). `@WebMvcTest` slices that import `SecurityConfig` also need `JwtConfig` and `ClockConfig`, and can use `@WithMockUser`.
  - **Known gap:** an access token stays valid until it expires (15 minutes max), even if the account is disabled meanwhile. Refresh is blocked immediately.
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
- **Entities:**
  - IDs: `@Id @UuidGenerator(style = VERSION_7) UUID id`.
  - Base class: mutable entities extend `AuditableEntity`.
  - Locking: entities users edit concurrently get `@Version long version`.
  - Lombok: `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, and `@Setter` only on fields that may change freely. Never use `@Data` on entities.
  - Value types: enums are `@Enumerated(STRING)`, matching the table's CHECK list. `jsonb` columns use `@JdbcTypeCode(SqlTypes.JSON)` with a `Map` or a record; `text[]` uses `@JdbcTypeCode(SqlTypes.ARRAY) List<String>`; `smallint` maps to `short`.
  - Cross-module links: references to another module's rows, including `user_id`, are plain `UUID` columns, never JPA relations. Relations are only used inside a module, for example `VocabularyItem` with its examples and tags. Link tables that have extra columns get a small entity with an `@EmbeddedId` record key (`SessionVocabulary`).
  - Shared enums: `CefrLevel` and `KnowledgeStatus` are in `common.domain`.
  - Domain rules live in the entity where they can, e.g. `GrammarTopic` refuses to start as `MASTERED` and records every status change.
  - Tests: round-trip new entities in a `@DataJpaTest` with validate mode, Testcontainers and `JpaAuditingConfig` plus `ClockConfig` imported (see `LearningMaterialMappingTests`).
- **Users and passwords:**
  - Look users up by email with `UserRepository.findByEmail` / `existsByEmail`. They use `lower(email)`, which matches the `users_email_lower_uq` index. Don't use Spring Data's derived `...IgnoreCase` methods, which compare with `upper()` and miss the index.
  - Encode passwords with the `PasswordEncoder` bean from `auth.PasswordEncoderConfig`. It's a delegating encoder, so hashes look like `{bcrypt}$2a$...`.
  - `User` trims its fields and never holds a raw password.
- **Registration (AUTH-02):** `POST /api/auth/register`, handled by `auth.AuthController` and `RegistrationService`. It returns 201 without logging the user in, and 409 with code `EMAIL_TAKEN` when the email is already registered (ignoring case), including when two registrations race.
  - The password policy is `auth.password.@ValidPassword`: at least 10 characters, at most **72 UTF-8 bytes** (BCrypt's limit), not whitespace-only, and not on a common-passwords list.
  - Request records that carry a password override `toString()` so the password can't reach logs.
- **Email verification and password reset (AUTH-07):** `auth.AccountService` and `AccountController`.
  - **Tokens:** emailed links carry single-use tokens from `user_action_tokens`, stored as SHA-256 hashes (`common.security.SecureTokens`, also used for refresh tokens). Verification links last 48 hours and reset links 1 hour. Issuing a new link invalidates the user's older unused one.
  - **Verification:** registration sends the link automatically. Logged-in users can ask for a new one with `POST /api/auth/verify-email/request`. Login doesn't require a verified email.
  - **Reset request:** `POST /api/auth/password-reset/request` always returns 202, whether or not the email is registered, and is rate-limited.
  - **Reset:** `POST /api/auth/password-reset` sets the new password and revokes all the user's refresh tokens.
  - **Bad links:** an invalid, used or expired link gets 400 `INVALID_TOKEN`.
  - **Mail:** sent through `common.mail.Mailer`, chosen by `linguaflow.mail.mode`.
    - `log` (dev and test) writes the email with its link to the log.
    - `disabled` (the default, and prod) sends nothing and never logs links. There's no SMTP mailer yet.
    - Links point at `linguaflow.app.frontend-url` (env `FRONTEND_URL`) under the frontend pages `/verify-email` and `/reset-password`.
  - **Tests:** register `testsupport.RecordingMailer` as an `@Primary` bean and use `lastTokenFor(email)`.
- **Transaction pitfall:** an inner `@Transactional` method that takes part in the caller's transaction and throws will mark the whole transaction rollback-only, according to the inner method's own rollback rules. The caller's commit then fails with `UnexpectedRollbackException`, which surfaces as a 500. For "record, then throw" flows, put `noRollbackFor = ApiException.class` on the inner methods too, as in `ActionTokenService.redeem`.
- **Rate limiting (AUTH-06):** `common.ratelimit.AuthRateLimitFilter` limits `POST /api/auth/login` (10 per minute) and `/api/auth/register` (5 per hour) per client IP.
  - It uses Bucket4j token buckets in a bounded Caffeine cache, and runs before Spring Security, so floods are rejected before any password hashing.
  - Over the limit, the response is 429 `TOO_MANY_REQUESTS` with `Retry-After`.
  - **Settings:** `linguaflow.rate-limit.{enabled,login,register}`, each limit being `{requests, period}`. It's **off in the test profile**; `RateLimitTests` switches it on with small limits, using a different IP per test.
  - **Client IP:** it's `request.getRemoteAddr()`. Because of `server.forward-headers-strategy: native`, Tomcat takes `X-Forwarded-For` only from private-network proxies such as nginx.
  - **Single instance only:** buckets live in each instance's memory. Move to a shared Bucket4j backend (Redis or JDBC) before running several instances.
- **Ownership (AUTH-05):** every user-owned entity implements `common.persistence.OwnedEntity`, and its repository extends `OwnedRepository<T>`, **not** `JpaRepository`.
  - `OwnedRepository` only offers owner-scoped methods: `findByIdAndUserId`, `existsByIdAndUserId`, `findAllByUserId(Pageable)`, `countByUserId`, `deleteByIdAndUserId`, plus `save`/`saveAndFlush`.
  - In services, use `repo.requireOwned(id, userId)`. It throws the same 404 ("Resource not found") whether the resource is missing or belongs to someone else.
  - New queries always take `userId`. A system-wide lookup, for example for an AI worker, must be a separate method with an explicit name like `findForProcessingById`.
  - `OwnershipGuardTests` fails the build if an owned entity's repository exposes unscoped CRUD.
  - **Test helpers in `com.linguaflow.testsupport`:**
    - For each owned repository, write `@OwnedRepositoryTest class XRepositoryTests extends OwnedRepositoryContract<X>`. It supplies `repository()` and `newEntity(userId, n)` and inherits the five isolation tests.
    - Every controller endpoint that takes a resource ID must be covered by `OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, method, uri, id, ownerToken, otherToken)`.
    - `TestUsers` creates learners with a German profile and an access token.
- **Tokens (AUTH-03):** `auth.AuthService` handles login and refresh; `AuthController` handles the HTTP side.
  - **Access token:** an HS256 JWT signed with `JWT_SECRET` (`JwtConfig` provides the `JwtEncoder` and `JwtDecoder`). Claims are `sub` (user UUID), `role`, `iss=linguaflow`, and an expiry of 15 minutes. It's returned in the body as `TokenResponse`.
  - **Refresh token:** 32 random bytes, sent only as the cookie `lf_refresh` (`HttpOnly; Secure; SameSite=Strict; Path=/api/auth`, 30 days). Only its SHA-256 hash is stored, in `refresh_tokens`.
  - **Rotation:** every refresh rotates the token within its family. Presenting a spent token revokes the whole family. The revocation is done in `@Transactional(noRollbackFor = ApiException.class)` so it commits even though the request fails. Keep that pattern for any "record, then throw" logic.
  - **Settings:** token lifetimes live under `linguaflow.security.jwt.*` (`access-token-ttl`, `refresh-token-ttl`, `issuer`) and the cookie under `linguaflow.security.refresh-cookie.*`.
  - **Config records:** a `@ConfigurationProperties` record must have exactly one constructor, otherwise binding silently fails. Put defaults in the compact constructor.
  - **Testing time:** use `com.linguaflow.testsupport.MutableClock`, registered as an `@Primary` `Clock` bean in a `@TestConfiguration`, to test expiry. See `AuthFlowTests`.
- **Profile (USR-01):** `user.UserProfileController` serves `GET` and `PATCH /api/users/me`. PATCH is partial: absent fields stay unchanged. The time zone must be an IANA region ID or `UTC`, and the interface language must exist in `languages` (`user.Language`, a read-only entity).
  - `auth.CredentialsController` handles `PUT /api/users/me/password` and `DELETE /api/users/me`. Both need the current password, so a stolen access token alone can't use them.
  - A password change revokes every session, then returns new tokens and a new cookie, so the current client stays logged in.
  - Deletion removes the user row, the DB cascades remove all their data, and the cookie is cleared.
  - **Deleted account:** a still-valid access token for a deleted account gets 401 from `UserProfileService.currentUser`.
  - **Wrong password:** a wrong current password is a **400 field error** (`InvalidFieldException`), never a 401, because the frontend treats 401 as "refresh the token".
- **Learning languages (USR-02):** `user.LanguageController` serves `GET /api/languages` and `GET`, `POST`, `PATCH` and `DELETE /api/users/me/languages[/{code}]`. A `user.UserLanguage` is a learner profile; its key `(userId, languageCode)` is what every learning table's composite FK points to.
  - **Rules:** the target must be `supported_as_target` (only `de` today), and the explanation language must be `supported_as_explanation` and different from the target.
  - **Active language:** the first language becomes active automatically. Only `ActiveLanguageService` switches it, by deactivating all first and then activating the new one, because of the one-active unique index.
  - **Delete:** it cascades away **all** learning data for that language, so the frontend must confirm it. If the deleted language was active, the most recently added remaining one becomes active.
  - **Scoping learning endpoints:** learning modules scope new and listed material with `ActiveLanguageService.requireActive(userId)`. Before onboarding, that returns 409 `NO_ACTIVE_LANGUAGE`.
  - **Tests:** `TestUsers.user(email)` creates a user with no language (for onboarding flows); `learner(email)` creates one with active German.
- **Learning settings (USR-03):** each `UserLanguage` carries `LearningSettings`: `dailyReviewGoal` 1–1000 (default 50), `newCardsPerDay` 0–500 (default 20), `preferredExerciseTypes` (empty means no preference), `practiceSessionLength` 5–200 (default 20), and `cardDirections` (FC-01: 1–2 of `common.domain.CardDirection`, default both, deduplicated; an empty list is a 400 on `settings.cardDirections`).
  - They're returned nested as `settings` in the language resource and partially updated with `PATCH /api/users/me/languages/{code}` and `{"settings": {...}}`.
  - Read them in other modules with `UserLanguageService.settingsFor(userId, languageCode)`.
  - `common.domain.ExerciseType` must match the `exercises_type_ck` check; a migration test enforces this.
- **Vocabulary (VOC-01):** `vocabulary.VocabularyService` holds the rules. Its inputs are the records in `VocabularyCommands`.
  - **Language:** new words go into the active language (`ActiveLanguageService.requireActive`).
  - **Normalization:** `LemmaNormalizer.normalize(languageCode, lemma)` collapses whitespace, lower-cases, applies NFC, strips one German article (`der`/`die`/`das` followed by a space) and turns `ß` into `ss`. Its output is the duplicate key.
  - **Duplicates:** a word is a duplicate when it has the same normalized lemma and the same part of speech, where a missing part of speech on either side matches any (`DuplicateRule.SAME_WORD`, schema.md Q2). So "Hund" is a duplicate of the noun "der Hund", but the verb "laufen" and the noun "das Laufen" coexist, and among several matches the exact one is reported (`findSameWordIds`). Create and update both use this rule; only CSV import passes `DuplicateRule.EXACT`, which is the unique key itself (a null only matches a null). The DB constraint is unchanged. A duplicate throws `DuplicateVocabularyItemException`: 409 `VOCABULARY_DUPLICATE` with `existingId`. This also happens when two requests race past the check; the `vocabulary_items_lemma_uq` violation is caught outside the failed transaction, the same pattern as `RegistrationService`.
  - **Rules:** the learner can't set `MASTERED` (`VocabularyItem.changeStatusByUser`). Gender is only for nouns, and changing the part of speech away from noun clears it. Every field the learner edits is removed from `aiGeneratedFields` (`markEditedByUser`, field names in `VocabularyService.AiFields`).
  - **Updates:** they are partial (null means unchanged). Changing the lemma re-normalizes it and checks for duplicates again; the item itself doesn't count.
  - **Return values:** returned items have their examples and tags loaded, because open-in-view is off.
- **Vocabulary API (VOC-02):** `VocabularyController` serves `/api/vocabulary` (GET list, POST, and GET, PATCH, DELETE `/{id}`). The bodies are in `VocabularyDtos`; OpenAPI names them `VocabularyItem`, `CreateVocabularyRequest`, `UpdateVocabularyRequest` and so on.
  - **Listing:** the list uses the active language. Its filters are `q`, `partOfSpeech`, `cefrLevel` and `status` (each may be repeated, meaning any of them), `aiStatus` (same, added for FE-VOC-03), `sessionId` and `tagId`, plus `sort` by `createdAt` (default, newest first), `updatedAt`, `lemma`, `translation`, `cefrLevel` or `status`.
  - **Search:** `q` matches the normalized lemma (so `strasse` finds "die Straße") or the lower-cased translation, with `%` and `_` treated literally.
  - **Filtered query:** it lives in the custom repository fragment `VocabularyItemSearch`/`VocabularyItemSearchImpl` (Criteria API, always scoped by user and language). Don't add `JpaSpecificationExecutor` to an owned repository: it would allow unscoped queries. Tags and session links are filtered with `EXISTS` subqueries, so rows aren't duplicated.
  - **Batching:** `examples` and `tags` use `@BatchSize(100)`, so a page loads them in a few queries rather than one per item.
  - **PATCH:** absent or null fields stay unchanged, and an empty string clears translation, plural or notes. The response includes `version`, and `article` is derived from the gender for German nouns.
- **Tags (VOC-03):** `vocabulary.TagService` and `TagController` serve `/api/tags` (GET list, POST, and PATCH, DELETE `/{id}`). Tags belong to one learning language and are created and listed in the active one.
  - **Names:** trimmed with inner whitespace collapsed, 1–50 characters, and unique per language ignoring case (`tags_name_uq`). A clash is 409 `TAG_NAME_TAKEN` with `existingId`, also when two requests race; the fallback reads the language lazily, because a rename only learns it inside its transaction.
  - **Listing:** not paginated, sorted by name, each with `wordCount`.
  - **Tagging words:** `tagIds` on the vocabulary create and PATCH requests. On PATCH a non-null list **replaces** all the word's tags (`[]` removes them all). At most 20 per word; a tag that isn't the user's or is in another language is a 400 field error on `tagIds` (`TagService.resolveForWord`, which must run inside the caller's transaction).
  - **Delete:** deleting a tag keeps its words; the join rows cascade away.
- **Bulk input (VOC-04):** `BulkVocabularyController` (`/api/vocabulary/bulk`) with `BulkVocabularyService` and the pure `BulkVocabularyParser`. It works in two stateless steps:
  - **Preview:** `POST /api/vocabulary/bulk` with `{text, format: TEXT|CSV}` saves nothing. It returns one entry per non-blank line (1-based `line`, blank lines counted) with status `NEW`, `DUPLICATE` (`existingId`, or `duplicateOfLine` for a repeat within the input) or `INVALID` (`message`), plus counts. At most 500 lines; more is a 400 field error on `text`.
  - **Text format:** `word - translation`. The separator is the first `-` or `=` with spaces around it, an en or em dash, or a tab, so "E-Mail" stays whole.
  - **CSV format:** comma or semicolon (semicolon wins a tie), RFC 4180 quotes. Without a header the columns are lemma, translation; a header row (it needs a `lemma` or `word` column) maps lemma/word, translation, partOfSpeech/pos, cefrLevel/level, notes in any order and ignores other columns.
  - **Confirm:** `POST /api/vocabulary/bulk/confirm` with `{items, tagIds}` creates each item on its own through `VocabularyService.create`, so one failure doesn't stop the rest, and reports `CREATED`, `DUPLICATE` or `INVALID` per item in request order. An unusable tag fails the whole request up front.
  - **Duplicate rule:** the same as adding a single word: the normalized lemma matches and the parts of speech are equal **or either side has none** (`BulkVocabularyService.sameWord`, checked in one batch query). "Hund" is a duplicate of a noun "der Hund".
- **CSV import and export (VOC-07):** `VocabularyTransferController` and `VocabularyTransferService`, with the format in `VocabularyCsv`.
  - **Export:** `GET /api/vocabulary/export` takes the same filters as the list and returns the active language oldest first. It's `text/csv`, UTF-8 with a BOM, CRLF line endings, and sent as an attachment named `linguaflow-vocabulary-{lang}-{date}.csv`.
  - **Columns:** `lemma, translation, partOfSpeech, gender, plural, cefrLevel, status, notes, tags`, where tags are names joined by `|`, so tag names may not contain `|`. Examples, AI data and dates aren't exported.
  - **Formula guard:** cells starting with `= + - @`, a tab or CR get a leading `'`, which import strips again. `VocabularyTransferApiTests` checks that export → import → export gives an identical file.
  - **Import:** `POST /api/vocabulary/import` takes a multipart `file` (UTF-8, at most 2 MB, set in `spring.servlet.multipart`, and 2000 rows). It needs a header naming a `lemma` column; other columns are optional and may be in any order. Each row is created on its own and reported as `CREATED`, `DUPLICATE` or `INVALID` with its line.
  - **Import rules:** tags are matched by name ignoring case and created when missing (`TagService.findOrCreate`, at most 200 per file). MASTERED becomes LEARNED, with a message. Import uses `DuplicateRule.EXACT` (lemma and part of speech, a null only matching a null), unlike adding words by hand or in bulk, so an exported file imports back completely.
- **AI enrichment (VOC-05):** every new word starts `PENDING_AI` and is enriched in the background. The fields filled are translation, part of speech, gender and plural (nouns), verb forms (verbs), CEFR level, 2–3 examples and notes.
  - **Queueing (`EnrichmentQueue`):** a single create queues one job inside the word's own transaction (`VocabularyService.create(..., enrichNow)`). Bulk confirm and CSV import create the words with `enrichNow = false`, then queue them in batches of 20 and return `aiJobIds`, so the bulk add page can follow them (AI-08).
  - **Answer (`EnrichmentAnswer`):** package-private records, sent as the JSON Schema; the field descriptions guide the model. Bean Validation requires 2–3 examples and limits the lengths. `EnrichmentRules.check` adds the domain rules:
    - **Part of speech:** the one the learner chose must be kept.
    - **Gender and plural:** only for nouns. A German noun must have a gender, and the plural is written without an article.
    - **Verb forms:** only for verbs, and required for German verbs.
    - **Batches:** a batch must answer every word, in order, echoing it.
  - **Merge (`EnrichmentRules.merge`):** AI only fills fields the learner left empty, including ones they typed in while the word was pending, and adds each to `aiGeneratedFields`.
    - **Verb forms** go into `extra` as `{praeteritum, partizip2, auxiliary}` (field name `verbForms`).
    - **Examples:** AI examples fill up to 3 after the learner's own, with `ai_generated` set.
    - **Gender, plural and verb forms** only apply when they fit the word's final part of speech.
    - **Part of speech:** it isn't set when another of the user's words already has that lemma with it (the unique key).
    - **Notes:** editing notes now also removes `notes` from `aiGeneratedFields`.
  - **Handler (`VocabularyEnrichmentHandler`, `AiJobType.VOCABULARY_ENRICHMENT`):**
    1. **Pick-up:** only words still `PENDING_AI` are handled, so the job is idempotent, and deleted words drop out.
    2. **Cache:** each word is first looked up in the shared AI cache, keyed by language, explanation language, normalized lemma and the learner's part of speech, with namespace `CACHE_VERSION` (both templates' versions). The prompt carries no learner-specific variables, so answers may be shared.
    3. **Batch call:** the misses go to the model in one `vocabulary-enrichment-batch` call, with words written as `laufen (verb)` when typed.
    4. **Fallback:** if the batch answer is still invalid after its retry, each word is asked on its own with the single-word template. A word without a usable answer becomes `AI_FAILED`, and the job still succeeds.
    5. **Merging:** merges retry on an optimistic-lock conflict with a concurrent edit.
    6. **Errors:** provider errors, the quota and an open circuit propagate, so the queue retries or postpones the job. `onGiveUp` marks the job's pending words `AI_FAILED`.
    7. **Results:** the job result is `{ready, failed, fromCache}`. Results from suspicious prompts (AI-07) aren't cached.
  - **Explanation language:** read with `UserLanguageService.explanationLanguageFor(userId, languageCode)`.
  - **Repository:** the worker's system-wide queries are `findAllForEnrichmentByIdIn`, `findForEnrichmentById` and `markFailedForEnrichment`.
  - **Tests:** `VocabularyEnrichmentTests` drive the real API, queue and handler against a scripted fake, through the context's `AiJobWorker.runDueJobs()`; it covers the done-when "Hund" → der Hund, die Hunde, dog. `EnrichmentRulesTests` covers the rules.
  - **Fake client:** the fake's default schema sample has no examples, so it fails validation. Without an API key, dev words become `AI_FAILED` rather than getting made-up data.
- **Ask again and edit everything (VOC-06):**
  - **`POST /api/vocabulary/{id}/regenerate`** (`VocabularyService.regenerate`) returns 202 `{item, aiJobId}`.
    - **What changes:** every value listed in `aiGeneratedFields` is cleared and AI examples are removed. The learner's own values and examples stay, and so does the part of speech, which tells the AI which word is meant; the learner changes it first if it's wrong. The word goes back to `PENDING_AI`.
    - **The job:** `EnrichmentQueue.enqueueFresh` queues it with payload `fresh: true`, so the handler skips the AI cache read; a fresh answer still updates the cache.
    - **While pending:** asking again while the word is `PENDING_AI` is 409 `CONFLICT`.
  - **PATCH additions (`UpdateVocabularyRequest`):**
    - **`examples`:** replaces all examples, in order (at most 10, `VocabularyService.MAX_EXAMPLES`). An entry with an existing example's `id`, sent back unchanged, keeps its AI flag; anything else becomes the learner's.
    - **`verbForms`:** `{praeteritum, partizip2, auxiliary: HABEN|SEIN}`, verbs only (400 on `verbForms` otherwise). It is stored in `extra` through `VocabularyItem.setVerbForms`.
    - **`clear`:** a set of `PART_OF_SPEECH`, `GENDER`, `CEFR_LEVEL` and `VERB_FORMS`, for what an empty string can't express. Setting and clearing the same field is a 400 on that field.
    - **Part-of-speech side effects:** changing the part of speech away from verb removes the verb forms, as leaving nouns clears the gender. Each edited or cleared field leaves `aiGeneratedFields`.
  - **Example positions:** they are unique per word and the check isn't deferred, while Hibernate inserts before it deletes. So examples are replaced by clearing, flushing (`saveAndFlush`) and re-adding (`rewriteExamples`). `addExample` takes the next free position, never `size()`.
- **Grammar topics (GRM-01):** `grammar.GrammarTopicService` and `GrammarTopicController` serve `/api/grammar-topics` (GET list, POST, and GET, PATCH, DELETE `/{id}`). They are built like the vocabulary API.
  - **Listing:** filters `q` (title or description), `cefrLevel` and `status` (repeatable), with `sort` by `createdAt` (default, newest first), `updatedAt`, `title`, `cefrLevel` or `status`. It uses the Criteria fragment `GrammarTopicSearch` and the `SORTABLE` whitelist.
  - **Duplicate titles:** `TitleNormalizer` reuses `LemmaNormalizer` (case, spacing, ß/ss, one leading article), so "Der Genitiv" and "genitiv" are the same topic. That also matches the dev seed's `normalized_title` (`TitleNormalizerTests` checks it). A clash is 409 `GRAMMAR_TOPIC_DUPLICATE` with `existingId`, also when two requests race (`grammar_topics_title_uq`).
  - **Status lifecycle (GRM-02):** the rules live in `GrammarTopic`.
    - **Creating:** a topic starts as NEW, LEARNING or LEARNED, never MASTERED.
    - **By hand (`changeStatusByUser`):** LEARNING and LEARNED can be chosen freely in either direction. MASTERED needs `confirmMastered: true` on the PATCH; the frontend should ask first. NEW can't be chosen again, and choosing the current status records nothing.
    - **By rules (`changeStatusByRule`, for PRG-02):** `MASTERY_RULE` may only grant MASTERED, and `DEMOTION_RULE` may only move to LEARNING or LEARNED.
    - **Errors:** a refused change is a 400 field error on `status`; the entity's `IllegalArgumentException` fragment is mapped by the service. The rest of the PATCH is rolled back too.
    - **History:** every change is recorded in `grammar_topic_status_history` (reason CREATED, MANUAL, MASTERY_RULE or DEMOTION_RULE) with the injected `Clock`. It's served oldest first by `GET /api/grammar-topics/{id}/status-history`, with ties ordered by the UUIDv7 id.
  - **Text fields:** `personalNotes` is Markdown, so only trailing whitespace is stripped (max 20,000). The description max is 2,000 and the title max 200. On PATCH, `""` clears the description or notes. The response includes `aiExplanation` (null until the learner asks for one, GRM-04) and `version`.
- **AI explanations (GRM-04):** written on request, never automatically, since each costs money.
  - **`POST /api/grammar-topics/{id}/explanation`** (`GrammarTopicService.requestExplanation`) returns 202 `{aiJobId, alreadyRunning}`.
    - It queues a `GRAMMAR_EXPLANATION` job with payload `{grammarTopicId}`.
    - While one is still queued or running, its job is returned instead (`AiJobQueue.findActive`, which finds the user's active job by a payload value).
    - The UI follows the job with `GET /api/ai/jobs/{id}`, then reloads the topic. Another request later replaces the explanation.
  - **Handler (`GrammarExplanationHandler`):** renders the `grammar-explanation` template with the title, description and level in the learner's explanation language. It also passes up to 15 of the learner's LEARNED or MASTERED topics (`findTitlesByStatus`) and up to 30 of their most recently changed words (`VocabularyService.recentLemmas`).
    - **No cache:** the prompt is learner-specific, so explanations aren't shared through the AI cache.
    - **Saving:** the result goes into `ai_explanation` with `ai_explanation_generated_at`, retrying on an optimistic-lock conflict with a concurrent edit. The learner's notes are never touched.
    - **Edge cases:** a topic deleted meanwhile is skipped (job result `{skipped}`). An answer still invalid after the retry fails the job (`INVALID_OUTPUT`) and leaves the old explanation.
  - **Shape (`GrammarExplanation`, answer record `ExplanationAnswer`):** `{summary, rules[], examples[{sentence, translation}], commonMistakes[]}`. Bean Validation requires 1–8 rules and 2–8 examples. Rules may hold plain-text tables, so their line breaks matter.
  - **Tests:** `GrammarExplanationTests`.
- **Flashcards (FC-01):** package `flashcard`, entities `Flashcard` and `SrsState`, both `OwnedEntity`. `SrsState`'s `id` is its card's ID (column `flashcard_id`, a shared key), and its `@Version` is a `Long`, so Spring Data persists a new row instead of merging.
  - **Which cards exist:** every READY word gets one card per `CardDirection` (`TARGET_TO_NATIVE`, `NATIVE_TO_TARGET`), each with a NEW `SrsState` (ease 2.50, no due date) for SRS-01 and SRS-02.
    - Cards always exist for both directions. The learner's `cardDirections` setting only decides which are reviewed, so the review queue (SRS-02) must filter by it. Switching the setting never loses history.
  - **Creating (`FlashcardGenerator`):** after enrichment commits a word as READY, `VocabularyEnrichmentHandler` publishes `VocabularyItemReady`, and `onWordReady` calls `createFor(userId, itemId)`.
    - It's idempotent: existing directions are kept, and a concurrent unique-key clash is ignored.
    - A failure is logged. At every startup (`ApplicationReadyEvent`), `reconcile()` gives READY words their missing cards in batches of 500. That covers words from before FC-01, the dev seed (106 cards for the demo's 53 words) and failed creations.
    - Words that never become READY (AI_FAILED) get no cards until "Ask AI again" succeeds.
  - **Content (`FlashcardContent.of(direction, word)`):** never stored (schema.md Q3), so it's built from the word on every read and an edited word changes its cards at once.
    - **Front and back:** `TARGET_TO_NATIVE` asks "der Hund" for "dog". `NATIVE_TO_TARGET` asks "dog", with the part of speech as a `hint` ("noun"), for "der Hund". The article comes from the gender (`VocabularyItem.article()`), however the lemma was typed.
    - **Extras:** both directions carry the plural with "die" ("die Hunde"), verb forms ("lief", "ist gelaufen", from `extra` with the auxiliary) and up to 3 examples.
    - **`complete`:** false while the word has no translation (shown as "…"). SRS-02 should skip such cards.
  - **Deleting:** removing a word removes its cards and states (the database cascades).
  - **Settings UI:** the language section of Settings has a "Flashcards" fieldset with "German → English" and "English → German" checkboxes, at least one.
  - **Tests:** `FlashcardGenerationTests`, `FlashcardContentTests`, plus the repository contracts.
  - **Verb auxiliaries:** the AI and the edit form write `SEIN`/`HABEN`, while older data (the dev seed) writes `sein`/`haben`. Read them case-insensitively (`FlashcardContent`, and `verbFormsOf` in the frontend).
- **Flashcard API (FC-02):** `FlashcardController` and `FlashcardService` serve `/api/flashcards`.
  - **Endpoints:** a paged list, `GET /{id}`, and `PATCH /{id}` with `{suspended}` (required; suspending keeps the review state).
  - **The `Flashcard` body:** the card, the word's `lemma`, its `content` (`FlashcardContent`: front, hint, back, plural, praeteritum, perfect, examples, complete) and its `srs` (state, dueAt, intervalDays, repetitions, lapses, lastReviewedAt).
  - **Listing:** the active language's cards (409 `NO_ACTIVE_LANGUAGE` before onboarding), newest first.
    - **Filters:** repeatable `direction` and `state`, plus `suspended`, `vocabularyItemId`, and `q`, which matches the normalized lemma or the lower-cased translation with `%` and `_` taken literally.
    - **Sort:** by `createdAt`, `updatedAt` or `dueAt`. NEW cards have no due date and sort last ascending; anything else is a 400 on `sort`.
  - **Query (`FlashcardRepository.search`):** a JPQL query with entity joins (`join VocabularyItem v on …`, `join SrsState s on …`, not comma joins). Spring Data only recognises explicit join aliases, so `JpaSort.unsafe("s.dueAt")` isn't prefixed with `f.`.
    - Empty filters are passed as all values, and an empty `q` as `''`.
    - Words and states for a page are loaded in two batched queries (`VocabularyService.findOwned`, `SrsStateRepository.findAllByUserIdAndIdIn`).
  - **One card:** `GET /{id}` works in any of the user's languages. Another user's card is 404.
  - **Review history (FE-FC-02):** `GET /{id}/reviews` pages the card's `FlashcardReview`s, newest first (`FlashcardService.reviews`). Only `reviewedAt` is sortable, with the UUIDv7 `id` as tie-break. Another user's card is 404.
  - **Frontend:** the flashcard browser is FE-FC-02, and review is SRS-02 and FE-FC-01.
  - **Tests:** `FlashcardApiTests`.
- **Scheduling (SRS-01):** `flashcard.SrsScheduler` is a pure, deterministic SM-2 variant (Anki-style, no fuzz, no clock): `review(Card, ReviewGrade, now, zone)` returns the next `Card` (state, ease, interval, repetitions, lapses, dueAt, lastReviewedAt). `SrsState.toCard()` and `apply(card)` convert to and from the entity.
  - **Learning:** NEW and LEARNING cards go through steps of 1 and 10 minutes (`repetitions` is the step index). AGAIN restarts, HARD repeats the step, GOOD moves on and finally graduates to REVIEW with 1 day, EASY graduates at once with 4 days.
  - **Review:** with interval `i` and ease `e`, HARD is `i×1.2` (ease −0.15), GOOD `i×e`, EASY `i×e×1.3` (ease +0.15). Each beats the grade below by at least a day, and intervals are capped at 36,500 days. The scheduled interval is used, not the days elapsed.
  - **Lapses:** AGAIN on a review card adds a lapse, takes 0.20 off the ease and relearns through one 10-minute step, then returns with 1 day (`lapseIntervalMultiplier` 0). Ease never drops below 1.30 and is kept to two decimals.
  - **Time zone:** review cards fall due at the start of a local day in the learner's zone (`dueOn`, DST-safe); `SrsScheduler.today(now, zone)` is the learner's "today" for SRS-02's daily limits.
  - **Settings:** `SrsScheduler.Config` (`DEFAULT` follows Anki's defaults). `ReviewGrade` must match `flashcard_reviews_grade_ck`; `MigrationTests` checks it and the SRS states.
  - **Tests:** `SrsSchedulerTests` (table-driven transitions, zones and DST, grade ordering, a card's whole life).
- **Review queue (SRS-02):** `ReviewService` and `ReviewController` serve `/api/reviews`, on the append-only `FlashcardReview` (`flashcard_reviews`, `@Immutable`, no audit columns).
  - **`GET /api/reviews/due?limit=`** (1–200, default 50; else a 400 on `limit`) returns the active language's `ReviewQueue`: `cards` (each a `Flashcard` with `previews` of where AGAIN/HARD/GOOD/EASY would send it, for the grade buttons), `dueCount`, `newCount`, `today`, `reviewedToday`, `dailyReviewGoal`, `newCardsToday`, `newCardsPerDay` and `nextLearningDueAt`.
  - **What's reviewable:** not suspended, in one of the learner's `cardDirections`, and the word has a translation (`FlashcardRepository.REVIEWABLE_FROM`). Due means a REVIEW card due before the learner's tomorrow starts, or a (re)learning card whose step is over.
  - **Order:** overdue (due before today) oldest first; then today's, learning steps first, then the weakest (lowest ease, most lapses); then new cards, oldest word first.
  - **Daily limits:** `newCardsPerDay` is a hard limit, counted as cards whose first review was today (`countIntroducedSince`). `dailyReviewGoal` is only a target: due cards are never held back, and the UI shows `reviewedToday` against it.
  - **Siblings:** a word's NATIVE_TO_TARGET card stays out of the new cards while its TARGET_TO_NATIVE card is reviewed too and is still NEW or was reviewed today, so recognition comes first and a word's two cards aren't asked back to back.
  - **`POST /api/reviews`** `{flashcardId, grade, reviewToken, durationMs?}` runs `SrsScheduler` in the user's zone, updates `srs_state` and appends a review: 201 `ReviewResult` `{review, card, replayed: false}`. Any of the user's cards can be graded, due or not; another user's card is 404.
  - **Idempotent:** the same `reviewToken` for the same card returns the stored review with 200 and `replayed: true` and changes nothing, even with another grade. Concurrent copies race on `srs_state`'s version or the token key; `submit` catches that outside the failed transaction (`TransactionTemplate`) and replays. Two different tokens racing on one card give 409 `CONFLICT`.
  - **Not yet:** `practice_session_id` stays null until PRC-02.
  - **Tests:** `ReviewApiTests` (with a `MutableClock`; past 15 minutes the access token expires, so later days are checked through the service), `FlashcardReviewRepositoryTests`.
- **Review statistics (SRS-03):** `GET /api/reviews/stats` (`ReviewService.stats`) gives the active language's `ReviewStats`, on the learner's calendar (`User.timezone`).
  - **Fields:** `today`, `dueToday` (reviewable cards past NEW due before today ends, overdue and later learning steps included, so it can exceed the queue's `dueCount`), `newAvailable`, `reviewedToday`, `dailyReviewGoal`, `currentStreak`, `longestStreak`, `retention` and `forecast`.
  - **Streaks:** days in a row with at least one review. The current streak ends today, or yesterday while today has no review yet. Review days come from `findReviewDays`, a native query grouping by `reviewed_at at time zone :zone`; PostgreSQL gets the IANA name, or "UTC" for the fallback.
  - **Retention:** `{days: 30, reviews, remembered, rate}` over reviews of cards that were in REVIEW, remembered meaning any grade but AGAIN (Anki's "true retention"); `rate` is null without reviews. It needs `flashcard_reviews.state_before` (V7), which every review now records.
  - **Forecast:** 7 entries from today, counting cards falling due on each local day; overdue cards count for today.
  - **Tests:** `ReviewApiTests` and the table-driven `ReviewStreakTests`.
- **Exercise model (EX-01):** package `exercise`, entities `ExerciseSet` and `Exercise` (both `OwnedEntity`, with `ExerciseSetRepository` and `ExerciseRepository`). There's no API yet; EX-02 adds it.
  - **Sets:** `ExerciseSet(userId, languageCode, source, request, practiceSessionId)` starts `GENERATING`; `markReady()` or `markFailed()` ends it, once. `request` is the JSON record `ExerciseSetRequest {grammarTopicIds, types, count, difficulty}`. The enums `ExerciseSetSource`, `ExerciseSetStatus` and `ExerciseDifficulty` match their CHECKs (`MigrationTests`).
  - **Typed content:** `ExerciseContent(type, prompt, solution)`. The prompt is a record of the sealed `ExercisePrompt` and the solution one of the sealed `ExerciseSolution`, paired per type by `ExerciseFormat.of(type)`; any other pairing is an `IllegalArgumentException`.
    - **Pairs:** FILL_BLANK is `FillBlank` (text with `___` gaps, hint) with `Blanks` (one `Blank {answer, alternatives}` per gap). MULTIPLE_CHOICE is `MultipleChoice` (question, 2–6 options) with `Choice {correctIndex}`. WORD_ORDER is `WordOrder` (shuffled tokens) with `TokenOrder {tokens, alternatives}`, where each alternative is another full order. TRANSLATION (`direction` as a `CardDirection`, text), SENTENCE_CONSTRUCTION (task, words to use), ERROR_CORRECTION (sentence) and TRANSFORMATION (sentence, task) share `TextAnswer {answer, alternatives}`.
    - **Shared fields:** every prompt has a nullable `instruction` and every solution a nullable `explanation`, both in the explanation language.
    - **Storage:** the entity stores `prompt` and `solution` as JSON maps with the records' field names, and reads them back with `content()`. Unknown stored fields are ignored. Link sets `grammarTopicIds` and `vocabularyItemIds` are `@ElementCollection`s on `exercise_grammar_topics` and `exercise_vocabulary`. Deleting a topic or word drops the link and keeps the exercise.
  - **Schemas for EX-02:** the records are answer records. Lengths are Bean Validation annotations, and the `@JsonPropertyDescription`s guide the model. `ExerciseFormat.promptSchema()` and `solutionSchema()` build the JSON Schemas (`ai.JsonSchemas` is now public). Record list copies keep null elements, so validation reports them instead of failing the parse.
  - **Rules (`ExerciseRules`):** EX-02's output check (`check(content, problems)`, with JSON paths). The `Exercise` constructor refuses content that breaks them.
    - FILL_BLANK: as many gaps (three or more underscores) as blanks.
    - MULTIPLE_CHOICE: options distinct ignoring case and spacing, and `correctIndex` in range.
    - WORD_ORDER: the solution and every alternative use exactly the prompt's tokens, and the prompt isn't already solved.
    - ERROR_CORRECTION and TRANSFORMATION: the answer differs from the sentence.
    - SENTENCE_CONSTRUCTION: the words are distinct.
  - **Reporting (EX-07):** `report(reason, at)` keeps the first time and the latest reason.
  - **Tests:** `ExerciseRulesTests`, `ExerciseFormatTests`, `ExerciseMappingTests` and the repository contracts. `ExerciseSamples` has one valid exercise per type.
- **Grammar topic catalog (GRM-03):** `V5__grammar_catalog_seed.sql` seeds 52 German topics from A1 to C1. Titles are German and descriptions English.
  - **Stable IDs:** each ID is `md5('grammar-catalog:de:<slug>')::uuid`, so it's the same in every environment. To extend the catalog, write a new migration with the same scheme.
  - **Distinct titles:** titles must stay distinct after `TitleNormalizer` (`GrammarCatalogApiTests` checks this).
  - **Code:** `GrammarTopicCatalogRepository` is a read-only Spring Data `Repository`, since the catalog isn't user-owned. `GrammarCatalogService` and `GrammarCatalogController` serve it.
  - **Listing:** `GET /api/grammar-topics/catalog?cefrLevel=` returns the active language's entries, by level then title. Each has a `topicId` when the learner already has it: picked from it (`catalog_topic_id`) or a topic with the same normalized title.
  - **Adding:** `POST /api/grammar-topics/catalog/{id}` (optional body `{status}`) creates a topic with the entry's title, description, level and `catalogTopicId` through `GrammarTopicService.create`, so the duplicate and MASTERED rules apply. An entry in another language, or an unknown ID, is 404.
- **Study sessions (SES-01):** `learning.StudySessionService` and `StudySessionController` serve `/api/study-sessions`.
  - **Endpoints:** a paged list, most recent `studiedOn` first; `GET /active` (204 when none); POST; GET, PATCH and DELETE `/{id}`; `GET /{id}/material`; `POST /{id}/vocabulary` and `/{id}/grammar-topics` to attach; `DELETE /{id}/vocabulary/{itemId}` and `/{id}/grammar-topics/{topicId}` to detach.
  - **Creating:** `studiedOn` defaults to today in the user's `User.timezone`.
  - **One active session:** at most one per language (`study_sessions_one_active_uq`). Activating one (`active: true` on create or PATCH) runs the bulk `deactivateOthers` first. That clears the persistence context, so `activate` returns the fresh entity to continue with.
  - **Auto-attach:** `VocabularyService.create` and `GrammarTopicService.create` publish `VocabularyItemCreated` and `GrammarTopicCreated` after their commit. `ActiveSessionAttacher` (`@EventListener`) links the new material to the active session, so hand-added, bulk and imported material all join it. A failure there is logged, not rethrown, because the material is already saved. The vocabulary and grammar modules don't depend on `learning`.
  - **Attaching by hand:** idempotent. Only the user's own material in the session's language (`findOwnedIds`) can be attached; anything else is a 400 field error. The links are persisted through the `EntityManager` (`SessionLinkRepository` only reads, counts and deletes).
  - **"Current Lesson" material:** `GET /{id}/material` gives the IDs, and `GET /api/vocabulary?sessionId=` and `GET /api/grammar-topics?sessionId=` give the full items. `StudySessionService.material` is for PRC-01.
  - **Deleting a session:** keeps its words and topics.
  - **Timeline (SES-02):** `GET /api/study-sessions/timeline?from=&to=` (both optional, inclusive ISO dates; `from` after `to` is a 400 on `from`). It returns the active language's lessons grouped by month (`"2026-11"`), most recent first, with per-month and overall `sessionCount`, `vocabularyCount` and `grammarTopicCount`. Counts are attachments, so a word studied in two lessons counts in both. It isn't paginated; learners have tens or hundreds of lessons.
- **OpenAPI success responses:** an operation that declares only error `@ApiResponse`s loses springdoc's default 200, and its generated type then has no body. `OpenApiDocsTests.everyOperationDocumentsASuccessResponse` fails the build when that happens.
- **LLM client (AI-01):** package `ai`. `LlmClient.complete(LlmPrompt(system, user), jsonSchema, LlmOptions)` returns an `LlmResponse` (text, the model that answered, tokens, cache reads, latency), or throws `LlmException` with a `Kind`; `kind().retryable()` tells AI-04's queue whether a retry can help.
  - **Implementations:** `AnthropicLlmClient` uses the official SDK (`com.anthropic:anthropic-java`, version in `pom.xml`). `FakeLlmClient` is deterministic: given a JSON Schema it returns the smallest JSON that fits it. Tests can script answers per use case (`respondTo`), queue failures (`failNext`) and read `calls()`.
  - **Choosing a client (`AiConfig`):** `linguaflow.ai.provider` is `auto` (Anthropic when `AI_API_KEY` is set, fake otherwise), `fake` or `anthropic` (fails at startup without a key). Dev is `auto`; **test is always `fake`**; prod defaults to `anthropic`.
  - **The key:** lives only in `backend/.env` (git-ignored) or a real environment variable. `Ai.toString()` masks it. Never put it in yml, tests or logs.
  - **Base URL:** set explicitly (`linguaflow.ai.base-url`, default `https://api.anthropic.com`), because Claude Code's shell exports `ANTHROPIC_BASE_URL`, which would otherwise redirect calls.
  - **Per use case (`AiUseCase`, `AiSettings`):** model (default `claude-opus-5-5`), effort, max tokens and timeout, overridable under `linguaflow.ai.use-cases.<use-case>.*`. Unknown keys or efforts fail at startup.
  - **No temperature:** current Claude models reject `temperature` and thinking budgets. Effort is the knob, and thinking stays adaptive.
  - **Request shape:** the system prompt is marked for prompt caching (keep templates byte-stable). A schema goes into `output_config.format`. Every call opts into server-side refusal fallbacks (`fallbacks: "default"`, beta header `server-side-fallback-2026-07-01`); a refusal that survives becomes `REFUSED`, and a hit output limit `TRUNCATED`. The SDK retries 429, 5xx and connection errors twice within the timeout.
  - **Tests:** `AnthropicLlmClientTests` checks the exact request and the error translation against a local JDK `HttpServer`, with no network. `AnthropicLiveTests` makes real calls (plain and through `StructuredLlm`) and is skipped unless run with `-Dai.live=true` and `AI_API_KEY` in the environment.
- **Structured output (AI-02):** feature code calls `ai.StructuredLlm.complete(caller, renderedPrompt, AnswerRecord.class, check)` (or, for a hand-built prompt, `complete(caller, useCase, prompt, …)`), never `LlmClient` or `AiGateway` directly. It returns `StructuredResponse<T>` (the value plus every call, for token logging).
  - **Answer records:** `JsonSchemas` turns a record into the JSON Schema sent as structured output. Every component is required; mark the ones that may be null `@Nullable` (jspecify), which becomes `anyOf: [schema, {type: null}]` — the API rejects `type: [..., "null"]` on enums. Supported: `String`, boxed numbers, `Boolean`, enums (by name), `List`/`Set`, nested records. Primitives, maps and recursive records are refused. `@JsonPropertyDescription` and `@JsonClassDescription` become descriptions the model reads.
  - **Constraints:** the schema carries no lengths, ranges or patterns (structured output doesn't accept them). Put them on the record as Bean Validation annotations, with `List<@Valid Nested>` to cascade.
  - **Checks, in order:** parse with Jackson (unknown properties ignored, a Markdown fence stripped), then every non-`@Nullable` component present at any depth, then Bean Validation, then the caller's `OutputCheck` for domain rules (`OutputProblems.requireOneOf("article", value, List.of("der", "die", "das"))`).
  - **Retry:** an invalid answer is retried once, with the same system prompt (still cached) and the user prompt followed by the rejected answer and its problems (`path: message`). A second invalid answer throws `InvalidLlmOutputException` (kind `INVALID_OUTPUT`, not retryable) with the problems and both calls. Provider errors pass through without this retry.
  - **Problem messages:** they reach the model, so they use JSON paths (`examples[0].sentence`) and never Java class names.
  - **Fake client:** `FakeLlmClient` answers every generated schema with a structurally valid sample, so code using `StructuredLlm` runs in dev and tests without scripting; script answers with `respondTo` to test validation and retries (`StructuredLlmTests`).
- **Prompt templates (AI-03):** package `ai.prompt`. Callers do `promptLibrary.render(PromptTemplate.X, PromptVariables.forLanguages("de", "en").set(PromptVar.WORD, …))` and pass the `RenderedPrompt` to `StructuredLlm.complete(caller, rendered, AnswerRecord.class, check)`. Answer records belong to the feature tasks (VOC-05, GRM-04, EX-02), not to `ai`.
  - **Templates:** single and batch vocabulary enrichment, grammar explanation, one exercise template per `ExerciseType` (`PromptTemplate.exercise(type)`), answer evaluation and mistake explanation.
  - **Files:** `src/main/resources/prompts/<id>/v<n>.system.md` and `.user.md`, with shared text in `prompts/partials/<name>.md`, included as `{{> name}}`. Prompts are written in English and name languages in English ("German"); explanations are requested in the explanation language.
  - **Syntax:** a Mustache subset. `{{var}}` inserts a value; `{{#var}}…{{/var}}` is kept only when it's set, and `{{^var}}…{{/var}}` only when it isn't. A tag alone on its line takes the line with it. Inserted values are never parsed again.
  - **Variables:** `PromptVar` lists them all. Each `PromptTemplate` declares its required and optional ones, and every template requires the two language names. `PromptVariables` writes collections as `- item` lines and treats null, blank or empty values as not set.
  - **Checked at startup (`PromptLibrary`):** missing files or partials, malformed tags, undeclared or unused variables, and a system prompt that uses anything but the language names. The system prompt must stay identical across calls so it's cached; per-call data goes in the user prompt. Rendering refuses missing required values and variables the template doesn't declare.
  - **Learner input:** every template includes `partials/learner-data.md` and wraps learner data in XML-style tags (`<word>`, `<learner_answer>`), to be treated as data. AI-07 adds the cleaning, limits and moderation below.
  - **Versions:** never edit a deployed version. Write `v<n+1>` files and bump the version in `PromptTemplate`. `versionTag()` (`vocabulary-enrichment/v1`) is what AI-05 logs as `prompt_version` and AI-06 puts into cache keys. `PromptVersionTests` compares each version's hash (partials included, line endings normalized) with `src/test/resources/prompt-versions.txt`. After adding a version, record it with `./mvnw test -Dtest=PromptVersionTests -Dprompts.update=true`. Editing a shared partial changes every template that uses it.
- **AI job queue (AI-04):** package `ai.job`, on the `ai_jobs` table, so background AI work survives a restart.
  - **Enqueueing:** `AiJobQueue.enqueue(type, userId, payload)` or `enqueueInBatches(type, userId, "vocabularyItemIds", ids)`, which makes one job per `batch-size` (default 20) IDs. Both join the caller's transaction: no job without its data, and a rollback removes the jobs. The request returns at once.
  - **Handlers:** each `AiJobType` gets one `AiJobHandler` bean from its feature task (a second one fails at startup). A job type without a handler fails with "No handler…".
    - `handle(job)` runs **outside any transaction** (open short ones yourself) and must be idempotent, because a retry or a crash can run it again.
    - It returns the result map stored on the job. `onGiveUp(job)` runs once after FAILED or DEAD is committed, e.g. to mark words `AI_FAILED`.
  - **Worker (`AiJobWorker`, a `SmartLifecycle`):**
    - **Claiming:** it polls every `poll-interval` (2 s), right after a commit that enqueued jobs, and whenever a slot frees up. It claims due jobs with `FOR UPDATE SKIP LOCKED` (several instances never run the same job) and marks them RUNNING with one more attempt.
    - **Running:** jobs run on virtual threads, `concurrency` (4) at a time.
    - **Outcome:** recorded only if this worker still holds the job.
  - **Outcomes:**
    - SUCCEEDED, with the result.
    - A retryable `LlmException`, or any unexpected exception, goes back to QUEUED with backoff: `backoff-base` (30 s) doubling per attempt, at most `backoff-max` (1 h). After `max-attempts` (3) it's DEAD, the dead-letter state.
    - A non-retryable kind (`INVALID_OUTPUT`, `REFUSED`, …), or `AiJobException(msg, false)`, is FAILED at once.
    - `last_error` is cut to 1000 characters.
  - **Crashes:** a job RUNNING longer than `stale-after` (10 min) had its worker stop mid-job. Any worker queues it again, or dead-letters it if that was its last attempt. A stopped worker's late outcome is dropped.
  - **Settings:** `linguaflow.ai.jobs.*` (`LinguaFlowProperties.Ai.Jobs`). The worker is **off in the test profile**. `AiJobWorkerTests` builds workers by hand with a `MutableClock` and calls `runDueJobs()`, which runs due jobs in the calling thread; `claim()` and `execute()` let tests simulate a crash.
  - **User status:** `AiJobRepository` is an `OwnedRepository` for AI-08. The worker's system-wide queries are named `…ForProcessing…`.
- **Resilience, quota and cost log (AI-05):** every LLM call goes through `ai.AiGateway` (`StructuredLlm` uses it). It takes an `AiCaller(userId, jobId)`: use `AiCaller.user(id)` for a user's request, `job.caller()` inside a job handler, and `AiCaller.system()` for work that isn't a user's.
  - **Order:** the quota check comes first, then the circuit breaker around the client, then one `ai_requests` row (`ai.usage.AiRequest`). Calls refused by the quota or by an open circuit never reach the provider and aren't logged.
  - **Circuit breaker (Resilience4j core, `resilience4j-circuitbreaker`; no Spring starter):**
    - **What counts:** only TIMEOUT, UNAVAILABLE and NETWORK failures. Bad requests and refusals say nothing about the provider.
    - **Opening:** at 50% of the last 20 calls (after at least 10), calls are refused for 60 s with a retryable `UNAVAILABLE`, so jobs back off.
    - **Settings:** `linguaflow.ai.circuit-breaker.*`. There is one circuit per app instance.
  - **Usage log (`AiRequestLog`):** written in its own `REQUIRES_NEW` transaction, so it's kept when the caller rolls back. A logging failure is only logged, never fails the call.
    - It never stores prompts or answers (SEC-02).
    - `input_tokens` counts every input token, cached or not. A failed call has null tokens and its `LlmException.Kind` in `error_code`.
    - `prompt_version` is the template's `versionTag()`, or `custom` for a hand-built prompt.
  - **Cost (`AiPricing`):** USD per million tokens of each kind (input, 5-minute cache write, cache read, output). The built-in standard prices are from the provider's pricing page on 2026-11-11.
    - **Prices:** Fable 5.2, Opus 5.5, Sonnet 5.5 and Haiku 5.5 (≤100k-token prompts) are built in. Override or add models under `linguaflow.ai.pricing.<model-id>.{input,cache-write,cache-read,output}`.
    - **Matching:** a dated model ID matches its base ID.
    - **Special cases:** fake models cost 0, and an unknown model has a null cost (warned once). `LlmResponse` now carries `cacheWriteTokens`.
  - **Daily quota (`AiQuota`):** `linguaflow.ai.quota.{enabled,daily-requests,daily-tokens}`, defaults 300 calls and 1.5 M tokens per user per **UTC** day, counted from `ai_requests`. System calls aren't limited. It's checked before each call, so concurrent calls may overshoot slightly.
    - **When it's used up:** `AiQuotaExceededException`, an `LlmException` with kind `QUOTA_EXCEEDED`, carrying `resetsAt`.
    - **In the job queue:** the job is postponed to `resetsAt` and the attempt isn't counted, so a big batch resumes the next day.
    - **In a request:** an endpoint that calls the AI while the user waits converts errors with `AiErrors.toApiException(ex, clock)`: 429 `AI_QUOTA_EXCEEDED` with `Retry-After` and `resetsAt`, or 503 `AI_UNAVAILABLE` for any other AI failure (never with the provider's message).
  - **`RetryLaterException(code, detail, retryAfter)`** (`common.error`) is the base for any error with `Retry-After`. The auth rate limit uses it too, and `GlobalExceptionHandler` adds the header.
  - **Endpoints (`AiUsageController`):**
    - `GET /api/ai/usage`: today's calls and tokens against the limits, plus `exceeded` and `resetsAt`.
    - `GET /api/admin/ai-usage?from=&to=` (ADMIN only, UTC days, default the last 30, at most 366): totals, plus by use case, by model and by day, and the top 10 users by cost. A reversed or too-long range is a 400 on `from`.
  - **Tests:** `TestGateways.unlimited(client)` builds a gateway without quota or log for unit tests. `AiUsageApiTests` covers the log, the quota and the endpoints. `AiGatewayTests` covers the circuit.
  - **Answer records** may be package-private in their feature module. `StructuredLlm` makes their accessors accessible when it checks for missing fields.
- **Shared AI cache (AI-06):** package `ai.cache`, on `ai_cache`. Use `StructuredLlm.completeCached(caller, renderedPrompt, key, AnswerRecord.class, check)`.
  - **A hit:** no call is made, so it isn't logged in `ai_requests`, doesn't count against the quota, and has `StructuredResponse.cached()` (no `calls`). It must still pass every check, so a rule tightened since then sends it back to the model.
  - **A miss:** the call runs normally, and the valid answer is stored for the next learner.
  - **Keys (`AiCacheKey`):** use case, learning language (`ai_cache.language_code`), prompt version and normalized parts, stored as a SHA-256 hash.
    - **Language data only:** never a user ID, a learner's own text or anything personal (schema.md Q5).
    - **Enrichment:** `AiCacheKey.vocabularyEnrichment(lang, explanationLang, promptVersion, normalizedLemma, partOfSpeech)` takes `LemmaNormalizer`'s output, so "der Hund" and "Hund" share an entry. The explanation language is part of the key, because translations and notes are written in it.
    - **Versions:** the prompt version is in the key, so a new template version starts a fresh cache. Bump it when the answer record's shape changes.
    - **Learner-specific prompts:** a prompt with learner-specific variables (known vocabulary, translation hint) would cache one learner's flavour for everyone. Cache only calls whose prompt is built from the key's data alone.
  - **`AiCache`:**
    - **Reading:** an entry older than `ttl` (180 days), or one that no longer converts to the record, is a miss, and the next write replaces it.
    - **Writing:** an upsert, with hits counted in `hit_count` and `last_hit_at`. Results over `max-payload-bytes` (64 KB) aren't stored.
    - **Failures:** reads and writes run in their own transactions, and a failure there is logged and treated as a miss or a skipped write, never an error.
    - **Settings:** `linguaflow.ai.cache.{enabled,ttl,max-payload-bytes}`.
  - **Tests:** `AiCacheTests`.
- **Prompt safety (AI-07):** learner input is data, never instructions.
  - **Input pipeline:** `PromptVariables.set` makes every value safe except the two language names (`Shape.TRUSTED`), in this order:
    1. **Clean-up:** NFC, and invisible or control characters removed (`TextSafety.removeInvisible`: C0/C1 controls apart from tab and newline, zero-width and bidi-override characters, BOM, and Unicode tag characters that can hide instructions).
    2. **Personal data redacted:** emails become `[email]`, IBANs `[iban]`, and phone numbers or runs of 8 or more digits `[number]`. Years and small numbers stay.
    3. **Reshaped** by the variable's `PromptVar.Shape`:
       - `LINE` joins lines and spaces into one line.
       - `TEXT` keeps line breaks, with at most one blank line in a row.
       - `LIST` writes one `- item` line per element, each like `LINE`; a single value counts as a one-item list.
    4. **Cut** to `PromptVar.maxLength()`, ending with "…", and lists to `maxItems()`.
    5. **Escaped:** `<` and `>` become `&lt;`/`&gt;`, so input can never close the `<word>`-style tag that delimits it. Templates don't need to change for this.
  - **Moderation:** `TextSafety.looksLikeInjection` flags text that tries to instruct the model ("ignore previous instructions", "ignoriere … Anweisungen", role markers like `system:`, `</system>`). The text is still sent, as delimited data, but `StructuredLlm.completeCached` doesn't store its result in the shared cache.
  - **Flags:** `RenderedPrompt.inputFlags()` (`PromptInputFlag`: TRUNCATED, CONTROL_CHARACTERS_REMOVED, MARKUP_ESCAPED, PERSONAL_DATA_REDACTED, INJECTION_SUSPECTED) records what happened. `StructuredLlm` logs them by template, never with the text.
  - **Model output:** every string in a parsed answer has invisible and control characters removed before mapping to the record (`StructuredLlm.withoutInvisible`). Answers are only ever parsed into records, and the frontend renders AI text as text or sanitized Markdown (SEC-01).
  - **Never executed:** `NoCodeExecutionTests` fails the build if `src/main/java` uses script engines, expression parsers, `Runtime.exec`/`ProcessBuilder`, `Class.forName`, `defineClass` or Java deserialization.
  - **Limits:** when adding a `PromptVar`, choose its shape and limits deliberately; they're the hard ceiling for anything a learner can push into a prompt.
  - **Tests:** `PromptSafetyTests`.
- **AI job status (AI-08):** `ai.job.AiJobController` and `AiJobStatusService`, always scoped by owner. Another user's job is 404.
  - **Endpoints:**
    - `GET /api/ai/jobs/{id}`: one job.
    - `GET /api/ai/jobs`: paged, newest first, with repeatable filters `status` and `type`, and `sort` by `createdAt` or `updatedAt`.
    - `GET /api/ai/jobs/progress?id=…&id=…`: 1–100 IDs. It returns counts by status, `total`, `finished` (every found job is done) and `notFound`, which lists missing and other users' jobs alike.
  - **Polling, not SSE:** jobs take seconds to minutes, so the UI polls every few seconds until `finished` is true.
  - **The `AiJob` body:** status, `finished`, attempts, and `result` once SUCCEEDED (handlers must keep results fit for the owner to see). A QUEUED job also has `nextRunAt` and `waiting`: `RETRY` after a temporary failure, `DAILY_LIMIT` when postponed by the quota (recognised by `AiQuota.LIMIT_REACHED`).
  - **Hidden from the UI:** the raw `last_error`, `locked_by` and the payload.
  - **Wiring:** VOC-05 should return the bulk add's job IDs so the bulk add page can show batch progress through `/progress`.
  - **Tests:** `AiJobApiTests`.
- **`InvalidFieldException(field, message)`** renders a business-rule failure exactly like Bean Validation: 400 `VALIDATION_FAILED` with `errors[]`. Use it for checks that need the database, such as "is incorrect" or "already used".
- **Error codes:** add a specific `ErrorCode` (like `EMAIL_TAKEN`) when the frontend needs to react to one particular error. Adding one changes the OpenAPI spec, so regenerate the snapshot and the frontend types. To carry extra data (such as `existingId`), pass a `properties` map to `ApiException`; the handler copies it into the problem body. Document every such field in `ApiProblem`.
- **`@WebMvcTest`:** always set `controllers = ...`. Otherwise the slice picks up every app controller and fails on their missing services.
- **Dev demo data (DB-05):** `backend/src/main/resources/db/dev/R__dev_seed.sql` is a repeatable Flyway migration. It loads only in the dev profile, which adds `classpath:db/dev` to `spring.flyway.locations`; test and prod never see it.
  - It contains the demo learner `demo@linguaflow.local` (password `demo-password-123`), 53 German words, 10 grammar topics, tags and 2 study sessions.
  - It's generated by `backend/tools/generate_dev_seed.py`. Edit the script and regenerate, rather than editing the SQL by hand. It uses fixed uuid5 IDs and `ON CONFLICT DO NOTHING`, so reruns only add rows.
  - The script's `norm()` must stay identical to `vocabulary.LemmaNormalizer`. `LemmaNormalizerTests` checks every seeded word against the Java code, so a mismatch fails the build. Python isn't installed on the Windows dev machine, so changes that keep the generated SQL the same can be made in the script by hand.
  - The password hash uses the `{bcrypt}` prefix of Spring's `DelegatingPasswordEncoder`, so AUTH-01 should use `PasswordEncoderFactories.createDelegatingPasswordEncoder()`.
  - `DevSeedTests` checks that the seed applies, loads through the entities, and survives a rerun.
- **Migrations:** they live in `backend/src/main/resources/db/migration/` and follow `docs/database/schema.md`, including its migration plan.
  - Name every constraint (`<table>_<what>_{pk,fk,uq,ck,ix}`) so a violation can be mapped to an API error.
  - Never edit a migration that's already been applied; add a new `V<n>__*.sql` instead.
  - Extend `common.persistence.MigrationTests` (`@JdbcTest` on a Testcontainers PostgreSQL) with constraint checks for each new migration. A violated constraint aborts the Postgres transaction, so each test may trigger at most one violation, as its last statement.
- **Pagination:** controllers take a `Pageable` (`page` is zero-based, `size` defaults to 20 and is capped at 100, `sort=field,dir`) and return `PageResponse.from(page, mapper)`. Never return a Spring `Page` directly.
  - **Default size:** don't add `@PageableDefault`: its own default size of 10 overrides the configured 20. Use `@SortDefault` alone for the default sort. `DefaultPageSizeTests` checks every list defaults to 20 and fails on any `@PageableDefault`.
  - **Sort whitelist:** map the allowed API sort names to entity attributes, and reject anything else with an `InvalidFieldException("sort", …)` (see `VocabularyController.SORTABLE`). An unchecked `sort` lets clients sort by any attribute and can turn into a 500.
  - **Reported sort:** when API names differ from entity names (`lemma` sorts by `normalizedLemma`), build the response from the client's `Pageable`, so `sort` reports the names the client used.
  - **Concurrent edits:** an `OptimisticLockingFailureException` from an `@Version` entity becomes 409 `CONFLICT`, asking the client to reload.
- **Entities:** they extend `common.persistence.AuditableEntity`, which fills `created_at` and `updated_at` (`timestamptz not null`) from the injected `Clock`.
- **Time:** inject `java.time.Clock` instead of calling `Instant.now()`.
- **Test-only JPA entities:** they live outside `com.linguaflow` (see `testsupport.auditing`). Otherwise the app's entity scan picks them up and schema validation fails. A test that adds its own `@EntityScan` must also include `LinguaFlowApplication.class`, because the app's repositories need their entities managed.
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
- **HTTP:** API calls go through `src/lib/api.ts`, an Axios instance with base URL `VITE_API_BASE_URL`, default `/api`, and `withCredentials`. Validate responses with Zod schemas.
- **Auth state (FE-03):** code lives in `src/auth/`.
  - **Access token:** held in memory only by `tokenStore`, never in `localStorage`. On startup `AuthProvider` restores the session by calling `/auth/refresh` with the `lf_refresh` cookie.
  - **Client behaviour:** `api` adds `Authorization: Bearer …`. On a 401 it calls `refreshAccessToken()` once and retries the request once. Parallel 401s wait for the same refresh.
  - **Single-flight refresh:** `refreshAccessToken()` is shared by all callers and serialised across tabs with the Web Locks API. The backend treats a reused refresh token as theft, so never call `/auth/refresh` any other way.
  - **Ending the session:** a refresh rejected with 401 clears the token, which logs the user out. Network or 5xx errors during refresh keep the session. When the token is cleared, `AuthProvider` drops the whole query cache.
  - **Auth endpoints:** pass `skipAuthRefresh: true` on calls to them (login, logout, refresh), because their 401 means wrong credentials, not an expired token. Use the functions in `auth/authApi.ts`.
  - **In components:** `useAuth()` returns `{ status: 'loading' | 'authenticated' | 'anonymous', user, login, logout }`. `user` is the `/users/me` profile under the query key `CURRENT_USER_QUERY_KEY`; after a profile change, update that cache entry.
  - **Route guards:** `ProtectedRoute` wraps `AppLayout`. Anonymous visitors go to `/login` with `state.from` (type `AuthPageState` in `auth/redirect.ts`) holding the page they wanted.
    - `GuestRoute` wraps `/login` and `/register`. It sends a logged-in user to `redirectTarget(state)`, which is `state.from` if it's a safe in-app path, otherwise `/dashboard`.
    - Because of that, the login and register pages never navigate on success: setting the token is enough.
  - **Login and registration pages (FE-04):** `pages/auth/`. Their Zod schemas (`authForms.ts`) mirror the backend rules, including the password limits: at least 10 characters and at most 72 UTF-8 bytes. `authErrorMessage` turns API errors into the form-level message.
    - Registration logs straight in afterwards. If that login fails, the user lands on `/login` with `state.registeredEmail`, which prefills the email field.
  - **Password change:** it returns new tokens. Put the new access token in `tokenStore.set(...)`.
- **Learning languages and onboarding (FE-05):**
  - **API:** `src/languages/languagesApi.ts` has `fetchLanguages`, `fetchMyLanguages`, `addLanguage` and `updateLanguage`, with Zod-validated `AvailableLanguage` and `LearnedLanguage`. The hooks in `languages/queries.ts` are `useLanguages()` and `useMyLanguages()`. After changing a learning language, update or invalidate `MY_LANGUAGES_QUERY_KEY`.
  - **Gate:** `OnboardingGate`, inside `ProtectedRoute`, wraps `AppLayout`. A user with no learning language goes to `/onboarding` with `state.from`.
  - **Wizard:** `pages/onboarding/OnboardingPage.tsx` asks for target language, explanation language, level ("not sure" sends no level) and daily goal (`dailyReviewGoal`).
    - It saves with POST and then PATCH. On a retry, a 409 `CONFLICT` from the POST is ignored, so a half-finished save completes.
    - Like `GuestRoute`, the page itself redirects once the user has a language, so finishing only has to invalidate the query.
- **Vocabulary list (FE-VOC-01):** `pages/vocabulary/VocabularyPage.tsx`, with its data layer in `src/vocabulary/`.
  - **Data layer:** `vocabularyApi.ts` has the Zod schemas, `fetchVocabulary`, `deleteVocabularyItem` and `fetchTags`. `queries.ts` has `useVocabularyList` (keeps the previous page on screen while the next loads), `useTags` and `useDeleteVocabularyItem`.
  - **Query keys:** after changing a word, invalidate `VOCABULARY_QUERY_KEY` and `TAGS_QUERY_KEY` (tag word counts). Delete does both.
  - **State in the URL:** filters, sort and page live in the URL (`?q=&status=&cefrLevel=&partOfSpeech=&tagId=&sort=&page=`; `page` is 1-based there and 0-based in the API). `vocabularyFilters.ts` parses it and ignores unknown values. Filter changes go back to page 1 and replace the history entry; paging pushes one.
  - **Search:** debounced by 300 ms (`SEARCH_DELAY_MS`). `VocabularyFilterBar` keeps the typed text in local state and only resets it when the URL changes from outside (Clear filters, back button).
  - **Field pitfall:** `Field` wires its label and ARIA to its *direct* child, so never wrap the control in a div inside `Field`. Put decorations such as the search icon outside it.
  - **Table:** less important columns hide on narrow screens (status moves under the word), and there's no horizontal scrolling at 375 px. AI status shows as "AI pending" or "AI failed" badges; READY shows nothing.
  - **Delete:** a `ConfirmDialog`. On failure the dialog stays open and the global toast reports the error. Deleting the last word of a page steps back a page.
- **Add and edit a word (FE-VOC-02):** `WordModal` holds `WordForm`. "Add word" (header and empty state) opens it in add mode; clicking a word in the table opens edit mode, which loads `GET /vocabulary/{id}` (`useVocabularyItem`).
  - **Form logic:** the pure functions live in `wordFormValues.ts`. It isn't named `wordForm.ts` because on Windows that collides with `WordForm.tsx` (TS1261).
  - **Requests:** `toCreateRequest` sends only filled-in fields. `toUpdateRequest` sends only changed fields (null when nothing changed) and `""` to clear text. Gender and plural are only shown and sent for nouns or untyped words.
  - **Clearing choices:** every choice has a "Not set" option. Emptying a type, gender or level (or all three verb forms) sends it in the PATCH's `clear` (VOC-06). MASTERED appears only as a disabled option on mastered words.
  - **Edit mode:** AI-generated fields (`aiGeneratedFields`), notes and verb forms included, get the hint "Filled in by AI…".
    - **Examples:** editable in both modes (`useFieldArray`, up to 10). An example's ID travels as `exampleId`, because RHF uses `id` as its own row key. Unchanged AI examples keep their "AI example" badge.
    - **Verb forms:** a verb gets Präteritum, Partizip II and "Perfect with" fields, edit mode only; the schema requires all three or none. Server errors on `verbForms.*` map onto them through `toFormField`.
  - **Ask AI again (VOC-06):** the edit dialog's "Ask AI again" button calls `regenerateVocabularyItem`, toasts and closes. It's disabled while the form has unsaved changes or the word is still `PENDING_AI`.
  - **Pending words:** a pending word shows an info alert. `useVocabularyItem` and `useVocabularyList` poll every 5 s (`PENDING_AI_POLL_MS`) while a shown word is `PENDING_AI`, and the form adopts the refreshed values unless the learner has started typing.
  - **Duplicates:** a 409 `VOCABULARY_DUPLICATE` shows an info alert with "Open “…”", which switches the modal to edit the existing word. The page keys `WordModal` by target, so the form starts fresh.
  - **Tags:** checkboxes, plus a "New tag" input. Enter adds the tag without submitting, and a `TAG_NAME_TAKEN` selects the existing tag.
  - **After saving:** `useInvalidateVocabulary()` refreshes lists, words and tag counts.
  - **Focus:** add mode focuses the word field through `Modal initialFocus`. `WordForm` takes a `lemmaRef` merged with RHF's ref.
- **Bulk add screen (FE-VOC-03):** `pages/vocabulary/BulkAddPage.tsx` at `/vocabulary/add`, linked as "Add many" from the vocabulary header. It's a route of its own, not in `NAV_ITEMS`. The API calls are in `src/vocabulary/bulkApi.ts`.
  - **Three steps in local state:** paste (Text or CSV via `RadioCards`, counts lines, at most 500), review, done. Going back keeps the text.
  - **Review:** one row per line. NEW lines are preselected; DUPLICATE and INVALID rows are tinted, can't be selected and show the server's reason. A repeat within the list is labelled "Repeated", one already in the vocabulary "Already added". There are optional tags for all added words, and "Add N words" confirms only the selected lines.
  - **Done:** shows what was added and skipped, plus an AI panel that follows this bulk add's own jobs.
    - **Polling:** `useAiJobProgress(result.aiJobIds)` (`src/ai/aiJobsApi.ts`) polls `GET /ai/jobs/progress` every 3 s until `finished` is true.
    - **What it shows:** "N of M batches done" while running; then "ready", or "AI couldn't fill in every word" when a job failed or was dead-lettered.
    - **Afterwards:** it invalidates `VOCABULARY_QUERY_KEY`, so lists show the AI's results.
  - **Status colours:** only the `-soft-fg` tokens exist for success, warning and info (`text-success-soft-fg`). `text-success` isn't a token and renders uncoloured.
- **`ButtonLink`** (design system): a router `Link` with the Button look. Use it for navigation, and Button for actions. The shared classes are in `buttonStyles.ts`, because the fast-refresh lint rule forbids exporting non-components from `Button.tsx`.
- **Grammar topics page (FE-GRM-01):** `pages/grammar/GrammarPage.tsx` at `/grammar`, with its data layer in `src/grammar/` (`grammarApi.ts`, and `queries.ts` with `GRAMMAR_QUERY_KEY`; invalidate it after any topic change, since it also refreshes the catalog's "Added" marks).
  - **Loading:** `fetchGrammarTopics` loads *all* matching topics page by page (size 100), sorted by `cefrLevel,title`. Learners have tens of topics, so the page groups them by CEFR level (A1 first, "No level set" last) rather than paging.
  - **Filters:** `q`, `status` and `cefrLevel` live in the URL, replacing the history entry; search is debounced like the vocabulary list's.
  - **`AddTopicModal`:** two `Tabs`. "Suggestions" filters the catalog client-side by level and text, shows "Added" for entries the learner has, and adds with `POST /grammar-topics/catalog/{id}`; the dialog stays open, so several can be added. "Your own" is a small RHF form (title, level, starting status, description); a duplicate title is a field error.
  - **Topic cards:** the title links to the topic's detail page.
  - **Accessible names:** don't build them as `Add<span className="sr-only"> {name}</span>`. Name computation drops the span's leading space ("AddDativ"), so use `aria-label` instead.
- **Grammar topic detail (FE-GRM-02):** `pages/grammar/topic/GrammarTopicPage.tsx` at `/grammar/:id`. Each area is a `SettingsSection` card.
  - **Status (`TopicStatusSection`):** `RadioCards`. NEW is only offered while the topic is NEW. MASTERED opens a `ConfirmDialog` and sends `confirmMastered: true`. The status history is listed newest first, with reasons ("by you", "from your practice results").
  - **Notes (`TopicNotesSection`):** Write/Preview `Tabs`. The preview uses `MarkdownView` (react-markdown, lazy-loaded into its own chunk, so the main bundle stays around 650 kB).
  - **Safe Markdown:** never add `rehype-raw` or render notes as HTML. `GrammarTopicPage.test.tsx` checks that `<script>`, `<img onerror>` and `javascript:` links are neutralized (SEC-01). `MarkdownView` strips react-markdown's `node` prop before spreading props onto DOM elements.
  - **AI explanation (`AiExplanationSection`):** the "Explain with AI" button (later "Write a new explanation") calls `requestExplanation`, and `useAiJob` (`src/ai/aiJobsApi.ts`) polls the job every 3 s.
    - **While it runs:** a status line, which also covers waiting for a retry or the daily AI allowance (`waiting`).
    - **When it ends:** on SUCCEEDED the topic query is invalidated, so the explanation appears. FAILED or DEAD shows an error and offers the button again.
    - **Display:** summary, rules (`whitespace-pre-line`, for their text tables), examples with translations, and common mistakes.
  - **Details (`TopicDetailsSection`):** title, level and description, sending only changed fields. The level can't be cleared once set, and a duplicate title is a field error.
  - **Saving:** `useSaveTopic` puts the PATCH result into the topic's cache entry and invalidates the rest of `GRAMMAR_QUERY_KEY`.
  - **Deleting:** navigate away first, then `removeQueries` the topic and its history before invalidating; refetching the deleted topic would show a 404 refresh-error toast. A 404 on load shows "Topic not found".
  - **Stats:** practice stats (attempts, accuracy) wait for the PRG tasks.
- **Study sessions (FE-SES-01):** `pages/sessions/SessionsPage.tsx` at `/sessions` and `SessionPage.tsx` at `/sessions/:id`, with the data layer in `src/sessions/` (`sessionsApi.ts`, and `queries.ts` with `SESSIONS_QUERY_KEY`).
  - **After a change:** `useInvalidateSessions()` refreshes sessions, vocabulary and grammar, since their lists can be filtered by lesson.
  - **List page:** a banner for the active lesson (Open, End lesson), then the SES-02 timeline: one region per month (`formatMonth`), each with lesson cards.
  - **New lesson:** `NewSessionModal` asks for title, date (defaults to the viewer's today), notes and "Make it the active lesson" (ticked by default). It then opens the lesson.
  - **Dates:** lesson dates are calendar dates, so `sessionFormat.ts` formats them in UTC, which keeps the day from shifting with the viewer's time zone.
  - **Lesson page:** a make-active or end button, Words and Grammar topics lists (A–Z, each with a remove button), a details form sending only changed fields, and delete. Deleting removes the lesson's cached query before refreshing.
  - **Loading a lesson's material:** words load through `fetchSessionWords` (all pages, size 100, `sessionId` filter on `fetchVocabulary`); topics through `fetchGrammarTopics({ sessionId })`.
  - **Pickers (`AddMaterialModals.tsx`):** a shared searchable checklist. Words are searched on the server (debounced, first 50 shown); topics are filtered on the client. Material already in the lesson is shown ticked and locked.
  - **Dialog focus:** a dialog with a form must pass `initialFocus` to its first field (see `NewSessionModal` and `WordModal`). Otherwise `Modal` focuses the close button, and typing right after opening goes nowhere.
- **Flashcard review (FE-FC-01):** `pages/flashcards/ReviewPage.tsx` at `/flashcards`, with the data layer in `src/reviews/` (`reviewsApi.ts`, `queries.ts` with `REVIEWS_QUERY_KEY`, and the pure `reviewFormat.ts`).
  - **Overview (`ReviewOverview`):** what's due today plus new cards, a "Type the answer" checkbox (remembered in `localStorage` under `lf-review-typing`), "Start review", tiles for reviewed today against the goal, streak and retention, and the 7-day forecast as one-hue bars with their counts and a labelled list ("Cards due by day"). With nothing to review it shows "All caught up" and the next due day.
  - **Session (`useReviewSession`):** loads `GET /reviews/due` (50 cards); when the batch is done it asks again, so learning steps that came due and cards beyond the first batch continue the session. The progress bar counts graded plus remaining cards. Every showing of a card gets a new `crypto.randomUUID()` review token, kept on a failed submit, so a retry is recorded once. The summary shows counts per grade, % remembered and time, and when learning cards come back ("Check for due cards"). Stats are invalidated when a session ends.
  - **Card (`ReviewCard`, remounted per showing via `key={token}`):** a 3D flip (arbitrary properties like `[transform:rotateY(180deg)]`, both faces stacked in one grid cell so the card takes the taller face's height; `motion-reduce` turns the animation off). The hidden face is `inert` and `aria-hidden`. The back shows plural, Präteritum, Perfekt and examples.
  - **Keys:** Space (or Enter) shows the answer, 1–4 grade it. The handler ignores inputs, modifier keys and repeats, and leaves Space and Enter to a focused button. After the flip, focus moves to the answer (outline turned off inline: the global `:focus-visible` rule is unlayered and beats utility classes).
  - **Typed answers:** `checkAnswer` ignores case, spacing, trailing punctuation and ß/ss, and accepts any of a back's comma-separated meanings. If the expected answer has an article, a missing or wrong one gives "Almost: check the article". The result suggests a grade (correct → Good, article → Hard, wrong → Again) with a ring; the learner still chooses.
  - **Article colours:** `GermanText` colours a leading der/die/das with the `text-article-der/die/das` tokens (blue, rose, green; light and dark values in `index.css`). Plurals aren't coloured, since their "die" isn't a gender.
  - **Grade buttons:** each shows its next interval from the queue's `previews` (`formatInterval`: "1 min", "4 days", "2 mo", "1.5 yr").
  - **Tests:** `ReviewPage.test.tsx` and `reviewFormat.test.ts`.
  - **Browsing cards:** the "All cards" button (hidden during a session) opens the browser.
- **Flashcard browser (FE-FC-02):** `pages/flashcards/FlashcardBrowserPage.tsx` at `/flashcards/browse`, a route of its own (not in `NAV_ITEMS`). The data layer is `src/flashcards/` (`flashcardsApi.ts`, and `queries.ts` with `FLASHCARDS_QUERY_KEY`). It reuses `flashcardSchema` and `reviewSchema` from `reviews/reviewsApi.ts`.
  - **State in the URL** (`browserFilters.ts`): `?q=&direction=&state=&suspended=yes|no&sort=due&page=` (1-based page, default sort newest), with a debounced search like the vocabulary list's.
  - **Table:** "front → back" with coloured articles, direction, SRS state and a relative due date (`formatDue`). Suspended rows are dimmed and badged, and on narrow screens the state moves under the card.
  - **Details (`CardDetailsModal`):** front and back, state, due date, interval, repetitions, lapses and last review, plus the review history from `GET /flashcards/{id}/reviews` (latest 100). It has a Suspend/Unsuspend button (`useSetSuspended`, which refreshes the lists and review stats).
  - **Refreshing:** a review session invalidates `FLASHCARDS_QUERY_KEY` with the stats when it ends.
  - **Tests:** `FlashcardBrowserPage.test.tsx`.
- **Crash test:** `errorHandling.test.tsx` crashes the Progress placeholder. Move it again when that section is built.
- **Settings (FE-06):** `pages/settings/SettingsPage.tsx` has one `SettingsSection` card per area: profile, one per learning language, password, and deleting the account.
  - **Account API:** the calls are in `src/account/accountApi.ts`. `changePassword` stores the new access token, and `deleteAccount` clears the session.
  - **After saving:** the profile updates `CURRENT_USER_QUERY_KEY` and the language settings update `MY_LANGUAGES_QUERY_KEY` with the response, so the sidebar and other screens refresh without a refetch. Reset the form with the saved values so Save is disabled again.
  - **Removing a language:** this needs a `ConfirmDialog`, because the backend deletes all its data. When the last language is removed, `OnboardingGate` sends the user to onboarding.
  - **Styling note:** `cn` only joins class names; it doesn't resolve Tailwind conflicts. You can't recolour a `Button` variant with `className` (e.g. `text-danger` on `ghost`), so use another variant or a plain element.
- **Routes:** all routes are defined in `src/app/routes.tsx`.
  - `AuthLayout` (a centered card, no navigation) wraps `/login` and `/register`; `<AuthLayout width="wide" />` wraps `/onboarding`.
  - `AppLayout` wraps the sections from `src/app/navigation.ts` (`NAV_ITEMS`: path, label, lucide icon, description). `AppLayout` has a sidebar at `lg` and up, and a top bar with a `MobileNav` drawer below that.
  - `/` redirects to `/dashboard`, and `*` shows `NotFoundPage`.
  - Every route sets `handle: { title }`; `RouteTitle` turns it into `document.title` ("Vocabulary · LinguaFlow").
  - To build a section, add its page to `SECTION_PAGES` in `routes.tsx`; sections without one show `PlaceholderPage`. Add new sections to `NAV_ITEMS`.
  - Tests render routes with `renderRoute(path, { session, user, languages })` from `src/test/renderWithProviders.tsx`, which uses a memory router, a fresh QueryClient and `AuthProvider`.
    - By default it starts logged in as `testUser`, learning German (`testLearnedLanguage`). Pass `session: 'anonymous'` to start without a session, or `languages: []` for a user who hasn't onboarded yet.
    - The profile, the user's languages and the language catalog (`testCatalog`) are answered by fake-API fallbacks, which a test's own `fakeApi.on` overrides.
    - It returns `{ router, queryClient }`. Its query client comes from `createQueryClient` (retries off), with `ErrorToasts` mounted, so tests see the same toasts as the app.
    - The app shell appears asynchronously, after the session and language checks. Use `await renderApp(path)` when a test needs the shell. `src/app/routes.test.tsx` covers the shell and `src/auth/auth.test.tsx` the session flows.
  - **No network in tests:** `src/test/setup.ts` installs `fakeAdapter` on `api`. Answer requests with `fakeApi.on(method, url, reply | handler)`, where a reply is `{ status, data?, headers? }` and `status: 0` simulates a network failure, and inspect them with `fakeApi.requests(url)` (each entry records method, url, Authorization header, parsed body and `query`, the query string built from axios `params`) or `sentBody(method, url)`. An unregistered request fails the test. `/auth/refresh` answers 401 unless the test registers a handler. `fakeApi.fallback` is for helpers' defaults, which a test's own `on` overrides. `renderRoute` answers `GET /vocabulary` and `GET /grammar-topics` with an empty page, and `GET /tags` and `GET /grammar-topics/catalog` with `[]`, `GET /study-sessions/timeline` with an empty timeline, and `GET /study-sessions/active` with 204, by default.
- **Design system (FE-02):** building blocks live in `src/components/ui` and are imported from `@/components/ui`. They are Alert, Button, RadioCards (a single choice shown as cards, built on native radios), LoadError, PageSkeleton/CardSkeleton, Field with Input, Select and Textarea, Badge/CefrBadge/StatusBadge, Card, Table, Tabs, Modal, ConfirmDialog, Toast (`useToast`), EmptyState, Skeleton/SkeletonText, Spinner and ThemeToggle. Build pages from these rather than raw elements.
  - **Colours:** use only the semantic tokens from `src/index.css` (`bg-canvas`, `bg-surface`, `bg-surface-muted`, `text-fg`, `text-fg-muted`, `text-fg-subtle`, `border-border`, `bg-primary`, `bg-danger`, `bg-{success,warning,info,danger}-soft` and so on). **Never use raw palette classes** like `bg-slate-50`. Light and dark mode both come from the tokens.
  - **Theme:** `ThemeProvider` stores `system`, `light` or `dark` in `localStorage` under `lf-theme` and sets `<html data-theme>`. A script in `index.html` applies the stored theme before first paint.
  - **Forms:** wrap every control in `<Field label hint error>`, which wires `htmlFor`, `aria-describedby` and `aria-invalid`.
  - **Dialogs:** `Modal` traps focus, closes on Escape and returns focus to its opener; `initialFocus` picks the element focused on open. `ConfirmDialog` focuses Cancel first and takes an async `onConfirm`.
    - **Tall dialogs:** the overlay scrolls, so a dialog taller than the window stays reachable. The panel sits in a `min-h-full` centring layer, and a click on that layer beside the panel closes it like the backdrop.
  - **Toasts:** errors go to an assertive live region, others to a polite one.
  - **Contexts:** they live in `*Context.ts` files, separate from the provider components, because the react-refresh lint rule requires it.
  - **Showcase:** `/design-system` is a **dev-only** page showing every component. It isn't in the production bundle.
- **Icons:** `lucide-react`. Import named icons only, so the bundle keeps only the icons used.
- **Accessibility:** navigation landmarks have `aria-label="Main"`, `NavLink` sets `aria-current="page"`, the drawer is `role="dialog"`, closes on Escape and focuses its close button, and icon-only buttons carry `sr-only` labels.
- **Formatting:** Prettier with no semicolons and single quotes.
- **API types are generated, never hand-written** (INF-09). The pipeline:
  1. The backend's `OpenApiDocsTests` keeps the committed snapshot `frontend/openapi.json` in sync with `/v3/api-docs/all`. The test fails when the snapshot is stale.
  2. `npm run api:generate` (openapi-typescript) writes `src/api/schema.ts`. CI fails if the file is stale.
  3. Import aliases from `src/api/types.ts`. Add an alias there for each new DTO.
  - After changing the backend API, run `./mvnw test -Dtest=OpenApiDocsTests -Dopenapi.update=true` in `backend/`, then `npm run api:generate` in `frontend/`, and commit both files.
- **Error handling:** read API errors with `src/api/errors.ts` (`getApiProblem`, `hasErrorCode`, `getFieldErrors`, `getStatus`, `isNetworkError`, `getRetryAfterSeconds`), which uses the generated `ApiProblem` type. For the message shown to users, use `describeError(error)`: it gives the connection message when offline, a generic message for 5xx (never the raw detail), and otherwise the problem's `detail`.
- **Global error and loading handling (FE-07):**
  - **Render errors:** a crash inside a section shows `RouteErrorPage inline` inside the shell, so the navigation keeps working. Any other route error shows the full-page `RouteErrorPage`. `AppErrorBoundary` in `main.tsx` catches crashes outside the router. In dev, the error page shows the stack.
  - **When a failed request shows a toast:** `createQueryClient()` in `lib/queryClient.ts` decides, and `ErrorToasts` (inside `ToastProvider`) shows them.
    - A query whose first load fails: no toast. The component shows it with `<LoadError title onRetry retrying>`.
    - A query whose refresh fails while data is already on screen: toast.
    - A failed mutation: toast, unless it has its own `onError` or `meta: { errorToast: false }`. Use the latter when the form shows the error itself.
    - A 401 (session ended): never a toast.
    - The same notice within 4 seconds is shown once.
  - **Loading:** use `<PageSkeleton label cards withHeader>` (a busy status region) for pages and sections. `FullPageLoader` is only for the session and onboarding checks before any page renders. Don't build ad hoc spinners or skeletons.
- **Forms:** use React Hook Form with `zodResolver` and `noValidate` on the `<form>`. Map a backend `VALIDATION_FAILED` response onto the fields with `applyServerFieldErrors(error, setError, fields)` from `lib/formErrors.ts`. Backend messages are fragments ("is incorrect"), so pass a `labels` map to turn them into sentences ("Current password is incorrect."). Nested backend fields such as `settings.dailyReviewGoal` must be mapped by hand (see `LanguageSettingsSection`). When it returns false, show the error as a form-level `<Alert variant="danger">` above the fields.
- **Alerts in tests:** the toast provider always renders an empty `role="alert"` region. Use `findAlert()` and `queryAlerts()` from `src/test/queries.ts` instead of `getByRole('alert')`.
- **openapi-typescript and TypeScript 6:** openapi-typescript only declares support for TypeScript 5, so an npm `overrides` entry in `package.json` points it at the project's TypeScript.

## CI

`.github/workflows/ci.yml` runs on every pull request and on every push to `main`. It has two jobs:
- **backend:** `./mvnw -B -ntp verify`, with Testcontainers using the runner's Docker.
- **frontend:** `npm ci`, `format:check`, `lint`, `typecheck`, `test`, `build`.

The frontend job also fails if `src/api/schema.ts` doesn't match `openapi.json`. Run the same commands locally before pushing; unformatted frontend code fails CI. A third job, **docker**, validates `docker-compose.yml`, builds both images, starts the stack and waits until `/actuator/health` through the frontend's nginx reports UP.

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
