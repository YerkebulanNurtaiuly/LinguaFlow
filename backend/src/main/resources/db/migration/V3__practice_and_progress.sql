-- V3: practice, progress and AI infrastructure (DB-04). Design: docs/database/schema.md,
-- sections 3.3 to 3.7 and 5. Tables are created in foreign-key order.
--
-- Tables that copy user_id from their parent (for per-user indexes) reference the parent
-- through a composite FK (parent_id, user_id) -> parent (id, user_id), so the copy can
-- never disagree with the owner. The parents get a matching UNIQUE (id, user_id).

alter table vocabulary_items add constraint vocabulary_items_id_user_uq unique (id, user_id);
alter table grammar_topics add constraint grammar_topics_id_user_uq unique (id, user_id);

-- AI infrastructure (AI-04 .. AI-06) ------------------------------------------------------
create table ai_jobs (
    id           uuid primary key,
    user_id      uuid,
    type         text        not null,
    status       text        not null default 'QUEUED',
    payload      jsonb       not null,
    result       jsonb,
    attempts     int         not null default 0,
    max_attempts int         not null default 3,
    next_run_at  timestamptz not null default now(),
    locked_at    timestamptz,
    locked_by    text,
    last_error   text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint ai_jobs_user_fk foreign key (user_id) references users (id) on delete cascade,
    constraint ai_jobs_type_ck check (type in
        ('VOCABULARY_ENRICHMENT', 'GRAMMAR_EXPLANATION', 'EXERCISE_GENERATION', 'ANSWER_EVALUATION')),
    constraint ai_jobs_status_ck check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD')),
    constraint ai_jobs_attempts_ck check (attempts >= 0 and max_attempts >= 1)
);

-- Workers poll: select ... where status = 'QUEUED' and next_run_at <= now() for update skip locked
create index ai_jobs_queue_ix on ai_jobs (status, next_run_at) where status in ('QUEUED', 'RUNNING');
create index ai_jobs_user_ix on ai_jobs (user_id, created_at);

-- One row per LLM call. Prompts and responses are not stored (they hold user content).
create table ai_requests (
    id                uuid primary key,
    user_id           uuid,
    job_id            uuid,
    use_case          text           not null,
    model             text           not null,
    prompt_version    text           not null,
    input_tokens      int,
    output_tokens     int,
    latency_ms        int,
    cost_estimate_usd numeric(10, 6),
    success           boolean        not null,
    error_code        text,
    created_at        timestamptz    not null default now(),
    constraint ai_requests_user_fk foreign key (user_id) references users (id) on delete cascade,
    constraint ai_requests_job_fk foreign key (job_id) references ai_jobs (id) on delete set null,
    constraint ai_requests_tokens_ck check (coalesce(input_tokens, 0) >= 0 and coalesce(output_tokens, 0) >= 0)
);

create index ai_requests_user_ix on ai_requests (user_id, created_at);
create index ai_requests_created_ix on ai_requests (created_at);

-- Shared across users: language data only, never user-specific content (schema.md Q5)
create table ai_cache (
    cache_key      text primary key,
    use_case       text        not null,
    language_code  text        not null,
    prompt_version text        not null,
    payload        jsonb       not null,
    created_at     timestamptz not null default now(),
    last_hit_at    timestamptz,
    hit_count      int         not null default 0,
    constraint ai_cache_language_fk foreign key (language_code) references languages (code)
);

-- Practice sessions (PRC-01, PRC-02) ------------------------------------------------------
create table practice_sessions (
    id               uuid primary key,
    user_id          uuid        not null,
    language_code    text        not null,
    mode             text        not null,
    study_session_id uuid,
    config           jsonb       not null,
    status           text        not null default 'IN_PROGRESS',
    started_at       timestamptz not null,
    completed_at     timestamptz,
    summary          jsonb,
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    constraint practice_sessions_user_language_fk foreign key (user_id, language_code)
        references user_languages (user_id, language_code) on delete cascade,
    constraint practice_sessions_study_session_fk foreign key (study_session_id)
        references study_sessions (id) on delete set null,
    constraint practice_sessions_mode_ck check (mode in ('CURRENT_LESSON', 'MIXED', 'FULL_REVISION', 'WEAK_SPOTS')),
    constraint practice_sessions_status_ck check (status in ('IN_PROGRESS', 'COMPLETED', 'ABANDONED')),
    constraint practice_sessions_completed_ck check ((status = 'COMPLETED') = (completed_at is not null))
);

