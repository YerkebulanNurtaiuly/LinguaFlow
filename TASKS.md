# LinguaFlow — Implementation Backlog

Full task breakdown for building LinguaFlow from an empty repo to a production-ready MVP and beyond.

**How to read this**

- Tasks are grouped into **epics**, and epics are ordered by the README roadmap phases.
- Each task has an ID (`<AREA>-<NN>`), a size (**S** ≤ 0.5 day, **M** 1–2 days, **L** 3–5 days), dependencies, and a "Done when" acceptance check.
- Prefixes: `INF` infrastructure, `DB` database, `AUTH`, `USR` user, `VOC` vocabulary, `GRM` grammar, `SES` study sessions, `AI`, `FC` flashcards, `SRS` spaced repetition, `EX` exercises, `PRC` practice modes, `PRG` progress, `FE` frontend, `QA` testing, `OPS` deployment, `ADV` advanced.
- Backend tasks include unit + integration tests unless stated otherwise. Each REST endpoint must appear in Swagger.

---

## Phase 1 — Project Foundation

### Epic 1: Repository & Tooling (`INF`)

- [x] **INF-01 — Initialize monorepo** (S)
  Create `backend/`, `frontend/`, `docs/database/`, `docs/technical-specification/`, root `README.md`, `.gitignore`, `.editorconfig`, `LICENSE`.
  _Done when:_ structure matches the README and is committed.

- [x] **INF-02 — Bootstrap Spring Boot backend** (S) — _deps: INF-01_
  Spring Boot 4.x, Java 21, Maven. Dependencies: web, validation, data-jpa, security, flyway, postgresql, springdoc-openapi, lombok (optional), actuator, test, testcontainers.
  Package layout as a modular monolith: `com.linguaflow.{auth,user,vocabulary,grammar,ai,flashcard,exercise,learning,progress,common}`.
  _Done when:_ `mvn verify` passes and `/actuator/health` returns `UP`.

- [x] **INF-03 — Bootstrap React frontend** (S) — _deps: INF-01_
  Vite + React + TypeScript (strict), Tailwind CSS, ESLint + Prettier, React Router, TanStack Query, Axios (or fetch wrapper), Zod + React Hook Form, Vitest + React Testing Library.
  _Done when:_ `npm run dev`, `npm run build`, `npm run lint`, `npm test` all succeed.

- [ ] **INF-04 — Docker Compose for local dev** (S) — _deps: INF-02_
  `docker-compose.yml` with PostgreSQL 16 (named volume), optional pgAdmin. Backend `Dockerfile` (multi-stage), frontend `Dockerfile` (build → nginx).
  _Done when:_ `docker compose up` starts DB + backend + frontend and they talk to each other.

- [x] **INF-05 — Configuration & profiles** (S) — _deps: INF-02_
  `application.yml` with `dev`, `test`, `prod` profiles; all secrets (DB password, JWT secret, AI API key) from environment variables; `.env.example` committed.
  _Done when:_ app starts with only env vars, no secrets in git.

- [x] **INF-06 — CI pipeline** (M) — _deps: INF-02, INF-03_
  GitHub Actions: backend build + tests (Testcontainers), frontend lint + type-check + tests + build, Docker image build. Cache Maven/npm.
  _Done when:_ PRs show green/red checks.
  _Note:_ Docker image build job deferred until INF-04 adds the Dockerfiles.

- [x] **INF-07 — Common backend infrastructure** (M) — _deps: INF-02_
  Global exception handler returning RFC 7807 `ProblemDetail`; standard error codes; validation error mapping; pagination/sorting DTO; base auditing entity (`createdAt`, `updatedAt`) via JPA auditing; request logging with correlation ID; CORS config for the frontend origin.
  _Done when:_ invalid requests return consistent JSON errors, covered by tests.

- [x] **INF-08 — OpenAPI / Swagger setup** (S) — _deps: INF-02_
  springdoc with JWT bearer security scheme, API grouping per module, `/swagger-ui` enabled in dev only.
  _Done when:_ Swagger UI lets you authorize and call endpoints.

