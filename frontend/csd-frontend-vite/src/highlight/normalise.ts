// Finding a claim's quote in a page's text, by the rules Research Evaluation uses to call a quote
// verbatim (backend/src/research_evaluation/impact/quotes.py on feat/claim-passages-backend), so a quote
// it found is found here too. Unlike quotes.py, the match is mapped back to the original text, which is
// what lets the highlighter box exactly the matched characters.

// a letter, "-", a line break, then a letter: a word hyphenated across lines (quotes.py, _HYPHEN_BREAK)
const HYPHEN_BREAK = /(\p{L})-[ \t]*\n[ \t]*(\p{L})/gu
const ELLIPSIS = /\.\.\.|…/
const STRAIGHT: Record<string, string> = { '’': "'", '“': '"', '”': '"' }

// A word hyphenated across a line break is joined when it carries on in lower case ("calcu-\nlation");
// "Dose-\nResponse" keeps its hyphen, as quotes.join_hyphens does
export function joinHyphens(text: string): string {
  return text.replace(HYPHEN_BREAK, (whole, before: string, after: string) =>
    after === after.toLowerCase() && after !== after.toUpperCase() ? before + after : whole,
  )
}

// quotes.norm: NFKC, curly quotes straightened, whitespace collapsed and trimmed, lower-case
export function norm(text: string): string {
  return normalised(text).text
}

type Normalised = { text: string; at: number[] }

// norm(text), with at[i] the index in text of the character that gave normalised character i
function normalised(text: string): Normalised {
  const out: string[] = []
  const at: number[] = []
  let i = 0
  for (const char of text) {
    for (const piece of [...char.normalize('NFKC')]) {
      for (const lower of [...(STRAIGHT[piece] ?? piece).toLowerCase()]) {
        if (/\s/u.test(lower)) {
          if (out.length > 0 && out[out.length - 1] !== ' ') {
            out.push(' ')
            at.push(i)
          }
        } else {
          out.push(lower)
          at.push(i)
        }
      }
    }
    i += char.length
  }
  if (out[out.length - 1] === ' ') {
    out.pop()
    at.pop()
  }
  return { text: out.join(''), at }
}

// Removes the hyphen breaks joinHyphens would, keeping where each kept character came from
function withoutHyphenBreaks(text: string): { text: string; at: number[] } {
  const drop = new Set<number>()
  for (const match of text.matchAll(HYPHEN_BREAK)) {
    const [whole, before, after] = match
    if (after !== after.toLowerCase() || after === after.toUpperCase()) continue
    const start = match.index + before.length
    const end = match.index + whole.length - after.length
    for (let k = start; k < end; k++) drop.add(k)
  }
  const kept: string[] = []
  const at: number[] = []
  for (let k = 0; k < text.length; k++) {
    if (drop.has(k)) continue
    kept.push(text[k])
    at.push(k)
  }
  return { text: kept.join(''), at }
}

export type Match = { start: number; end: number }

// Where the quote is in text, as [start, end) offsets into text itself; null when it isn't there.
// "..." or "…" in the quote skips words, so each piece must be there, in order (quotes.quoted_in).
export function findQuote(quote: string, text: string): Match | null {
  const pieces = norm(quote)
    .split(ELLIPSIS)
    .map((piece) => piece.trim())
    .filter(Boolean)
  if (pieces.length === 0) return null

  const joined = withoutHyphenBreaks(text)
  const page = normalised(joined.text)
  let from = 0
  let first = -1
  let last = -1
  for (const piece of pieces) {
    const found = page.text.indexOf(piece, from)
    if (found < 0) return null
    if (first < 0) first = found
    last = found + piece.length - 1
    from = found + piece.length
  }
  const original = (k: number) => joined.at[page.at[k]]
  return { start: original(first), end: original(last) + 1 }
}
