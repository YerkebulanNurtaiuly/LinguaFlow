-- PRG-01: progress aggregates. Everything here is derived from flashcard_reviews,
-- exercise_attempts and mistakes, and can be rebuilt from them.

-- Per-word mastery inputs: the share of recent answers that were right, and how far the
-- word's cards are scheduled (PRG-02 counts a word mastered from its interval)
alter table vocabulary_progress
    add column recent_accuracy numeric(4, 3),
    add column interval_days   int not null default 0,
    add constraint vocabulary_progress_accuracy_ck check (recent_accuracy between 0 and 1),
    add constraint vocabulary_progress_interval_ck check (interval_days >= 0);

-- Mistake counters by error type, per learning language (PRG-04, PRG-06)
create table mistake_counts (
    user_id       uuid        not null,
    language_code text        not null,
    error_type    text        not null,
    mistakes      int         not null,
    last_seen_at  timestamptz not null,
    updated_at    timestamptz not null default now(),
    constraint mistake_counts_pk primary key (user_id, language_code, error_type),
    constraint mistake_counts_user_language_fk foreign key (user_id, language_code)
        references user_languages (user_id, language_code) on delete cascade,
    constraint mistake_counts_mistakes_ck check (mistakes > 0),
    constraint mistake_counts_error_type_format_ck check (error_type ~ '^[A-Z][A-Z_]*$')
);