- [x] **INF-09 — Generated frontend API client** (S) — _deps: INF-03, INF-08_
  Generate TypeScript types from the OpenAPI spec (`openapi-typescript` or `orval`); npm script `api:generate`.
  _Done when:_ frontend uses generated types instead of hand-written DTOs.

### Epic 2: Database Schema (`DB`)

- [x] **DB-01 — Design ERD & document it** (M)
  Produce `docs/database/schema.md` (+ diagram) covering all entities below, keys, indexes, constraints, and cascade rules.
  Core tables: `users`, `refresh_tokens`, `languages`, `user_languages`, `study_sessions`, `vocabulary_items`, `vocabulary_examples`, `grammar_topics`, `session_vocabulary`, `session_grammar`, `flashcards`, `flashcard_reviews`, `srs_state`, `exercise_sets`, `exercises`, `exercise_attempts`, `mistakes`, `ai_requests` (audit/cost log), `practice_sessions`.
  _Done when:_ schema reviewed and approved.
  _Status:_ approved 2026-11-09 (`docs/database/schema.md`).

- [x] **DB-02 — Flyway setup + baseline migration** (S) — _deps: INF-02, DB-01_
  `V1__init.sql` for `users`, `languages`, `user_languages`; seed German (`de`) and English (`en`) in `languages`.
  _Done when:_ app migrates a fresh DB on start; `flyway validate` passes in CI.

- [x] **DB-03 — Learning material migrations** (S) — _deps: DB-02_
  `vocabulary_items` (lemma, normalized_lemma, translation, part_of_speech, article/gender, plural, extra JSONB for verb forms etc., CEFR level, status, user_id, language_id), unique `(user_id, language_id, normalized_lemma, part_of_speech)` for duplicate prevention; `vocabulary_examples`; `grammar_topics` (title, description, personal_notes, cefr_level, status `LEARNING|LEARNED|MASTERED`); `study_sessions` + join tables.
  _Done when:_ migrations apply and JPA entities map without `ddl-auto` errors (`validate` mode).

- [x] **DB-04 — Practice & progress migrations** (S) — _deps: DB-03_
  `flashcards`, `flashcard_reviews`, `srs_state` (ease_factor, interval_days, repetitions, due_at, lapses), `exercise_sets`, `exercises` (type, prompt JSONB, solution JSONB, grammar_topic_id, vocabulary refs), `exercise_attempts`, `mistakes`, `practice_sessions`, `ai_requests`. Indexes on `(user_id, due_at)` and `(user_id, grammar_topic_id)`.
  _Done when:_ migrations apply; Testcontainers test runs full migration chain.

- [x] **DB-05 — Dev seed data** (S) — _deps: DB-04_
  Profile-gated `R__dev_seed.sql` (or `CommandLineRunner`) with a demo user, ~50 German words, ~10 grammar topics.
  _Done when:_ fresh dev DB has usable demo content.

### Epic 3: Authentication (`AUTH`)

- [x] **AUTH-01 — User entity & repository** (S) — _deps: DB-02_
  Email (unique, case-insensitive), password hash (BCrypt/Argon2), display name, role (`USER`, `ADMIN`), enabled flag, timestamps.

- [x] **AUTH-02 — Registration endpoint** (M) — _deps: AUTH-01, INF-07_
  `POST /api/auth/register` with validation (email format, password policy, duplicates → 409).
  _Done when:_ user is created with hashed password; tests cover edge cases.

- [x] **AUTH-03 — Login with JWT access + refresh tokens** (M) — _deps: AUTH-02_
  `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`. Short-lived access token (15 min), rotating refresh token stored hashed in DB and sent as HttpOnly, Secure, SameSite cookie.
  _Done when:_ refresh rotation works, reused refresh token is rejected and revokes the family.

- [x] **AUTH-04 — Spring Security configuration** (M) — _deps: AUTH-03_
  Stateless filter chain, JWT resource server validation, public routes (`/api/auth/**`, health, docs in dev), CSRF strategy matching cookie use, `@AuthenticationPrincipal` helper to get current user ID.
  _Done when:_ protected endpoints return 401 without a token, 403 for wrong role.

- [x] **AUTH-05 — Ownership enforcement** (S) — _deps: AUTH-04_
  All user-owned queries scoped by `user_id`; accessing another user's resource returns 404. Add a reusable test helper verifying isolation.

