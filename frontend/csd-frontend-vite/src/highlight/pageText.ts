// One PDF page's text as pdf.js gives it, joined into a single string with each text item's place in it,
// so a match found by findQuote can be mapped back to the text-layer spans that drew it.

// the part of pdf.js's TextItem this needs (a TextMarkedContent has no str, and is skipped)
export type TextPiece = { str?: string; hasEOL?: boolean }

// item: the index among the pieces that have a str, which is the index of its span in pdf.js's
// TextLayer.textDivs; start and end: where its str sits in the joined text
export type ItemRange = { item: number; start: number; end: number }

export type PageText = { text: string; items: ItemRange[] }

export function pageText(pieces: readonly TextPiece[]): PageText {
  let text = ''
  const items: ItemRange[] = []
  for (const piece of pieces) {
    if (typeof piece.str !== 'string') continue
    items.push({ item: items.length, start: text.length, end: text.length + piece.str.length })
    text += piece.str
    if (piece.hasEOL) text += '\n'
  }
  return { text, items }
}

// Two pages as one text, so a quote that runs onto the next page is found; offsets of the second page
// are shifted past the first and its separating line break
export function joinPages(first: PageText, second: PageText | null): { text: string; split: number } {
  if (!second) return { text: first.text, split: first.text.length }
  return { text: `${first.text}\n${second.text}`, split: first.text.length + 1 }
}

// The characters [start, end) of a joined text, as ranges inside each item's str
export type ItemSlice = { item: number; from: number; to: number }

export function slicesOf(page: PageText, start: number, end: number): ItemSlice[] {
  return page.items
    .filter((range) => range.end > start && range.start < end && range.end > range.start)
    .map((range) => ({
      item: range.item,
      from: Math.max(start, range.start) - range.start,
      to: Math.min(end, range.end) - range.start,
    }))
}
