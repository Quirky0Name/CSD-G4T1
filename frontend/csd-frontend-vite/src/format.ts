import type { Alert, AlertStatus, ChangeType, Severity } from './api'

// Records keyed by the full union so a new API value is a compile error
// here, not a silent blank label.
export const SEVERITY_LABEL: Record<Severity, string> = {
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
  other: 'Other notice',
}

export const ALERT_STATUS_LABEL: Record<AlertStatus, string> = {
  new: 'New',
  acknowledged: 'Acknowledged',
  dismissed: 'Dismissed',
}

// high, then medium, then low (docs/CONTRACTS.md, GET /papers/{id}/alerts).
const SEVERITY_RANK: Record<Severity, number> = { high: 0, medium: 1, low: 2 }

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

// The paper's severity state (LOCAL_STORAGE_DB.md, "Retrieve paper severity
// state"): the highest severity among its unresolved alerts, or none.
export function highestNewSeverity(alerts: Alert[]): Severity | null {
  let highest: Severity | null = null
  for (const alert of alerts) {
    if (alert.status !== 'new') continue
    if (highest === null || SEVERITY_RANK[alert.severity] < SEVERITY_RANK[highest]) {
      highest = alert.severity
    }
  }
  return highest
}