- [x] **AUTH-06 — Rate limiting on auth endpoints** (S) — _deps: AUTH-03_
  Bucket4j (or similar) limit on login/register per IP.

- [x] **AUTH-07 — Password reset & email verification** (M, optional for MVP) — _deps: AUTH-03_
  Token-based flows; pluggable mail sender (log-only in dev).

### Epic 4: User & Language Management (`USR`)

- [x] **USR-01 — Profile endpoints** (S) — _deps: AUTH-04_
  `GET/PATCH /api/users/me` (display name, interface language, timezone), `PUT /api/users/me/password`, `DELETE /api/users/me` (cascade delete).

- [x] **USR-02 — Language preferences** (M) — _deps: USR-01_
  `GET /api/languages`; `GET/POST/DELETE /api/users/me/languages` — target language (German), native/explanation language, self-assessed CEFR level, active language. All learning data is scoped by `(user, target language)`.
  _Done when:_ a user can set German as target with English explanations.

- [x] **USR-03 — Learning settings** (S) — _deps: USR-02_
  Daily review goal, new-cards-per-day limit, preferred exercise types, session length. Stored per user-language.

### Epic 5: Frontend Foundation (`FE`)

- [x] **FE-01 — App shell & routing** (M) — _deps: INF-03_
  Layout with sidebar/topbar, responsive mobile nav, routes: `/login`, `/register`, `/dashboard`, `/vocabulary`, `/grammar`, `/sessions`, `/flashcards`, `/practice`, `/progress`, `/settings`. 404 page.

- [x] **FE-02 — Design system components** (M) — _deps: FE-01_
  Tailwind theme tokens (light/dark), Button, Input, Select, Textarea, Badge (CEFR/status), Card, Modal, Toast, Table, Tabs, EmptyState, Skeleton, ConfirmDialog. Accessible (keyboard, ARIA).

- [x] **FE-03 — API layer & auth state** (M) — _deps: FE-01, INF-09, AUTH-03_
  HTTP client with access-token injection, silent refresh on 401 with request queueing, logout on refresh failure. Auth context/store, `ProtectedRoute`.

- [x] **FE-04 — Login & registration pages** (S) — _deps: FE-03_
  Forms with Zod validation, server error display, redirect after login.

- [x] **FE-05 — Onboarding flow** (S) — _deps: FE-04, USR-02_
  First-login wizard: choose target language, explanation language, level, daily goal.

- [x] **FE-06 — Settings page** (S) — _deps: FE-05, USR-03_
  Profile, password change, language & learning settings, account deletion.

- [x] **FE-07 — Global error/loading handling** (S) — _deps: FE-02_
  Error boundary, query error toasts, consistent loading skeletons.

---

## Phase 2 — Learning Material Management

### Epic 6: Vocabulary (`VOC`)

- [x] **VOC-01 — Vocabulary entity, repository, service** (M) — _deps: DB-03, AUTH-05_
  Lemma normalization (trim, lower-case, strip German article prefix "der/die/das", Unicode NFC, handle ß/ss consistently) used for duplicate detection.

- [x] **VOC-02 — Vocabulary CRUD API** (M) — _deps: VOC-01_
  `POST/GET/PATCH/DELETE /api/vocabulary`, `GET /api/vocabulary/{id}`. List supports pagination, search (lemma/translation), filters (part of speech, CEFR, status, session, tag), sort.
  _Done when:_ duplicates return 409 with the existing item's ID.

- [x] **VOC-03 — Tags / organization** (S) — _deps: VOC-02_
  User-defined tags (many-to-many) for grouping words (e.g. "Food", "Chapter 3").

- [x] **VOC-04 — Bulk vocabulary input** (M) — _deps: VOC-02_
  `POST /api/vocabulary/bulk` accepts raw text (one word/phrase per line, optional `word - translation` format) or CSV. Returns a **preview** per line: parsed, duplicate, invalid. Second call confirms the selected items.
  _Done when:_ 100 lines are processed in one request with clear per-line results.