create index practice_sessions_started_ix on practice_sessions (user_id, language_code, started_at desc);

-- Flashcards and spaced repetition (FC-01, SRS-01, SRS-02) --------------------------------
create table flashcards (
    id                 uuid primary key,
    user_id            uuid        not null,
    vocabulary_item_id uuid        not null,
    direction          text        not null,
    suspended          boolean     not null default false,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint flashcards_item_fk foreign key (vocabulary_item_id, user_id)
        references vocabulary_items (id, user_id) on delete cascade,
    constraint flashcards_direction_ck check (direction in ('TARGET_TO_NATIVE', 'NATIVE_TO_TARGET')),
    constraint flashcards_item_direction_uq unique (vocabulary_item_id, direction),
    constraint flashcards_id_user_uq unique (id, user_id)
);

create index flashcards_user_ix on flashcards (user_id);

create table srs_state (
    flashcard_id     uuid primary key,
    user_id          uuid          not null,
    state            text          not null default 'NEW',
    ease_factor      numeric(4, 2) not null default 2.50,
    interval_days    int           not null default 0,
    repetitions      int           not null default 0,
    lapses           int           not null default 0,
    due_at           timestamptz,
    last_reviewed_at timestamptz,
    version          bigint        not null default 0,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    constraint srs_state_flashcard_fk foreign key (flashcard_id, user_id)
        references flashcards (id, user_id) on delete cascade,
    constraint srs_state_state_ck check (state in ('NEW', 'LEARNING', 'REVIEW', 'RELEARNING')),
    constraint srs_state_ease_ck check (ease_factor >= 1.30),
    constraint srs_state_counts_ck check (interval_days >= 0 and repetitions >= 0 and lapses >= 0),
    constraint srs_state_due_ck check (state = 'NEW' or due_at is not null)
);

-- The review queue (DB-04): due cards per user
create index srs_state_due_ix on srs_state (user_id, due_at);
create index srs_state_state_ix on srs_state (user_id, state);

create table flashcard_reviews (
    id                  uuid primary key,
    flashcard_id        uuid          not null,
    user_id             uuid          not null,
    practice_session_id uuid,
    review_token        uuid          not null,
    grade               text          not null,
    reviewed_at         timestamptz   not null,
    duration_ms         int,
    interval_before     int           not null,
    interval_after      int           not null,
    ease_before         numeric(4, 2) not null,
    ease_after          numeric(4, 2) not null,
    constraint flashcard_reviews_flashcard_fk foreign key (flashcard_id, user_id)
        references flashcards (id, user_id) on delete cascade,
    constraint flashcard_reviews_practice_session_fk foreign key (practice_session_id)
        references practice_sessions (id) on delete set null,
    -- Idempotent submission (SRS-02): the same token for the same card is stored once
    constraint flashcard_reviews_token_uq unique (flashcard_id, review_token),
    constraint flashcard_reviews_grade_ck check (grade in ('AGAIN', 'HARD', 'GOOD', 'EASY')),
    constraint flashcard_reviews_duration_ck check (duration_ms is null or duration_ms >= 0)
);

create index flashcard_reviews_user_ix on flashcard_reviews (user_id, reviewed_at);
create index flashcard_reviews_card_ix on flashcard_reviews (flashcard_id, reviewed_at);

