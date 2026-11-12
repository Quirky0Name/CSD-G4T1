-- impact's claims as JSON, kept exactly as Research Evaluation sent it (docs/CONTRACTS.md, "Claim"); a
-- column of its own so storage never has to look inside assessment. Null on reports assessed before
-- claims existed: they aren't backfilled
alter table reports add column claims text;