- [ ] **VOC-05 — AI enrichment of vocabulary** (M) — _deps: VOC-04, AI-03_
  On create/bulk, enqueue AI processing: translation, part of speech, article/gender, plural, verb forms (Präteritum, Partizip II, auxiliary), CEFR estimate, 2–3 example sentences with translations. Item status `PENDING_AI → READY | AI_FAILED`. User edits always override AI values; flag `aiGenerated` per field.
  _Done when:_ adding "Hund" yields "der Hund, die Hunde, dog" with examples.

- [ ] **VOC-06 — Re-generate / edit AI data** (S) — _deps: VOC-05_
  `POST /api/vocabulary/{id}/regenerate`; manual edits on all fields.

- [x] **VOC-07 — Import / export** (S) — _deps: VOC-02_
  CSV export and import of vocabulary.

- [x] **FE-VOC-01 — Vocabulary list page** (M) — _deps: FE-02, VOC-02_
  Table/cards with search, filters, pagination, AI status indicator, inline delete.

- [x] **FE-VOC-02 — Add / edit word form** (M) — _deps: FE-VOC-01_
  Single-word form with optional manual fields; edit modal showing AI-generated fields and examples; duplicate warning linking to existing word.

- [x] **FE-VOC-03 — Bulk add screen** (M) — _deps: FE-VOC-01, VOC-04_
  Paste area → preview table (duplicate/invalid rows highlighted, toggle include) → confirm → progress indicator while AI processes.

### Epic 7: Grammar (`GRM`)

- [x] **GRM-01 — Grammar topic CRUD API** (M) — _deps: DB-03, AUTH-05_
  `POST/GET/PATCH/DELETE /api/grammar-topics`. Fields: title, description, personal notes (Markdown), CEFR level, status. Filters by CEFR and status; duplicate title check per user-language.

- [x] **GRM-02 — Knowledge status lifecycle** (S) — _deps: GRM-01_
  Status `NEW → LEARNING → LEARNED → MASTERED`. Manual change allowed for `LEARNING/LEARNED`; `MASTERED` granted automatically by PRG rules (or manually with confirmation). Store status history.
  _Done when:_ adding a topic never auto-marks it mastered (design principle 6).

- [ ] **GRM-03 — Predefined topic catalog (suggestions)** (S) — _deps: GRM-01_
  Seed table of common German grammar topics per CEFR level (Akkusativ, Dativ, Perfekt, Nebensätze, Adjektivdeklination, Konjunktiv II …) the user can pick from instead of typing.

- [ ] **GRM-04 — AI grammar explanation** (S) — _deps: GRM-01, AI-03_
  Optional AI-generated summary, rules and examples for a topic, stored separately from personal notes.

- [ ] **FE-GRM-01 — Grammar topics page** (M) — _deps: FE-02, GRM-01_
  List grouped by CEFR, status badges, filters, add-from-catalog picker.

- [ ] **FE-GRM-02 — Grammar topic detail/edit** (M) — _deps: FE-GRM-01_
  Description, Markdown notes editor, AI explanation panel, status control, stats (attempts, accuracy) once PRG exists.

### Epic 8: Study Sessions (`SES`)

- [ ] **SES-01 — Study session API** (M) — _deps: VOC-02, GRM-01_
  A study session = one lesson (e.g. "Lesson 5 – 2026-11-09"). `POST/GET/PATCH/DELETE /api/study-sessions`; attach/detach vocabulary and grammar topics. New material added while a session is "active" is attached automatically.
  _Done when:_ "Current Lesson" practice can query material by session.

- [ ] **SES-02 — Learning history timeline** (S) — _deps: SES-01_
  `GET /api/study-sessions/timeline` — sessions ordered by date with counts.

- [ ] **FE-SES-01 — Sessions page** (M) — _deps: SES-01, FE-VOC-02, FE-GRM-01_
  Create a session, add words/topics inside it, session detail page, timeline view.

### Epic 9: AI Integration (`AI`)

- [ ] **AI-01 — Provider abstraction** (M) — _deps: INF-05_
  `LlmClient` interface (`complete(prompt, schema, options)`), one implementation for the chosen provider (e.g. Claude API), plus a deterministic `FakeLlmClient` for tests/dev. Config: model, timeouts, max tokens, temperature per use-case.

