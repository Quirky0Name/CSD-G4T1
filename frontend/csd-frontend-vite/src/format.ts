import type { Alert, AlertStatus, AssessmentLevel, ChangeType, AlertSeverity, ReportStatus } from './api'

// Records keyed by the full union so a new API value is a compile error
// here, not a silent blank label.
export const ALERT_SEVERITY_LABEL: Record<AlertSeverity, string> = {
  high: 'High',
  medium: 'Medium',
  low: 'Low',
}

export const CHANGE_TYPE_LABEL: Record<ChangeType, string> = {
  retraction: 'Retraction',
  correction: 'Correction',
  erratum: 'Erratum',
  expression_of_concern: 'Expression of concern',
  doaj_delisting: 'DOAJ delisting',
  citation_jump: 'Citation jump',
  other: 'Other notice',
}

export const ALERT_STATUS_LABEL: Record<AlertStatus, string> = {
  new: 'New',
  acknowledged: 'Acknowledged',
  dismissed: 'Dismissed',
}

export const REPORT_STATUS_LABEL: Record<ReportStatus, string> = {
  investigating: 'Investigating',
  investigated: 'Investigated',
  assessed: 'Assessed',
}

export const REPORT_STATUS_STYLES: Record<ReportStatus, string> = {
  investigating: 'bg-yellow-400/10 text-yellow-500 inset-ring-yellow-400/20',
  investigated: 'bg-indigo-400/10 text-indigo-400 inset-ring-indigo-400/20',
  assessed: 'bg-emerald-400/10 text-emerald-400 inset-ring-emerald-400/20',
}

export const LEVEL_STYLES: Record<AssessmentLevel, string> = {
  high: 'bg-red-400/10 text-red-400 inset-ring-red-400/20',
  medium: 'bg-yellow-400/10 text-yellow-500 inset-ring-yellow-400/20',
  low: 'bg-gray-400/10 text-gray-400 inset-ring-gray-400/20',
  none: 'bg-gray-400/10 text-gray-400 inset-ring-gray-400/20',
}

// high, then medium, then low (docs/CONTRACTS.md, GET /papers/{id}/alerts).
const ALERT_SEVERITY_RANK: Record<AlertSeverity, number> = { high: 0, medium: 1, low: 2 }

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

// GET /papers/{id}/alerts' order (docs/CONTRACTS.md): newest first by
// detected_at, ties broken by alert severity then by id descending. Alert lists
// from different papers are each ordered this way already; combining them
// needs the same comparator to stay newest-first as one list.
export function compareAlerts(a: Alert, b: Alert): number {
  if (a.detected_at !== b.detected_at) return a.detected_at < b.detected_at ? 1 : -1
  if (a.alert_severity !== b.alert_severity) return ALERT_SEVERITY_RANK[a.alert_severity] - ALERT_SEVERITY_RANK[b.alert_severity]
  return b.id - a.id
}

// The paper's alert severity state (docs/CONTRACTS.md, the frontend feature
// table): the highest alert severity among its unresolved alerts, or none.
export function highestNewAlertSeverity(alerts: Alert[]): AlertSeverity | null {
  let highest: AlertSeverity | null = null
  for (const alert of alerts) {
    if (alert.status !== 'new') continue
    if (highest === null || ALERT_SEVERITY_RANK[alert.alert_severity] < ALERT_SEVERITY_RANK[highest]) {
      highest = alert.alert_severity
    }
  }
  return highest
}

export const LEVEL_LABEL: Record<AssessmentLevel, string> = {
  high: 'High',
  medium: 'Medium',
  low: 'Low',
  none: 'None',
}
