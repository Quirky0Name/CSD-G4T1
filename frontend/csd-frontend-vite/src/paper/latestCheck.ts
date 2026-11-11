import type { Alert, UserReport } from '../api'

export type LatestCheck = {
  // null while the paper's alerts haven't joined a report
  report: UserReport | null
  // alerts in none of the paper's reports: an alert is listed a moment before its report (docs/CONTRACTS.md)
  unreportedAlerts: Alert[]
  reportCount: number
  alertCount: number
}

// A paper's most recent background check, its newest report and any alerts not in a report yet.
// Null for a paper with neither, which has nothing to show.
export function latestCheck(paperId: string, alerts: Alert[], reports: UserReport[]): LatestCheck | null {
  const paperAlerts = alerts.filter((alert) => alert.paper_id === paperId)
  const paperReports = reports
    .filter((report) => report.paper_id === paperId)
    .toSorted((a, b) => b.created_at.localeCompare(a.created_at))
  if (paperAlerts.length === 0 && paperReports.length === 0) return null

  const reported = new Set(paperReports.flatMap((report) => report.alerts.map((alert) => alert.id)))
  return {
    report: paperReports[0] ?? null,
    unreportedAlerts: paperAlerts.filter((alert) => !reported.has(alert.id)),
    reportCount: paperReports.length,
    alertCount: paperAlerts.length,
  }
}
