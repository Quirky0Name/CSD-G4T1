// Where a passage is shown: a PDF, a text, or nothing. The report page only asks this, so when Storage
// gains the researcher PDF endpoints (feat/claim-passages-backend), only this file changes.
import type { DocumentKind, Passage, UserDocument, UserReport } from '../api'
import { CHANGE_TYPE_LABEL } from '../format'
import { isFake } from '../mocks/fakeReport'

export type PassageSource =
  | { kind: 'pdf'; key: string; label: string; url: string }
  | { kind: 'text'; key: string; label: string; text: string }
  | { kind: 'none'; label: string }

// dev mode's public route for the example's PDFs (docs/CONTRACTS.md, GET /public/fake-report/{file})
export const FAKE_PDF_BASE = '/api/public/fake-report/'

const DOCUMENT_LABEL: Record<DocumentKind, string> = {
  notice: 'Notice',
  new_version: 'New version',
  current_version: 'Current copy',
  related: 'Related document',
}

const NOT_YET: PassageSource = { kind: 'none', label: 'Not available yet' }

export function sourceFor(report: UserReport, passage: Passage): PassageSource {
  const fake = isFake(report)
  switch (passage.target) {
    case 'draft':
      return fake ? pdf('draft', 'Your draft (example)', `${FAKE_PDF_BASE}draft.pdf`) : NOT_YET
    case 'paper':
      return fake ? pdf('paper', 'Paper', `${FAKE_PDF_BASE}paper.pdf`) : NOT_YET
    case 'document': {
      const doc = report.documents.find((d) => d.id === passage.document_id)
      return doc ? documentSource(doc, fake) : notFound()
    }
    case 'alert': {
      const alert = report.alerts.find((a) => a.id === passage.alert_id)
      if (!alert) return notFound()
      return { kind: 'text', key: `alert:${alert.id}`, label: `Alert: ${CHANGE_TYPE_LABEL[alert.change_type]}`, text: alert.description }
    }
    default:
      return notFound()
  }
}

export function documentLabel(doc: UserDocument): string {
  if (doc.kind === 'notice') return doc.crossref_record?.title ?? `Notice ${doc.doi}`
  return DOCUMENT_LABEL[doc.kind]
}

function documentSource(doc: UserDocument, fake: boolean): PassageSource {
  const label = documentLabel(doc)
  // only a fake report's PDFs can be read today: they're on the public route, the rest are behind a token
  if (fake && doc.pdf_status === 'ok' && doc.pdf_source_url?.startsWith(FAKE_PDF_BASE)) {
    return pdf(`document:${doc.id}`, label, doc.pdf_source_url)
  }
  if (doc.text) return { kind: 'text', key: `document:${doc.id}`, label, text: doc.text }
  return doc.kind === 'notice' ? { kind: 'none', label: `${label} has no text to show` } : NOT_YET
}

function pdf(key: string, label: string, url: string): PassageSource {
  return { kind: 'pdf', key, label, url }
}

function notFound(): PassageSource {
  return { kind: 'none', label: 'Not found in any source' }
}
