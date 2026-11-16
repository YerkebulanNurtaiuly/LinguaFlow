# Architecture

## System context

```
 Browser (React SPA)
     │  HTTPS, one origin
     ▼
 nginx (frontend image) ── serves the built app, /healthz, /version.txt
     │  /api/**, /actuator/health
     ▼
 Spring Boot backend (modular monolith) ──── LLM provider (Anthropic), outbound only
     │  JDBC
     ▼
 PostgreSQL 16 (one database, schema owned by Flyway)
```

- **One origin.** The browser only talks to nginx, which proxies the API. Cookies,
  CORS and the CSP (`connect-src 'self'`) rely on that.
- **One backend instance.** Rate-limit buckets, the AI circuit breaker and the job worker's
  in-flight jobs live in memory. The job queue itself is in the database
  (`FOR UPDATE SKIP LOCKED`), so a second instance wouldn't double-run jobs; the limits would
  need a shared store first (see the security review).
- **Stateless API.** Access tokens are JWTs in the `Authorization` header; the only cookie is
  the refresh token, scoped to `/api/auth`.
- **Deployment:** images built once in GitHub Actions, run on Render (staging and production);
  see [operations/deployment.md](../operations/deployment.md).

## Backend modules

The backend is one Spring Boot application (`com.linguaflow`), split into one package per
domain module. Modules share one database but own their tables: a module reads and writes
its own tables through its own repositories and reaches other modules through their
services, events or small interfaces in `common`.

| Module | Owns | Responsibilities |
|---|---|---|
| `common` | — | config (`LinguaFlowProperties`), security chain, errors (RFC 7807), persistence base classes and ownership guard, rate limiting, mail, monitoring, shared enums (`CefrLevel`, `KnowledgeStatus`, `CardDirection`, `ExerciseType`) and cross-module interfaces |
| `user` | `users`, `user_languages`, `languages` | profile, learning languages and their settings, active language, data export |
| `auth` | `refresh_tokens`, `user_action_tokens` | registration, login, token refresh and rotation, email verification, password reset and change, account deletion |
| `ai` | `ai_jobs`, `ai_requests`, `ai_cache` | LLM client, structured output, prompt templates and safety, job queue, quota, circuit breaker, cost log, shared cache |
| `vocabulary` | `vocabulary_items`, `vocabulary_examples`, `tags`, `vocabulary_item_tags` | words, normalization and duplicates, tags, bulk input, CSV import/export, AI enrichment |
| `grammar` | `grammar_topics`, `grammar_topic_status_history`, `grammar_topic_catalog` | topics, status lifecycle, catalog, AI explanations |
| `learning` | `study_sessions`, `session_vocabulary`, `session_grammar`, `practice_sessions`, `practice_session_items` | lessons and the timeline; practice material selection and practice sessions (`learning.practice`) |
| `flashcard` | `flashcards`, `srs_state`, `flashcard_reviews` | card creation, SRS scheduling, review queue, review statistics |
| `exercise` | `exercise_sets`, `exercises` and their link tables, `exercise_attempts`, `mistakes` | exercise model and rules, generation, answer checking, AI judge, attempts, explanations, reports |
| `progress` | `grammar_topic_progress`, `vocabulary_progress`, `mistake_counts` | progress aggregates, mastery rules, weak points, frequent mistakes, statistics, weak-spot exercises |

### Dependencies

Imports between modules (`common` is used by all and depends on none):

```
auth ──────► user
vocabulary ► user, ai, (learning*)
grammar ───► user, ai, vocabulary, (learning*)
flashcard ─► user, vocabulary
exercise ──► user, ai, vocabulary, grammar
learning ──► user, vocabulary, grammar, flashcard, exercise
progress ──► user, vocabulary, grammar, flashcard, exercise
ai ────────► (common only)
```

\* `vocabulary` and `grammar` reference the lesson link entities (`SessionVocabulary`,
`SessionGrammar`) only inside their list-search fragments, for the `sessionId` filter.

