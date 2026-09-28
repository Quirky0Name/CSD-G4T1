import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  errorMessage,
  listReports,
  type Alert,
  type Paper,
  type ReportStatus,
  type UserReport,
} from '../api'
import { CHANGE_TYPE_LABEL, SEVERITY_LABEL, formatDateTime } from '../format'
import { useTracking } from '../tracking'

const REPORT_STATUS_LABEL: Record<ReportStatus, string> = {
  investigating: 'Investigating',
  investigated: 'Investigated',
  assessed: 'Assessed',
}

const REPORT_STATUS_STYLES: Record<ReportStatus, string> = {
  investigating: 'bg-yellow-400/10 text-yellow-500 inset-ring-yellow-400/20',
  investigated: 'bg-indigo-400/10 text-indigo-400 inset-ring-indigo-400/20',
  assessed: 'bg-emerald-400/10 text-emerald-400 inset-ring-emerald-400/20',
}

const LEVEL_STYLES: Record<string, string> = {
  high: 'bg-red-400/10 text-red-400 inset-ring-red-400/20',
  medium: 'bg-yellow-400/10 text-yellow-500 inset-ring-yellow-400/20',
  low: 'bg-gray-400/10 text-gray-400 inset-ring-gray-400/20',
  none: 'bg-gray-400/10 text-gray-400 inset-ring-gray-400/20',
}

type ReportsState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; reports: UserReport[] }

export default function ReportList() {
  const { state } = useTracking()
  const [searchParams] = useSearchParams()
  const paperFilter = searchParams.get('paper')
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [reportsState, setReportsState] = useState<ReportsState>({ status: 'loading' })

  const papers = state.status === 'ready' ? state.papers : null

  useEffect(() => {
    if (!papers) return
    let active = true
    const ids = paperFilter ? [paperFilter] : papers.map((paper) => paper.id)
    setReportsState({ status: 'loading' })
    Promise.all(ids.map((id) => listReports(id)))
      .then((lists) => {
        if (!active) return
        const reports = lists.flat().toSorted((a, b) => b.created_at.localeCompare(a.created_at))
        setReportsState({ status: 'ready', reports })
      })
      .catch((err: unknown) => {
        if (active) setReportsState({ status: 'error', message: errorMessage(err) })
      })
    return () => {
      active = false
    }
  }, [papers, paperFilter])

  const error =
    state.status === 'error' ? state.message : reportsState.status === 'error' ? reportsState.message : null
  const loading = state.status === 'loading' || (state.status === 'ready' && reportsState.status === 'loading')

  return (
    <div>
      <div className="mb-5 flex items-start justify-between gap-4">
        <div>
          <h2 className="text-base/7 font-semibold text-white">Reports</h2>
          <p className="mt-1 text-sm/6 text-gray-400">Changes that may affect your research, grouped per paper.</p>
        </div>
        {paperFilter && (
          <Link to="/reports" className="mt-1 text-sm font-semibold text-indigo-400 hover:text-indigo-300">
            Show all
          </Link>
        )}
      </div>

      {loading && <p className="text-sm text-gray-400">Loading reports…</p>}
      {error && (
        <p className="rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">
          {error}
        </p>
      )}
      {state.status === 'ready' && reportsState.status === 'ready' && (
        <ReportRows
          papers={state.papers}
          reports={reportsState.reports}
          expandedId={expandedId}
          onToggle={(id) => setExpandedId((current) => (current === id ? null : id))}
        />
      )}
    </div>
  )
}

