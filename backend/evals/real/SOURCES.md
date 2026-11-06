# Real papers for the evals

This folder holds real, published open-access papers that eval cases attach with `paper_file` or
`pdf_file`. Every file here is CC BY 4.0, for the paper and for its notice, per Crossref's
`license` field (checked 2026-11-06); papers under any other licence are never committed.

Each `<stem>.txt` is made from `<stem>.pdf` by `extract.py` (`uv run --with pypdf python
evals/real/extract.py` from `backend/`). The `.txt` is what the quote check (`score.py`) and the
judge (`judge.py`) read; the PDF is what `run.py` attaches.

## Papers

| File | DOI | Title | Fetched from | Date fetched | Licence | sha256 |
|---|---|---|---|---|---|---|
| `pone.0258361.pdf` | [10.1371/journal.pone.0258361](https://doi.org/10.1371/journal.pone.0258361) | Detecting phishing websites using machine learning technique | https://journals.plos.org/plosone/article/file?id=10.1371/journal.pone.0258361&type=printable | 2026-11-06 | http://creativecommons.org/licenses/by/4.0/ | `6288b1b11ed72e3a5429b23a7b3d8650d4aa7d4b662ed47cabf23e498a758458` |
| `pone.0035065.pdf` | [10.1371/journal.pone.0035065](https://doi.org/10.1371/journal.pone.0035065) | Over-Expression of LSD1 Promotes Proliferation, Migration and Invasion in Non-Small Cell Lung Cancer | https://journals.plos.org/plosone/article/file?id=10.1371/journal.pone.0035065&type=printable | 2026-11-06 | http://creativecommons.org/licenses/by/4.0/ | `72e4dad68e0e30b981733dba0c3066688652d66dcabb5e27e048ecdba3990403` |
| `pone.0309025.pdf` | [10.1371/journal.pone.0309025](https://doi.org/10.1371/journal.pone.0309025) | LightGBM hybrid model based DEM correction for forested areas | https://journals.plos.org/plosone/article/file?id=10.1371/journal.pone.0309025&type=printable | 2026-11-06 | http://creativecommons.org/licenses/by/4.0/ | `80e4afdcd737a3fc3f3da873a95625b20b2b26d249cf00f9146ecfb194f298ff` |
| `f1000research.187739.1.pdf` | [10.12688/f1000research.187739.1](https://doi.org/10.12688/f1000research.187739.1) | Reassessing the Education–FDI–Trade Nexus for a Sustainable Future: Evidence from Sub-Saharan Africa (version 1) | https://f1000research.com/articles/15-1392/v1/pdf | 2026-11-06 | https://creativecommons.org/licenses/by/4.0/ | `0a3bbbd9517465f52df8a2b1478ceccece00edf615a0a0f8b3a65cb91154cb68` |
| `f1000research.187739.2.pdf` | [10.12688/f1000research.187739.2](https://doi.org/10.12688/f1000research.187739.2) | Reassessing the Education–FDI–Trade Nexus for a Sustainable Future: Evidence from Sub-Saharan Africa (version 2) | https://f1000research.com/articles/15-1392/v2/pdf | 2026-11-06 | https://creativecommons.org/licenses/by/4.0/ | `5ab13e34d016b76d792efa0e4685d7f2c70f03412670c35ff87557b78e25f0fb` |

## Notices

The notices aren't committed here: each case carries its notice's plain text (title and body from
Europe PMC's `fullTextXML`, as investigation stores it). Fetched 2026-11-06.

- Retraction of `pone.0258361`: [10.1371/journal.pone.0322065](https://doi.org/10.1371/journal.pone.0322065), PMC12017829, https://www.ebi.ac.uk/europepmc/webservices/rest/PMC12017829/fullTextXML, http://creativecommons.org/licenses/by/4.0/
- Expression of concern on `pone.0035065`: [10.1371/journal.pone.0298436](https://doi.org/10.1371/journal.pone.0298436), PMC10836678, https://www.ebi.ac.uk/europepmc/webservices/rest/PMC10836678/fullTextXML, http://creativecommons.org/licenses/by/4.0/
- Correction of `pone.0309025`: [10.1371/journal.pone.0320535](https://doi.org/10.1371/journal.pone.0320535), PMC11902294, https://www.ebi.ac.uk/europepmc/webservices/rest/PMC11902294/fullTextXML, http://creativecommons.org/licenses/by/4.0/
- The F1000Research new version has no separate notice: version 2 is the "after".

## Dropped

- C1 admin correction, Scientific Reports [10.1038/s41598-024-79772-7](https://doi.org/10.1038/s41598-024-79772-7) (notice [10.1038/s41598-025-93099-x](https://doi.org/10.1038/s41598-025-93099-x)): paper and notice are CC BY-NC-ND 4.0, not CC BY.
- E4 concern lifted, RSC Advances [10.1039/c3ra45263h](https://doi.org/10.1039/c3ra45263h): no licence in Crossref, no Europe PMC record, and the publisher page returns `403`.