- [ ] **AI-02 — Structured output & validation** (M) — _deps: AI-01_
  Request JSON output matching a schema; parse into Java records; validate (Bean Validation + domain checks such as article ∈ {der, die, das}); retry once with error feedback on invalid output.

- [ ] **AI-03 — Prompt templates library** (M) — _deps: AI-02_
  Versioned templates in `resources/prompts/` for: vocabulary enrichment (single + batch), grammar explanation, exercise generation (per type), answer evaluation, mistake explanation. Variables: target/explanation language, CEFR, known vocabulary, focus topics.

- [ ] **AI-04 — Async processing & job queue** (M) — _deps: AI-03_
  `@Async` executor or DB-backed job table (`ai_jobs`) with status, retries with exponential backoff, and dead-letter state. Batch vocabulary into chunks (e.g. 20 words per call).
  _Done when:_ bulk add of 100 words completes without blocking the request and survives a restart.

- [ ] **AI-05 — Resilience, limits, and cost tracking** (M) — _deps: AI-04_
  Resilience4j circuit breaker + timeouts; per-user daily AI quota; log every call in `ai_requests` (use-case, tokens, latency, cost estimate, success). Admin endpoint for usage summary.

- [ ] **AI-06 — Caching** (S) — _deps: AI-04_
  Cache enrichment results by `(language, normalized_lemma, part_of_speech)` across users to cut cost.

- [ ] **AI-07 — Prompt safety** (S) — _deps: AI-03_
  Treat user input as data (delimiting, length limits), strip control content, never execute model output; content moderation for free-text fields.

- [ ] **AI-08 — Job status for the UI** (S) — _deps: AI-04_
  `GET /api/ai/jobs/{id}` (polling) or SSE stream so the frontend can show progress.

### Epic 10: Flashcard Generation (`FC`)

- [ ] **FC-01 — Flashcard model & generation** (M) — _deps: VOC-05, DB-04_
  Auto-create flashcards when a vocabulary item becomes `READY`. Card directions: DE→native, native→DE (configurable). Front/back content built deterministically from vocabulary data (article, plural, examples). Regenerate when the word is edited.

- [ ] **FC-02 — Flashcard API** (S) — _deps: FC-01_
  `GET /api/flashcards` (filters), `PATCH` (suspend/unsuspend), `GET /api/flashcards/{id}`.

---

## Phase 3 — Learning and Practice

### Epic 11: Spaced Repetition & Flashcard Review (`SRS`)

- [ ] **SRS-01 — Scheduling algorithm** (M) — _deps: FC-01_
  Implement SM-2 (or FSRS) as a pure, deterministic service: inputs = current state + grade (`AGAIN`, `HARD`, `GOOD`, `EASY`), outputs = new interval, ease, due date, lapses. Respect user timezone for "today".
  _Done when:_ thoroughly unit-tested with table-driven cases.

- [ ] **SRS-02 — Review queue API** (M) — _deps: SRS-01, USR-03_
  `GET /api/reviews/due?limit=` returns due cards + new cards respecting daily limits; ordering: overdue first, then weak, then new. `POST /api/reviews` records a grade, updates `srs_state`, writes `flashcard_reviews`.
  _Done when:_ review submission is idempotent per (card, review token).

- [ ] **SRS-03 — Review statistics** (S) — _deps: SRS-02_
  Due today, reviewed today, streak, retention rate, forecast for next 7 days.

- [ ] **FE-FC-01 — Flashcard review UI** (L) — _deps: SRS-02, FE-02_
  Card flip animation, show answer → grade buttons with next-interval preview, keyboard shortcuts (space, 1–4), progress bar, session summary screen, "type the answer" mode option. Shows article color coding (der/die/das), plural, examples.

- [ ] **FE-FC-02 — Flashcard browser** (S) — _deps: FC-02_
  Browse/search all cards, suspend, see review history per card.

### Epic 12: Grammar Exercises (`EX`)

- [ ] **EX-01 — Exercise domain model** (M) — _deps: DB-04_
  Polymorphic exercise types with JSON prompt/solution schemas: `FILL_BLANK`, `MULTIPLE_CHOICE`, `TRANSLATION`, `SENTENCE_CONSTRUCTION`, `ERROR_CORRECTION`, `WORD_ORDER`, `TRANSFORMATION`. Each links to grammar topic(s) and vocabulary item(s) used.

