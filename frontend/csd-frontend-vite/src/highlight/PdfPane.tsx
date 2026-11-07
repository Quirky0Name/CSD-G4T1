// A PDF, scrolled to a passage with its quote boxed. pdf.js draws each page (canvas plus text layer);
// the boxes are drawn over the text-layer spans the quote covers, found by text, not by coordinates,
// since a passage carries only its quote and page (docs/CONTRACTS.md, "Claim").
import { getDocument, GlobalWorkerOptions, TextLayer, type PDFDocumentProxy, type PDFPageProxy } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { locate } from './locate'
import { scrollMotion } from './motion'
import { pageText, type ItemSlice, type PageText, type TextPiece } from './pageText'
import './pdf.css'

GlobalWorkerOptions.workerSrc = workerUrl

export type PaneResult = 'found' | 'not_on_page' | 'unavailable'
// seq: bumped by the caller each time the reader selects it, so selecting it again scrolls back to it
export type PdfHighlight = { quote: string; page: number; seq?: number }

// each PDF is read once while the app is open; a failed read is forgotten, so a retry reads it again
const documents = new Map<string, Promise<PDFDocumentProxy>>()
function loadDocument(url: string): Promise<PDFDocumentProxy> {
  let pending = documents.get(url)
  if (!pending) {
    pending = getDocument({ url }).promise
    pending.catch(() => documents.delete(url))
    documents.set(url, pending)
  }
  return pending
}

type Size = { width: number; height: number }
type TextContent = Awaited<ReturnType<PDFPageProxy['getTextContent']>>
type Loaded = { url: string; pdf: PDFDocumentProxy; sizes: Size[] } | { url: string; failed: true }

export default function PdfPane({
  url,
  highlight,
  onResult,
}: {
  url: string
  highlight: PdfHighlight | null
  onResult?: (result: PaneResult) => void
}) {
  const scroller = useRef<HTMLDivElement>(null)
  const pages = useRef(new Map<number, HTMLDivElement>())
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [width, setWidth] = useState(0)
  const [located, setLocated] = useState<{ key: string; slices: Map<number, ItemSlice[]> } | null>(null)
  // the highlight already scrolled to, so redrawing on resize doesn't yank the reader back
  const scrolled = useRef<string | null>(null)
  const report = useRef(onResult)
  useEffect(() => {
    report.current = onResult
  }, [onResult])

  useEffect(() => {
    const element = scroller.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    let live = true
    loadDocument(url)
      .then(async (pdf) => {
        const sizes = await Promise.all(
          Array.from({ length: pdf.numPages }, async (_, i) => {
            const { width, height } = (await pdf.getPage(i + 1)).getViewport({ scale: 1 })
            return { width, height }
          }),
        )
        if (live) setLoaded({ url, pdf, sizes })
      })
      .catch(() => {
        if (!live) return
        setLoaded({ url, failed: true })
        report.current?.('unavailable')
      })
    return () => {
      live = false
    }
  }, [url])

  const pdf = loaded && loaded.url === url && 'pdf' in loaded ? loaded.pdf : null
  const key = highlight ? `${url}#${highlight.page}#${highlight.quote}` : null
  const scrollKey = key && `${key}#${highlight?.seq ?? 0}`

  useEffect(() => {
    if (!pdf || !highlight || !key) return
    let live = true
    locate(highlight.quote, highlight.page, (n) => (n <= pdf.numPages ? readPage(pdf, n) : Promise.resolve(null)))
      .then((result) => {
        if (!live) return
        setLocated({ key, slices: result.found ? result.slices : new Map() })
        report.current?.(result.found ? 'found' : 'not_on_page')
        if (!result.found) scrollToPage(scroller.current, pages.current.get(highlight.page))
      })
      .catch(() => {
        if (live) report.current?.('unavailable')
      })
    return () => {
      live = false
    }
    // highlight is read through key, which changes exactly when it does
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pdf, key])

  // a page drew its first box of the current highlight: bring it into view, once
  function onDrawn(mark: HTMLElement) {
    const box = scroller.current
    if (!box || !scrollKey || scrolled.current === scrollKey) return
    scrolled.current = scrollKey
    const top = mark.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop
    box.scrollTo({ top: Math.max(0, top - box.clientHeight / 3), behavior: scrollMotion() })
  }

  const slices = located && located.key === key ? located.slices : null
  const firstPage = highlight && slices?.size ? highlight.page : null

  return (
    <div
      ref={scroller}
      tabIndex={0}
      aria-label="PDF pages"
      className="pdf-pane relative h-full overflow-y-auto overscroll-contain bg-gray-950/60 px-3 py-3 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-indigo-500 sm:px-4"
    >
      {!loaded || loaded.url !== url ? (
        <PaneNote>Loading the PDF…</PaneNote>
      ) : 'failed' in loaded ? (
        <PaneNote>No copy of this document to show.</PaneNote>
      ) : (
        width > 0 &&
        loaded.sizes.map((size, i) => (
          <PdfPage
            key={i}
            pdf={loaded.pdf}
            number={i + 1}
            size={size}
            width={width}
            slices={slices?.get(i + 1) ?? null}
            onDrawn={i + 1 === firstPage ? onDrawn : undefined}
            register={(element) => {
              if (element) pages.current.set(i + 1, element)
              else pages.current.delete(i + 1)
            }}
          />
        ))
      )}
    </div>
  )
}

function scrollToPage(box: HTMLElement | null, page: HTMLElement | undefined) {
  if (page && box) box.scrollTo({ top: page.offsetTop - 12, behavior: scrollMotion() })
}

