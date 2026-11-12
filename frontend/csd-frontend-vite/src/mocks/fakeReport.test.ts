import { describe, expect, it } from 'vitest'
import { toUserReport, type Alert, type Claim, type RawReport, type UserDocument, type UserReport } from '../api'
import { sourceFor } from '../highlight/sources'
import { isFake, withMockClaims } from './fakeReport'
import { CURRENT_COPY, EOC_NOTICE, FAKE_REPORT_CLAIMS, NEW_VERSION, RETRACTION_NOTICE } from './fakeReportClaims'

function doc(id: number, kind: UserDocument['kind'], doi: string, extra: Partial<UserDocument> = {}): UserDocument {
  return {
    id, kind, doi,
    crossref_status: 'ok', crossref_record: null, update_to_includes_paper: null,
    text_status: 'not_open_access', text: null, text_truncated: false,
    pdf_status: kind === 'notice' ? 'skipped' : 'ok',
    pdf_source_url: kind === 'notice' ? null : `/api/public/fake-report/${kind === 'new_version' ? 'new-version' : 'current-version'}.pdf`,
    created_at: '2026-11-07T08:00:00Z', pdf_fetched_at: null, links: null,
    ...extra,
  }
}

function alert(id: number, notice: string, change: Alert['change_type']): Alert {
  return {
    id, paper_id: 'p', change_type: change, alert_severity: 'high',
    description: change === 'expression_of_concern'
      ? 'The publisher issued an expression of concern about this paper (notice 10.9999/fake.2019.011e).'
      : 'This paper has been retracted.',
    recommendation: '...', notice_doi: notice, detected_at: '2026-11-07T08:00:00Z', status: 'new', status_changed_at: null,
  }
}

// a fake report as this branch's Storage sends it: no claims, no change fields, its own ids
function raw(status: UserReport['status'] = 'assessed', ids = 100): RawReport {
  return {
    id: 1, paper_id: 'p', status, created_at: '2026-11-07T08:00:00Z', investigated_at: null,
    evaluation: 'e', recommendation: 'r', evaluated_at: null,
    alerts: [alert(ids + 1, EOC_NOTICE, 'expression_of_concern'), alert(ids + 2, RETRACTION_NOTICE, 'retraction')],
    documents: [
      doc(ids + 10, 'notice', EOC_NOTICE, { text: 'Expression of concern text', text_status: 'ok' }),
      doc(ids + 11, 'notice', RETRACTION_NOTICE, { text: 'Retraction text', text_status: 'ok', crossref_record: { title: 'Retraction notice' } }),
      doc(ids + 12, 'new_version', NEW_VERSION),
      doc(ids + 13, 'current_version', CURRENT_COPY),
    ],
  }
}

const PASSAGE_KEYS = ['alert_id', 'document_id', 'page', 'quote', 'target', 'verified']

describe('toUserReport', () => {
  it('sets the fields this branch leaves out to null', () => {
    const report = toUserReport(raw())
    expect(report.change_summary).toBeNull()
    expect(report.change_severity).toBeNull()
    expect(report.impact_level).toBeNull()
    expect(report.claims).toBeNull()
  })

  it('keeps the fields when the server sends them', () => {
    const claims: Claim[] = [{ id: 'change-0', kind: 'change', text: 't', supported: false, passages: [] }]
    const report = toUserReport({ ...raw(), change_summary: 's', change_severity: 'high', impact_level: 'none', claims })
    expect(report).toMatchObject({ change_summary: 's', change_severity: 'high', impact_level: 'none', claims })
  })
})

