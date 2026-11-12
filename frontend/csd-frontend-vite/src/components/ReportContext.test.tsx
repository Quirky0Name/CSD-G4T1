// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Alert, Profile, UserReport } from '../api'
import type { ContextEntry } from '../context/resolve'
import { ContextList, ContextPane } from './ReportContext'

// the PDF viewer needs a real canvas, and no entry here is a PDF page
vi.mock('../highlight/PdfPane', () => ({ default: () => null }))

afterEach(cleanup)

const alert: Alert = {
  id: 42,
  paper_id: 'paper-1',
  change_type: 'correction',
  alert_severity: 'low',
  description: 'Corrected',
  recommendation: 'Read the correction',
  notice_doi: null,
  detected_at: '2026-10-13T00:00:00Z',
  status: 'new',
  status_changed_at: null,
}

const profile: Profile = {
  occupation: 'Other',
  occupation_other: 'Hospital dietitian',
  research_fields: ['Medicine', 'Nursing'],
  research_interests: [],
  work_types: ['Clinical'],
  response_preferences: null,
}

const entries: ContextEntry[] = [
  { kind: 'alert', key: '0', alert },
  {
    kind: 'feedback',
    key: '1',
    reportId: '7',
    paperId: 'paper-1',
    paperTitle: 'Dose-response of iron supplementation on fatigue',
    date: '2026-10-13T00:00:00Z',
    verdict: 'overstated',
    report: null,
  },
  { kind: 'profile', key: '2', profile },
]

function Context() {
  const [selected, setSelected] = useState<ContextEntry | undefined>()
  return (
    <MemoryRouter>
      <ContextList entries={entries} selected={selected} onPick={setSelected} />
      {selected && <ContextPane entry={selected} seq={0} />}
    </MemoryRouter>
  )
}

describe('ContextList', () => {
  it('groups the past, the feedback and the profile, in that order', () => {
    render(<Context />)

    const groups = screen.getAllByRole('region')
    expect(groups.map((group) => group.getAttribute('aria-label'))).toEqual([
      "From this paper's past",
      'From your feedback',
      'From your profile',
    ])
    expect(within(groups[2]).getByRole('button', { name: /Your profile/ })).toBeTruthy()
  })
})

describe('ContextPane', () => {
  it("shows a profile's answered questions and leaves out the unanswered", () => {
    render(<Context />)

    fireEvent.click(screen.getByRole('button', { name: /Your profile/ }))

    const pane = screen.getByRole('heading', { level: 2, name: 'Your profile' }).closest('div')!.parentElement!
    const answers = within(pane)
    expect(answers.getByText('Occupation')).toBeTruthy()
    expect(answers.getByText('Hospital dietitian')).toBeTruthy()
    expect(answers.queryByText('Other')).toBeNull()
    expect(answers.getByText('Research fields')).toBeTruthy()
    expect(answers.getByText('Medicine, Nursing')).toBeTruthy()
    expect(answers.getByText('Type of work')).toBeTruthy()
    expect(answers.getByText('Clinical')).toBeTruthy()
    expect(answers.queryByText('Research interests')).toBeNull()
    expect(answers.queryByText('How reports are written')).toBeNull()
  })

  it('names the occupation itself when it is a listed one, and shows interests and preferences when given', () => {
    const full: Profile = {
      ...profile,
      occupation: 'PhD student',
      occupation_other: null,
      research_interests: ['iron deficiency', 'fatigue'],
      response_preferences: 'Keep it short.',
    }
    render(
      <MemoryRouter>
        <ContextPane entry={{ kind: 'profile', key: '0', profile: full }} seq={0} />
      </MemoryRouter>,
    )

    expect(screen.getByText('PhD student')).toBeTruthy()
    expect(screen.getByText('iron deficiency, fatigue')).toBeTruthy()
    expect(screen.getByText('How reports are written')).toBeTruthy()
    expect(screen.getByText('Keep it short.')).toBeTruthy()
  })

  it('shows the note on the report the feedback was given on, found by its source id', () => {
    const report: UserReport = {
      id: 7,
      paper_id: 'paper-1',
      status: 'assessed',
      created_at: '2026-10-12T00:00:00Z',
      investigated_at: null,
      evaluation: null,
      recommendation: null,
      evaluated_at: null,
      change_summary: 'A correction to the dosing table.',
      change_severity: 'medium',
      impact_level: 'low',
      claims: null,
      context_chunks: null,
      feedback: { verdict: 'overstated', note: 'The table change was cosmetic.', updated_at: '2026-10-13T00:00:00Z' },
      alerts: [],
      documents: [],
    }
    const entry = { ...entries[1], report } as ContextEntry
    render(
      <MemoryRouter>
        <ContextPane entry={entry} seq={0} />
      </MemoryRouter>,
    )

    expect(screen.getByText('Your note')).toBeTruthy()
    expect(screen.getByText('The table change was cosmetic.')).toBeTruthy()
  })

  it('leaves the note out when the feedback had none', () => {
    render(
      <MemoryRouter>
        <ContextPane entry={entries[1]} seq={0} />
      </MemoryRouter>,
    )

    expect(screen.queryByText('Your note')).toBeNull()
  })
})
