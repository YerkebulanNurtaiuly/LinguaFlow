# AI usage and prompt design

The model writes content and judges free-text answers. It never decides a rule: what it
returns is parsed into Java records, validated, and only then used, and everything that
counts (scheduling, mastery, scores of closed exercises, practice selection) is ordinary code.

## Where the AI is used

| Use case (`AiUseCase`) | Triggered by | How it runs | Output | Cached across learners |
|---|---|---|---|---|
| `VOCABULARY_ENRICHMENT` | every new word, "Ask AI again" | background job, batches of 20 | translation, part of speech, gender, plural, verb forms, CEFR level, 2–3 examples, notes | yes, by normalized lemma |
| `GRAMMAR_EXPLANATION` | "Explain with AI" on a topic | background job | summary, rules, examples, common mistakes | no (uses the learner's words and topics) |
| `EXERCISE_GENERATION` | an exercise set, a practice session, weak-spot training | background job, one call per exercise type | exercises with solutions, linked words and topics | no (built from the learner's words) |
| `ANSWER_EVALUATION` | an open answer the rules can't decide | while the learner waits | correct, score, typed errors with spans and topics, corrected answer, feedback | no (the learner's own answer) |
| `MISTAKE_EXPLANATION` | "Why is this wrong?" | while the learner waits | the mistake, the rule, an example, other errors | per exercise and normalized answer |

Nothing else calls the model. The AI is never needed to review cards or to grade
fill-in-the-blank, multiple-choice or word-order exercises.

## The call pipeline

Feature code never talks to a provider directly:

```
feature service / job handler
  └─ PromptLibrary.render(template, variables)      prompt templates + input safety
      └─ StructuredLlm.complete[Cached](caller, prompt, AnswerRecord.class, check)
          ├─ AiCache (optional)                       shared answers, before any call
          └─ AiGateway                                quota → circuit breaker → client → usage log, metrics
              └─ LlmClient                            Anthropic | Fake | Scripted
```

1. **Rendering** (`ai.prompt`): a versioned template plus typed variables becomes a system
   and a user prompt (below).
2. **Cache** (`ai.cache`, only `completeCached`): a hit returns a stored answer without a call.
3. **Gateway** (`AiGateway`): the per-user daily quota is checked first, then the call goes
   through a circuit breaker to the client, and one row is written to `ai_requests` with
   tokens, cost, latency and outcome. Metrics are recorded for every outcome.
4. **Structured output** (`StructuredLlm`): the answer record is turned into a JSON Schema
   and sent as the required output format. The reply is parsed, checked, and retried once
   with the problems listed (below).

### Clients

- `AnthropicLlmClient`: the official Java SDK. The system prompt is marked for prompt
  caching; the schema goes into the output format; server-side refusal fallbacks are on.
  The model (`claude-opus-5-5` by default), effort, token limit and timeout are set per use
  case under `linguaflow.ai.use-cases.*`. No temperature: current models take effort instead.
- `FakeLlmClient` (tests): answers any schema with the smallest valid JSON; tests script
  answers per use case and inject failures.
- `ScriptedLlmClient` (end-to-end tests, staging): canned, rule-valid content from small
  dictionaries and exercise banks, so the whole app works without a key or cost.

`linguaflow.ai.provider=auto` picks Anthropic when `AI_API_KEY` is set, the fake otherwise.

## Prompt design

### Templates

Files under `backend/src/main/resources/prompts/<id>/v<n>.system.md` and `.user.md`, with
shared text in `prompts/partials/`. Templates are English and name languages in English;
explanations are asked for in the learner's explanation language.

| Template | Use case | Notes |
|---|---|---|
| `vocabulary-enrichment` / `-batch` | enrichment | the batch template answers up to 20 words in order, echoing each |
| `grammar-explanation` | explanation | adds the learner's known topics and recent words for relevant examples |
| `exercise-<type>` (7) | generation | one per exercise type; system prompts share `exercise-rules-v2`, user prompts `exercise-request-v4` |
| `answer-evaluation` | judging | v2: fixed error types, spans, a topic per error |
| `mistake-explanation` | explaining | the exercise as shown, the correct and the learner's answer, the judge's errors |

- **Syntax:** a small Mustache subset: `{{var}}`, sections `{{#var}}…{{/var}}` and
  `{{^var}}…{{/var}}`, partials `{{> name}}`. Inserted values are never parsed again.
- **Variables:** `PromptVar` lists them all; each template declares its required and
  optional ones. `PromptLibrary` checks at startup for missing files, malformed tags, and
  undeclared or unused variables.
- **Caching-friendly:** the system prompt may only use the two language names, so it stays
  byte-identical across calls and is served from the provider's prompt cache. Everything
  per call goes in the user prompt.
- **Versions are immutable:** a deployed version is never edited. A change is a new
  `v<n+1>` and a bump in `PromptTemplate`; `PromptVersionTests` pins each version's hash, and
  `PromptGoldenTests` pins each template's rendered output for fixed inputs, so any change
  to what the model receives shows as a diff. The version tag goes into the usage log and
  the cache key.

### Answers as records

Each use case has a package-private answer record in its feature module
(`EnrichmentAnswer`, `ExplanationAnswer`, `GeneratedExercises`, `JudgeAnswer`,
`MistakeExplanation`). `JsonSchemas` turns it into the schema the model must follow: every
field required, `@Nullable` fields as `anyOf [schema, null]`, field descriptions from
`@JsonPropertyDescription`. Lengths and ranges aren't expressible in the output format, so
they are Bean Validation annotations checked after parsing.

Checks, in order: JSON parse (a Markdown fence is stripped, unknown fields ignored) → every
required field present at any depth → Bean Validation → the feature's domain check, e.g.:
- enrichment keeps the learner's part of speech, gives German nouns a gender, verbs their
  forms, and a batch answers every word in order;
- exercises pass `ExerciseRules` (gap count matches blanks, options distinct and the index in
  range, word-order tokens exactly the prompt's, …);
- the judge is consistent (correct ⇔ score 1 and no errors; every span occurs in the answer).

An invalid answer is retried **once**: same system prompt, the user prompt followed by the
rejected answer and its problems as `path: message`. A second invalid answer fails with
`INVALID_OUTPUT`. Generation validates exercises one by one, keeps the good ones and asks
again for the missing (at most 3 rounds per type).

### Merging AI content

AI content never overwrites the learner's: enrichment only fills fields the learner left
empty and records each in `aiGeneratedFields`; editing a field removes it from that list;
"Ask AI again" clears only AI-filled values. Explanations are stored separately from the
learner's notes.

## Safety

### Input

Every learner-supplied value passes through `PromptVariables.set`:

1. NFC, and invisible or control characters removed (zero-width, bidi overrides, Unicode tag
   characters that can hide instructions);
2. personal data redacted: emails, IBANs, phone numbers and long digit runs;
3. reshaped by the variable's shape (`LINE`, `TEXT`, `LIST`) and cut to its length and item
   limits, the hard ceiling for what a learner can push into a prompt;
4. `<` and `>` escaped, so input can't close the XML-style tag (`<word>`, `<learner_answer>`)
   that delimits it. Every template tells the model that tagged content is data.

Text that looks like an injection attempt ("ignore previous instructions", role markers) is
still sent, as delimited data, but its answer isn't stored in the shared cache. The flags of
what happened (`PromptInputFlag`) are logged per template, never with the text.

No account data reaches a prompt: there is no variable for names, emails or notes, and
`PersonalDataInPromptsTests` runs every use case for a learner with distinctive personal data
and fails if any of it appears.

### Output

Strings in a parsed answer have invisible and control characters removed; answers only ever
become records and are never executed; the frontend renders AI text as text or sanitized
Markdown. `NoCodeExecutionTests` forbids script engines, expression parsers, process
execution, reflection loading and Java deserialization in the main code.

## Jobs

Background AI work goes through the `ai_jobs` table (`ai.job`), so it survives restarts:

- **Enqueue** joins the caller's transaction: no job without its data, and a rollback
  removes the job. The request returns at once with the job ID.
- **Worker:** polls every 2 s and right after an enqueueing commit, claims due jobs with
  `FOR UPDATE SKIP LOCKED`, runs 4 at a time on virtual threads. Handlers run outside any
  transaction and are idempotent (enrichment only touches words still `PENDING_AI`).
- **Retries:** a retryable failure (timeout, overload, network, open circuit) goes back to
  the queue with exponential backoff (30 s doubling, at most 1 h); after 3 attempts the job is
  DEAD. A non-retryable failure (invalid output, refusal) is FAILED at once. `onGiveUp` lets
  the feature react, e.g. mark words `AI_FAILED`.
- **Quota:** a job that hits the daily quota is postponed to the reset without using an
  attempt, so large imports finish the next day.
- **Crashes:** a job RUNNING longer than 10 minutes is requeued (or dead-lettered on its last
  attempt).
- **Status API:** `/api/ai/jobs` (owner-scoped) gives status, `waiting` (`RETRY` or
  `DAILY_LIMIT`) and the result; the UI polls it.

## Cache

`ai_cache` stores validated answers for prompts built only from language data, so learners
share them. The key is a SHA-256 of use case, learning language, prompt version and
normalized parts, never a user ID or a learner's own text:
- enrichment: explanation language, normalized lemma, part of speech ("der Hund" and "Hund"
  share an entry);
- mistake explanations: exercise ID and normalized answer, for answers up to 100 characters.

Entries expire after 180 days; a hit must still pass every current check (a tightened rule
sends it back to the model); results over 64 KB, from suspicious input, or with redacted
personal data aren't stored. Cache failures count as misses, never errors.

## Cost and reliability

- **Usage log:** one `ai_requests` row per call (use case, model, prompt version, tokens by
  kind, cost in USD from `AiPricing`, latency, error kind), written in its own transaction.
  No prompts or answers are stored.
- **Daily quota:** 300 calls and 1.5 M tokens per learner per UTC day by default; system calls
  are exempt. Over the quota: 429 `AI_QUOTA_EXCEEDED` with `Retry-After` in requests, a
  postponed job in the queue. `GET /api/ai/usage` shows the learner's day.
- **Circuit breaker:** opens at 50% failures (timeouts, unavailability, network) over the last
  20 calls, for 60 s; calls are refused without reaching the provider and jobs back off.
- **Cost levers:** the shared cache, batched enrichment, prompt caching of byte-stable system
  prompts, explanations only on request, and per-use-case model and effort settings.
- **Observability:** metrics for calls, tokens, cost, refusals and cache hits, and a Grafana
  dashboard ([observability.md](../operations/observability.md));
  `GET /api/admin/ai-usage` summarises cost by use case, model, day and top users.

## Quality checks

- **Contract tests** (`*ContractTests`) parse hand-written sample answers, valid and invalid,
  through the production records and checks.
- **Live eval** (`EnrichmentEvalTests`, `ExerciseAiEvalTests`, `-Dai.live=true`) runs the
  real model on cases with known answers and passes on a score threshold; the
  **AI eval** workflow runs it nightly when the repository has an API key.
- **Reports:** learners can report bad exercises; `GET /api/admin/exercise-reports` lists
  them with prompts and solutions for tuning the templates.
