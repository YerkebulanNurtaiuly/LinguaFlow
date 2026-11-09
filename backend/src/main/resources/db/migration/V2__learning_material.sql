-- V2: learning material (DB-03). Design: docs/database/schema.md, sections 3.2 and 5.
-- Every top-level table is scoped by (user_id, language_code) -> user_languages.

-- Trigram search for vocabulary (VOC-02 ILIKE '%...%'). Needs CREATE privilege on the
-- database; on managed PostgreSQL the extension may have to be enabled by an admin first.
create extension if not exists pg_trgm;

-- Vocabulary ------------------------------------------------------------------------------
create table vocabulary_items (
    id                  uuid primary key,
    user_id             uuid        not null,
    language_code       text        not null,
    lemma               text        not null,
    normalized_lemma    text        not null,
    part_of_speech      text,
    translation         text,
    gender              text,
    plural              text,
    extra               jsonb       not null default '{}',
    cefr_level          text,
    status              text        not null default 'LEARNING',
    ai_status           text        not null default 'PENDING_AI',
    ai_generated_fields text[]      not null default '{}',
    notes               text,
    version             bigint      not null default 0,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint vocabulary_items_user_language_fk foreign key (user_id, language_code)
        references user_languages (user_id, language_code) on delete cascade,
    constraint vocabulary_items_lemma_length_ck check (char_length(lemma) between 1 and 200),
    constraint vocabulary_items_normalized_lemma_ck check (char_length(normalized_lemma) between 1 and 200),
    constraint vocabulary_items_part_of_speech_ck check (part_of_speech in
        ('NOUN', 'VERB', 'ADJECTIVE', 'ADVERB', 'PRONOUN', 'PREPOSITION', 'CONJUNCTION', 'ARTICLE',
         'NUMERAL', 'INTERJECTION', 'PHRASE', 'OTHER')),
    constraint vocabulary_items_gender_ck check (gender in ('MASCULINE', 'FEMININE', 'NEUTER')),
    constraint vocabulary_items_cefr_level_ck check (cefr_level in ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
    constraint vocabulary_items_status_ck check (status in ('NEW', 'LEARNING', 'LEARNED', 'MASTERED')),
    constraint vocabulary_items_ai_status_ck check (ai_status in ('PENDING_AI', 'READY', 'AI_FAILED')),
    constraint vocabulary_items_extra_object_ck check (jsonb_typeof(extra) = 'object'),
    -- Duplicate prevention (VOC-02 -> 409). NULLS NOT DISTINCT: while part_of_speech is still
    -- unknown, a second identical lemma is rejected too (schema.md Q2).
    constraint vocabulary_items_lemma_uq unique nulls not distinct
        (user_id, language_code, normalized_lemma, part_of_speech)
);

create index vocabulary_items_created_ix on vocabulary_items (user_id, language_code, created_at desc);
create index vocabulary_items_status_ix on vocabulary_items (user_id, language_code, status);
create index vocabulary_items_ai_status_ix on vocabulary_items (user_id, language_code, ai_status);
create index vocabulary_items_lemma_trgm_ix on vocabulary_items using gin (normalized_lemma gin_trgm_ops);
create index vocabulary_items_translation_trgm_ix on vocabulary_items using gin (lower(translation) gin_trgm_ops);

create table vocabulary_examples (
    id                 uuid primary key,
    vocabulary_item_id uuid        not null,
    sentence           text        not null,
    translation        text,
    position           smallint    not null,
    ai_generated       boolean     not null,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    constraint vocabulary_examples_item_fk foreign key (vocabulary_item_id)
        references vocabulary_items (id) on delete cascade,
    constraint vocabulary_examples_position_uq unique (vocabulary_item_id, position),
    constraint vocabulary_examples_position_ck check (position >= 0),
    constraint vocabulary_examples_sentence_length_ck check (char_length(sentence) between 1 and 1000)
);

-- Tags (VOC-03) ---------------------------------------------------------------------------
create table tags (
    id            uuid primary key,
    user_id       uuid        not null,
    language_code text        not null,
    name          text        not null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint tags_user_language_fk foreign key (user_id, language_code)
        references user_languages (user_id, language_code) on delete cascade,
    constraint tags_name_length_ck check (char_length(name) between 1 and 50)
);

create unique index tags_name_uq on tags (user_id, language_code, lower(name));

create table vocabulary_item_tags (
    vocabulary_item_id uuid not null,
    tag_id             uuid not null,
    constraint vocabulary_item_tags_pk primary key (vocabulary_item_id, tag_id),
    constraint vocabulary_item_tags_item_fk foreign key (vocabulary_item_id)
        references vocabulary_items (id) on delete cascade,
    constraint vocabulary_item_tags_tag_fk foreign key (tag_id) references tags (id) on delete cascade
);

create index vocabulary_item_tags_tag_ix on vocabulary_item_tags (tag_id);

-- Grammar ---------------------------------------------------------------------------------
-- Global catalog of suggested topics (GRM-03); seeded in a later migration, not user-owned
create table grammar_topic_catalog (
    id            uuid primary key,
    language_code text not null,
    slug          text not null,
    title         text not null,
    description   text not null,
    cefr_level    text not null,
    constraint grammar_topic_catalog_language_fk foreign key (language_code) references languages (code),
    constraint grammar_topic_catalog_slug_uq unique (language_code, slug),
    constraint grammar_topic_catalog_slug_format_ck check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    constraint grammar_topic_catalog_cefr_level_ck check (cefr_level in ('A1', 'A2', 'B1', 'B2', 'C1', 'C2'))
);

create index grammar_topic_catalog_level_ix on grammar_topic_catalog (language_code, cefr_level);

create table grammar_topics (
    id                          uuid primary key,
    user_id                     uuid        not null,
    language_code               text        not null,
    catalog_topic_id            uuid,
    title                       text        not null,
    normalized_title            text        not null,
    description                 text,
    personal_notes              text,
    ai_explanation              jsonb,
    ai_explanation_generated_at timestamptz,
    cefr_level                  text,
    status                      text        not null default 'NEW',
    version                     bigint      not null default 0,
    created_at                  timestamptz not null default now(),
    updated_at                  timestamptz not null default now(),
    constraint grammar_topics_user_language_fk foreign key (user_id, language_code)
        references user_languages (user_id, language_code) on delete cascade,
    constraint grammar_topics_catalog_fk foreign key (catalog_topic_id)
        references grammar_topic_catalog (id) on delete set null,
    constraint grammar_topics_title_length_ck check (char_length(title) between 1 and 200),
    constraint grammar_topics_cefr_level_ck check (cefr_level in ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
    constraint grammar_topics_status_ck check (status in ('NEW', 'LEARNING', 'LEARNED', 'MASTERED')),
    constraint grammar_topics_title_uq unique (user_id, language_code, normalized_title)
);

create index grammar_topics_status_ix on grammar_topics (user_id, language_code, status);
create index grammar_topics_level_ix on grammar_topics (user_id, language_code, cefr_level);

create table grammar_topic_status_history (
    id               uuid primary key,
    grammar_topic_id uuid        not null,
    from_status      text,
    to_status        text        not null,
    reason           text        not null,
    changed_at       timestamptz not null,
    constraint grammar_topic_status_history_topic_fk foreign key (grammar_topic_id)
        references grammar_topics (id) on delete cascade,
    constraint grammar_topic_status_history_from_ck check (from_status in ('NEW', 'LEARNING', 'LEARNED', 'MASTERED')),
    constraint grammar_topic_status_history_to_ck check (to_status in ('NEW', 'LEARNING', 'LEARNED', 'MASTERED')),
    constraint grammar_topic_status_history_reason_ck check (reason in
        ('CREATED', 'MANUAL', 'MASTERY_RULE', 'DEMOTION_RULE'))
);

create index grammar_topic_status_history_topic_ix on grammar_topic_status_history (grammar_topic_id, changed_at);

-- Study sessions (SES-01) -----------------------------------------------------------------
create table study_sessions (
    id            uuid primary key,
    user_id       uuid        not null,
    language_code text        not null,
    title         text        not null,
    studied_on    date        not null,
    notes         text,
    active        boolean     not null default false,
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint study_sessions_user_language_fk foreign key (user_id, language_code)
        references user_languages (user_id, language_code) on delete cascade,
    constraint study_sessions_title_length_ck check (char_length(title) between 1 and 200)
);

create index study_sessions_timeline_ix on study_sessions (user_id, language_code, studied_on desc);
-- New material is attached to the active session; only one per user and language
create unique index study_sessions_one_active_uq on study_sessions (user_id, language_code) where active;

create table session_vocabulary (
    study_session_id   uuid        not null,
    vocabulary_item_id uuid        not null,
    added_at           timestamptz not null default now(),
    constraint session_vocabulary_pk primary key (study_session_id, vocabulary_item_id),
    constraint session_vocabulary_session_fk foreign key (study_session_id)
        references study_sessions (id) on delete cascade,
    constraint session_vocabulary_item_fk foreign key (vocabulary_item_id)
        references vocabulary_items (id) on delete cascade
);

create index session_vocabulary_item_ix on session_vocabulary (vocabulary_item_id);

create table session_grammar (
    study_session_id uuid        not null,
    grammar_topic_id uuid        not null,
    added_at         timestamptz not null default now(),
    constraint session_grammar_pk primary key (study_session_id, grammar_topic_id),
    constraint session_grammar_session_fk foreign key (study_session_id)
        references study_sessions (id) on delete cascade,
    constraint session_grammar_topic_fk foreign key (grammar_topic_id)
        references grammar_topics (id) on delete cascade
);

create index session_grammar_topic_ix on session_grammar (grammar_topic_id);
