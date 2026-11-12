-- feedback chunks replace profile chunks (docs/STORAGE.md, "Chunks"). A feedback chunk is one per
-- report, keyed by report_id like a report's own chunks, so every chunk now has a paper: profile rows
-- (never written) go, and with them user_id and its index.
-- db/vendor/h2 holds the same change for the tests: change both.
delete from chunks where source_type = 'profile';
alter table chunks drop constraint ck_chunks_source;
alter table chunks drop constraint ck_chunks_paper;
alter table chunks drop constraint uq_chunks_source_index;
drop index idx_chunks_user;
alter table chunks drop column user_id;
alter table chunks add constraint ck_chunks_source check (
    (source_type = 'alert' and alert_id is not null and report_id is null and document_id is null)
    or (source_type = 'report' and report_id is not null and alert_id is null and document_id is null)
    or (source_type = 'document' and document_id is not null and alert_id is null and report_id is null)
    or (source_type = 'feedback' and report_id is not null and alert_id is null and document_id is null));
alter table chunks add constraint ck_chunks_paper check (paper_id is not null);
-- only one of the three ids is ever set, so nulls must count as equal here; a report's report and
-- feedback chunks share report_id and differ by source_type
alter table chunks add constraint uq_chunks_source_index
    unique nulls not distinct (source_type, alert_id, report_id, document_id, chunk_index);
