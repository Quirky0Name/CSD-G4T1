create table papers (
    id               uuid primary key,
    owner_id         uuid         not null,
    -- User Management's folder; a bare reference, no FK across services
    folder_id        uuid,
    doi              varchar(255),
    openalex_id      varchar(64),
    title            text,
    journal          text,
    issn             varchar(16),
    publication_year integer,
    file_key         varchar(255),
    created_at       timestamp with time zone not null,
    -- one user tracking the same DOI twice is a mistake; nulls (no DOI found) don't collide
    constraint uq_papers_owner_doi unique (owner_id, doi)
);

create index idx_papers_owner on papers (owner_id);
