-- FC-01: which flashcard directions a learner reviews in a language. Cards exist for both
-- directions; this setting only decides which ones the review queue (SRS-02) offers, so
-- switching it never loses review history.
alter table user_languages
    add column card_directions text[] not null default '{TARGET_TO_NATIVE,NATIVE_TO_TARGET}',
    add constraint user_languages_card_directions_ck check (
        cardinality(card_directions) between 1 and 2
        and card_directions <@ array ['TARGET_TO_NATIVE', 'NATIVE_TO_TARGET']::text[]);
