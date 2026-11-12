// Dev mode's fake report, as the researcher sees it. This branch's Storage doesn't send claims or the
// change fields yet, so an assessed fake report gets the example's (fakeReportClaims.ts,
// fakeReportEvaluation.ts). A value the server did send always wins, so real claims replace these.
import type { Claim, Passage, UserReport } from '../api'
import { FAKE_REPORT_CLAIMS, type MockPassage } from './fakeReportClaims'
import { FAKE_CHANGE_SEVERITY, FAKE_CHANGE_SUMMARY, FAKE_IMPACT_LEVEL } from './fakeReportEvaluation'

// A fake report's PDFs are served from the public fake-report route, and nothing else's are
export function isFake(report: UserReport): boolean {
  return report.documents.some((doc) => doc.pdf_source_url?.includes('/public/fake-report/'))
}

export function withMockClaims(report: UserReport): UserReport {
  if (report.status !== 'assessed' || !isFake(report)) return report
  return {
    ...report,
    change_summary: report.change_summary ?? FAKE_CHANGE_SUMMARY,
    change_severity: report.change_severity ?? FAKE_CHANGE_SEVERITY,
    impact_level: report.impact_level ?? FAKE_IMPACT_LEVEL,
    claims: report.claims ?? FAKE_REPORT_CLAIMS.map((claim) => mockClaim(report, claim)),
  }
}

function mockClaim(report: UserReport, claim: (typeof FAKE_REPORT_CLAIMS)[number]): Claim {
  const passages = claim.passages.map((passage) => resolve(report, passage))
  return {
    id: claim.id,
    kind: claim.kind,
    text: claim.text,
    supported: passages.some((passage) => passage.verified === 'verbatim'),
    passages,
  }
}

// The DOI a passage names becomes this report's id; one the report doesn't have is found nowhere
function resolve(report: UserReport, passage: MockPassage): Passage {
  const base = { quote: passage.quote, page: passage.page }
  if (passage.target === 'document') {
    const doc = report.documents.find((d) => d.doi === passage.document_doi)
    if (doc) return { ...base, target: 'document', document_id: doc.id, alert_id: null, verified: passage.verified }
    return lost(passage)
  }
  if (passage.target === 'alert') {
    const alert = report.alerts.find((a) => a.notice_doi === passage.alert_notice_doi)
    if (alert) return { ...base, target: 'alert', document_id: null, alert_id: alert.id, verified: passage.verified }
    return lost(passage)
  }
  return { ...base, target: passage.target, document_id: null, alert_id: null, verified: passage.verified }
}

function lost(passage: MockPassage): Passage {
  return { target: null, document_id: null, alert_id: null, quote: passage.quote, page: null, verified: 'not_found' }
}
