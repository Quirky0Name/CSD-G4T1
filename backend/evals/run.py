"""Eval runner: feeds each case in evals/cases through the real 3-call assess() and saves the
raw Evaluation JSON. Scoring happens later, on the saved files, so re-scoring costs no quota.

    python evals/run.py 01_correction_effect_size --runs 1   # case file stem
    python evals/run.py --runs 3            # every case

Reads GEMINI_API_KEY (and optionally GEMINI_MODEL, GEMINI_FALLBACK_MODELS) from the environment
or backend/.env. Needs fpdf2 to turn the cases' texts into PDFs (not a project dependency)."""

import argparse
import asyncio
import hashlib
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import httpx
from fpdf import FPDF
from google import genai
from google.genai import types

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT.parent / "src"))

import case_text

from research_evaluation.impact.assess import assess
from research_evaluation.impact.inputs import ReportInputs
from research_evaluation.impact.llm import FallbackLlm
from research_evaluation.impact.prompts import PROMPT_VERSION
from research_evaluation.storage import (
    AlertInReport,
    DocumentInReport,
    PaperDetails,
    Report,
)

PAPER_ID = UUID("00000000-0000-0000-0000-000000000001")


def load_env() -> None:
    env = ROOT.parent / ".env"
    if not env.exists():
        return
    for line in env.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() in ("GEMINI_API_KEY", "GEMINI_MODEL", "GEMINI_FALLBACK_MODELS"):
            os.environ.setdefault(key.strip(), value.strip().strip("\"'"))

FONTS = ROOT / "fonts"
FONT = "DejaVu"  # covers non-Latin-1 text (·, –, ≥, α, curly quotes), unlike the core fonts

# rich PDF layout on A4, in mm
MARGIN, GAP, CONTENT_TOP, BOTTOM = 18, 6, 20, 18
BODY, LINE = 9.5, 4.6


def new_pdf(cls: type[FPDF] = FPDF, *args) -> FPDF:
    pdf = cls(*args)
    pdf.add_font(FONT, "", FONTS / "DejaVuSans.ttf")
    pdf.add_font(FONT, "B", FONTS / "DejaVuSans-Bold.ttf")
    return pdf


## text to pdf
def make_pdf(text: str) -> bytes:
    pdf = new_pdf()
    pdf.add_page()
    pdf.set_font(FONT, size=11)
    for paragraph in text.split("\n"):
        pdf.multi_cell(0, 6, paragraph or " ", new_x="LMARGIN", new_y="NEXT")
    return bytes(pdf.output())


