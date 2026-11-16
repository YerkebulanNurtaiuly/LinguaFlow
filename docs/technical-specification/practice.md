# Practice: material selection, sessions and exercise generation

## Overview

A practice session mixes flashcards and AI-written exercises from the learner's own
material. Building one takes three steps:

1. **Select material** (`learning.practice.MaterialSelectionService`): which words and
   topics, by mode, weighted towards what needs practice, reproducibly from a seed.
2. **Plan the session** (`PracticePlan`): one card per word, a share of exercises, and the
   order.
3. **Generate exercises** (`exercise.ExerciseGenerator`, a background job): the session
   starts with its cards, and the exercises join when they are written.

## Modes (`PracticeMode`)

| Mode | Material | Picking weight (w = weakness) |
|---|---|---|
| `CURRENT_LESSON` | the chosen lesson, or the latest by date then creation | `0.5 + 0.5w` |
| `MIXED` | a share `newRatio` (default 0.6) from fresh material, the rest older; a short pool is filled by the other | fresh: `1`; older: `0.1 + 0.5w + 0.4·staleness` |
| `FULL_REVISION` | everything | `0.1 + 0.5w + 0.4·dueness` |
| `WEAK_SPOTS` | weakest first | `0.02 + w²` |

Without any lesson, `CURRENT_LESSON` is 409 `NO_STUDY_SESSION`.

## Candidates and scores

`PracticeCandidates` reads, per learner and language, every READY word and every topic with:
- **words:** lowest ease and most lapses over its cards, whether a card is (re)learning, the
  last review, the earliest due date, the last exercise attempt;
- **topics:** attempt count, the average score of the latest 20 attempts, the last attempt;
- the stored weakness score from the progress module, when there is one.

`PracticeWeights` turns these into scores from 0 to 1:

| Score | Definition |
|---|---|
| fresh | added within 7 days, or never practised |
| word weakness | the stored weakness score; or `0.4 × (ease below 2.5, 1.3 = full) + 0.3 × min(lapses, 4)/4 + 0.3 if (re)learning`, never reviewed = 0.5 |
| topic weakness | the stored score; or `1 − recent score`, no attempts = 0.5 |
| mastery | MASTERED halves the estimate (the stored score already includes it) |
| staleness | days since practice over 30, at most 1; never practised = 1 |
| word dueness | 1 when due, falling to 0 a week ahead; never reviewed = 0.5 |
| topic dueness | its staleness |

## Weighted sampling (`MaterialSelector`)

Picking uses **Efraimidis–Spirakis weighted sampling without replacement**:

1. sort the candidates by ID;
2. for each, draw `u` from `Random(seed ^ pool)` and compute the key `ln(u) / weight`;
3. take the candidates with the largest keys.

Properties:
- **Proportional, not greedy:** heavier material is likelier, but anything with a positive
  weight can be picked, so the learner doesn't see the same top items every time.
- **Reproducible:** the same seed and candidates give the same picks, whatever order the
  database returned them in. The seed is stored in the session's `config` (below 2^53, so
  JavaScript reads it exactly). Results also depend on the clock, through staleness and
  dueness.
- Words and topics use separate random streams.

`MaterialSelectorTests` checks reproducibility, order independence, the MIXED ratio and its
fill-up, and over many seeds that weighting really favours weak material.

## The session plan (`PracticePlan`)

- **Size:** the learner's `practiceSessionLength` (5–200, default 20), or the requested
  length. Selection asks for up to twice the length in words and 3 topics.
- **Cards:** each selected word shows one card: its reviewable card falling due first, or
  for a new word the recognition card (German → English). The daily new-card limit doesn't
  apply inside practice.
- **Split:** with both kinds, a third of the length (rounded up) is exercises, at most 20 (one
  set); exercises fill in when cards run short, cards when exercises hit 20. Exercises need a
  topic.
- **Errors:** neither kind requested is a 400; nothing to practise is 409
  `NOTHING_TO_PRACTISE`.
- **Joining exercises:** when the set is ready, the items are rewritten: answered items first
  in their order, then the open cards interleaved with the new exercises. Reported exercises
  are left out; a FAILED set leaves the cards alone.
- **Answering:** reviews and attempts carry the session ID and mark their item answered.
- **Completing** stores a summary from the session's own reviews and attempts (the latest
  answer per item counts): grades, remembered, exercises right, average score, time, and the
  missed cards and exercises. "Practise mistakes again" starts a new session with exactly
  those, without writing new exercises.

## Exercise generation (`ExerciseGenerator`)

Used by exercise sets the learner asks for (1–5 topics, chosen formats, 1–20 exercises,
difficulty), by practice sessions and by weak-spot training.

### Input

- **Practice words:** the selected words, or else up to 50 of the learner's NEW or LEARNING
  enriched words, topped up to `count × 2` with others; written as "der Hund (dog)".
- **Known words:** up to 100 more, so sentences stay within what the learner knows.
- **Topics** as "Title: description"; the **level** is the highest topic level or the
  learner's own.
- **Recent exercises:** up to 30 of the learner's latest on the same topics, so they aren't
  repeated.
- **Combined** (two or more topics in a practice session or on request): every exercise must
  practise at least two of the topics together, and when the words come from two or more
  lessons they are grouped by lesson ("Lektion 2 (2 Nov): das Kind, der Hund").
- **Focus mistakes** (weak-spot training): the learner's two most frequent error types per
  weak topic with one example each.

### Process

1. Split the count over the formats (10 over three formats is 4, 3, 3); one call per format.
2. Validate each item on its own: Bean Validation, then `ExerciseRules`. Drop invalid
   items, repeats and copies of recent exercises.
3. Ask again for the missing ones, listing what's already made, at most 3 rounds per format.
4. Link the model's `words` back by normalized lemma and its `topics` by normalized title
   (an exercise naming none is linked to all the set's topics).
5. Save the exercises with formats interleaved and mark the set READY in one transaction,
   or FAILED when nothing usable came back.

The job result records requested, made, dropped and (for combined sets) how many practise
two or more topics.

### Exercise formats

| Format | Prompt | Solution | Graded by |
|---|---|---|---|
| `FILL_BLANK` | text with `___` gaps, hint | one answer (+ alternatives) per gap | rules |
| `MULTIPLE_CHOICE` | question, 2–6 options | the correct index | rules |
| `WORD_ORDER` | shuffled tokens | the order (+ alternative orders) | rules, partial credit |
| `TRANSLATION` | direction, text | answer + alternatives | rules, else AI judge |
| `SENTENCE_CONSTRUCTION` | task, words to use | answer + alternatives | rules, else AI judge |
| `ERROR_CORRECTION` | a sentence with an error | the corrected sentence | rules, else AI judge |
| `TRANSFORMATION` | sentence, task | answer + alternatives | rules, else AI judge |

Exercise content is typed: a sealed `ExercisePrompt` and `ExerciseSolution` per format,
stored as JSON and validated by `ExerciseRules` both when generated and when saved.

## Weak-spot training

`POST /api/progress/weak-spots/exercises` takes the 3 weakest topics and up to 20 weakest
words (score at least 0.2) and writes a set focused on the learner's frequent mistakes in
those topics. Without a weak topic it is 409 `NOTHING_TO_PRACTISE`.
