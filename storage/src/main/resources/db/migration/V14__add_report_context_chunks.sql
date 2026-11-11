-- the past chunks impact added to the report's prompt (source_type, source_id and page of each), as a
-- JSON array exactly as Research Evaluation sent it, so a frontend can show them; storage never looks
-- inside. Null when none was sent, and for reports assessed before V14
alter table reports add column context_chunks text;
