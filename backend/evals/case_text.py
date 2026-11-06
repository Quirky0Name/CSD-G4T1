"""The text of a case's paper, draft and documents, exactly as their built PDFs show it, so the
quote check (score.py) and the judge (judge.py) read what the model saw.

A case gives each of these as a plain `*_text` field or as a rich `*_doc` field (see
docs/EVALUATION.md, "A case"); run.py builds the PDF from either. Standard library only: judge.py
imports this and needs neither rich nor research_evaluation."""

# the labels the rich PDF prints before the abstract and the references
ABSTRACT, REFERENCES = "Abstract", "References"


def doc_text(doc: dict) -> str:
    """Every word a rich document's PDF shows, in reading order: running head, banner,
    watermark, title, authors, abstract, each section's heading, text, table caption and cells,
    figure caption, then references. The running head and watermark repeat on every page of the
    PDF, but appear here once."""
    parts = [doc.get("running_head"), doc.get("banner"), doc.get("watermark"), doc["title"],
             doc.get("authors")]
    if doc.get("abstract"):
        parts += [ABSTRACT, doc["abstract"]]
    for section in doc["sections"]:
        parts += [section.get("heading"), section.get("text")]
        if table := section.get("table"):
            parts.append(table.get("caption"))
            parts += [" ".join(row) for row in table["rows"]]
        if figure := section.get("figure"):
            parts.append(figure.get("caption"))
    if doc.get("references"):
        parts += [REFERENCES, *doc["references"]]
    return "\n".join(p for p in parts if p)


def form(holder: dict, field: str) -> str | None:
    """Which form a case (or document) gives `field` in: "text" for `<field>_text`, "doc" for
    `<field>_doc`, None for neither. A field has one form, never two."""
    forms = [f for f in ("text", "doc") if holder.get(f"{field}_{f}")]
    if len(forms) > 1:
        raise ValueError(f"{field} is given as both " + " and ".join(f"{field}_{f}" for f in forms))
    return forms[0] if forms else None


def _text(holder: dict, field: str) -> str | None:
    """`<field>_text` as it is, or the text of `<field>_doc`; None when the holder has neither."""
    kind = form(holder, field)
    if kind == "text":
        return holder[f"{field}_text"]
    if kind == "doc":
        return doc_text(holder[f"{field}_doc"])
    return None


def paper_text(case: dict) -> str:
    """The stored paper's text (every case has one)."""
    text = _text(case, "paper")
    if text is None:
        raise KeyError("case has no paper_text or paper_doc")
    return text


def draft_text(case: dict) -> str | None:
    """The draft's text; None when the case has no draft."""
    return _text(case, "draft")


def pdf_text(document: dict) -> str | None:
    """The text of the PDF stored for a document; None when it has none."""
    return _text(document, "pdf")
