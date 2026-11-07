"""Eval runner: feeds each case in evals/cases through the real 3-call assess() and saves the
raw Evaluation JSON. Scoring happens later, on the saved files, so re-scoring costs no quota.

    python evals/run.py 01_correction_effect_size --runs 1   # case file stem
    python evals/run.py --runs 3            # every case
    python evals/run.py --provider claude-code              # through Claude Code, on the subscription
    python evals/run.py --into evals/results/<stamp> --resume   # only the runs not saved yet, or failed

--provider gemini (default) reads GEMINI_API_KEY and GEMINI_MODEL from the environment or
backend/.env; it never falls back to another model, so GEMINI_FALLBACK_MODELS isn't used.
--provider claude-code runs `claude -p` on the Claude Code login (a claude.ai subscription, never
an API key; backend/.env isn't read), default `--model sonnet --effort medium`. --provider
claude-api calls the Claude API with ANTHROPIC_API_KEY (environment or backend/.env), billed to
the key, default `--model claude-sonnet-5-5 --effort medium`; it needs `--with anthropic`.

Needs fpdf2 to turn the cases' texts into PDFs (not a project dependency). It writes
prompts.json into the folder: the prompt texts the runs used, keyed by prompt_sha256."""

import argparse
import asyncio
import hashlib
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Protocol
from uuid import UUID

import httpx
from fpdf import FPDF
from google import genai
from google.genai import types

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT.parent / "src"))

import case_text
import claude_llm
import judge
from claude_llm import ApiStopped, ClaudeLimitReached, NotOnSubscription, Usage

from research_evaluation.impact import prompts
from research_evaluation.impact.assess import assess
from research_evaluation.impact.inputs import ReportInputs
from research_evaluation.impact.llm import FallbackLlm, Llm
from research_evaluation.storage import (
    AlertInReport,
    DocumentInReport,
    PaperDetails,
    Report,
)

PAPER_ID = UUID("00000000-0000-0000-0000-000000000001")


PROVIDERS = ("gemini", "claude-code", "claude-api")
# the tested model and its effort when --model / --effort aren't given
DEFAULT_MODELS = {"claude-code": "sonnet", "claude-api": "claude-sonnet-5-5"}
DEFAULT_EFFORT = "medium"


def load_env(keys: tuple[str, ...]) -> None:
    env = ROOT.parent / ".env"
    if not env.exists():
        return
    for line in env.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() in keys:
            os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


class EvalLlm(Llm, Protocol):
    """An Llm that also says which models it was given, as meta.json records them."""

    @property
    def models(self) -> tuple[str, ...]: ...

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
        # the title block spans the page; the columns start after it (build)
        self.in_columns = False
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
        if self.in_columns and self.column == 0:
            self.set_column(1)
            self.set_y(self.column_top)
            return False
        if self.in_columns:
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
        self.in_columns = self.two_columns
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


def prompt_texts() -> dict[str, str]:
    """The prompt templates a run uses, read when called (so a changed prompts.py shows)."""
    return {
        "system": prompts.SYSTEM,
        "change_task": prompts.CHANGE_TASK,
        "impact_task": prompts.IMPACT_TASK,
        "actions_task": prompts.ACTIONS_TASK,
        "judge_system": judge.SYSTEM,
        "judge_passage_system": judge.PASSAGE_SYSTEM,
    }


