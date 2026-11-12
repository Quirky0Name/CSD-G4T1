import { describe, expect, it } from 'vitest'
import type { Alert, UserReport } from '../api'
import { latestCheck } from './latestCheck'

const PAPER = 'paper-1'

function alert(id: number, paperId = PAPER): Alert {
  return {
    id,
    paper_id: paperId,
    change_type: 'retraction',
    alert_severity: 'high',
    description: 'Retracted',
    recommendation: 'Check it',
    notice_doi: null,
    detected_at: '2026-11-01T00:00:00Z',
    status: 'new',
    status_changed_at: null,
  }
}

function report(id: number, createdAt: string, alerts: Alert[] = [], paperId = PAPER): UserReport {
  return {
    id,
    paper_id: paperId,
    status: 'assessed',
    created_at: createdAt,
    investigated_at: null,
    context_chunks: null,
    evaluation: null,
    recommendation: null,
    evaluated_at: null,
    change_summary: null,
    change_severity: null,
    impact_level: null,
    claims: null,
    alerts,
    documents: [],
  }
}

describe('latestCheck', () => {
  it('is null for a paper with no reports and no alerts', () => {
    const others = report(1, '2026-11-01T00:00:00Z', [alert(1, 'paper-2')], 'paper-2')
    expect(latestCheck(PAPER, [alert(1, 'paper-2')], [others])).toBeNull()
  })

  it('picks the newest report of the paper, whatever order they come in', () => {
    const older = report(1, '2026-10-01T00:00:00Z')
    const newer = report(2, '2026-11-01T00:00:00Z')
    const check = latestCheck(PAPER, [], [older, newer, report(3, '2026-12-01T00:00:00Z', [], 'paper-2')])
    expect(check?.report?.id).toBe(2)
  })

  it('counts the paper\'s reports and alerts, for the links to the full lists', () => {
    const inReport = alert(1)
    const check = latestCheck(PAPER, [inReport, alert(2), alert(3, 'paper-2')], [report(1, '2026-11-01T00:00:00Z', [inReport])])
    expect(check?.reportCount).toBe(1)
    expect(check?.alertCount).toBe(2)
  })

  it('keeps out of the unreported alerts any alert already in one of the paper\'s reports', () => {
    const inOlder = alert(1)
    const inNewer = alert(2)
    const waiting = alert(3)
    const check = latestCheck(
      PAPER,
      [inOlder, inNewer, waiting],
      [report(1, '2026-10-01T00:00:00Z', [inOlder]), report(2, '2026-11-01T00:00:00Z', [inNewer])],
    )
    expect(check?.unreportedAlerts.map((a) => a.id)).toEqual([3])
  })

  it('still is a check when a paper has alerts but no report yet', () => {
    const check = latestCheck(PAPER, [alert(1)], [])
    expect(check?.report).toBeNull()
    expect(check?.unreportedAlerts.map((a) => a.id)).toEqual([1])
  })
})
