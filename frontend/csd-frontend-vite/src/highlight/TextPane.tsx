// A text that isn't a PDF page (a notice's text, an alert's description) with a passage's quote marked.
// Publisher and model text: rendered as text, never as HTML.
import { useEffect, useRef } from 'react'
import { findQuote } from './normalise'

export default function TextPane({ text, quote, seq = 0 }: { text: string; quote: string | null; seq?: number }) {
  const mark = useRef<HTMLElement>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const match = quote ? findQuote(quote, text) : null

  useEffect(() => {
    const box = scroller.current
    const marked = mark.current
    if (!box || !marked) return
    const top = marked.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop
    box.scrollTo({ top: Math.max(0, top - box.clientHeight / 3), behavior: 'smooth' })
  }, [text, quote, seq])

  return (
    <div ref={scroller} className="h-full overflow-y-auto overscroll-contain bg-gray-950/60 p-3 sm:p-4">
      <p className="mx-auto max-w-prose whitespace-pre-line rounded-md bg-white px-5 py-4 text-sm/6 text-gray-800 shadow-md shadow-black/40">
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
  )
}
