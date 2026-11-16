# API overview

REST under `/api`, JSON in and out. The live description is the OpenAPI document: Swagger UI
at `/swagger-ui.html` and the full spec at `/v3/api-docs/all` in the dev profile, and the
committed snapshot `frontend/openapi.json` from which the frontend types are generated.
This page explains the conventions and lists every operation; request and response shapes
are in the OpenAPI document.

## Conventions

### Authentication

- **Access token:** `Authorization: Bearer <JWT>`, HS256, 15 minutes, claims `sub` (user ID),
  `role` and `iss=linguaflow`. Returned in the body of login, refresh and password change.
- **Refresh token:** the cookie `lf_refresh` (`HttpOnly; Secure; SameSite=Strict;
  Path=/api/auth`, 30 days). Every refresh rotates it within its family; presenting a spent
  token revokes the whole family.
- **Public operations:** health, register, login, refresh, logout, email verification,
  password reset, and `POST /api/client-errors`. Everything else needs a token; `/api/admin/**`
  needs the ADMIN role.
- **401 vs 403:** a missing or invalid token is 401 with `WWW-Authenticate: Bearer`; a wrong
  role is 403. A wrong *current password* is a 400 field error, never 401, because clients
  treat 401 as "refresh the token".
- **CSRF** protection is off on purpose: authentication uses a header, and the only cookie is
  `SameSite=Strict` and limited to `/api/auth`.

### Ownership and scoping

- Every resource belongs to one user. Another user's resource is **404**, exactly like a
  missing one, so IDs reveal nothing.
- Learning material is created and listed in the **active learning language**. Before
  onboarding those operations answer 409 `NO_ACTIVE_LANGUAGE`. Single resources (`GET /{id}`)
  work in any of the user's languages.

### Errors

Every error is RFC 7807 `application/problem+json` with `type`, `title`, `status`, `detail`,
`instance`, plus:
- `code`: a stable `ErrorCode` name the client can branch on (`VALIDATION_FAILED`,
  `VOCABULARY_DUPLICATE`, `NOTHING_TO_PRACTISE`, `AI_QUOTA_EXCEEDED`, …);
- `correlationId`: the request's `X-Correlation-Id`, also on every log line;
- `timestamp`;
- `errors: [{field, message}]` for `VALIDATION_FAILED`, with JSON paths as field names;
- extra fields for some codes, e.g. `existingId` on duplicates or `resetsAt` on the AI quota.

Common statuses: 400 validation, 404 not found or not yours, 409 conflict (duplicates,
concurrent edits through `@Version`, invalid state), 429 rate limit or AI quota (with
`Retry-After`), 503 AI unavailable. A 500 never carries the exception message.

### Lists

- `page` (zero-based) and `size` (default 20, at most 100); responses are
  `{content, page, size, totalElements, totalPages, sort, …}`.
- `sort=field,dir` with a whitelist per list; anything else is a 400 on `sort`.
- Filters are query parameters; repeatable ones (`status=LEARNING&status=LEARNED`) mean any of
  them. Text search (`q`) treats `%` and `_` literally.
- Short, naturally bounded lists (tags, catalog, timeline, languages) aren't paged.

### Updates

- `PATCH` is partial: absent or null fields stay unchanged; `""` clears a text field; a
  `clear` set clears choices that an empty string can't express.
- Resources that learners edit concurrently return `version`; a stale write is 409 `CONFLICT`.

### Long-running AI work

Operations that need the AI in the background (enrichment, explanations, exercise sets,
weak-spot sets) return **202** with an `aiJobId`. Clients poll `GET /api/ai/jobs/{id}`, or
`GET /api/ai/jobs/progress?id=…` for several, every few seconds until `finished`, then reload
the resource. Operations that call the AI while the learner waits (grading an open answer,
explaining a mistake) answer directly, or 429/503 when the AI can't be used.

### Rate limits

Per client IP: login 10 a minute, registration 5 an hour, browser error reports 30 a minute;
over the limit is 429 `TOO_MANY_REQUESTS` with `Retry-After`. AI calls have a per-user daily
quota (`GET /api/ai/usage`).

