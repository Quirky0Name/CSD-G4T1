-- A refresh token's SHA-256 hash, never the token. A token works once: refreshing marks it used
-- and issues a new one. Deleting an account deletes its tokens.
create table refresh_tokens (
    id         uuid primary key,
    user_id    uuid                     not null,
    token_hash varchar(64)              not null,
    expires_at timestamp with time zone not null,
    used_at    timestamp with time zone,
    constraint uq_refresh_tokens_hash unique (token_hash),
    constraint fk_refresh_tokens_user foreign key (user_id) references users (id) on delete cascade
);
