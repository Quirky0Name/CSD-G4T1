"""Writes the text of each real PDF in this folder to <same stem>.txt, which the quote check
(score.py) and the judge (judge.py) read instead of the PDF, so neither needs a PDF library.

    python evals/real/extract.py           # PDFs here with no .txt yet
    python evals/real/extract.py --redo    # every PDF here

Needs pypdf (`uv run --with pypdf`). A word hyphenated across a line break is joined
("pro-" / "liferation" becomes "proliferation"). Check the .txt is in reading order: two-column
text that comes out interleaved makes real quotes look invented."""

import argparse
import re
from pathlib import Path

from pypdf import PdfReader

# a line ending in "<letter>-", then a line starting with a letter (joined if it is lowercase)
HYPHEN_BREAK = re.compile(r"([^\W\d_])-[ \t]*\n[ \t]*([^\W\d_])")


def join_hyphen(match: re.Match) -> str:
    before, after = match.groups()
    return before + after if after.islower() else match.group()


def pdf_text(path: Path) -> str:
    pages = [page.extract_text() or "" for page in PdfReader(path).pages]
    return HYPHEN_BREAK.sub(join_hyphen, "\n\n".join(pages))


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
