-- SRS-03: the card's SRS state when it was graded. Retention counts only reviews of cards in
-- REVIEW (not learning or relearning steps), which interval_before alone can't tell apart.
alter table flashcard_reviews add column state_before text;

-- Reviews from SRS-02: learning steps keep interval 0, everything else had an interval
update flashcard_reviews
set state_before = case when interval_before = 0 then 'LEARNING' else 'REVIEW' end;

alter table flashcard_reviews
    alter column state_before set not null,
    add constraint flashcard_reviews_state_before_ck
        check (state_before in ('NEW', 'LEARNING', 'REVIEW', 'RELEARNING'));