- [ ] **EX-02 — AI exercise generation** (L) — _deps: EX-01, AI-03, GRM-01, VOC-05_
  `POST /api/exercise-sets` with `{grammarTopicIds, types, count, difficulty}`. Backend picks vocabulary the user knows, builds prompt, validates each generated exercise (schema, solution present, MC options unique with exactly one correct, word-order tokens match solution), drops invalid ones and tops up.
  _Done when:_ a 10-exercise set for "Dativ" uses only (or mostly) the user's own words.

- [ ] **EX-03 — Deterministic answer evaluation** (M) — _deps: EX-01_
  Closed formats (MC, fill-blank, word order) evaluated in code: normalization (whitespace, case where irrelevant, punctuation, umlaut alternatives ae/oe/ue configurable), accepted alternatives list, partial credit for word order.

- [ ] **EX-04 — AI evaluation for open answers** (M) — _deps: EX-03, AI-03_
  Translation, sentence construction, error correction, transformation: first try exact/alternatives match, then AI judge returning `{correct, score, errors[{type, span, explanation}], correctedAnswer}`. Error types map to grammar topics (e.g. wrong case → Dativ).

- [ ] **EX-05 — Attempt submission API** (S) — _deps: EX-03, EX-04_
  `POST /api/exercises/{id}/attempts` → result, explanation, correct answer. Persist attempt and mistakes; emit `ExerciseAttemptedEvent` for the progress module.

- [ ] **EX-06 — Explanations for incorrect answers** (S) — _deps: EX-05_
  AI explanation in the user's explanation language, referencing the grammar rule; cached per (exercise, wrong-answer-normalized).

- [ ] **EX-07 — Exercise reporting** (S) — _deps: EX-05_
  "Report a bad exercise" button → flags exercise, excludes it from future sets, logged for prompt tuning.

- [ ] **FE-EX-01 — Exercise player** (L) — _deps: EX-05, FE-02_
  One component per exercise type (drag-and-drop word order, MC, text input with umlaut helper buttons ä ö ü ß), submit/next flow, immediate feedback with explanation, end-of-set summary.

- [ ] **FE-EX-02 — Exercise set builder** (M) — _deps: FE-EX-01, EX-02_
  Pick grammar topics, exercise types, count, difficulty → generating state → start.

---

## Phase 4 — Personalization

### Epic 13: Practice Modes (`PRC`)

- [ ] **PRC-01 — Material selection engine** (L) — _deps: SES-01, SRS-02, EX-02_
  Deterministic service choosing vocabulary + grammar for a practice session by mode:
    - **Current Lesson** — material from the latest (or chosen) study session.
    - **Mixed Practice** — configurable ratio (default 60% new / 40% older), older material weighted by weakness and time since last practice.
    - **Full Revision** — entire history, weighted by weakness and SRS due-ness.
      _Done when:_ selection is reproducible given a seed and fully unit-tested.

- [ ] **PRC-02 — Practice session API** (M) — _deps: PRC-01_
  `POST /api/practice-sessions {mode, studySessionId?, length, includeFlashcards, includeExercises}` → ordered list of items (flashcards + generated exercises interleaved). `POST .../complete` stores summary.

- [ ] **PRC-03 — Combined exercises** (M) — _deps: PRC-01, EX-02_
  Prompt variant generating exercises that combine several grammar topics and vocabulary from different sessions.

- [ ] **FE-PRC-01 — Practice hub page** (M) — _deps: PRC-02, FE-FC-01, FE-EX-01_
  Three mode cards with descriptions and item counts, session config, unified player handling both flashcards and exercises, summary with "practice mistakes again".

### Epic 14: Progress & Analytics (`PRG`)

- [ ] **PRG-01 — Progress event handling** (M) — _deps: EX-05, SRS-02_
  Listen to review/attempt events (Spring application events, transactional) and update aggregates: per-vocabulary mastery, per-grammar-topic accuracy (rolling window, e.g. last 20 attempts), mistake counters by type.