-- Exercises (EX-01 .. EX-07) --------------------------------------------------------------
create table exercise_sets (
    id                  uuid primary key,
    user_id             uuid        not null,
    language_code       text        not null,
    practice_session_id uuid,
    source              text        not null,
    request             jsonb       not null,
    status              text        not null default 'GENERATING',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint exercise_sets_user_language_fk foreign key (user_id, language_code)
        references user_languages (user_id, language_code) on delete cascade,
    constraint exercise_sets_practice_session_fk foreign key (practice_session_id)
        references practice_sessions (id) on delete set null,
    constraint exercise_sets_source_ck check (source in ('MANUAL', 'PRACTICE', 'WEAK_SPOTS')),
    constraint exercise_sets_status_ck check (status in ('GENERATING', 'READY', 'FAILED')),
    constraint exercise_sets_id_user_uq unique (id, user_id)
);

create index exercise_sets_created_ix on exercise_sets (user_id, language_code, created_at desc);

create table exercises (
    id              uuid primary key,
    exercise_set_id uuid        not null,
    user_id         uuid        not null,
    position        smallint    not null,
    type            text        not null,
    prompt          jsonb       not null,
    solution        jsonb       not null,
    difficulty      text,
    reported_at     timestamptz,
    report_reason   text,
    ai_request_id   uuid,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint exercises_set_fk foreign key (exercise_set_id, user_id)
        references exercise_sets (id, user_id) on delete cascade,
    constraint exercises_ai_request_fk foreign key (ai_request_id) references ai_requests (id) on delete set null,
    constraint exercises_position_uq unique (exercise_set_id, position),
    constraint exercises_position_ck check (position >= 0),
    constraint exercises_type_ck check (type in ('FILL_BLANK', 'MULTIPLE_CHOICE', 'TRANSLATION',
        'SENTENCE_CONSTRUCTION', 'ERROR_CORRECTION', 'WORD_ORDER', 'TRANSFORMATION')),
    constraint exercises_difficulty_ck check (difficulty in ('EASY', 'MEDIUM', 'HARD')),
    constraint exercises_id_user_uq unique (id, user_id)
);

create table exercise_grammar_topics (
    exercise_id      uuid not null,
    grammar_topic_id uuid not null,
    constraint exercise_grammar_topics_pk primary key (exercise_id, grammar_topic_id),
    constraint exercise_grammar_topics_exercise_fk foreign key (exercise_id)
        references exercises (id) on delete cascade,
    constraint exercise_grammar_topics_topic_fk foreign key (grammar_topic_id)
        references grammar_topics (id) on delete cascade
);

create index exercise_grammar_topics_topic_ix on exercise_grammar_topics (grammar_topic_id);

create table exercise_vocabulary (
    exercise_id        uuid not null,
    vocabulary_item_id uuid not null,
    constraint exercise_vocabulary_pk primary key (exercise_id, vocabulary_item_id),
    constraint exercise_vocabulary_exercise_fk foreign key (exercise_id)
        references exercises (id) on delete cascade,
    constraint exercise_vocabulary_item_fk foreign key (vocabulary_item_id)
        references vocabulary_items (id) on delete cascade
);

create index exercise_vocabulary_item_ix on exercise_vocabulary (vocabulary_item_id);

create table exercise_attempts (
    id                  uuid primary key,
    exercise_id         uuid          not null,
    user_id             uuid          not null,
    practice_session_id uuid,
    answer              jsonb         not null,
    correct             boolean       not null,
    score               numeric(4, 3) not null,
    evaluated_by        text          not null,
    feedback            jsonb,
    attempted_at        timestamptz   not null,
    duration_ms         int,
    constraint exercise_attempts_exercise_fk foreign key (exercise_id, user_id)
        references exercises (id, user_id) on delete cascade,
    constraint exercise_attempts_practice_session_fk foreign key (practice_session_id)
        references practice_sessions (id) on delete set null,
    constraint exercise_attempts_score_ck check (score between 0 and 1),
    constraint exercise_attempts_evaluated_by_ck check (evaluated_by in ('RULES', 'AI')),
    constraint exercise_attempts_duration_ck check (duration_ms is null or duration_ms >= 0),
    constraint exercise_attempts_id_user_uq unique (id, user_id)
);

