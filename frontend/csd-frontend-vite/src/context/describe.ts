// What a row and a pane header say about an item impact read beside a report
import type { FeedbackVerdict, UserDocument } from '../api'
import { ALERT_SEVERITY_LABEL, CHANGE_TYPE_LABEL, LEVEL_LABEL } from '../format'
import type { ContextEntry } from './resolve'

export const DOCUMENT_KIND: Record<UserDocument['kind'], string> = {
  notice: 'Notice',
  new_version: 'Newer version',
  current_version: 'Current copy',
  related: 'Related document',
}

export const VERDICT_LABEL: Record<FeedbackVerdict, string> = {
  fair: 'Fair',
  overstated: 'Overstated',
  understated: 'Understated',
}

export const MISSING_LABEL = { document: 'A document', alert: 'An alert', report: 'A report' } as const

export function shortDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' })
}

export function entryTitle(entry: ContextEntry): string {
  switch (entry.kind) {
    case 'document':
      return entry.doc.crossref_record?.title?.trim() || `${DOCUMENT_KIND[entry.doc.kind]} ${entry.doc.doi}`
    case 'alert':
      return `${CHANGE_TYPE_LABEL[entry.alert.change_type]} alert`
    case 'report':
      return `Report of ${shortDate(entry.report.created_at)}`
    case 'feedback':
      return entry.paperTitle ? `Your feedback on “${entry.paperTitle}”` : 'Your feedback on an earlier report'
    case 'missing':
      return `${MISSING_LABEL[entry.sourceType]} no longer here`
  }
}

// the facts under a row's title, empty ones left out
export function entryMeta(entry: ContextEntry): string[] {
  const facts = ((): string[] => {
    switch (entry.kind) {
      case 'document':
        return [
          DOCUMENT_KIND[entry.doc.kind],
          entry.page !== null ? `p. ${entry.page}` : entry.range ? 'part of its text' : '',
          entry.report ? `report of ${shortDate(entry.report.created_at)}` : '',
        ]
      case 'alert':
        return [`${ALERT_SEVERITY_LABEL[entry.alert.alert_severity]} severity`, `detected ${shortDate(entry.alert.detected_at)}`]
      case 'report':
        return [
          entry.report.change_severity ? `${LEVEL_LABEL[entry.report.change_severity]} severity` : '',
          entry.report.impact_level ? `${LEVEL_LABEL[entry.report.impact_level]} impact` : '',
        ]
      case 'feedback':
        return [`You said ${VERDICT_LABEL[entry.verdict].toLowerCase()}`, entry.date ? shortDate(entry.date) : '']
      case 'missing':
        return ['Removed since this report was written']
    }
  })()
  return facts.filter(Boolean)
}
