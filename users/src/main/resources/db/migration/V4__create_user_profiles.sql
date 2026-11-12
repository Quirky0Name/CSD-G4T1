-- One researcher's profile: their answers to the sign-up questionnaire. No row means no profile, as
-- for accounts made before this migration and the dev mode admin. Every answer is nullable,
-- because the profile page can clear any of them.
create table user_profiles (
    user_id              uuid primary key,
    occupation           text,
    occupation_other     text,
    -- JSON arrays of strings, as text, so the same migration runs on Postgres and on the tests' H2
    research_fields      text,
    research_interests   text,
    work_types           text,
    response_preferences text,
    constraint fk_user_profiles_user foreign key (user_id) references users (id) on delete cascade
);