describe('withMockClaims', () => {
  it("gives an assessed fake report the fixture's claims and change fields", () => {
    const report = withMockClaims(toUserReport(raw()))
    expect(report.claims?.map((c) => c.id)).toEqual(FAKE_REPORT_CLAIMS.map((c) => c.id))
    expect(report.claims).toHaveLength(13)
    expect(report.change_severity).toBe('medium')
    expect(report.impact_level).toBe('low')
    expect(report.change_summary).toMatch(/^The paper was first flagged with an expression of concern/)
  })

  it("points every document and alert at this report's own rows, in the contract's shape", () => {
    for (const ids of [100, 500]) {
      const report = withMockClaims(toUserReport(raw('assessed', ids)))
      const docIds = new Set(report.documents.map((d) => d.id))
      const alertIds = new Set(report.alerts.map((a) => a.id))
      for (const passage of report.claims!.flatMap((c) => c.passages)) {
        expect(Object.keys(passage).sort()).toEqual(PASSAGE_KEYS)
        if (passage.target === 'document') expect(docIds.has(passage.document_id!)).toBe(true)
        else expect(passage.document_id).toBeNull()
        if (passage.target === 'alert') expect(alertIds.has(passage.alert_id!)).toBe(true)
        else expect(passage.alert_id).toBeNull()
      }
    }
  })

  it('marks a claim supported exactly when a passage is verbatim', () => {
    const claims = withMockClaims(toUserReport(raw())).claims!
    for (const claim of claims) {
      expect(claim.supported).toBe(claim.passages.some((p) => p.verified === 'verbatim'))
    }
    expect(claims.filter((c) => !c.supported).map((c) => c.id)).toEqual(['impact-2', 'impact-3', 'action-2'])
  })

  it('leaves a real report, an unassessed fake one, and one with claims of its own alone', () => {
    const real = toUserReport({ ...raw(), documents: raw().documents.map((d) => ({ ...d, pdf_source_url: d.pdf_source_url && 'https://example.org/x.pdf' })) })
    expect(isFake(real)).toBe(false)
    expect(withMockClaims(real)).toBe(real)

    const investigated = toUserReport(raw('investigated'))
    expect(withMockClaims(investigated)).toBe(investigated)

    const own: Claim[] = [{ id: 'change-0', kind: 'change', text: 'from the server', supported: false, passages: [] }]
    expect(withMockClaims(toUserReport({ ...raw(), claims: own })).claims).toBe(own)
  })

  it('turns a DOI the report lacks into a passage found nowhere', () => {
    const report = toUserReport(raw())
    report.documents = report.documents.filter((d) => d.doi !== NEW_VERSION)
    const passage = withMockClaims(report).claims!.find((c) => c.id === 'change-1')!.passages[1]
    expect(passage).toEqual({
      target: null, document_id: null, alert_id: null,
      quote: 'This corrected version replaces the retracted article', page: null, verified: 'not_found',
    })
  })
})

describe('sourceFor', () => {
  const report = withMockClaims(toUserReport(raw()))
  const passage = (id: string, index = 0) => report.claims!.find((c) => c.id === id)!.passages[index]

  it("reads a fake report's draft and paper from the public example route", () => {
    expect(sourceFor(report, passage('impact-0', 0))).toEqual({ kind: 'pdf', key: 'draft', label: 'Your draft (example)', url: '/api/public/fake-report/draft.pdf' })
    expect(sourceFor(report, passage('impact-0', 1))).toEqual({ kind: 'pdf', key: 'paper', label: 'Paper', url: '/api/public/fake-report/paper.pdf' })
  })

  it("reads a document's PDF from its pdf_source_url, labelled by kind", () => {
    expect(sourceFor(report, passage('change-1', 1))).toEqual({ kind: 'pdf', key: 'document:112', label: 'New version', url: '/api/public/fake-report/new-version.pdf' })
    expect(sourceFor(report, passage('change-3'))).toMatchObject({ kind: 'pdf', label: 'Current copy', url: '/api/public/fake-report/current-version.pdf' })
  })

  it("shows a notice's text, labelled by its title or DOI", () => {
    expect(sourceFor(report, passage('change-1', 0))).toEqual({ kind: 'text', key: 'document:111', label: 'Retraction notice', text: 'Retraction text' })
    expect(sourceFor(report, passage('change-0', 0))).toMatchObject({ kind: 'text', label: `Notice ${EOC_NOTICE}` })
  })

  it("shows an alert's description", () => {
    expect(sourceFor(report, passage('change-5'))).toEqual({
      kind: 'text', key: 'alert:101', label: 'Alert: Expression of concern',
      text: 'The publisher issued an expression of concern about this paper (notice 10.9999/fake.2019.011e).',
    })
  })

  it('has nothing for a passage found nowhere, or an id not in the report', () => {
    const lost = { target: null, document_id: null, alert_id: null, quote: 'q', page: null, verified: 'not_found' } as const
    expect(sourceFor(report, lost)).toEqual({ kind: 'none', label: 'Not found in any source' })
    expect(sourceFor(report, { ...lost, target: 'document', document_id: 9999 })).toEqual({ kind: 'none', label: 'Not found in any source' })
  })

  it("has no draft or paper for a report that isn't fake", () => {
    const real = { ...report, documents: report.documents.map((d) => ({ ...d, pdf_source_url: d.pdf_source_url && 'https://example.org/x.pdf' })) }
    expect(sourceFor(real, passage('impact-0', 0))).toEqual({ kind: 'none', label: 'Not available yet' })
    expect(sourceFor(real, passage('impact-0', 1))).toEqual({ kind: 'none', label: 'Not available yet' })
  })
})