class DocPdf(FPDF):
    """A rich document (a case's `*_doc` field) as a journal-like PDF. On every page: the running
    head, and the watermark in light grey, diagonal, drawn first so it sits behind the text. In
    two columns, the title block spans the page and the sections flow down the left column, then
    the right, then onto the next page."""

    def __init__(self, doc: dict):
        super().__init__(format="A4")
        self.doc = doc
        self.two_columns = doc.get("layout") == "two_column"
        self.column = 0
        self.column_top = CONTENT_TOP
        self.set_margins(MARGIN, CONTENT_TOP, MARGIN)
        self.set_auto_page_break(True, BOTTOM)

    def header(self) -> None:
        x, y = self.x, self.y
        if watermark := self.doc.get("watermark"):
            self.set_font(FONT, "B", 64)
            self.set_text_color(222, 222, 222)
            cx, cy = self.w / 2, self.h / 2
            with self.rotation(45, x=cx, y=cy):
                self.text(cx - self.get_string_width(watermark) / 2, cy + 8, watermark)
        if head := self.doc.get("running_head"):
            self.set_font(FONT, size=7.5)
            self.set_text_color(90, 90, 90)
            self.set_xy(MARGIN, 9)
            self.cell(self.w - 2 * MARGIN, 4, head, align="C")
        self.set_text_color(0, 0, 0)
        self.set_xy(x, y)

    @property
    def accept_page_break(self) -> bool:
        """In two columns, a full left column moves on to the right one instead of a new page."""
        if self.two_columns and self.column == 0:
            self.set_column(1)
            self.set_y(self.column_top)
            return False
        if self.two_columns:
            self.set_column(0)
            self.column_top = CONTENT_TOP
        return True

    def set_column(self, column: int) -> None:
        self.column = column
        if not self.two_columns:
            return
        width = (self.w - 2 * MARGIN - GAP) / 2
        left = MARGIN + column * (width + GAP)
        self.set_left_margin(left)
        self.set_right_margin(self.w - left - width)
        self.x = left

    def need(self, height: float) -> None:
        """Move to the next column or page first when `height` doesn't fit below."""
        if self.y + height > self.page_break_trigger and self.accept_page_break:
            self.add_page()

    def paragraph(self, text: str, size: float = BODY, style: str = "", gap: float = 1.5) -> None:
        self.set_font(FONT, style, size)
        for line in text.split("\n"):
            self.multi_cell(0, LINE * size / BODY, line or " ", new_x="LMARGIN", new_y="NEXT")
        self.ln(gap)

    def table(self, table: dict) -> None:
        if table.get("caption"):
            self.paragraph(table["caption"], 8.5, "B", 1)
        width = self.w - self.l_margin - self.r_margin
        for r, row in enumerate(table["rows"]):
            cell_w = width / len(row)
            self.set_font(FONT, "B" if r == 0 else "", 8)
            height = 4 * max(
                len(self.multi_cell(cell_w, 4, c, dry_run=True, output="LINES")) for c in row
            )
            self.need(height)
            x0, y0 = self.l_margin, self.y
            for c, value in enumerate(row):
                self.set_xy(x0 + c * cell_w, y0)
                self.multi_cell(cell_w, 4, value, new_x="RIGHT", new_y="TOP")
                self.rect(x0 + c * cell_w, y0, cell_w, height)
            self.set_xy(x0, y0 + height)
        self.ln(3)

    def figure(self, figure: dict) -> None:
        self.need(36)
        self.set_draw_color(150, 150, 150)
        self.rect(self.l_margin, self.y, self.w - self.l_margin - self.r_margin, 32)
        self.set_draw_color(0, 0, 0)
        self.set_y(self.y + 34)
        if figure.get("caption"):
            self.paragraph(figure["caption"], 8.5, gap=3)

    def build(self) -> bytes:
        """Lays the document out in case_text.doc_text's order, so the text matches."""
        doc = self.doc
        self.add_page()
        if banner := doc.get("banner"):
            self.set_font(FONT, "B", 10)
            self.set_fill_color(253, 226, 226)
            self.set_text_color(160, 0, 0)
            self.multi_cell(0, 5.5, banner, border=1, fill=True, align="C", padding=1.5,
                            new_x="LMARGIN", new_y="NEXT")
            self.set_text_color(0, 0, 0)
            self.ln(4)
        self.paragraph(doc["title"], 16, "B", 2)
        if doc.get("authors"):
            self.paragraph(doc["authors"], 10, gap=3)
        if doc.get("abstract"):
            self.paragraph(case_text.ABSTRACT, 10, "B", 0.5)
            self.paragraph(doc["abstract"], gap=4)
        self.column_top = self.y
        self.set_column(0)
        for section in doc["sections"]:
            if section.get("heading"):
                self.need(3 * LINE)
                self.paragraph(section["heading"], 10.5, "B", 0.5)
            if section.get("text"):
                self.paragraph(section["text"], gap=3)
            if section.get("table"):
                self.table(section["table"])
            if section.get("figure"):
                self.figure(section["figure"])
        if doc.get("references"):
            self.need(3 * LINE)
            self.paragraph(case_text.REFERENCES, 10.5, "B", 0.5)
            for reference in doc["references"]:
                self.paragraph(reference, 8, gap=0.8)
        return bytes(self.output())


def make_doc_pdf(doc: dict) -> bytes:
    return new_pdf(DocPdf, doc).build()


def built_pdf(holder: dict, field: str) -> bytes | None:
    """The PDF a case (or document) gives for `field` (paper, draft or pdf): built from its `_text`
    or `_doc` form, or a `_file`'s bytes as they are; None when it gives none."""
    kind = case_text.form(holder, field)
    if kind == "file":
        return case_text.real_pdf(holder[f"{field}_file"]).read_bytes()
    if kind == "doc":
        return make_doc_pdf(holder[f"{field}_doc"])
    if kind == "text":
        return make_pdf(holder[f"{field}_text"])
    return None


