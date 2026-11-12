// A text that isn't a PDF page (a notice's text, an alert's description) with a passage's quote marked.
// Publisher and model text: rendered as text, never as HTML.
import { useEffect, useRef } from 'react'
import { scrollMotion } from './motion'
import { findQuote } from './normalise'
import './pdf.css'

// range: an exact span of the text to mark instead of a quote to find (a retrieved chunk's place in it)
export default function TextPane({
  text,
  quote,
  range = null,
  seq = 0,
}: {
  text: string
  quote: string | null
  range?: { start: number; end: number } | null
  seq?: number
}) {
  const mark = useRef<HTMLElement>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const match = range
    ? { start: Math.max(0, Math.min(range.start, text.length)), end: Math.max(0, Math.min(range.end, text.length)) }
    : quote
      ? findQuote(quote, text)
      : null

  useEffect(() => {
    const box = scroller.current
    const marked = mark.current
    if (!box || !marked) return
    const top = marked.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop
    box.scrollTo({ top: Math.max(0, top - box.clientHeight / 3), behavior: scrollMotion() })
  }, [text, quote, range?.start, range?.end, seq])

  return (
    <div
      ref={scroller}
      tabIndex={0}
      aria-label="Source text"
      className="pdf-pane h-full overflow-y-auto overscroll-contain bg-gray-950/60 p-3 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-indigo-500 sm:p-4"
    >
      <div className="paper-column rounded-md bg-white px-5 py-4 shadow-md shadow-black/40 sm:px-8 sm:py-6">
        <p className="mx-auto max-w-[75ch] whitespace-pre-line text-sm/6 text-gray-800">
          {match ? (
            <>
              {text.slice(0, match.start)}
              <mark ref={mark} className="rounded-sm bg-yellow-300/70 px-0.5 text-gray-900">
                {text.slice(match.start, match.end)}
              </mark>
              {text.slice(match.end)}
            </>
          ) : (
            text
          )}
        </p>
      </div>
    </div>
  )
}
