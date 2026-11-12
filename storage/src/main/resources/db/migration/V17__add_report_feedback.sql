-- The researcher's feedback on an assessed report (docs/STORAGE.md): how serious the report as a whole
-- made the change out to be, an optional note, and when it was last saved. All three null when there is
-- none; otherwise verdict and time are both set. Saving again replaces them, deleting nulls them.
alter table reports add column feedback_verdict varchar(16);
alter table reports add column feedback_note varchar(500);
alter table reports add column feedback_updated_at timestamp with time zone;
alter table reports add constraint ck_reports_feedback_verdict
    check (feedback_verdict is null or feedback_verdict in ('fair', 'overstated', 'understated'));
alter table reports add constraint ck_reports_feedback
    check ((feedback_verdict is null) = (feedback_updated_at is null)
        and (feedback_note is null or feedback_verdict is not null));
