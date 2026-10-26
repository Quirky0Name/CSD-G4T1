-- A researcher's projects. Storage Management keeps only a bare project_id on papers and
-- drafts, with no FK across services; null there is the default project, which has no row here.
create table projects (
    id         uuid primary key,
    owner_id   uuid                     not null,
    name       text                     not null,
    created_at timestamp with time zone not null,
    constraint uq_projects_owner_name unique (owner_id, name)
);
