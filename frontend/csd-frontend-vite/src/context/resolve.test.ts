import { describe, expect, it } from 'vitest'
import type { Alert, ContextChunk, UserDocument, UserReport } from '../api'
import { parseJsonText, resolveContext } from './resolve'

const PAPER = 'paper-1'

function alert(id: number): Alert {
  return {
    id,
    paper_id: PAPER,
    change_type: 'retraction',
    alert_severity: 'high',
    description: 'Retracted',
    recommendation: 'Stop citing it',
    notice_doi: null,
    detected_at: '2026-10-01T00:00:00Z',
    status: 'dismissed',
    status_changed_at: null,
  }
}

function doc(id: number, text: string | null = null): UserDocument {
  return {
    id,
    kind: 'notice',
    doi: `10.1/${id}`,
    crossref_status: 'ok',
    crossref_record: null,
    update_to_includes_paper: null,
    text_status: text ? 'ok' : 'not_indexed',
    text,
    text_truncated: false,
    pdf_status: 'skipped',
    pdf_source_url: null,
    created_at: '2026-10-01T00:00:00Z',
    pdf_fetched_at: null,
    links: null,
  }
}

function report(id: number, paperId: string, extra: Partial<UserReport> = {}): UserReport {
  return {
    id,
    paper_id: paperId,
    status: 'assessed',
    created_at: '2026-10-01T00:00:00Z',
    investigated_at: null,
    evaluation: null,
    recommendation: null,
    evaluated_at: null,
    change_summary: null,
    change_severity: null,
    impact_level: null,
    claims: null,
    context_chunks: null,
    alerts: [],
    documents: [],
    ...extra,
  }
}

describe('resolveContext', () => {
  const past = report(1, PAPER, { alerts: [alert(42)], documents: [doc(7, 'notice text')] })
  const other = report(9, 'paper-2', { alerts: [alert(43)], documents: [doc(8)] })
  const reports = [past, other]

  it('finds alerts and reports only in the same paper', () => {
    const entries = resolveContext(
      [
        { source_type: 'alert', source_id: '42', page: null },
        { source_type: 'alert', source_id: '43', page: null },
        { source_type: 'report', source_id: '1', page: null },
        { source_type: 'report', source_id: '9', page: null },
      ],
      reports,
      PAPER,
    )
    expect(entries.map((e) => e.kind)).toEqual(['alert', 'missing', 'report', 'missing'])
  })

  it('finds a document in any report, with its page or range', () => {
    const [paged, ranged, elsewhere, gone] = resolveContext(
      [
        { source_type: 'document', source_id: '7', page: 3 },
        { source_type: 'document', source_id: '7', page: null, start: 2800, end: 6000 },
        { source_type: 'document', source_id: '8', page: null },
        { source_type: 'document', source_id: '99', page: null },
      ],
      reports,
      PAPER,
    )
    expect(paged).toMatchObject({ kind: 'document', page: 3, range: null })
    expect(ranged).toMatchObject({ kind: 'document', page: null, range: { start: 2800, end: 6000 } })
    expect(elsewhere).toMatchObject({ kind: 'document', report: other })
    expect(gone).toEqual({ kind: 'missing', key: '3', sourceType: 'document' })
  })

  it('keeps feedback as sent, with its report when it is loaded', () => {
    const [found, notLoaded] = resolveContext(
      [
        { source_type: 'feedback', source_id: '9', page: null, paper_id: 'paper-2', paper_title: 'T', source_date: null, verdict: 'overstated' },
        { source_type: 'feedback', source_id: '77', page: null, paper_id: null, paper_title: null, source_date: null, verdict: 'fair' },
      ],
      reports,
      PAPER,
    )
    expect(found).toMatchObject({ kind: 'feedback', report: other, paperId: 'paper-2', verdict: 'overstated' })
    expect(notLoaded).toMatchObject({ kind: 'feedback', report: null, paperId: null })
  })

  it('keeps a profile item with the profile it carries, and drops one carrying none', () => {
    const profile = {
      occupation: 'PhD student',
      occupation_other: null,
      research_fields: ['Medicine'],
      research_interests: ['iron deficiency'],
      work_types: ['Clinical'],
      response_preferences: 'Keep it short.',
    }
    const entries = resolveContext(
      [
        { source_type: 'profile', source_id: 'example', page: null, profile },
        { source_type: 'profile', source_id: 'example', page: null } as unknown as ContextChunk,
        { source_type: 'profile', source_id: 'example', page: null, profile: 'PhD student' } as unknown as ContextChunk,
        { source_type: 'profile', source_id: 'example', page: null, profile: null } as unknown as ContextChunk,
        { source_type: 'alert', source_id: '42', page: null },
      ],
      reports,
      PAPER,
    )
    expect(entries).toEqual([
      { kind: 'profile', key: '0', profile },
      expect.objectContaining({ kind: 'alert', key: '4' }),
    ])
  })
})

describe('parseJsonText', () => {
  it('parses an object or array', () => {
    expect(parseJsonText('  {"a": 1}\n')).toEqual({ a: 1 })
    expect(parseJsonText('[1, 2]')).toEqual([1, 2])
  })

  it('leaves prose, scalars and broken JSON alone', () => {
    expect(parseJsonText('Retraction notice to ...')).toBeNull()
    expect(parseJsonText('"just a string"')).toBeNull()
    expect(parseJsonText('{"cut": "off')).toBeNull()
    expect(parseJsonText('[see the note]')).toBeNull()
  })
})