function ReportRows({
  papers,
  reports,
  expandedId,
  onToggle,
}: {
  papers: Paper[]
  reports: UserReport[]
  expandedId: number | null
  onToggle: (id: number) => void
}) {
  const paperTitle = new Map(papers.map((paper) => [paper.id, paper.title ?? 'Untitled paper']))

  if (reports.length === 0) {
    return <p className="text-sm/6 text-gray-400">No reports yet.</p>
  }

  return (
    <ul role="list" className="space-y-3">
      {reports.map((report) => (
        <li
          key={report.id}
          className="overflow-hidden bg-gray-800/50 px-4 py-4 shadow-none outline -outline-offset-1 outline-white/10 sm:rounded-md sm:px-6"
        >
          <div className="flex items-start justify-between gap-x-6">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
                <p className="text-sm/6 font-semibold text-white">
                  {paperTitle.get(report.paper_id) ?? 'Unknown paper'}
                </p>
                <span
                  className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${REPORT_STATUS_STYLES[report.status]}`}
                >
                  {REPORT_STATUS_LABEL[report.status]}
                </span>
                {report.change_severity && (
                  <span
                    className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${LEVEL_STYLES[report.change_severity]}`}
                  >
                    {report.change_severity} severity
                  </span>
                )}
              </div>

              <p className="mt-1.5 max-w-4xl text-sm/6 text-gray-400">
                {report.change_summary ?? 'Assessment pending.'}
              </p>

              <p className="mt-2 text-xs/5 text-gray-400">
                Opened <time dateTime={report.created_at}>{formatDateTime(report.created_at)}</time>
                {' · '}
                {report.alerts.length} alert{report.alerts.length === 1 ? '' : 's'}
                {' · '}
                {report.documents.length} document{report.documents.length === 1 ? '' : 's'}
              </p>
            </div>

            <button
              type="button"
              onClick={() => onToggle(report.id)}
              className="flex-none rounded-md bg-white/10 px-2.5 py-1.5 text-sm font-semibold text-white shadow-none inset-ring inset-ring-white/10 hover:bg-white/20"
            >
              {expandedId === report.id ? 'Hide details' : 'View details'}
            </button>
          </div>

          {expandedId === report.id && <ReportDetails report={report} />}
        </li>
      ))}
    </ul>
  )
}

function ReportDetails({ report }: { report: UserReport }) {
  return (
    <div className="mt-4 space-y-4 border-t border-white/10 pt-4 text-sm/6">
      {report.status === 'assessed' ? (
        <>
          {report.impact_level && (
            <div>
              <p className="font-semibold text-white">Impact on your research</p>
              <p className="mt-1 capitalize text-gray-400">{report.impact_level}</p>
            </div>
          )}
          {report.evaluation && (
            <div>
              <p className="font-semibold text-white">Evaluation</p>
              <p className="mt-1 text-gray-400">{report.evaluation}</p>
            </div>
          )}
          {report.recommendation && (
            <div>
              <p className="font-semibold text-white">Recommendation</p>
              <p className="mt-1 text-gray-400">{report.recommendation}</p>
            </div>
          )}
        </>
      ) : (
        <p className="text-gray-400">This report hasn't been assessed yet.</p>
      )}

      <div>
        <p className="font-semibold text-white">Alerts in this report</p>
        {report.alerts.length === 0 ? (
          <p className="mt-1 text-gray-400">None.</p>
        ) : (
          <ul className="mt-2 space-y-2">
            {report.alerts.map((alert) => (
              <AlertSummary key={alert.id} alert={alert} />
            ))}
          </ul>
        )}
      </div>

      {report.documents.length > 0 && (
        <div>
          <p className="font-semibold text-white">Documents</p>
          <ul className="mt-2 space-y-1 text-gray-400">
            {report.documents.map((doc) => (
              <li key={doc.id}>{doc.filename ?? `Document ${doc.id}`}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function AlertSummary({ alert }: { alert: Alert }) {
  return (
    <li className="rounded-md bg-gray-900/60 px-3 py-2 text-gray-300">
      <p className="font-medium text-white">
        {CHANGE_TYPE_LABEL[alert.change_type]}{' '}
        <span className="text-xs text-gray-400">({SEVERITY_LABEL[alert.severity]})</span>
      </p>
      <p className="mt-1 text-gray-400">{alert.description}</p>
      <p className="mt-1 text-xs text-gray-500">{formatDateTime(alert.detected_at)}</p>
    </li>
  )
}