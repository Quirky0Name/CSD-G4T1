-- One row per account. Its id is the sub of every JWT the services check, so it's a uuid.
create table users (
    id         uuid primary key,
    email      varchar(320)             not null,
    created_at timestamp with time zone not null,
    constraint uq_users_email unique (email)
);