## fake change notice
def build_inputs(meta: dict) -> tuple[ReportInputs, bytes | None]:
    """A case lists its documents as `documents`: each has kind (notice, related, new_version or
    current_version), doi, and optionally title, text, update_to_includes_paper and pdf_text,
    pdf_doc or pdf_file (a PDF stored for it). A single `notice` is still accepted; none means
    the alert has no notice."""
    specs = list(meta.get("documents") or [])
    if meta.get("notice"):
        specs.insert(0, {"kind": "notice", **meta["notice"]})
    documents = []
    document_pdfs = []
    for i, spec in enumerate(specs, 1):
        pdf = built_pdf(spec, "pdf")
        has_pdf = pdf is not None
        document = DocumentInReport(
            id=i,
            kind=spec["kind"],
            doi=spec["doi"],
            crossref_status="ok",
            crossref_record={"title": [spec["title"]]} if spec.get("title") else None,
            update_to_includes_paper=spec.get("update_to_includes_paper"),
            text_status="ok" if spec.get("text") else "not_open_access",
            text=spec.get("text"),
            pdf_status="ok" if has_pdf else "not_applicable" if spec["kind"] == "notice" else "not_found",
            pdf_source_url=spec.get("pdf_source_url"),
            pdf_fetched_at="2026-10-02T00:00:00Z" if has_pdf else None,
        )
        documents.append(document)
        if has_pdf:
            document_pdfs.append((document, pdf))
    report = Report(
        id=1,
        paper_id=PAPER_ID,
        status="investigated",
        alerts=[AlertInReport.model_validate(a) for a in meta["alerts"]],
        documents=documents,
    )
    inputs = ReportInputs(
        report=report,
        paper=PaperDetails.model_validate(meta["paper"]),
        paper_pdf=built_pdf(meta, "paper"),
        document_pdfs=document_pdfs,
    )
    return inputs, built_pdf(meta, "draft")


def fake_storage(draft: bytes | None) -> httpx.AsyncClient:
    """Stands in for Storage Management: assess() only asks it for the draft."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/draft") and draft is not None:
            return httpx.Response(200, content=draft)
        return httpx.Response(404, json={"detail": "no draft"})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://storage")


def record_run(out_dir: Path, name: str, entry: dict) -> None:
    """Adds a run's entry to <out_dir>/meta.json, replacing the entry of a run that is re-run."""
    path = out_dir / "meta.json"
    meta = json.loads(path.read_text()) if path.exists() else {"runs": {}}
    meta["runs"][name] = entry
    path.write_text(json.dumps(meta, indent=2))


async def run_case(llm: FallbackLlm, case_file: Path, run: int, out_dir: Path) -> None:
    name = f"{case_file.stem}__run{run}"
    case_bytes = case_file.read_bytes()
    # what produced the run, so a regression can be told from noise or from a changed case
    entry = {
        "started_at": datetime.now(UTC).isoformat(),
        "prompt_version": PROMPT_VERSION,
        "models": list(llm.models),
        # assess sets neither: the model's defaults, as in production
        "generation": {"temperature": None, "seed": None},
        "case_sha256": hashlib.sha256(case_bytes).hexdigest(),
    }
    inputs, draft = build_inputs(json.loads(case_bytes))
    async with fake_storage(draft) as sm:
        evaluation = await assess(llm, sm, inputs)
    body = evaluation.model_dump(mode="json")
    (out_dir / f"{name}.json").write_text(json.dumps(body, indent=2))
    record_run(out_dir, name, entry)
    a = body["assessment"]
    print(
        f"{case_file.stem} run {run}: change={body['change_severity']} impact={body['impact_level']} "
        f"placeholder={a['placeholder']} model={a['model_version']}"
    )


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("cases", nargs="*", help="case names (file stems); default is all")
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--into", help="an existing results folder to add to (resume)")
    parser.add_argument("--dry", action="store_true", help="build each case's inputs, no Gemini call")
    args = parser.parse_args()

    if args.dry:
        from research_evaluation.impact.prompts import change_prompt

        for path in sorted((ROOT / "cases").glob("*.json")):
            if args.cases and path.stem not in args.cases:
                continue
            inputs, draft = build_inputs(json.loads(path.read_text()))
            prompt = change_prompt(inputs)
            print(
                f"{path.stem}: alerts={len(inputs.report.alerts)} documents={len(inputs.report.documents)} "
                f"pdfs={prompt.attached} draft={'yes' if draft else 'no'}"
            )
        return

    load_env()
    if not os.environ.get("GEMINI_API_KEY"):
        sys.exit("GEMINI_API_KEY is not set")
    models = [os.environ.get("GEMINI_MODEL", "gemini-flash-latest")]
    models += os.environ.get("GEMINI_FALLBACK_MODELS", "gemini-flash-lite-latest").split(",")
    llm = FallbackLlm(
        genai.Client(
            api_key=os.environ["GEMINI_API_KEY"],
            # same limit as production (IMPACT_LLM_TIMEOUT_SECONDS), in milliseconds
            http_options=types.HttpOptions(timeout=120_000),
        ),
        tuple(dict.fromkeys(m.strip() for m in models if m.strip())),
    )

    stamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
    out_dir = Path(args.into) if args.into else ROOT / "results" / stamp
    out_dir.mkdir(parents=True, exist_ok=True)
    names = args.cases or sorted(p.stem for p in (ROOT / "cases").glob("*.json"))
    for name in names:
        for run in range(1, args.runs + 1):
            await run_case(llm, ROOT / "cases" / f"{name}.json", run, out_dir)
    print(f"saved to {out_dir}")


if __name__ == "__main__":
    asyncio.run(main())
