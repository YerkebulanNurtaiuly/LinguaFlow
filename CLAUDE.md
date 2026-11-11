# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

LinguaFlow is an AI-assisted language-learning platform (German first), built as a Spring Boot backend and a React + TypeScript + Tailwind frontend. The repo is at an early stage. INF-01 to INF-03 and INF-05 to INF-09 are done. INF-04 (Docker Compose) is parked, half-finished, on branch `inf-04-docker-compose`:
- `backend/` is a bootstrapped Spring Boot app: Boot 4.1.2, Java 21, base package `com.linguaflow`, with one package per domain module and a temporary `common.config.SecurityConfig`.
- `frontend/` is a Vite app with the app shell and full route map (FE-01). Sections show placeholders until their feature task builds them; Settings (FE-06), Vocabulary (FE-VOC-01 to 03), Grammar (FE-GRM-01, FE-GRM-02) and Study sessions (FE-SES-01) are built.
- `docs/database/schema.md` is the approved schema design from DB-01. Migrations must follow it, and schema changes update it in the same PR. `docs/technical-specification/` is still a placeholder.
- Flyway migrations exist up to `V5__grammar_catalog_seed.sql` (V4 is AUTH-07, V5 is GRM-03's catalog seed). Phase 2 has started: VOC-01 (vocabulary service), VOC-02 (vocabulary API), VOC-03 (tags), VOC-04 (bulk input) and VOC-07 (CSV import/export) are done; VOC-05 and VOC-06 wait for the AI layer. GRM-01 (grammar topic API), GRM-02 (status lifecycle) and GRM-03 (topic catalog) are done, and so are SES-01 (study sessions) and SES-02 (learning timeline). The AI epic has started: AI-01 (LLM client), AI-02 (structured output) and AI-03 (prompt templates) are done. V1 (DB-02) has identity and languages; V2 (DB-03) has vocabulary, tags, grammar topics and catalog, and study sessions; V3 (DB-04) has flashcards and SRS, exercises and attempts, mistakes, practice sessions, progress summary tables, and the AI job, request and cache tables. V3 tables have no entities yet; they come with their feature tasks (FC-01, EX-01, PRC-02 and so on). The JPA entities for V2 are in `vocabulary`, `grammar` and `learning`. `User` and `UserRepository` are in the `user` module (AUTH-01), which the `auth` module will use. There's no Docker Compose yet.

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
- **Learning settings (USR-03):** each `UserLanguage` carries `LearningSettings`: `dailyReviewGoal` 1–1000 (default 50), `newCardsPerDay` 0–500 (default 20), `preferredExerciseTypes` (empty means no preference), and `practiceSessionLength` 5–200 (default 20).
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
- **Grammar topics (GRM-01):** `grammar.GrammarTopicService` and `GrammarTopicController` serve `/api/grammar-topics` (GET list, POST, and GET, PATCH, DELETE `/{id}`). They are built like the vocabulary API.
  - **Listing:** filters `q` (title or description), `cefrLevel` and `status` (repeatable), with `sort` by `createdAt` (default, newest first), `updatedAt`, `title`, `cefrLevel` or `status`. It uses the Criteria fragment `GrammarTopicSearch` and the `SORTABLE` whitelist.
  - **Duplicate titles:** `TitleNormalizer` reuses `LemmaNormalizer` (case, spacing, ß/ss, one leading article), so "Der Genitiv" and "genitiv" are the same topic. That also matches the dev seed's `normalized_title` (`TitleNormalizerTests` checks it). A clash is 409 `GRAMMAR_TOPIC_DUPLICATE` with `existingId`, also when two requests race (`grammar_topics_title_uq`).
  - **Status lifecycle (GRM-02):** the rules live in `GrammarTopic`.
    - **Creating:** a topic starts as NEW, LEARNING or LEARNED, never MASTERED.
    - **By hand (`changeStatusByUser`):** LEARNING and LEARNED can be chosen freely in either direction. MASTERED needs `confirmMastered: true` on the PATCH; the frontend should ask first. NEW can't be chosen again, and choosing the current status records nothing.
    - **By rules (`changeStatusByRule`, for PRG-02):** `MASTERY_RULE` may only grant MASTERED, and `DEMOTION_RULE` may only move to LEARNING or LEARNED.
    - **Errors:** a refused change is a 400 field error on `status`; the entity's `IllegalArgumentException` fragment is mapped by the service. The rest of the PATCH is rolled back too.
    - **History:** every change is recorded in `grammar_topic_status_history` (reason CREATED, MANUAL, MASTERY_RULE or DEMOTION_RULE) with the injected `Clock`. It's served oldest first by `GET /api/grammar-topics/{id}/status-history`, with ties ordered by the UUIDv7 id.
  - **Text fields:** `personalNotes` is Markdown, so only trailing whitespace is stripped (max 20,000). The description max is 2,000 and the title max 200. On PATCH, `""` clears the description or notes. The response includes `aiExplanation` (null until GRM-04) and `version`.
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
- **Structured output (AI-02):** feature code calls `ai.StructuredLlm.complete(useCase, prompt, AnswerRecord.class[, OutputCheck])`, never `LlmClient` directly. It returns `StructuredResponse<T>` (the value plus every call, for token logging).
  - **Answer records:** `JsonSchemas` turns a record into the JSON Schema sent as structured output. Every component is required; mark the ones that may be null `@Nullable` (jspecify), which becomes `anyOf: [schema, {type: null}]` — the API rejects `type: [..., "null"]` on enums. Supported: `String`, boxed numbers, `Boolean`, enums (by name), `List`/`Set`, nested records. Primitives, maps and recursive records are refused. `@JsonPropertyDescription` and `@JsonClassDescription` become descriptions the model reads.
  - **Constraints:** the schema carries no lengths, ranges or patterns (structured output doesn't accept them). Put them on the record as Bean Validation annotations, with `List<@Valid Nested>` to cascade.
  - **Checks, in order:** parse with Jackson (unknown properties ignored, a Markdown fence stripped), then every non-`@Nullable` component present at any depth, then Bean Validation, then the caller's `OutputCheck` for domain rules (`OutputProblems.requireOneOf("article", value, List.of("der", "die", "das"))`).
  - **Retry:** an invalid answer is retried once, with the same system prompt (still cached) and the user prompt followed by the rejected answer and its problems (`path: message`). A second invalid answer throws `InvalidLlmOutputException` (kind `INVALID_OUTPUT`, not retryable) with the problems and both calls. Provider errors pass through without this retry.
  - **Problem messages:** they reach the model, so they use JSON paths (`examples[0].sentence`) and never Java class names.
  - **Fake client:** `FakeLlmClient` answers every generated schema with a structurally valid sample, so code using `StructuredLlm` runs in dev and tests without scripting; script answers with `respondTo` to test validation and retries (`StructuredLlmTests`).
- **Prompt templates (AI-03):** package `ai.prompt`. Callers do `promptLibrary.render(PromptTemplate.X, PromptVariables.forLanguages("de", "en").set(PromptVar.WORD, …))` and pass the `RenderedPrompt` to `StructuredLlm.complete(rendered, AnswerRecord.class, check)`. Answer records belong to the feature tasks (VOC-05, GRM-04, EX-02), not to `ai`.
  - **Templates:** single and batch vocabulary enrichment, grammar explanation, one exercise template per `ExerciseType` (`PromptTemplate.exercise(type)`), answer evaluation and mistake explanation.
  - **Files:** `src/main/resources/prompts/<id>/v<n>.system.md` and `.user.md`, with shared text in `prompts/partials/<name>.md`, included as `{{> name}}`. Prompts are written in English and name languages in English ("German"); explanations are requested in the explanation language.
  - **Syntax:** a Mustache subset. `{{var}}` inserts a value; `{{#var}}…{{/var}}` is kept only when it's set, and `{{^var}}…{{/var}}` only when it isn't. A tag alone on its line takes the line with it. Inserted values are never parsed again.
  - **Variables:** `PromptVar` lists them all. Each `PromptTemplate` declares its required and optional ones, and every template requires the two language names. `PromptVariables` writes collections as `- item` lines and treats null, blank or empty values as not set.
  - **Checked at startup (`PromptLibrary`):** missing files or partials, malformed tags, undeclared or unused variables, and a system prompt that uses anything but the language names. The system prompt must stay identical across calls so it's cached; per-call data goes in the user prompt. Rendering refuses missing required values and variables the template doesn't declare.
  - **Learner input:** every template includes `partials/learner-data.md` and wraps learner data in XML-style tags (`<word>`, `<learner_answer>`), to be treated as data. Length limits and stripping are AI-07.
  - **Versions:** never edit a deployed version. Write `v<n+1>` files and bump the version in `PromptTemplate`. `versionTag()` (`vocabulary-enrichment/v1`) is what AI-05 logs as `prompt_version` and AI-06 puts into cache keys. `PromptVersionTests` compares each version's hash (partials included, line endings normalized) with `src/test/resources/prompt-versions.txt`. After adding a version, record it with `./mvnw test -Dtest=PromptVersionTests -Dprompts.update=true`. Editing a shared partial changes every template that uses it.
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
  - **Choices can't be cleared:** the PATCH can't clear type, gender or level, so once one is set its select has no empty option. MASTERED appears only as a disabled option on mastered words.
  - **Edit mode:** AI-generated fields (`aiGeneratedFields`) get the hint "Filled in by AI…", and examples are shown read-only (the API can't edit them yet). Add mode can add up to 10 examples (`useFieldArray`).
  - **Duplicates:** a 409 `VOCABULARY_DUPLICATE` shows an info alert with "Open “…”", which switches the modal to edit the existing word. The page keys `WordModal` by target, so the form starts fresh.
  - **Tags:** checkboxes, plus a "New tag" input. Enter adds the tag without submitting, and a `TAG_NAME_TAKEN` selects the existing tag.
  - **After saving:** `useInvalidateVocabulary()` refreshes lists, words and tag counts.
  - **Focus:** add mode focuses the word field through `Modal initialFocus`. `WordForm` takes a `lemmaRef` merged with RHF's ref.
- **Bulk add screen (FE-VOC-03):** `pages/vocabulary/BulkAddPage.tsx` at `/vocabulary/add`, linked as "Add many" from the vocabulary header. It's a route of its own, not in `NAV_ITEMS`. The API calls are in `src/vocabulary/bulkApi.ts`.
  - **Three steps in local state:** paste (Text or CSV via `RadioCards`, counts lines, at most 500), review, done. Going back keeps the text.
  - **Review:** one row per line. NEW lines are preselected; DUPLICATE and INVALID rows are tinted, can't be selected and show the server's reason. A repeat within the list is labelled "Repeated", one already in the vocabulary "Already added". There are optional tags for all added words, and "Add N words" confirms only the selected lines.
  - **Done:** shows what was added and skipped, plus an AI panel. `usePendingAiCount` polls `GET /vocabulary?aiStatus=PENDING_AI&size=1` every 10 s until it reaches 0. It counts *all* the learner's words waiting for AI, not just this batch; per-batch job progress comes with AI-04 and AI-08.
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
  - **AI explanation (`AiExplanationSection`):** shows summary, rules and examples once GRM-04 fills them, and an empty note until then.
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
