"""The text of a case's paper, draft and documents, exactly as their built PDFs show it, so the
quote check (score.py) and the judge (judge.py) read what the model saw.

A case gives each of these as a plain `*_text` field, a rich `*_doc` field, or (paper and
documents) a `*_file`: a real PDF in evals/real/ (see docs/EVALUATION.md, "A case"). run.py builds
the PDF from the first two and attaches a real one as it is; a real PDF's text is the committed
<same stem>.txt beside it, made by evals/real/extract.py. Standard library only: judge.py
imports this and needs neither rich nor research_evaluation."""

import re
from pathlib import Path

# where the real PDFs (`*_file`) and their extracted .txt texts are
REAL = Path(__file__).parent / "real"

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
    `<field>_doc`, "file" for `<field>_file`, None for none. A field has one form, never two."""
    forms = [f for f in ("text", "doc", "file") if holder.get(f"{field}_{f}")]
    if len(forms) > 1:
        raise ValueError(f"{field} is given as both " + " and ".join(f"{field}_{f}" for f in forms))
    return forms[0] if forms else None


def real_pdf(name: str) -> Path:
    """A real PDF named by a `*_file` field."""
    return REAL / name


def _text(holder: dict, field: str) -> str | None:
    """`<field>_text` as it is, the text of `<field>_doc`, or the extracted .txt of
    `<field>_file`; None when the holder has none of them."""
    kind = form(holder, field)
    if kind == "text":
        return holder[f"{field}_text"]
    if kind == "doc":
        return doc_text(holder[f"{field}_doc"])
    if kind == "file":
        return real_pdf(holder[f"{field}_file"]).with_suffix(".txt").read_text(encoding="utf-8")
    return None


def paper_text(case: dict) -> str:
    """The stored paper's text (every case has one)."""
    text = _text(case, "paper")
    if text is None:
        raise KeyError("case has no paper_text, paper_doc or paper_file")
    return text


def draft_text(case: dict) -> str | None:
    """The draft's text; None when the case has no draft."""
    return _text(case, "draft")


def pdf_text(document: dict) -> str | None:
    """The text of the PDF stored for a document; None when it has none."""
    return _text(document, "pdf")


# a heading line in a `*_text` field: a numbered one ("4. Discussion", "1.1 Scope") or a plain name
_HEADING_NUMBER = re.compile(r"^\d+(\.\d+)*\.?\s+\S")
_HEADING_NAMES = {"abstract", "introduction", "background", "methods", "results", "discussion",
                  "conclusion", "conclusions", "references"}


def _is_heading(line: str) -> bool:
    line = line.strip()
    return bool(line) and len(line) <= 80 and not line.endswith((".", ":")) and (
        bool(_HEADING_NUMBER.match(line)) or line.lower() in _HEADING_NAMES
    )


def _sections(holder: dict, field: str) -> list[tuple[str | None, str]]:
    """`(heading, text)` for each part of a `*_text` or `*_doc` field, in order; the part before
    the first heading has none. A `*_file` (a real PDF) has no reliable headings: none."""
    kind = form(holder, field)
    if kind == "doc":
        doc = holder[f"{field}_doc"]
        parts = [(ABSTRACT, doc["abstract"])] if doc.get("abstract") else []
        for section in doc["sections"]:
            body = [section.get("text")]
            if table := section.get("table"):
                body += [table.get("caption"), *(" ".join(row) for row in table["rows"])]
            if figure := section.get("figure"):
                body.append(figure.get("caption"))
            parts.append((section.get("heading"), "\n".join(b for b in body if b)))
        if doc.get("references"):
            parts.append((REFERENCES, "\n".join(doc["references"])))
        return parts
    if kind == "text":
        parts: list[tuple[str | None, list[str]]] = [(None, [])]
        for line in holder[f"{field}_text"].split("\n"):
            if _is_heading(line):
                parts.append((line.strip(), []))
            else:
                parts[-1][1].append(line)
        return [(h, "\n".join(lines)) for h, lines in parts]
    return []


def _flat(text: str) -> str:
    return " ".join(text.split()).casefold()


def section_of(case: dict, target: str | None, quote: str) -> str | None:
    """The heading of the part of the draft (`target` "draft") or stored paper ("paper") that
    `quote` sits in, when the case gives that text with headings; else None."""
    if target not in ("draft", "paper"):
        return None
    wanted = _flat(quote)
    for heading, text in _sections(case, target):
        if heading and wanted and wanted in _flat(text):
            return heading
    return None
