// Typed client for Storage Management and Updating (docs/CONTRACTS.md,
// "Frontend ↔ Storage Management" and "Updating"). Both are reached through
// the Vite dev proxy (vite.config.ts), which is also where their base URLs
// live for real deployments.
import axios from 'axios'
import { withMockClaims } from './mocks/fakeReport'
import { withAuth } from './authRetry'
import { browserLock, createRefresher } from './refresh'
import { getToken, notifyUnauthorized, saveSession, type Session } from './session'

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

// a report comes with its alerts and documents, so this one call loads everything the details show.
// Each is normalised to the contract, and a dev mode fake report gets the example's claims
// (src/mocks/fakeReport.ts), since this branch's Storage doesn't send claims yet.
export async function listReports(paperId: string): Promise<UserReport[]> {
  const res = await storage.get<{ reports: RawReport[] }>(`/papers/${paperId}/reports`)
  return res.data.reports.map((raw) => withMockClaims(toUserReport(raw)))
}

// The report as docs/CONTRACTS.md on feat/claim-passages-backend gives it ("GET /papers/{id}/reports")
export type UserReport = {
  id: number
  paper_id: string
  status: ReportStatus
  created_at: string
  investigated_at: string | null
  evaluation: string | null
  recommendation: string | null
  evaluated_at: string | null
  change_summary: string | null
  change_severity: AssessmentLevel | null
  // null when change_severity is 'none'
  impact_level: AssessmentLevel | null
  // null until assessed, and on reports assessed before claims existed
  claims: Claim[] | null
  // what impact read beside this report, in prompt order: [] when it found no past, null before
  // V14 or until assessed
  context_chunks: ContextChunk[] | null
  alerts: Alert[]
  documents: UserDocument[]
}

// One chunk impact added to its prompt (docs/CONTRACTS.md, GET /papers/{id}/reports, `context_chunks`).
// The chunk's text isn't stored: an alert, report or document is found again by its id.
export type ContextChunk = PastChunk | FeedbackChunk

export type PastChunk = {
  source_type: 'document' | 'alert' | 'report'
  // the document's, alert's or report's own id
  source_id: string
  // the PDF page it came from; null when it isn't from a PDF page
  page: number | null
  // both or neither: a page-less document's range in its `text`, when the text was cut into chunks
  start?: number
  end?: number
}

export type FeedbackVerdict = 'fair' | 'overstated' | 'understated'

export type FeedbackChunk = {
  source_type: 'feedback'
  // the report the feedback is on
  source_id: string
  page: null
  paper_id: string | null
  paper_title: string | null
  source_date: string | null
  verdict: FeedbackVerdict
}

const CONTEXT_SOURCES = new Set(['document', 'alert', 'report', 'feedback'])

// What this branch's Storage sends: the same, without the fields the claims and RAG work added
type Added = 'change_summary' | 'change_severity' | 'impact_level' | 'claims' | 'context_chunks'
export type RawReport = Omit<UserReport, Added> & Partial<Pick<UserReport, Added>>

// Fields the server leaves out become null, so nothing downstream checks for undefined. A context
// item of a source type this client doesn't know is dropped rather than shown wrong.
export function toUserReport(raw: RawReport): UserReport {
  return {
    ...raw,
    change_summary: raw.change_summary ?? null,
    change_severity: raw.change_severity ?? null,
    impact_level: raw.impact_level ?? null,
    claims: raw.claims ?? null,
    context_chunks: raw.context_chunks?.filter((item) => CONTEXT_SOURCES.has(item.source_type)) ?? null,
  }
}

export type ClaimKind = 'change' | 'impact' | 'action'

// One factual statement of the evaluation and the quotes behind it (docs/CONTRACTS.md, "Claim").
// text and every quote are model output: render them as text, never as HTML.
export type Claim = {
  // `<kind>-<index in that kind>`, unique in the report
  id: string
  kind: ClaimKind
  text: string
  // true exactly when a passage is 'verbatim'
  supported: boolean
  passages: Passage[]
}

export type PassageTarget = 'paper' | 'draft' | 'document' | 'alert'
export type PassageVerified = 'verbatim' | 'not_found' | 'no_text'

