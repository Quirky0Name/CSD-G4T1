// Typed client for Storage Management and Updating (docs/CONTRACTS.md,
// "Frontend ↔ Storage Management" and "Updating"). Both are reached through
// the Vite dev proxy (vite.config.ts), which is also where their base URLs
// live for real deployments.
import axios from 'axios'

export type Severity = 'high' | 'medium' | 'low'
export type AlertStatus = 'new' | 'acknowledged' | 'dismissed'
export type ChangeType =
  | 'retraction'
  | 'correction'
  | 'erratum'
  | 'expression_of_concern'
  | 'doaj_delisting'
  | 'other'

export type Paper = {
  id: string
  folder_id: string | null
  doi: string | null
  openalex_id: string | null
  title: string | null
  journal: string | null
  issn: string | null
  publication_year: number | null
  file_available: boolean
  created_at: string
}

export type Alert = {
  id: number
  paper_id: string
  change_type: ChangeType
  severity: Severity
  description: string
  recommendation: string
  notice_doi: string | null
  detected_at: string
  status: AlertStatus
  status_changed_at: string | null
}

export type AlertNote = {
  text: string
  created_at: string
}

export type ResearchPaper = {
  id: string
  folder_id: string | null
  filename: string
  uploaded_at: string
}

export type PollSummary = {
  run_id: number
  trigger: string
  paper_id: string | null
  started_at: string
  finished_at: string
  stored: { paper_id: string; doi: string; snapshot_id: number }[]
  skipped_no_doi: string[]
  source_errors: { paper_id: string; doi: string; crossref: string; openalex: string }[]
  store_errors: { paper_id: string; doi: string; reason: string }[]
  nudged: string[]
  nudge_error: { paper_ids: string[]; reason: string } | null
}

const storage = axios.create({
  baseURL: '/api',
  headers: { Authorization: `Bearer ${import.meta.env.VITE_DEMO_TOKEN}` },
})

// No token in sprint 1: docs/CONTRACTS.md, "Auth" — POST /run-poll takes
// the user's JWT once User Management exists (CG-99).
const updating = axios.create({ baseURL: '/updating' })

export async function listPapers(): Promise<Paper[]> {
  const res = await storage.get<{ papers: Paper[] }>('/papers')
  return res.data.papers
}

export async function uploadPaperPdf(file: File): Promise<Paper> {
  const body = new FormData()
  body.append('file', file)
  const res = await storage.post<Paper>('/papers', body)
  return res.data
}

export async function trackPaperByDoi(doi: string): Promise<Paper> {
  const res = await storage.post<Paper>('/papers', { doi })
  return res.data
}

export async function listAlerts(paperId: string, includeDismissed = false): Promise<Alert[]> {
  const res = await storage.get<{ alerts: Alert[] }>(`/papers/${paperId}/alerts`, {
    params: includeDismissed ? { include_dismissed: true } : undefined,
  })
  return res.data.alerts
}

export async function setAlertStatus(
  alertId: number,
  status: Extract<AlertStatus, 'acknowledged' | 'dismissed'>,
): Promise<Alert> {
  const res = await storage.patch<Alert>(`/alerts/${alertId}`, { status })
  return res.data
}

export async function listAlertNotes(alertId: number): Promise<AlertNote[]> {
  const res = await storage.get<{ notes: AlertNote[] }>(`/alerts/${alertId}/notes`)
  return res.data.notes
}

export async function addAlertNote(alertId: number, text: string): Promise<AlertNote> {
  const res = await storage.post<AlertNote>(`/alerts/${alertId}/notes`, { text })
  return res.data
}

export async function runPoll(paperId?: string): Promise<PollSummary> {
  const res = await updating.post<PollSummary>('/run-poll', null, {
    params: paperId ? { paper_id: paperId } : undefined,
  })
  return res.data
}

export async function getResearchPaper(): Promise<ResearchPaper | null> {
  try {
    const res = await storage.get<ResearchPaper>('/research-paper')
    return res.data
  } catch (err) {
    if (axios.isAxiosError(err) && err.response?.status === 404) return null
    throw err
  }
}

export async function uploadResearchPaper(file: File): Promise<ResearchPaper> {
  const body = new FormData()
  body.append('file', file)
  const res = await storage.post<ResearchPaper>('/research-paper', body)
  return res.data
}

export async function deleteResearchPaper(): Promise<void> {
  await storage.delete('/research-paper')
}

// Spring's ProblemDetail and FastAPI's HTTPException both put the reason in
// `detail` (docs/CONTRACTS.md errors sections).
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    if (err.response?.status === 401) {
      return 'Not authorized — check VITE_DEMO_TOKEN in .env.local.'
    }
    const detail: unknown = err.response?.data?.detail
    if (typeof detail === 'string') return detail
    if (err.response) return `Request failed (${err.response.status})`
    return err.message
  }
  return err instanceof Error ? err.message : 'Something went wrong'
}