### Security headers

API responses carry `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`,
`X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, a `Permissions-Policy` and
`nosniff`. CORS is limited to the configured frontend origins.

## Operations

Generated from `frontend/openapi.json` by `npm run docs:api` in `frontend/`; don't edit the
tables by hand. `apiOverview.test.ts` fails when an operation is missing here or listed but
gone. Admin operations need the ADMIN role; public operations are marked.

<!-- operations:start -->

### Auth

| Method | Path | What it does |
|---|---|---|
| POST | `/api/auth/login` | Log in _(public)_ |
| POST | `/api/auth/logout` | Log out _(public)_ |
| POST | `/api/auth/password-reset` | Set a new password with the token from the reset link _(public)_ |
| POST | `/api/auth/password-reset/request` | Email a password reset link _(public)_ |
| POST | `/api/auth/refresh` | Get a new access token _(public)_ |
| POST | `/api/auth/register` | Create an account _(public)_ |
| POST | `/api/auth/verify-email` | Confirm the email address with the token from the verification link _(public)_ |
| POST | `/api/auth/verify-email/request` | Send a new email verification link to the current user |

### Profile

| Method | Path | What it does |
|---|---|---|
| GET | `/api/users/me` | Get the current user's profile |
| PATCH | `/api/users/me` | Update display name, interface language and/or time zone |
| DELETE | `/api/users/me` | Delete the account and all its data |
| GET | `/api/users/me/export` | Download all your data (SEC-02) |
| PUT | `/api/users/me/password` | Change the password |

### Languages

| Method | Path | What it does |
|---|---|---|
| GET | `/api/languages` | List all languages and what they can be used for |
| GET | `/api/users/me/languages` | List the languages the current user learns |
| POST | `/api/users/me/languages` | Start learning a language |
| PATCH | `/api/users/me/languages/{languageCode}` | Change explanation language, level or learning settings, or make this the active language |
| DELETE | `/api/users/me/languages/{languageCode}` | Stop learning a language |

### Vocabulary

| Method | Path | What it does |
|---|---|---|
| GET | `/api/vocabulary` | List vocabulary |
| POST | `/api/vocabulary` | Add a word |
| GET | `/api/vocabulary/{id}` | Get one word |
| PATCH | `/api/vocabulary/{id}` | Edit a word |
| DELETE | `/api/vocabulary/{id}` | Delete a word |
| POST | `/api/vocabulary/{id}/regenerate` | Ask the AI again |
| POST | `/api/vocabulary/bulk` | Preview bulk input |
| POST | `/api/vocabulary/bulk/confirm` | Add the confirmed bulk items |
| GET | `/api/vocabulary/export` | Export vocabulary as CSV |
| POST | `/api/vocabulary/import` | Import vocabulary from CSV |

### Tags

| Method | Path | What it does |
|---|---|---|
| GET | `/api/tags` | List tags |
| POST | `/api/tags` | Create a tag |
| PATCH | `/api/tags/{id}` | Rename a tag |
| DELETE | `/api/tags/{id}` | Delete a tag |

### Grammar

| Method | Path | What it does |
|---|---|---|
| GET | `/api/grammar-topics` | List grammar topics |
| POST | `/api/grammar-topics` | Add a grammar topic |
| GET | `/api/grammar-topics/{id}` | Get one grammar topic |
| PATCH | `/api/grammar-topics/{id}` | Edit a grammar topic |
| DELETE | `/api/grammar-topics/{id}` | Delete a grammar topic |
| POST | `/api/grammar-topics/{id}/explanation` | Ask the AI to explain the topic |
| GET | `/api/grammar-topics/{id}/status-history` | Status history of a grammar topic |
| GET | `/api/grammar-topics/catalog` | List suggested grammar topics |
| POST | `/api/grammar-topics/catalog/{id}` | Add a suggested topic |

### Study sessions

| Method | Path | What it does |
|---|---|---|
| GET | `/api/study-sessions` | List study sessions |
| POST | `/api/study-sessions` | Create a study session |
| GET | `/api/study-sessions/{id}` | Get one study session |
| PATCH | `/api/study-sessions/{id}` | Edit a study session |
| DELETE | `/api/study-sessions/{id}` | Delete a study session |
| POST | `/api/study-sessions/{id}/grammar-topics` | Attach grammar topics |
| DELETE | `/api/study-sessions/{id}/grammar-topics/{grammarTopicId}` | Detach a grammar topic |
| GET | `/api/study-sessions/{id}/material` | What a study session covers |
| POST | `/api/study-sessions/{id}/vocabulary` | Attach words |
| DELETE | `/api/study-sessions/{id}/vocabulary/{vocabularyItemId}` | Detach a word |
| GET | `/api/study-sessions/active` | The active study session |
| GET | `/api/study-sessions/timeline` | Learning history |

### Flashcards

| Method | Path | What it does |
|---|---|---|
| GET | `/api/flashcards` | List flashcards |
| GET | `/api/flashcards/{id}` | Get a flashcard |
| PATCH | `/api/flashcards/{id}` | Suspend or unsuspend a flashcard |
| GET | `/api/flashcards/{id}/reviews` | A flashcard's review history |

### Reviews

| Method | Path | What it does |
|---|---|---|
| POST | `/api/reviews` | Grade a card |
| GET | `/api/reviews/due` | Cards to review now |
| GET | `/api/reviews/stats` | Review statistics |

### Exercises

| Method | Path | What it does |
|---|---|---|
| GET | `/api/admin/exercise-reports` | Reported exercises (admin) |
| GET | `/api/exercise-sets` | List exercise sets |
| POST | `/api/exercise-sets` | Ask for an exercise set |
| GET | `/api/exercise-sets/{id}` | Get an exercise set |
| DELETE | `/api/exercise-sets/{id}` | Delete an exercise set |
| GET | `/api/exercises/{id}/attempts` | An exercise's attempts |
| POST | `/api/exercises/{id}/attempts` | Answer an exercise |
| POST | `/api/exercises/{id}/attempts/{attemptId}/explanation` | Explain a wrong answer |
| POST | `/api/exercises/{id}/report` | Report a bad exercise |
| DELETE | `/api/exercises/{id}/report` | Take a report back |

### Practice

| Method | Path | What it does |
|---|---|---|
| GET | `/api/practice-sessions` | List practice sessions |
| POST | `/api/practice-sessions` | Start a practice session |
| GET | `/api/practice-sessions/{id}` | Get a practice session |
| POST | `/api/practice-sessions/{id}/complete` | Complete a practice session |
| POST | `/api/practice-sessions/{id}/retry` | Practise a session's mistakes again |
| GET | `/api/practice-sessions/overview` | What there is to practise |

### Progress

| Method | Path | What it does |
|---|---|---|
| GET | `/api/progress/mistakes` | Your frequent mistakes |
| GET | `/api/progress/stats` | Your statistics |
| GET | `/api/progress/weak-points` | Your weak points |
| POST | `/api/progress/weak-spots/exercises` | Practise my weak spots |

### AI jobs

| Method | Path | What it does |
|---|---|---|
| GET | `/api/ai/jobs` | List AI jobs |
| GET | `/api/ai/jobs/{id}` | Get an AI job |
| GET | `/api/ai/jobs/progress` | Progress of several AI jobs |

### AI usage

| Method | Path | What it does |
|---|---|---|
| GET | `/api/admin/ai-usage` | AI usage summary (admin) |
| GET | `/api/ai/usage` | My AI usage today |

### Monitoring

| Method | Path | What it does |
|---|---|---|
| POST | `/api/client-errors` | Report a browser error _(public)_ |

### Actuator

| Method | Path | What it does |
|---|---|---|
| GET | `/actuator` | Actuator root web endpoint |
| GET | `/actuator/health` | Actuator web endpoint 'health' |

<!-- operations:end -->
