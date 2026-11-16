# LinguaFlow technical specification (DOC-01)

How LinguaFlow is built and why: the parts, the rules they follow, and the algorithms that
decide what a learner sees. It describes the system as implemented; the product vision is
in the repository [README](../../README.md) and the backlog in [TASKS.md](../../TASKS.md).

| Document | Covers |
|---|---|
| [architecture.md](architecture.md) | system context, backend modules and their boundaries, events, data ownership, frontend structure |
| [api.md](api.md) | API conventions (auth, errors, paging, ownership) and every endpoint by area |
| [ai.md](ai.md) | where the AI is used, the call pipeline, prompt templates and safety, jobs, cache, cost control |
| [learning-rules.md](learning-rules.md) | spaced repetition (SRS), answer checking, progress aggregates, mastery and weak points |
| [practice.md](practice.md) | practice material selection, practice sessions, exercise generation, weak-spot training |

Related documents:
- [database/schema.md](../database/schema.md): the approved schema design; migrations follow it;
- [security/security-review.md](../security/security-review.md) and
  [security/privacy.md](../security/privacy.md): the OWASP review and the data inventory;
- [operations/](../operations/): deployment, observability, backups and restores.

## Principles

1. **AI produces content; the backend decides.** Every business rule is deterministic Java:
   validation, SRS scheduling, mastery, progress, practice selection and grading of closed
   exercise types. The model writes content (enrichment, exercises, explanations) and judges
   open answers, and everything it returns is parsed into records and checked before use.
2. **Everything is the learner's own.** All learning data is scoped by user and learning
   language. Another user's resource answers 404, never 403, and the repositories offer no
   unscoped queries.
3. **Status is earned.** Grammar topics and words become MASTERED only by rules over the
   learner's answers; the learner can't choose it for words and must confirm it for topics.
4. **Recompute, don't count.** Progress tables are rebuilt from the raw answers with set-based
   SQL, so rolling windows stay exact and drift repairs itself.
5. **Works without a key.** A deterministic fake (tests) and a scripted provider (end-to-end
   tests, staging) stand in for the model, so nothing waits on the provider.

## Glossary

| Term | Meaning |
|---|---|
| Learning language | a `(user, target language)` profile (`user_languages`); one is active at a time |
| Explanation language | the language translations and explanations are written in |
| Word / vocabulary item | an entry in the learner's vocabulary, enriched by the AI |
| Grammar topic | a grammar point the learner studies, own or from the catalog |
| Study session / lesson | a dated lesson grouping the words and topics learnt in it |
| Card | a flashcard, one per word and direction (German → English, English → German) |
| Review | grading a card: AGAIN, HARD, GOOD or EASY |
| Exercise set | AI-written exercises on chosen topics, built from the learner's words |
| Attempt | one answer to an exercise, graded by rules or the AI judge |
| Practice session | a mixed run of cards and exercises chosen by the selection algorithm |
| Weak point | a topic or word with a high weakness score |