- [ ] **PRG-02 — Mastery rules** (M) — _deps: PRG-01, GRM-02_
  Deterministic rules, e.g. grammar topic → `MASTERED` when ≥ 85% accuracy over ≥ 20 attempts across ≥ 3 different days; demoted to `LEARNED` when accuracy drops below 70%. Vocabulary mastered when SRS interval ≥ 21 days. Thresholds configurable.

- [ ] **PRG-03 — Weak-point analysis** (M) — _deps: PRG-01_
  Weakness score per grammar topic and vocabulary item (error rate, recency, lapses). `GET /api/progress/weak-points` returns top N with reasons. Feeds PRC-01 weighting.

- [ ] **PRG-04 — Frequent mistakes** (S) — _deps: PRG-01, EX-04_
  `GET /api/progress/mistakes` grouped by error type/topic with examples of the user's own wrong answers.

- [ ] **PRG-05 — Statistics API** (M) — _deps: PRG-01, SRS-03_
  Dashboard data: total words/topics by status, reviews per day (heatmap), accuracy trend, streak, time studied, CEFR distribution.

- [ ] **PRG-06 — Personalized exercise generation** (M) — _deps: PRG-03, EX-02_
  "Practice my weak spots" — generate exercises targeting the top weak topics and frequent error types, using weak vocabulary.

- [ ] **FE-PRG-01 — Dashboard** (M) — _deps: PRG-05_
  Today's due reviews, daily goal ring, streak, quick-start buttons for each practice mode, recent sessions, weak topics.

- [ ] **FE-PRG-02 — Progress page** (L) — _deps: PRG-03, PRG-04, PRG-05_
  Charts (activity heatmap, accuracy over time, mastery breakdown), weak-points list with "practice now", frequent-mistakes view.

---

## Cross-cutting: Quality, Security, Operations

### Epic 15: Testing (`QA`)

- [ ] **QA-01 — Backend test foundation** (S) — _deps: INF-02_
  Testcontainers PostgreSQL base class (reused container), test data builders, `FakeLlmClient` wiring, security test helpers (`@WithMockUser`-style for JWT).

- [ ] **QA-02 — Module integration tests** (ongoing)
  Each module: repository tests, service tests (Mockito), controller tests (`@WebMvcTest` / MockMvc) including auth and ownership checks.

- [ ] **QA-03 — AI contract tests** (M) — _deps: AI-03_
  Golden-file tests for prompt rendering; recorded-response tests for parsing; optional nightly job running a small eval set against the real provider to catch quality regressions.

- [ ] **QA-04 — Frontend tests** (ongoing)
  Component tests for forms and players; MSW for API mocking.

- [ ] **QA-05 — End-to-end tests** (M) — _deps: FE-PRC-01_
  Playwright: register → onboard → bulk add words → review flashcards → do exercises → see progress. Runs in CI against docker compose with fake LLM.

- [ ] **QA-06 — Coverage & quality gates** (S)
  JaCoCo (target ≥ 80% on services), Checkstyle/Spotless, ESLint strict; SonarCloud optional.

### Epic 16: Security & Compliance

- [ ] **SEC-01 — Security review** (M)
  OWASP top-10 pass: input validation, SQL injection (JPA params only), XSS in Markdown notes (sanitize rendering), security headers, dependency scanning (Dependabot / OWASP dependency-check).

- [ ] **SEC-02 — Privacy / GDPR basics** (S)
  Data export (`GET /api/users/me/export` JSON), account deletion with full data removal, privacy policy page, no personal data sent to the AI beyond learning content.

### Epic 17: Deployment & Operations (`OPS`)

- [ ] **OPS-01 — Production Docker images** (S) — _deps: INF-04_
  Slim JRE image, non-root user, healthchecks; nginx config for SPA routing and `/api` reverse proxy.

- [ ] **OPS-02 — Hosting & environments** (M) — _deps: OPS-01_
  Pick hosting (e.g. a VPS with Docker Compose, Fly.io, Render, or AWS ECS + RDS). Staging and production environments, managed PostgreSQL with automated backups, HTTPS.

- [ ] **OPS-03 — CD pipeline** (M) — _deps: OPS-02, INF-06_
  Push images to registry on merge to `main`, auto-deploy to staging, manual promotion to production; Flyway runs on startup.

