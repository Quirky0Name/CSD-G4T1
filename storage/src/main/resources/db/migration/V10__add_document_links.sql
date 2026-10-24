-- the DOIs and URLs a document points at, as JSON exactly as Research Evaluation sent them; storage
-- never looks inside. Null for documents stored before investigation sent links
alter table report_documents add column links text;
