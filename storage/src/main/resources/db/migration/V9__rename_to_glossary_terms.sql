-- Renames to the terms in GLOSSARY.md: folder -> project, research paper -> draft,
-- background metadata -> snapshot, an alert's severity -> alert severity. Data is kept.

-- User Management's project; still a bare reference, no FK across services. null = the default project
alter table papers rename column folder_id to project_id;

alter table research_papers rename to drafts;
alter table drafts rename column folder_id to project_id;
alter table drafts rename constraint uq_research_papers_owner_folder to uq_drafts_owner_project;

alter table background_metadata rename to snapshots;
alter index idx_background_metadata_paper rename to idx_snapshots_paper;

alter table alerts rename column severity to alert_severity;
