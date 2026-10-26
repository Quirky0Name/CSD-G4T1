// Typed client for Storage Management and Updating (docs/CONTRACTS.md,
// "Frontend ↔ Storage Management" and "Updating"). Both are reached through
// the Vite dev proxy (vite.config.ts), which is also where their base URLs
// live for real deployments.
import axios from 'axios'
import { getToken, notifyUnauthorized, type Session } from './session'

export type AlertSeverity = 'high' | 'medium' | 'low'
export type AlertStatus = 'new' | 'acknowledged' | 'dismissed'
export type ChangeType =
  | 'retraction'
  | 'correction'
  | 'erratum'
  | 'expression_of_concern'
  | 'doaj_delisting'
  | 'citation_jump'
  | 'other'

export type ReportStatus = 'investigating' | 'investigated' | 'assessed'
export type AssessmentLevel = 'none' | 'low' | 'medium' | 'high'
export type DocumentKind = 'notice' | 'new_version' | 'current_version' | 'related'

// the Crossref record as Research Evaluation stored it (docs/CONTRACTS.md, GET /papers/{id}/reports)
export type CrossrefRecord = {
  doi?: string
  title?: string | null
  published?: string | null
  journal?: string | null
}

// a DOI or URL a document points at (docs/CONTRACTS.md, POST /internal/documents); exactly one of doi and url is set
export type DocumentLink = {
  doi: string | null
  url: string | null
  role: string | null
  source: 'crossref' | 'europepmc_xml' | 'europepmc_text'
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
  links: DocumentLink[] | null
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
  project_id: string | null
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
  alert_severity: AlertSeverity
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

export type Draft = {
  id: string
  project_id: string | null
  filename: string
  uploaded_at: string
}

export type PollSummary = {
  poll_id: number
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

const storage = axios.create({ baseURL: '/api' })
const updating = axios.create({ baseURL: '/updating' })
// User Management; its register and login take no token
const authApi = axios.create({ baseURL: '/auth' })

// every call to Storage and Updating carries the signed-in user's token
for (const client of [storage, updating]) {
  client.interceptors.request.use((config) => {
    const token = getToken()
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })
  client.interceptors.response.use(undefined, (err: unknown) => {
    if (axios.isAxiosError(err) && err.response?.status === 401) notifyUnauthorized()
    return Promise.reject(err)
  })
}

export async function login(email: string, password: string): Promise<Session> {
  const res = await authApi.post<Session>('/login', { email, password })
  return res.data
}

export async function register(email: string, password: string): Promise<Session> {
  const res = await authApi.post<Session>('/register', { email, password })
  return res.data
}

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

export async function getDraft(): Promise<Draft | null> {
  try {
    const res = await storage.get<Draft>('/draft')
    return res.data
  } catch (err) {
    if (axios.isAxiosError(err) && err.response?.status === 404) return null
    throw err
  }
}

export async function uploadDraft(file: File): Promise<Draft> {
  const body = new FormData()
  body.append('file', file)
  const res = await storage.post<Draft>('/draft', body)
  return res.data
}

export async function deleteDraft(): Promise<void> {
  await storage.delete('/draft')
}

// Spring's ProblemDetail and FastAPI's HTTPException both put the reason in
// `detail` (docs/CONTRACTS.md errors sections).
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const detail: unknown = err.response?.data?.detail
    if (typeof detail === 'string') return detail
    if (err.response?.status === 401) return 'Please sign in again.'
    if (err.response) return `Request failed (${err.response.status})`
    return err.message
  }
  return err instanceof Error ? err.message : 'Something went wrong'
}

export async function deletePaper(paperId: string): Promise<void> {
  await storage.delete(`/dev/papers/${paperId}`)
}