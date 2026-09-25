"""DOI normalisation, kept identical to Storage Management's
`MetadataClient.normalizeDoi` so both services store the same form
(see docs/CONTRACTS.md, "Snapshot fields")."""

import re
from typing import NewType

Doi = NewType("Doi", str)

_PREFIX = re.compile(r"^(https?://(dx\.)?doi\.org/|doi:)", re.IGNORECASE)
# Java's String.trim() strips every character up to and including the space
_JAVA_TRIM = "".join(chr(c) for c in range(0x21))


def normalize_doi(raw: str | None) -> Doi | None:
    if raw is None:
        return None
    doi = _PREFIX.sub("", raw.strip(_JAVA_TRIM), count=1)
    return Doi(doi.lower()) if doi else None