create index exercise_attempts_user_ix on exercise_attempts (user_id, attempted_at);
create index exercise_attempts_exercise_ix on exercise_attempts (exercise_id);

create table mistakes (
    id                 uuid primary key,
    attempt_id         uuid        not null,
    user_id            uuid        not null,
    grammar_topic_id   uuid,
    vocabulary_item_id uuid,
    error_type         text        not null,
    excerpt            text,
    explanation        text,
    created_at         timestamptz not null default now(),
    constraint mistakes_attempt_fk foreign key (attempt_id, user_id)
        references exercise_attempts (id, user_id) on delete cascade,
    -- History survives deleting the topic or word (single-column FK so SET NULL keeps user_id)
    constraint mistakes_grammar_topic_fk foreign key (grammar_topic_id)
        references grammar_topics (id) on delete set null,
    constraint mistakes_vocabulary_item_fk foreign key (vocabulary_item_id)
        references vocabulary_items (id) on delete set null,
    constraint mistakes_error_type_format_ck check (error_type ~ '^[A-Z][A-Z_]*$')
);

-- Weak-point and frequent-mistake queries (DB-04, PRG-03, PRG-04)
create index mistakes_topic_ix on mistakes (user_id, grammar_topic_id);
create index mistakes_error_type_ix on mistakes (user_id, error_type, created_at);
create index mistakes_attempt_ix on mistakes (attempt_id);

-- Practice session items: needs flashcards and exercises -----------------------------------
create table practice_session_items (
    practice_session_id uuid        not null,
    position            smallint    not null,
    item_type           text        not null,
    flashcard_id        uuid,
    exercise_id         uuid,
    completed_at        timestamptz,
    constraint practice_session_items_pk primary key (practice_session_id, position),
    constraint practice_session_items_session_fk foreign key (practice_session_id)
        references practice_sessions (id) on delete cascade,
    constraint practice_session_items_flashcard_fk foreign key (flashcard_id)
        references flashcards (id) on delete cascade,
    constraint practice_session_items_exercise_fk foreign key (exercise_id)
        references exercises (id) on delete cascade,
    constraint practice_session_items_position_ck check (position >= 0),
    constraint practice_session_items_type_ck check (
        (item_type = 'FLASHCARD' and flashcard_id is not null and exercise_id is null)
        or (item_type = 'EXERCISE' and exercise_id is not null and flashcard_id is null))
);

-- Progress aggregates (PRG-01 .. PRG-03): derived from reviews and attempts, rebuildable ---
create table grammar_topic_progress (
    grammar_topic_id  uuid primary key,
    user_id           uuid          not null,
    attempts_total    int           not null default 0,
    correct_total     int           not null default 0,
    recent_accuracy   numeric(4, 3),
    practice_days     int           not null default 0,
    last_practiced_at timestamptz,
    weakness_score    numeric(6, 3) not null default 0,
    updated_at        timestamptz   not null default now(),
    constraint grammar_topic_progress_topic_fk foreign key (grammar_topic_id, user_id)
        references grammar_topics (id, user_id) on delete cascade,
    constraint grammar_topic_progress_counts_ck check (correct_total between 0 and attempts_total
        and practice_days >= 0),
    constraint grammar_topic_progress_accuracy_ck check (recent_accuracy between 0 and 1)
);

create index grammar_topic_progress_weakness_ix on grammar_topic_progress (user_id, weakness_score desc);

create table vocabulary_progress (
    vocabulary_item_id uuid primary key,
    user_id            uuid          not null,
    times_seen         int           not null default 0,
    times_correct      int           not null default 0,
    last_practiced_at  timestamptz,
    weakness_score     numeric(6, 3) not null default 0,
    updated_at         timestamptz   not null default now(),
    constraint vocabulary_progress_item_fk foreign key (vocabulary_item_id, user_id)
        references vocabulary_items (id, user_id) on delete cascade,
    constraint vocabulary_progress_counts_ck check (times_correct between 0 and times_seen)
);

create index vocabulary_progress_weakness_ix on vocabulary_progress (user_id, weakness_score desc);