def record_prompts(out_dir: Path) -> str:
    """Adds the current prompt texts to <out_dir>/prompts.json, unless their hash is already
    there, and returns the hash: sha256 of the six texts as canonical JSON."""
    texts = prompt_texts()
    canonical = json.dumps(texts, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    sha = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    path = out_dir / "prompts.json"
    recorded = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    if sha not in recorded:
        recorded[sha] = {"prompt_version": prompts.PROMPT_VERSION, **texts}
        path.write_text(json.dumps(recorded, indent=2, ensure_ascii=False), encoding="utf-8")
    return sha


async def run_case(
    llm: EvalLlm, case_file: Path, run: int, out_dir: Path, provider: str = "gemini"
) -> None:
    name = f"{case_file.stem}__run{run}"
    case_bytes = case_file.read_bytes()
    # what produced the run, so a regression can be told from noise or from a changed case
    entry = {
        "started_at": datetime.now(UTC).isoformat(),
        "prompt_version": prompts.PROMPT_VERSION,
        "prompt_sha256": record_prompts(out_dir),
        "provider": provider,
        "models": list(llm.models),
        # assess sets neither, and the Claude CLI has no flag for them: the model's defaults
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


def saved(out_dir: Path, name: str) -> bool:
    """A run file exists for `name` and isn't a placeholder (what --resume skips)."""
    path = out_dir / f"{name}.json"
    if not path.exists():
        return False
    try:
        return not json.loads(path.read_text())["assessment"]["placeholder"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return False


def print_usage(usage: Usage) -> None:
    """After each call: its tokens and, on Claude Code, the subscription's utilization."""
    parts = [
        f"in {usage.input_tokens}",
        f"cache write {usage.cache_write_tokens}",
        f"cache read {usage.cache_read_tokens}",
        f"out {usage.output_tokens}",
    ]
    if usage.five_hour is not None or usage.seven_day is not None:
        parts.append(f"5-hour {fraction(usage.five_hour)}, 7-day {fraction(usage.seven_day)} used")
    if usage.request_id:
        parts.append(f"request {usage.request_id}")
    print(f"  {'failed call' if usage.failed else 'call'}: " + ", ".join(parts), flush=True)


def fraction(value: float | None) -> str:
    return "?" if value is None else f"{value:.0%}"


def resume_command(args: argparse.Namespace, out_dir: Path) -> str:
    """The command that continues this run in the same folder."""
    flags = [*args.cases, "--runs", str(args.runs), "--provider", args.provider]
    if args.model:
        flags += ["--model", args.model]
    if args.effort:
        flags += ["--effort", args.effort]
    with_anthropic = " --with anthropic" if args.provider == "claude-api" else ""
    return (
        f"uv run --with fpdf2 --with rich{with_anthropic} python evals/run.py "
        + " ".join(flags)
        + f' --into "{out_dir}" --resume'
    )


def make_llm(args: argparse.Namespace) -> EvalLlm:
    """The tested model for the chosen provider; none falls back to another model."""
    if args.provider == "claude-code":
        claude_llm.preflight()
        return claude_llm.ClaudeLlm(
            args.model or DEFAULT_MODELS["claude-code"],
            effort=args.effort or DEFAULT_EFFORT,
            report=print_usage,
        )
    if args.provider == "claude-api":
        load_env(("ANTHROPIC_API_KEY",))
        if not os.environ.get("ANTHROPIC_API_KEY"):
            sys.exit("ANTHROPIC_API_KEY is not set")
        return claude_llm.ClaudeApiLlm(
            claude_llm.api_client(),
            args.model or DEFAULT_MODELS["claude-api"],
            effort=args.effort or DEFAULT_EFFORT,
            report=print_usage,
        )
    load_env(("GEMINI_API_KEY", "GEMINI_MODEL"))
    if not os.environ.get("GEMINI_API_KEY"):
        sys.exit("GEMINI_API_KEY is not set")
    model = args.model or os.environ.get("GEMINI_MODEL", "gemini-flash-latest")
    return FallbackLlm(
        genai.Client(
            api_key=os.environ["GEMINI_API_KEY"],
            # same limit as production (IMPACT_LLM_TIMEOUT_SECONDS), in milliseconds
            http_options=types.HttpOptions(timeout=120_000),
        ),
        # GEMINI_MODEL only: an eval never falls back, a failed run is re-run with --resume
        (model.strip(),),
    )


def failed_runs(out_dir: Path, names: list[str]) -> list[tuple[str, str]]:
    """The placeholder runs among `names`, with their failed step and causes."""
    out = []
    for name in names:
        path = out_dir / f"{name}.json"
        if not path.exists():
            continue
        a = json.loads(path.read_text())["assessment"]
        if a["placeholder"]:
            causes = "; ".join(f"{f['model']}: {f['cause']}" for f in a.get("failures") or [])
            out.append((name, f"step {a.get('failed_step')}: {causes}"))
    return out


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("cases", nargs="*", help="case names (file stems); default is all")
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--into", help="an existing results folder to add to")
    parser.add_argument("--dry", action="store_true", help="build each case's inputs, no model call")
    parser.add_argument("--provider", choices=PROVIDERS, default="gemini")
    parser.add_argument("--model", help="the tested model (default per provider)")
    parser.add_argument("--effort", help="the tested model's effort (claude providers; default medium)")
    parser.add_argument(
        "--resume", action="store_true",
        help="with --into: skip runs already saved, re-run placeholders",
    )
    args = parser.parse_args()
    if args.resume and not args.into:
        parser.error("--resume needs --into <folder>")
    if args.effort and args.provider == "gemini":
        parser.error("--effort applies only to --provider claude-code or claude-api")

    if args.dry:
        for path in sorted((ROOT / "cases").glob("*.json")):
            if args.cases and path.stem not in args.cases:
                continue
            inputs, draft = build_inputs(json.loads(path.read_text()))
            prompt = prompts.change_prompt(inputs)
            print(
                f"{path.stem}: alerts={len(inputs.report.alerts)} documents={len(inputs.report.documents)} "
                f"pdfs={prompt.attached} draft={'yes' if draft else 'no'}"
            )
        return

    try:
        llm = make_llm(args)
    except NotOnSubscription as exc:
        sys.exit(f"stopped before any call: {exc}")

    stamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
    out_dir = Path(args.into) if args.into else ROOT / "results" / stamp
    out_dir.mkdir(parents=True, exist_ok=True)
    names = args.cases or sorted(p.stem for p in (ROOT / "cases").glob("*.json"))
    targets = [(name, run) for name in names for run in range(1, args.runs + 1)]
    try:
        for name, run in targets:
            if args.resume and saved(out_dir, f"{name}__run{run}"):
                continue
            await run_case(llm, ROOT / "cases" / f"{name}.json", run, out_dir, args.provider)
    except ClaudeLimitReached as exc:
        when = (
            datetime.fromtimestamp(exc.resets_at, UTC).strftime("%Y-%m-%d %H:%M UTC")
            if exc.resets_at else "unknown"
        )
        print(f"\nstopped: {exc}; the run in progress was not saved. Resets at: {when}")
        print(f"continue with:\n    {resume_command(args, out_dir)}")
        sys.exit(3)
    except (NotOnSubscription, ApiStopped) as exc:
        sys.exit(f"\nstopped: {exc}")
    failures = failed_runs(out_dir, [f"{n}__run{r}" for n, r in targets])
    if failures:
        print(f"\n{len(failures)} failed run(s), saved as placeholders:")
        for name, cause in failures:
            print(f"  {name}: {cause}")
        print(f"re-run them with:\n    {resume_command(args, out_dir)}")
    print(f"saved to {out_dir}")


if __name__ == "__main__":
    asyncio.run(main())
