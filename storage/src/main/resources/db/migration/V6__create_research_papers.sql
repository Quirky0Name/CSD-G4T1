-- The researcher's own paper (their draft) for a project (docs/STORAGE-USER-RESEARCH-PAPER.md).
-- A project is one of the user's folders, or everything they keep outside folders (folder_id null).
-- One per project: a new upload replaces the file on the same row.
create table research_papers (
    id          uuid primary key,
    owner_id    uuid                     not null,
    -- User Management's folder; a bare reference, no FK across services. null = no folder
    folder_id   uuid,
    file_key    varchar(255)             not null,
    filename    text,
    uploaded_at timestamp with time zone not null,
    -- nulls not distinct, so each user has one "no folder" project too (Postgres 15+)
    constraint uq_research_papers_owner_folder unique nulls not distinct (owner_id, folder_id)
);
