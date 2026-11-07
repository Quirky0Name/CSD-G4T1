// Where a passage's quote sits in a PDF: on the page it names, possibly running onto the next one.
import { findQuote } from './normalise'
import { joinPages, slicesOf, type ItemSlice, type PageText } from './pageText'

export type Located = { found: true; slices: Map<number, ItemSlice[]> } | { found: false }

// readPage gives a 1-based page's text, or null past the last page
export async function locate(
  quote: string,
  page: number,
  readPage: (page: number) => Promise<PageText | null>,
): Promise<Located> {
  const first = await readPage(page)
  if (!first) return { found: false }
  const next = await readPage(page + 1)
  const { text, split } = joinPages(first, next)
  const match = findQuote(quote, text)
  // the passage promises the quote starts on its page
  if (!match || match.start >= split) return { found: false }

  const slices = new Map<number, ItemSlice[]>()
  slices.set(page, slicesOf(first, match.start, Math.min(match.end, first.text.length)))
  if (next && match.end > split) slices.set(page + 1, slicesOf(next, 0, match.end - split))
  return { found: true, slices }
}
