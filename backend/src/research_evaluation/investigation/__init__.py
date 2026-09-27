"""Investigation: fetching what a detected change actually is (its notice, the new version of
the paper, the paper's current copy) and storing it in a report in Storage Management
(docs/EVALUATION-INVESTIGATION.md). It fetches facts and never judges them; that's impact's job.

External calls go only to api.crossref.org and www.ebi.ac.uk, with fixed URLs whose only
input is a DOI, on their own HTTP client with no auth: never the Storage Management client,
which carries the service token."""
