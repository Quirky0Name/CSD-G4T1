-- What impact writes into a report besides evaluation and recommendation (V7), all null until the
-- report is assessed (docs/EVALUATION-IMPACT.md)
alter table reports add column change_summary text;
-- none / low / medium / high: how serious the change is for anyone relying on the paper; none = not meaningful
alter table reports add column change_severity varchar(16);
-- none / low / medium / high: how much the researcher's draft is affected; null when the change isn't meaningful
alter table reports add column impact_level varchar(16);
-- impact's full answer as JSON, kept exactly as Research Evaluation sent it; storage never looks inside
alter table reports add column assessment text;
