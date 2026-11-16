# Learning rules: SRS, answer checking, progress and mastery

All of these are deterministic code, mostly pure functions with table-driven tests. Times
use an injected `Clock`; "today" is the learner's local day in their time zone
(`users.timezone`).

## Words and cards

- A word starts `PENDING_AI` and becomes `READY` (or `AI_FAILED`) after enrichment.
- **Duplicates:** a word is a duplicate when its normalized lemma matches and the parts of
  speech are equal or either is unset. Normalization collapses whitespace, lower-cases,
  applies NFC, strips one German article and folds `ß` to `ss`, so "Hund" matches "der Hund"
  while the verb "laufen" and the noun "das Laufen" coexist. CSV import uses the exact
  unique key instead, so an export imports back completely.
- **Cards:** every READY word gets one card per direction, `TARGET_TO_NATIVE` ("der Hund" →
  "dog") and `NATIVE_TO_TARGET` ("dog", hint "noun" → "der Hund"). Card content is never
  stored: it's built from the word on every read, so an edit changes the cards at once. The
  learner's `cardDirections` setting chooses which directions are reviewed; both always
  exist, so switching never loses history.

## Spaced repetition (`flashcard.SrsScheduler`)

An SM-2 variant in the style of Anki, pure and deterministic (no fuzz):
`review(card, grade, now, zone) → card`.

### States and grades

Cards are `NEW`, `LEARNING`, `REVIEW` or `RELEARNING`; grades are `AGAIN`, `HARD`, `GOOD`,
`EASY`. A card starts with ease 2.50.

### Learning (NEW, LEARNING)

Steps of **1 and 10 minutes**; `repetitions` is the current step.

| Grade | Effect |
|---|---|
| AGAIN | back to the first step |
| HARD | repeat the current step |
| GOOD | next step; after the last, graduate to REVIEW with **1 day** |
| EASY | graduate at once with **4 days** |

### Review

With interval `i` days and ease `e`:

| Grade | Next interval | Ease |
|---|---|---|
| AGAIN | lapse: relearn through one 10-minute step, then 1 day | −0.20 |
| HARD | `i × 1.2` | −0.15 |
| GOOD | `i × e` | — |
| EASY | `i × e × 1.3` | +0.15 |

- Each grade's interval beats the one below by at least a day; intervals are capped at
  36,500 days; the scheduled interval is used, not the days actually elapsed.
- Ease never drops below 1.30 and is kept to two decimals.
- Review cards fall due at the **start of a local day** in the learner's zone (DST-safe).

### Review queue (`ReviewService`)

- **Reviewable:** not suspended, in one of the learner's directions, and the word has a
  translation.
- **Due:** REVIEW cards due before tomorrow starts; learning cards whose step has passed.
- **Order:** overdue oldest first; then today's, learning steps first, then the weakest
  (lowest ease, most lapses); then new cards, oldest word first.
- **Daily limits:** `newCardsPerDay` (default 20) is a hard limit on cards first reviewed
  today. `dailyReviewGoal` (default 50) is only a target; due cards are never held back.
- **Siblings:** a word's English → German card waits while its German → English card is still
  new or was reviewed today, so recognition comes first and the two aren't asked back to back.
- **Idempotent grading:** each showing of a card has a review token; the same token returns
  the stored review without changing anything, so a retried request counts once.
- **Each grading** appends an immutable `flashcard_reviews` row (with the state before) and
  shows the learner a preview of where each grade would send the card.

### Review statistics

Streaks are days in a row with a review (the current one ends today, or yesterday while today
has none yet); retention over 30 days is the share of REVIEW-state reviews that weren't
AGAIN; the forecast counts cards falling due on each of the next 7 local days.

## Answer checking (`exercise.AnswerEvaluator`)

Closed formats are graded by code; open formats by code when the answer matches, otherwise by
the AI judge ([ai.md](ai.md)).

- **Comparison** (`AnswerNormalizer`): NFC, typographic apostrophes made plain, punctuation
  and loose dashes removed, whitespace collapsed; hyphens inside words and apostrophes kept.
  The strictest of four levels that matches wins: exact; ignoring case (unless strict mode);
  umlauts written as ae/oe/ue/ss; both. Accepted differences are reported as notes
  (`CAPITALIZATION`, `UMLAUT_SPELLING`, `ALTERNATIVE`).