- [ ] **OPS-04 — Observability** (M) — _deps: OPS-02_
  Structured JSON logs, Micrometer metrics (+ Prometheus/Grafana or provider equivalent), AI latency/cost/error dashboards, error tracking (Sentry) for backend and frontend, uptime alerts.

- [ ] **OPS-05 — Backups & restore drill** (S) — _deps: OPS-02_
  Documented restore procedure, tested once.

### Epic 18: Documentation

- [ ] **DOC-01 — Technical specification** (M)
  `docs/technical-specification/`: module boundaries, API overview, AI usage and prompt design, SRS and mastery rules, practice selection algorithm.

- [ ] **DOC-02 — Developer setup guide** (S)
  How to run locally, env vars, running tests, generating the API client, adding a migration.

- [ ] **DOC-03 — Update README status & roadmap checkboxes** (S)
  Replace the status placeholder and tick roadmap items as epics complete.

---

## Phase 5 — Advanced Features (`ADV`)

- [ ] **ADV-01 — Writing practice** (L) — _deps: EX-04, PRG-03_
  AI writing prompts using known vocabulary/grammar; user writes a short text; AI returns inline corrections, error categories mapped to grammar topics (feeding progress), and an improved version.

- [ ] **ADV-02 — Audio pronunciation (TTS)** (M) — _deps: FC-01_
  Text-to-speech for words and example sentences (browser Web Speech API first, cloud TTS later with cached audio files in object storage).

- [ ] **ADV-03 — Listening exercises** (M) — _deps: ADV-02, EX-01_
  Dictation and listen-and-choose exercise types.

- [ ] **ADV-04 — Speaking practice** (L) — _deps: ADV-02_
  Record answer in browser, speech-to-text, evaluate content (and optionally pronunciation) with AI.

- [ ] **ADV-05 — Personalized learning plans** (L) — _deps: PRG-03, PRG-05_
  Weekly plan generated from goals, weak points, and due reviews; daily task list on the dashboard; reminders (email / web push).

- [ ] **ADV-06 — Additional languages** (L) — _deps: all core epics_
  Remove German-specific assumptions: language-specific grammar metadata (gender/articles/cases) moved behind a `LanguageProfile` strategy; per-language prompt templates, normalization rules, and topic catalogs. Add a second language (e.g. Spanish) as proof.

- [ ] **ADV-07 — PWA & offline reviews** (M) — _deps: FE-FC-01_
  Installable PWA; cache due cards for offline review and sync results later.

- [ ] **ADV-08 — Gamification** (M, optional)
  XP, achievements, streak freezes.

---

## Suggested Milestones

| Milestone | Contents | Outcome |
| --- | --- | --- |
| **M1 – Skeleton** | INF-01…09, DB-01…02, AUTH-01…05, USR-01…02, FE-01…05, QA-01 | Users can register, log in, choose German |
| **M2 – My Material** | DB-03, VOC-01…06, GRM-01…04, SES-01…02, AI-01…08, FE-VOC-*, FE-GRM-*, FE-SES-01 | Users add words/grammar, AI enriches them |
| **M3 – Review** | DB-04, FC-01…02, SRS-01…03, FE-FC-01…02 | Daily flashcard reviews with spaced repetition |
| **M4 – Practice** | EX-01…07, FE-EX-01…02 | AI grammar exercises with evaluation and explanations |
| **M5 – Personalized MVP** | PRC-01…03, PRG-01…06, FE-PRC-01, FE-PRG-01…02, QA-05, SEC-01…02 | Mixed practice, progress tracking, weak-spot training |
| **M6 – Launch** | OPS-01…05, DOC-01…03, QA-06 | Deployed, monitored, documented MVP |
| **M7+ – Advanced** | ADV-* | Writing, speech, plans, more languages |

## Critical Path

`INF-02 → DB-02 → AUTH-03 → VOC-01 → AI-03 → VOC-05 → FC-01 → SRS-02 → EX-02 → PRC-01 → PRG-03 → PRG-06`

AI integration (AI-01…04) is the main risk: start it early in M2, use `FakeLlmClient` so other work is never blocked on the real provider.
