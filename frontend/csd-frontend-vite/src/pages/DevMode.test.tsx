// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../api'
import DevMode from './DevMode'

vi.mock('../auth', () => ({ useAuth: () => ({ logout: vi.fn() }) }))
vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
  listAccounts: vi.fn(),
  listOwnerPapers: vi.fn(),
  listPaperSnapshots: vi.fn(),
  listPaperAlerts: vi.fn(),
  listPaperReports: vi.fn(),
  addFakeReport: vi.fn(),
}))

const paper: api.AdminPaper = {
  id: 'paper-1',
  owner_id: 'owner-1',
  project_id: null,
  doi: null,
  openalex_id: null,
  title: 'Dose-response of iron supplementation on fatigue',
  journal: 'J. Fake Nutrition',
  issn: null,
  publication_year: 2019,
  has_file: true,
  created_at: '2026-11-12T00:00:00Z',
  latest_snapshot: null,
} as unknown as api.AdminPaper

beforeEach(() => {
  vi.mocked(api.listAccounts).mockResolvedValue([{ id: 'owner-1', email: 'researcher@example.com' } as api.Account])
  vi.mocked(api.listOwnerPapers).mockResolvedValue([paper])
  vi.mocked(api.listPaperSnapshots).mockResolvedValue([])
  vi.mocked(api.listPaperAlerts).mockResolvedValue([])
  vi.mocked(api.listPaperReports).mockResolvedValue([])
  vi.mocked(api.addFakeReport).mockResolvedValue({} as api.UserReport)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

async function pickThePaper() {
  render(<DevMode />)
  await screen.findByRole('option', { name: 'researcher@example.com' })
  fireEvent.change(screen.getByLabelText('Researcher'), { target: { value: 'owner-1' } })
  await screen.findByRole('option', { name: /Dose-response of iron supplementation/ })
  fireEvent.change(screen.getByLabelText('Paper'), { target: { value: 'paper-1' } })
}

describe('DevMode, Add fake report', () => {
  it('tells the admin an assessed fake report brings an earlier report, feedback and a profile', async () => {
    await pickThePaper()

    fireEvent.click(screen.getByRole('button', { name: 'Add fake report' }))

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe(
        'Fake report added, with an earlier report, feedback and a profile for its Context tab. The researcher gets the toast within 30 s.',
      ),
    )
    expect(api.addFakeReport).toHaveBeenCalledWith('paper-1', 'assessed')
  })

  it('says a fake report left before assessment brings no toast', async () => {
    await pickThePaper()

    fireEvent.click(screen.getByRole('radio', { name: 'Investigated' }))
    fireEvent.click(screen.getByRole('button', { name: 'Add fake report' }))

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe(
        'Fake report added, stuck at investigated. No toast for this one.',
      ),
    )
    expect(api.addFakeReport).toHaveBeenCalledWith('paper-1', 'investigated')
  })
})