Where a dependency would point the wrong way or make a cycle, `common` holds an interface
implemented elsewhere:
- `common.practice.WordLessons` (implemented in `learning`) gives exercise generation the
  lessons a word was studied in, so `exercise` doesn't depend on `learning`;
- `common.practice.PracticeSessionRecorder` (implemented by `learning.practice.PracticeSessionTracker`)
  lets reviews and attempts mark practice items answered.

### Cross-module rules

- **No JPA relations across modules.** A reference to another module's row, `user_id`
  included, is a plain `UUID` column; relations exist only inside a module.
- **Ownership is enforced by type.** Every user-owned entity implements `OwnedEntity` and its
  repository extends `OwnedRepository`, which only offers owner-scoped methods;
  `OwnershipGuardTests` fails the build otherwise. Worker queries that must see every user
  are separate, explicitly named methods (`…ForProcessing…`, `findForEnrichmentById`).
- **Scoping by language.** Listing and creating learning material uses the active language
  (`ActiveLanguageService.requireActive`); before onboarding that is 409 `NO_ACTIVE_LANGUAGE`.

## Events

Modules react to each other through Spring application events, so the publisher doesn't know
the listeners. Listeners that update other material run after the publisher's transaction
commits, and a failure there is logged, never undoing the learner's action. The one exception
is `ExerciseSetFinishedEvent`, handled in the generator's transaction so that a practice
session never shows a set without its exercises.

| Event | Published by | Listener | Effect |
|---|---|---|---|
| `VocabularyItemCreated` | `VocabularyService.create` | `learning.ActiveSessionAttacher` | links the word to the active lesson |
| `GrammarTopicCreated` | `GrammarTopicService.create` | `ActiveSessionAttacher` | links the topic to the active lesson |
| `VocabularyItemReady` | the enrichment job, when a word becomes READY | `flashcard.FlashcardGenerator` | creates the word's two cards |
| `FlashcardReviewedEvent` | `ReviewService` | `progress.ProgressEventHandler` | recomputes the word's progress, mastery and weakness |
| `ExerciseAttemptedEvent` | `AttemptService` | `ProgressEventHandler` | recomputes the topics' and words' progress and the mistake counts |
| `ExerciseSetFinishedEvent` | `ExerciseGenerator` (same transaction) | `PracticeSessionService` | adds a practice session's new exercises to its items |

Startup jobs repair anything an event missed: `FlashcardGenerator.reconcile()` gives READY
words their missing cards, and the progress module rebuilds every learner's aggregates.

## Data

- **Schema:** Flyway migrations `V1`–`V8` in `backend/src/main/resources/db/migration`,
  designed in [schema.md](../database/schema.md). JPA runs with `ddl-auto=validate`.
- **Identifiers:** UUIDv7 (time-ordered), generated by Hibernate.
- **Keys:** learning tables carry `(user_id, language_code)` with a composite foreign key to
  `user_languages`, so removing a learning language cascades its data away, and deleting the
  user cascades everything.
- **Conventions:** every constraint is named so a violation maps to an API error; entities
  that learners edit concurrently carry `@Version`; audit timestamps come from an injected
  `Clock`; JSON columns map to records.
- **Reads across tables** that don't fit entities (practice candidates, progress, statistics,
  frequent mistakes, export) use `JdbcTemplate` with explicit owner scoping.

## Frontend

React 19 with TypeScript (strict), Vite, Tailwind 4, TanStack Query, React Router, React Hook
Form and Zod.

- **Structure:** `src/pages/<area>/` holds the screens; `src/<area>/` holds each area's API
  calls (Zod-validated) and query hooks; `src/components/ui` is the design system.
- **API types** are generated from the backend's OpenAPI snapshot (`frontend/openapi.json` →
  `src/api/schema.ts`); CI fails when either is stale.
- **Session:** the access token lives in memory only; a single-flight refresh, serialised
  across tabs with the Web Locks API, renews it from the HttpOnly cookie.
- **Server state** is TanStack Query: each area has a query key it invalidates after changes,
  and long-running AI work is followed by polling job status.
- **Safety:** no inline scripts (strict CSP); AI and learner text is rendered as text or
  sanitized Markdown, never raw HTML.
