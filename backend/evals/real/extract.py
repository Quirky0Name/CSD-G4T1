"""Writes the text of each real PDF in this folder to <same stem>.txt, which the quote check
(score.py) and the judge (judge.py) read instead of the PDF, so neither needs a PDF library.

    python evals/real/extract.py           # PDFs here with no .txt yet
    python evals/real/extract.py --redo    # every PDF here

Needs pypdf, a project dependency. Uses the service's PDF text and hyphen joining
(research_evaluation.impact.quotes): a word hyphenated across a line break is joined ("pro-" /
"liferation" becomes "proliferation"). Check the .txt is in reading order: two-column
text that comes out interleaved makes real quotes look invented."""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[2] / "src"))

from research_evaluation.impact.quotes import join_hyphens, pdf_pages


def pdf_text(path: Path) -> str:
    return join_hyphens("\n\n".join(pdf_pages(path.read_bytes())))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("folder", nargs="?", default=Path(__file__).parent, type=Path,
                        help="folder of PDFs; default is this one (evals/real)")
    parser.add_argument("--redo", action="store_true", help="also redo PDFs that have a .txt")
    args = parser.parse_args()
    for pdf in sorted(args.folder.glob("*.pdf")):
        txt = pdf.with_suffix(".txt")
        if txt.exists() and not args.redo:
            continue
        txt.write_text(pdf_text(pdf), encoding="utf-8")
        print(f"{pdf.name} -> {txt.name}")


if __name__ == "__main__":
    main()
