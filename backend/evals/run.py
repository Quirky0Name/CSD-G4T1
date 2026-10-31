"""Eval runner: feeds each case in evals/cases through the real 3-call assess() and saves the
raw Evaluation JSON. Scoring happens later, on the saved files, so re-scoring costs no quota.

    python evals/run.py 01_correction_effect_size --runs 1   # case file stem
    python evals/run.py --runs 3            # every case

Reads GEMINI_API_KEY (and optionally GEMINI_MODEL, GEMINI_FALLBACK_MODELS) from the environment
or backend/.env. Needs fpdf2 to turn the .txt files into PDFs (not a project dependency)."""

import argparse
import asyncio
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

from research_evaluation.impact.assess import assess
from research_evaluation.impact.inputs import ReportInputs
from research_evaluation.impact.llm import FallbackLlm
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

## text to pdf
def make_pdf(text: str) -> bytes:
    pdf = FPDF()
    pdf.add_page()
    pdf.set_font("Helvetica", size=11)
    for paragraph in text.split("\n"):
        pdf.multi_cell(0, 6, paragraph or " ", new_x="LMARGIN", new_y="NEXT")
    return bytes(pdf.output())

## fake change notice
def build_inputs(meta: dict) -> tuple[ReportInputs, bytes | None]:
    """A case lists its documents as `documents`: each has kind (notice, related, new_version or
    current_version), doi, and optionally title, text, update_to_includes_paper and pdf_text (a
    PDF stored for it). A single `notice` is still accepted; none means the alert has no notice."""
    specs = list(meta.get("documents") or [])
    if meta.get("notice"):
        specs.insert(0, {"kind": "notice", **meta["notice"]})
    documents = []
    document_pdfs = []
    for i, spec in enumerate(specs, 1):
        has_pdf = bool(spec.get("pdf_text"))
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
            document_pdfs.append((document, make_pdf(spec["pdf_text"])))
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
        paper_pdf=make_pdf(meta["paper_text"]),
        document_pdfs=document_pdfs,
    )
    draft = make_pdf(meta["draft_text"]) if meta.get("draft_text") else None
    return inputs, draft


def fake_storage(draft: bytes | None) -> httpx.AsyncClient:
    """Stands in for Storage Management: assess() only asks it for the draft."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/draft") and draft is not None:
            return httpx.Response(200, content=draft)
        return httpx.Response(404, json={"detail": "no draft"})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://storage")


async def run_case(llm: FallbackLlm, case_file: Path, run: int, out_dir: Path) -> None:
    inputs, draft = build_inputs(json.loads(case_file.read_text()))
    async with fake_storage(draft) as sm:
        evaluation = await assess(llm, sm, inputs)
    body = evaluation.model_dump(mode="json")
    (out_dir / f"{case_file.stem}__run{run}.json").write_text(json.dumps(body, indent=2))
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