function PaneNote({ children }: { children: ReactNode }) {
  return <p className="px-2 py-6 text-sm/6 text-gray-400">{children}</p>
}

// each page's text is read once per document, for both finding quotes and drawing the text layer
const texts = new WeakMap<PDFDocumentProxy, Map<number, Promise<{ content: TextContent; text: PageText }>>>()
function readContent(pdf: PDFDocumentProxy, n: number) {
  let byPage = texts.get(pdf)
  if (!byPage) texts.set(pdf, (byPage = new Map()))
  let pending = byPage.get(n)
  if (!pending) {
    pending = pdf
      .getPage(n)
      .then((page) => page.getTextContent())
      .then((content) => ({ content, text: pageText(content.items as TextPiece[]) }))
    byPage.set(n, pending)
  }
  return pending
}
async function readPage(pdf: PDFDocumentProxy, n: number): Promise<PageText> {
  return (await readContent(pdf, n)).text
}

function PdfPage({
  pdf,
  number,
  size,
  width,
  slices,
  onDrawn,
  register,
}: {
  pdf: PDFDocumentProxy
  number: number
  size: Size
  width: number
  slices: ItemSlice[] | null
  onDrawn?: (mark: HTMLElement) => void
  register: (element: HTMLDivElement | null) => void
}) {
  const frame = useRef<HTMLDivElement>(null)
  const layers = useRef<HTMLDivElement>(null)
  const marks = useRef<HTMLDivElement>(null)
  const spans = useRef<HTMLElement[] | null>(null)
  // what the page-drawing effect reads once its text layer is ready
  const latest = useRef({ slices, onDrawn })
  const [near, setNear] = useState(false)
  const scale = width / size.width
  const height = Math.round(size.height * scale)
  // a page with a highlight is drawn even before it scrolls near
  const show = near || slices !== null

  useEffect(() => {
    const element = frame.current
    if (!element) return
    const observer = new IntersectionObserver(([entry]) => entry.isIntersecting && setNear(true), {
      rootMargin: '600px 0px',
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  // the drawn page: canvas and text layer, again whenever the width changes
  useEffect(() => {
    const host = layers.current
    if (!show || !host) return
    let live = true
    let cancel = () => {}
    ;(async () => {
      const page = await pdf.getPage(number)
      const { content } = await readContent(pdf, number)
      if (!live) return
      const viewport = page.getViewport({ scale })
      const ratio = window.devicePixelRatio || 1
      const canvas = document.createElement('canvas')
      canvas.width = Math.floor(viewport.width * ratio)
      canvas.height = Math.floor(viewport.height * ratio)
      canvas.style.width = `${viewport.width}px`
      canvas.style.height = `${viewport.height}px`
      const text = document.createElement('div')
      text.className = 'textLayer'
      text.style.setProperty('--total-scale-factor', String(scale))
      host.replaceChildren(canvas, text)

      const render = page.render({ canvas, viewport, transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0] })
      const layer = new TextLayer({ textContentSource: content, container: text, viewport })
      cancel = () => {
        render.cancel()
        layer.cancel()
      }
      await layer.render()
      if (!live) return
      spans.current = layer.textDivs
      drawMarks(marks.current, frame.current, spans.current, latest.current.slices, latest.current.onDrawn)
      await render.promise.catch(() => {})
    })().catch(() => {})
    return () => {
      live = false
      cancel()
      spans.current = null
    }
  }, [show, pdf, number, scale])

  // new slices are drawn over the page already there; the page itself isn't drawn again
  useEffect(() => {
    latest.current = { slices, onDrawn }
    drawMarks(marks.current, frame.current, spans.current, slices, onDrawn)
  })

  return (
    <div
      ref={(element) => {
        frame.current = element
        register(element)
      }}
      className="pdf-page relative mx-auto mb-3 bg-white shadow-md shadow-black/40"
      style={{ width, height }}
      aria-label={`Page ${number}`}
      role="group"
    >
      <div ref={layers} className="absolute inset-0" />
      <div ref={marks} className="pointer-events-none absolute inset-0" aria-hidden="true" />
      <span className="pdf-page-number">{number}</span>
    </div>
  )
}

// Boxes over the characters each slice covers, positioned against the page; the first one is reported,
// so the pane can scroll to it
function drawMarks(
  overlay: HTMLElement | null,
  page: HTMLElement | null,
  spans: HTMLElement[] | null,
  slices: ItemSlice[] | null,
  drawn: ((mark: HTMLElement) => void) | undefined,
) {
  if (!overlay || !page) return
  if (!slices?.length || !spans) {
    overlay.replaceChildren()
    return
  }
  const origin = page.getBoundingClientRect()
  const boxes: HTMLElement[] = []
  for (const slice of slices) {
    const node = spans[slice.item]?.firstChild
    if (!node || node.nodeType !== Node.TEXT_NODE) continue
    const length = node.textContent?.length ?? 0
    const range = document.createRange()
    range.setStart(node, Math.min(slice.from, length))
    range.setEnd(node, Math.min(slice.to, length))
    for (const rect of range.getClientRects()) {
      if (rect.width < 1 || rect.height < 1) continue
      const box = document.createElement('div')
      box.className = 'pdf-mark'
      box.style.left = `${rect.left - origin.left}px`
      box.style.top = `${rect.top - origin.top}px`
      box.style.width = `${rect.width}px`
      box.style.height = `${rect.height}px`
      boxes.push(box)
    }
  }
  overlay.replaceChildren(...boxes)
  if (boxes[0]) drawn?.(boxes[0])
}
