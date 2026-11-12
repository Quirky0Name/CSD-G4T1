"""The claims an evaluation carries: every step's claims, in order, each passage's quote checked
in code against the text its source names (docs/EVALUATION.md, "Impact").

A source resolves to a text of the report: a report document's DOI to that `document`; the
paper's own DOI or "stored paper" to `paper`; "current copy" and "new version" to the first
document of that kind; "draft" to `draft`; "alert <id>" to that `alert`'s line as the prompt shows
it. A quote not in the text its source names, but in another of the report's texts, is re-targeted
to where it was found. Claims quote only source material, never the model's earlier answers.

Quotes and PDF text are never logged."""

import asyncio
from dataclasses import dataclass, field

from research_evaluation.impact.inputs import ReportInputs
from research_evaluation.impact.prompts import alerts_block, paper_block
from research_evaluation.impact.quotes import (
    ALERT,
    DOI,
    ELLIPSIS,
    PdfUnreadable,
    join_hyphens,
    leaves,
    norm,
    pdf_pages,
    quoted_in,
)
from research_evaluation.impact.schemas import (
    ChangeAssessment,
    Claim,
    ClaimKind,
    Evidence,
    ImpactAssessment,
    Passage,
    RecommendedActions,
    StoredClaim,
    Target,
    Verified,
)
from research_evaluation.storage import DocumentInReport


def _normal(text: str) -> str:
    return norm(join_hyphens(text))


@dataclass
class _Source:
    """One text of the report a quote can be found in."""

    target: Target
    document_id: int | None = None
    alert_id: int | None = None
    pdf: bytes | None = None
    # texts that count as text to check (a document's text, record and links; an alert's line)
    body: list[str] = field(default_factory=list)
    # texts searched but not counted as text to check (the paper's details)
    details: list[str] = field(default_factory=list)
    _pages: list[str] | None = None

    async def pages(self) -> list[str]:
        """The PDF's normalised page texts, read once ([] with no PDF or an unreadable one)."""
        if self._pages is None:
            self._pages = []
            if self.pdf is not None:
                try:
                    raw = await asyncio.to_thread(pdf_pages, self.pdf)
                except PdfUnreadable:
                    raw = []
                self._pages = [_normal(page) for page in raw]
        return self._pages

    async def has_text(self) -> bool:
        return any(await self.pages()) or any(self.body)

    async def find(self, quote: str) -> tuple[bool, int | None]:
        """Whether the normalised quote is here, and the 1-based PDF page it starts on."""
        page = await _page_of(quote, await self.pages())
        if page is not None:
            return True, page
        return any(quoted_in(quote, text) for text in self.body + self.details), None


async def _page_of(quote: str, pages: list[str]) -> int | None:
    """The page a quote starts on, searching the pages as one text (a quote may cross a page)."""
    joined, starts = "", []
    for number, text in enumerate(pages, start=1):
        if text:
            joined += " " if joined else ""
            starts.append((len(joined), number))
            joined += text
    if not starts or not quoted_in(quote, joined):
        return None
    first = next(piece.strip() for piece in ELLIPSIS.split(quote) if piece.strip())
    at = joined.find(first)
    return next(number for start, number in reversed(starts) if start <= at)


class _Sources:
    """The report's texts, in the order a quote is searched for when re-targeted."""

    def __init__(self, inputs: ReportInputs, draft: bytes | None):
        details = _normal(paper_block(inputs.paper))
        self.paper = _Source("paper", pdf=inputs.paper_pdf, details=[details])
        self.paper_doi = (inputs.paper.doi or "").lower()
        self.draft = _Source("draft", pdf=draft)
        pdfs = {document.id: content for document, content in inputs.document_pdfs}
        self.documents = [
            (document, self._document(document, pdfs.get(document.id)))
            for document in inputs.report.documents
        ]
        self.alerts = {
            alert.id: _Source(
                "alert",
                alert_id=alert.id,
                # the alert's own line, without alerts_block's header, as the prompt has it
                body=[_normal(alerts_block([alert]).split("\n", 1)[1])],
            )
            for alert in inputs.report.alerts
        }

    @staticmethod
    def _document(document: DocumentInReport, pdf: bytes | None) -> _Source:
        texts = [document.text or ""]
        texts += leaves(document.crossref_record) + leaves(document.links)
        return _Source(
            "document",
            document_id=document.id,
            pdf=pdf,
            body=[t for t in (_normal(text) for text in texts) if t],
        )

    def all(self) -> list[_Source]:
        return [self.paper, self.draft, *(s for _, s in self.documents), *self.alerts.values()]

    def named(self, label: str) -> _Source | None:
        """The text a passage's source names, or None when it names none of them."""
        label = norm(label)
        doi = DOI.search(label)
        if doi:
            doi_text = doi.group().rstrip(".,;)")
            match = next((s for d, s in self.documents if d.doi.lower() == doi_text), None)
            if match is not None:
                return match
            if doi_text == self.paper_doi:
                return self.paper
        alert = ALERT.search(label)
        if alert and int(alert.group(1)) in self.alerts:
            return self.alerts[int(alert.group(1))]
        if "stored paper" in label:
            return self.paper
        for name, kind in (("current copy", "current_version"), ("new version", "new_version")):
            if name in label:
                return next((s for d, s in self.documents if d.kind == kind), None)
        if "draft" in label:
            return self.draft
        return None


async def _passage(sources: _Sources, evidence: Evidence) -> Passage:
    quote = norm(evidence.quote)
    named = sources.named(evidence.source)
    if ELLIPSIS.sub("", quote).strip():  # an empty quote is found nowhere
        for source in [named] * (named is not None) + sources.all():
            found, page = await source.find(quote)
            if found:
                return _made(source, evidence.quote, page, "verbatim")
    if named is None:
        return Passage(
            target=None,
            document_id=None,
            alert_id=None,
            quote=evidence.quote,
            page=None,
            verified="not_found",
        )
    verified = "not_found" if await named.has_text() else "no_text"
    return _made(named, evidence.quote, None, verified)


def _made(source: _Source, quote: str, page: int | None, verified: Verified) -> Passage:
    return Passage(
        target=source.target,
        document_id=source.document_id,
        alert_id=source.alert_id,
        quote=quote,
        page=page,
        verified=verified,
    )


async def build_claims(
    inputs: ReportInputs,
    draft: bytes | None,
    change: ChangeAssessment | None,
    impact: ImpactAssessment | None,
    actions: RecommendedActions | None,
) -> list[StoredClaim]:
    """Every step's claims in order (`<kind>-<index>`), from the steps that ran. A claim is never
    dropped: `supported` is false when none of its passages was found verbatim."""
    sources = _Sources(inputs, draft)
    steps: list[tuple[ClaimKind, list[Claim]]] = [
        ("change", change.claims if change is not None else []),
        ("impact", impact.claims if impact is not None else []),
        ("action", actions.claims if actions is not None else []),
    ]
    out: list[StoredClaim] = []
    for kind, claims in steps:
        for index, claim in enumerate(claims):
            passages = [await _passage(sources, evidence) for evidence in claim.passages]
            out.append(
                StoredClaim(
                    id=f"{kind}-{index}",
                    kind=kind,
                    text=claim.text,
                    supported=any(p.verified == "verbatim" for p in passages),
                    passages=passages,
                )
            )
    return out
