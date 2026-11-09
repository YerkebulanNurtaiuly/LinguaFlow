-- V4: single-use tokens for email verification and password reset (AUTH-07).
-- Design: docs/database/schema.md section 3.1. Only SHA-256 hashes are stored.

create table user_action_tokens (
    id         uuid primary key,
    user_id    uuid        not null,
    purpose    text        not null,
    token_hash text        not null,
    expires_at timestamptz not null,
    used_at    timestamptz,
    created_at timestamptz not null default now(),
    constraint user_action_tokens_user_fk foreign key (user_id) references users (id) on delete cascade,
    constraint user_action_tokens_token_hash_uq unique (token_hash),
    constraint user_action_tokens_purpose_ck check (purpose in ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    constraint user_action_tokens_expiry_ck check (expires_at > created_at)
);

create index user_action_tokens_user_ix on user_action_tokens (user_id, purpose);