export type Passage = {
  // null: found nowhere, and its source named no text
  target: PassageTarget | null
  // set only when target is 'document'
  document_id: number | null
  // set only when target is 'alert'
  alert_id: number | null
  quote: string
  // 1-based page the quote starts on, when found in a PDF
  page: number | null
  verified: PassageVerified
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

export type Draft = {
  id: string
  project_id: string | null
  filename: string
  uploaded_at: string
}

// a researcher's answers to the questionnaire (docs/CONTRACTS.md, the profile body); the lists are
// always arrays, the rest a string or null
export type Profile = {
  occupation: string | null
  occupation_other: string | null
  research_fields: string[]
  research_interests: string[]
  work_types: string[]
  response_preferences: string | null
}

// a channel's minimum level for alerts or reports (docs/CONTRACTS.md, GET /auth/me/notifications);
// null is never
export type Threshold = 'low' | 'medium' | 'high' | null

export type ChannelThresholds = {
  min_alert_severity: Threshold
  min_change_severity: Threshold
  min_impact_level: Threshold
}

export type NotificationPreferences = {
  // address is the account email, read-only
  email: ChannelThresholds & { enabled: boolean; address: string }
  telegram: ChannelThresholds & { enabled: boolean; chat_id: number | null }
}

export type ChannelOutcome = 'sent' | 'failed' | 'disabled' | 'not_configured'

export type TestOutcomes = { channels: { email: ChannelOutcome; telegram: ChannelOutcome } }

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
// User Management's admin routes, which do take a token
const adminAuth = axios.create({ baseURL: '/auth/admin' })

// User Management's projects
const projectsApi = axios.create({ baseURL: '/projects' })
// User Management's routes for the signed-in researcher's own account, which take a token
const meApi = axios.create({ baseURL: '/auth/me' })

// A new access token from the refresh token's cookie (POST /auth/refresh), saved as the session.
// One at a time across tabs, and callers during one share it (refresh.ts).
export const refreshSession = createRefresher(async (): Promise<Session> => {
  const res = await authApi.post<Session>('/refresh')
  saveSession(res.data)
  return res.data
}, browserLock)

// Every call to Storage, Updating, the projects and the account carries the signed-in user's token.
// A 401 renews it with one refresh and retries the request once; a failed refresh signs out.
for (const client of [storage, updating, adminAuth, projectsApi, meApi]) {
  withAuth(client, { getToken, refresh: refreshSession, onUnauthorized: notifyUnauthorized })
}

// sign out: the refresh token stops working and its cookie is cleared (always 204)
export async function logout(): Promise<void> {
  await authApi.post('/logout')
}

export async function login(email: string, password: string): Promise<Session> {
  const res = await authApi.post<Session>('/login', { email, password })
  return res.data
}

// one request makes the account and saves its questionnaire (docs/CONTRACTS.md, POST /auth/register)
export async function register(email: string, password: string, profile: Profile): Promise<Session> {
  const res = await authApi.post<Session>('/register', { email, password, profile })
  return res.data
}

// the researcher's profile; null when they have none yet (404), as getDraft does for a draft
export async function getProfile(): Promise<Profile | null> {
  try {
    const res = await meApi.get<Profile>('/profile')
    return res.data
  } catch (err) {
    if (isNotFound(err)) return null
    throw err
  }
}

// replaces every answer, creating the profile when there is none; returns it as stored
export async function saveProfile(profile: Profile): Promise<Profile> {
  const res = await meApi.put<Profile>('/profile', profile)
  return res.data
}

// the researcher's outside notification preferences: email and Telegram, each with its thresholds
export async function getNotifications(): Promise<NotificationPreferences> {
  const res = await meApi.get<NotificationPreferences>('/notifications')
  return res.data
}

// each PUT replaces one whole channel and answers both, as stored
export async function saveEmailNotifications(body: ChannelThresholds & { enabled: boolean }): Promise<NotificationPreferences> {
  const res = await meApi.put<NotificationPreferences>('/notifications/email', body)
  return res.data
}

export async function saveTelegramNotifications(
  body: ChannelThresholds & { enabled: boolean; chat_id: number | null },
): Promise<NotificationPreferences> {
  const res = await meApi.put<NotificationPreferences>('/notifications/telegram', body)
  return res.data
}

// a fixed test message to every enabled channel, thresholds ignored; 429 within 60 s of the last
export async function sendTestNotification(): Promise<TestOutcomes> {
  const res = await meApi.post<TestOutcomes>('/notifications/test')
  return res.data
}

export async function listPapers(): Promise<Paper[]> {
  const res = await storage.get<{ papers: Paper[] }>('/papers')
  return res.data.papers
}

// A project from User Management (docs/CONTRACTS.md, POST /projects). The default project isn't one of
// these: it has no row, can't be renamed or deleted, and papers show it as project_id null.
export type Project = {
  id: string
  name: string
  created_at: string
}

export async function listProjects(): Promise<Project[]> {
  const res = await projectsApi.get<{ projects: Project[] }>('')
  return res.data.projects
}

export async function createProject(name: string): Promise<Project> {
  const res = await projectsApi.post<Project>('', { name })
  return res.data
}

export async function renameProject(id: string, name: string): Promise<Project> {
  const res = await projectsApi.put<Project>(`/${id}`, { name })
  return res.data
}

// also deletes the project's papers, with their alerts and reports, and its draft
export async function deleteProject(id: string): Promise<void> {
  await projectsApi.delete(`/${id}`)
}

export async function uploadPaperPdf(file: File, projectId?: string): Promise<Paper> {
  const body = new FormData()
  body.append('file', file)
  if (projectId) body.append('project_id', projectId)
  const res = await storage.post<Paper>('/papers', body)
  return res.data
}

export async function trackPaperByDoi(doi: string, projectId?: string): Promise<Paper> {
  const res = await storage.post<Paper>('/papers', projectId ? { doi, project_id: projectId } : { doi })
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

export async function runPoll(): Promise<PollSummary> {
  const res = await updating.post<PollSummary>('/run-poll')
  return res.data
}

export async function getDraft(projectId?: string): Promise<Draft | null> {
  try {
    const res = await storage.get<Draft>('/draft', { params: projectId ? { project_id: projectId } : undefined })
    return res.data
  } catch (err) {
    if (axios.isAxiosError(err) && err.response?.status === 404) return null
    throw err
  }
}

export async function uploadDraft(file: File, projectId?: string): Promise<Draft> {
  const body = new FormData()
  body.append('file', file)
  if (projectId) body.append('project_id', projectId)
  const res = await storage.post<Draft>('/draft', body)
  return res.data
}

export async function deleteDraft(projectId?: string): Promise<void> {
  await storage.delete('/draft', { params: projectId ? { project_id: projectId } : undefined })
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
  await storage.delete(`/papers/${paperId}`)
}
// Dev mode (docs/specs/2026-11-06-dev-mode.md, "Interfaces"). Admin token only.
export type Account = { id: string; email: string; role: 'user' | 'admin'; created_at: string }

export type SourceStatus = 'ok' | 'not_found' | 'error'

export type CrossrefUpdate = {
  notice_doi: string | null
  type: string | null
  label: string | null
  source: string | null
  date: string | null
  record_id: string | null
}

export type Snapshot = {
  snapshot_id: number
  paper_id: string
  fetched_at: string
  is_retracted: boolean | null
  in_doaj: boolean | null
  cited_by_count: number | null
  crossref_updates: CrossrefUpdate[] | null
  source_status: { crossref: SourceStatus; openalex: SourceStatus }
}

export type AdminPaper = Paper & { owner_id: string; latest_snapshot: Snapshot | null }

export type SnapshotEdit = {
  is_retracted?: boolean
  in_doaj?: boolean
  cited_by_count?: number
  removed_crossref_update_types?: string[]
}

export async function listAccounts(): Promise<Account[]> {
  const res = await adminAuth.get<{ users: Account[] }>('/users')
  return res.data.users
}

export async function listOwnerPapers(ownerId: string): Promise<AdminPaper[]> {
  const res = await storage.get<{ papers: AdminPaper[] }>(`/admin/users/${ownerId}/papers`)
  return res.data.papers
}

export async function editSnapshot(paperId: string, edit: SnapshotEdit): Promise<Snapshot> {
  const res = await storage.post<Snapshot>(`/admin/papers/${paperId}/snapshots`, edit)
  return res.data
}

export async function listPaperSnapshots(paperId: string): Promise<Snapshot[]> {
  const res = await storage.get<{ snapshots: Snapshot[] }>(`/admin/papers/${paperId}/snapshots`)
  return res.data.snapshots
}

export async function listPaperAlerts(paperId: string): Promise<Alert[]> {
  const res = await storage.get<{ alerts: Alert[] }>(`/admin/papers/${paperId}/alerts`)
  return res.data.alerts
}

export async function listPaperReports(paperId: string): Promise<UserReport[]> {
  const res = await storage.get<{ reports: RawReport[] }>(`/admin/papers/${paperId}/reports`)
  return res.data.reports.map(toUserReport)
}

// dev mode: the example's paper.pdf as a new paper of the researcher, in their default project
export async function addFakePaper(ownerId: string): Promise<AdminPaper> {
  const res = await storage.post<AdminPaper>(`/admin/users/${ownerId}/fake-paper`)
  return res.data
}

export async function clearPaperHistory(paperId: string): Promise<void> {
  await storage.delete(`/admin/papers/${paperId}/history`)
}

export async function addFakeReport(paperId: string, status: ReportStatus): Promise<UserReport> {
  const res = await storage.post<RawReport>(`/admin/papers/${paperId}/fake-report`, null, {
    params: { status },
  })
  return toUserReport(res.data)
}

export function isNotFound(err: unknown): boolean {
  return axios.isAxiosError(err) && err.response?.status === 404
}

// a 409: on register, an account with this email already exists
export function isConflict(err: unknown): boolean {
  return axios.isAxiosError(err) && err.response?.status === 409
}