- **Fill in the blank:** each gap on its own; score = right gaps / gaps; correct only with all.
- **Multiple choice:** the index.
- **Word order:** tokens compared ignoring case and punctuation against the solution and its
  alternatives; a wrong order earns partial credit, the longest common subsequence with the
  closest correct order over the longer length, capped at 0.999.
- **Open types** (translation, sentence construction, error correction, transformation):
  a match with the answer or an alternative is correct, an empty answer wrong, anything else
  goes to the AI judge, which returns a score, typed errors (`WRONG_CASE`, `WRONG_ARTICLE`,
  `VERB_FORM`, `WORD_ORDER`, `PREPOSITION`, …) and the topic of each error.
- **Mistakes:** every wrong attempt stores mistakes: one per judge error, or from the rules
  `WRONG_GAP` per gap, `WRONG_CHOICE`, `WORD_ORDER`, `OTHER`, each linked to a grammar topic
  when it can be told.

## Progress aggregates (`progress.ProgressAggregates`)

After every review or attempt commits, the affected rows are **recomputed** from the raw
answers with set-based SQL, never incremented, so rolling windows stay exact. Every learner
is rebuilt at startup, which also repairs drift (e.g. a deleted exercise set).

| Table | Per | Fields |
|---|---|---|
| `grammar_topic_progress` | topic | attempts, correct, `recent_accuracy` = average **score** of the latest 20 attempts, practice days (distinct local dates), last attempt, weakness |
| `vocabulary_progress` | word | times seen and right over reviews (right = not AGAIN) and linked exercise attempts, `recent_accuracy` over the latest 20, `interval_days` (the longest current card interval), last answer, weakness |
| `mistake_counts` | learning language and error type | count, last seen |

A failure while updating is logged; it never fails the learner's request or loses the answer.

## Mastery (`progress.MasteryRules`)

Applied right after the aggregates, in the same transaction. Thresholds live under
`linguaflow.progress.mastery.*`.

| Material | Becomes MASTERED when | Goes back when |
|---|---|---|
| Grammar topic | `recent_accuracy` ≥ **0.85** over at least **20** attempts on at least **3** days | a MASTERED topic drops below **0.70** → LEARNED |
| Word | `interval_days` ≥ **21** (its cards are well spaced) | a lapse brings it below → LEARNED |

Between the two topic thresholds nothing changes, so topics don't flip back and forth. Topic
changes are recorded in the status history as `MASTERY_RULE` or `DEMOTION_RULE`.

### Status by hand

- A topic starts as NEW, LEARNING or LEARNED, never MASTERED.
- The learner may move between LEARNING and LEARNED freely. MASTERED needs an explicit
  confirmation (`confirmMastered`), and NEW can't be chosen again.
- Rules may only grant MASTERED (`MASTERY_RULE`) or take it away to LEARNING/LEARNED
  (`DEMOTION_RULE`).
- Words can't be set to MASTERED by the learner at all.

## Weak points (`progress.WeaknessScores`)

A 0–1 score with reasons, stored after every update and recomputed fresh for the weak-points
endpoint.

- **Error rate:** `1 − recent_accuracy`, smoothed towards 0.5 with two imaginary answers,
  `(n·err + 1) / (n + 2)` with n at most 20, so a couple of answers don't decide. Without
  answers it is 0.5.
- **Staleness:** days since the last practice over 30, at most 1; never practised is 1.
- **Topic:** `0.75 × error rate + 0.25 × staleness`.
- **Word:** `0.5 × error rate + 0.3 × lapses + 0.2 × staleness`, lapses being the most on one
  card, capped at 4, over 4.
- MASTERED material scores half. Scores are rounded to three decimals; below 0.2 nothing is
  listed as weak.

| Reason | When | Value |
|---|---|---|
| `LOW_ACCURACY` | accuracy below 0.7 | the accuracy |
| `FREQUENT_MISTAKE` | the topic's most common error type occurred twice or more | the count and type |
| `LAPSES` | 2 or more lapses | lapses |
| `NOT_PRACTISED_RECENTLY` | 14 days or more | days |

The stored score feeds practice selection ([practice.md](practice.md)).

## Statistics

`GET /api/progress/stats` reports material by status and CEFR level, and daily activity
(reviews, remembered, attempts, scores, reported time) over all time, turned into a heatmap
of local days, Monday-first weeks with review rate and exercise score, and a practice streak
(days with a review **or** an exercise, unlike the review-only streak above).
