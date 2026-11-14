# Privacy and data protection (SEC-02)

These notes are for whoever runs a LinguaFlow service. They say what personal data the
app holds, where it goes, and how the learner's rights are met in code. The learner-facing
version is the `/privacy` page (`frontend/src/pages/privacy/PrivacyPage.tsx`). Keep both
in step with the code.

This isn't legal advice. Before a public launch, add the operator's name and contact
details to the privacy page, and have the text reviewed for the countries you serve.

## What's stored

| Data | Where | Personal? |
|---|---|---|
| Email, display name, interface language, time zone, last login | `users` | Yes |
| Password | `users.password_hash`, BCrypt, never readable | Yes (secret) |
| Login sessions; verification and reset links | `refresh_tokens`, `user_action_tokens`, SHA-256 hashes only | Yes (secret) |
| Learning languages and settings | `user_languages` | Linked to the user |
| Words, examples, tags, grammar topics, personal notes, lessons | `vocabulary_*`, `tags`, `grammar_topics`, `study_sessions`, link tables | Linked; notes are free text |
| Flashcards, reviews, exercises, answers, mistakes, practice sessions, progress | `flashcards`, `srs_state`, `flashcard_reviews`, `exercise_*`, `mistakes`, `practice_*`, `*_progress`, `mistake_counts` | Linked |
| AI jobs | `ai_jobs`: type, status, IDs and results | Linked |
| AI usage log | `ai_requests`: feature, model, token counts, cost, success; **never prompt or answer text** | Linked |
| Shared AI cache | `ai_cache`: dictionary-style answers keyed by language data only | No |

**Browser storage:**
- the refresh cookie `lf_refresh` (`HttpOnly; Secure; SameSite=Strict; Path=/api/auth`);
- `localStorage` with `lf-theme` and `lf-review-typing`;
- the access token, kept in memory only.

There are no analytics, ads or tracking.

## What reaches the AI provider

AI calls go to Anthropic (`AnthropicLlmClient`) and carry learning content only: words,
grammar topic titles and descriptions, exercises and the learner's answers to them. In detail:

- **No identity:** no prompt template has a variable for the name, email, user ID or account data (`PromptVar`).
- **No notes:** grammar topic notes, lesson notes and word notes are never put in prompts.
- **Personal data stripped (AI-07):** in anything the learner types, `PromptVariables` removes email addresses, IBANs, phone numbers and long digit runs before sending. Invisible characters go too.
- **No user in the cache:** cache keys hold language data only, never a user ID or a learner's own text.
- **Tested:** `PersonalDataInPromptsTests` runs every AI feature for a learner with a distinctive name, email, phone number and notes. It fails if any of them appears in a prompt.

The provider's own retention rules apply to what it receives. Check its current terms
and data processing agreement before launch.

## Learners' rights in code

| Right | How |
|---|---|
| Access and portability (GDPR art. 15, 20) | `GET /api/users/me/export`, Settings → "Your data" → "Download my data" |
| Rectification (art. 16) | Every item can be edited in the app; the profile through `PATCH /api/users/me` |
| Erasure (art. 17) | `DELETE /api/users/me` with the password, Settings → "Delete account" |

### The export

- **Code:** `DataExportService`, a versioned JSON file (`format: linguaflow-export`, `version: 1`).
- **What it holds:** the account (without the password hash) and the user's rows from every learning, progress and AI table, with `jsonb` embedded as JSON.
- **What it leaves out:** each table left out has a reason in `NOT_EXPORTED`: secrets (password and token hashes) and shared data (catalogs, AI cache).
- **New tables:** `DataExportTests` fails when a table is in neither list, so a new table forces a decision.

### Erasure

- **How:** deleting the user row cascades through every user table. Each one has `on delete cascade` to `users` or `user_languages`.
- **Tested:** `DataExportTests.deletingTheAccountLeavesNothingBehind` checks that no exported row, token or account row is left, and that other users are untouched.
- **Backups:** these still hold deleted data until they expire (OPS-05 should document the retention).
- **Access tokens:** an access token can still be used for up to 15 minutes after deletion (see the security review), but it finds no data.

## Follow-ups

- Operator name, contact and legal basis on the privacy page before launch.
- Backup retention (OPS-05) and log retention (OPS-04), both stated on the privacy page.
- A data processing agreement with the AI provider.
