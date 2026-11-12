// A report's `context_chunks` turned into what the report page shows: each item found again among the
// reports already loaded (docs/CONTRACTS.md, GET /papers/{id}/reports). An alert or report is the one
// with that id in the same paper's reports; a document is the one with that id in any report, since a
// document's id is unique across reports. The chunk's own text was never stored, so this is all there is.
import type { Alert, ContextChunk, FeedbackVerdict, UserDocument, UserReport } from '../api'

export type ContextEntry =
  | {
      kind: 'document'
      key: string
      doc: UserDocument
      // the report the document was fetched for, when it is still loaded
      report: UserReport | null
      page: number | null
      range: { start: number; end: number } | null
    }
  | { kind: 'alert'; key: string; alert: Alert }
  | { kind: 'report'; key: string; report: UserReport }
  | {
      kind: 'feedback'
      key: string
      reportId: string
      paperId: string | null
      paperTitle: string | null
      date: string | null
      verdict: FeedbackVerdict
      // the report the feedback is on, when it is one of this researcher's loaded reports
      report: UserReport | null
    }
  | { kind: 'missing'; key: string; sourceType: 'document' | 'alert' | 'report' }

export function resolveContext(items: ContextChunk[], reports: UserReport[], paperId: string): ContextEntry[] {
  const samePaper = reports.filter((r) => r.paper_id === paperId)
  return items.map((item, index): ContextEntry => {
    const key = `${index}`
    switch (item.source_type) {
      case 'feedback': {
        const report = reports.find((r) => String(r.id) === item.source_id) ?? null
        return {
          kind: 'feedback',
          key,
          reportId: item.source_id,
          paperId: item.paper_id ?? report?.paper_id ?? null,
          paperTitle: item.paper_title,
          date: item.source_date,
          verdict: item.verdict,
          report,
        }
      }
      case 'alert': {
        const alert = samePaper.flatMap((r) => r.alerts).find((a) => String(a.id) === item.source_id)
        return alert ? { kind: 'alert', key, alert } : { kind: 'missing', key, sourceType: 'alert' }
      }
      case 'report': {
        const report = samePaper.find((r) => String(r.id) === item.source_id)
        return report ? { kind: 'report', key, report } : { kind: 'missing', key, sourceType: 'report' }
      }
      case 'document': {
        for (const report of reports) {
          const doc = report.documents.find((d) => String(d.id) === item.source_id)
          if (doc) {
            const range =
              item.page === null && typeof item.start === 'number' && typeof item.end === 'number'
                ? { start: item.start, end: item.end }
                : null
            return { kind: 'document', key, doc, report, page: item.page, range }
          }
        }
        return { kind: 'missing', key, sourceType: 'document' }
      }
    }
  })
}

// A text that is really JSON (some sources hand back a record instead of prose): the parsed object or
// array, or null when it reads as ordinary text
export function parseJsonText(text: string): object | null {
  const trimmed = text.trim()
  if (!/^[[{]/.test(trimmed)) return null
  try {
    const value: unknown = JSON.parse(trimmed)
    return typeof value === 'object' && value !== null ? value : null
  } catch {
    return null
  }
}
