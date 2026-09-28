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

export type ReportStatus = 'investigating' | 'investigated' | 'assessed'
export type AssessmentLevel = 'none' | 'low' | 'medium' | 'high'
export type DocumentKind = 'notice' | 'new_version' | 'current_version'

// the Crossref record as Research Evaluation stored it (docs/CONTRACTS.md, GET /papers/{id}/reports)
export type CrossrefRecord = {
  doi?: string
  title?: string | null
  published?: string | null
  journal?: string | null
}

export type UserDocument = {
  id: number
  kind: DocumentKind
  doi: string
  crossref_status: 'ok' | 'not_found' | 'error'
  crossref_record: CrossrefRecord | null
  update_to_includes_paper: boolean | null
  text_status: 'ok' | 'not_indexed' | 'not_open_access' | 'error'
  text: string | null
  text_truncated: boolean
  pdf_status: 'skipped' | 'pending' | 'ok' | 'not_found'
  pdf_source_url: string | null
  created_at: string
  pdf_fetched_at: string | null
}

// a report comes with its alerts and documents, so this one call loads everything the details show
export async function listReports(paperId: string): Promise<UserReport[]> {
  const res = await storage.get<{ reports: UserReport[] }>(`/papers/${paperId}/reports`)
  return res.data.reports
}

export type UserReport = {
  id: number
  paper_id: string
  status: ReportStatus
  created_at: string
  investigated_at: string | null
  // not in GET /papers/{id}/reports today (only the internal report API has them), so often absent
  change_summary?: string | null
  change_severity?: AssessmentLevel | null
  impact_level?: AssessmentLevel | null
  evaluation: string | null
  recommendation: string | null
  evaluated_at: string | null
  alerts: Alert[]
  documents: UserDocument[]
}

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
  headers: { Authorization: `Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEiLCJleHAiOjE3OTMxNzA3NDN9.xMwctT54euBxMseX5UtB56zpTa-CpRV4l9ubthstrU8` },
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

export async function deletePaper(paperId: string): Promise<void> {
  await storage.delete(`/dev/papers/${paperId}`)
}