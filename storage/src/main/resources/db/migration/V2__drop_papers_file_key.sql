-- uploaded PDFs are read by GROBID and then discarded, so there's no file to point at
alter table papers drop column file_key;
