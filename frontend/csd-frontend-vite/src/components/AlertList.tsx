import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import type { Alert, AlertStatus, Paper, Severity } from '../api'
import { ALERT_STATUS_LABEL, CHANGE_TYPE_LABEL, SEVERITY_LABEL, compareAlerts, formatDateTime } from '../format'
import { useTracking } from '../tracking'

const SEVERITY_STYLES: Record<Severity, string> = {
  high: 'bg-red-400/10 text-red-400 inset-ring-red-400/20',
  medium: 'bg-yellow-400/10 text-yellow-500 inset-ring-yellow-400/20',
  low: 'bg-gray-400/10 text-gray-400 inset-ring-gray-400/20',
}

const STATUS_STYLES: Record<Exclude<AlertStatus, 'new'>, string> = {
  acknowledged: 'bg-emerald-400/10 text-emerald-400 inset-ring-emerald-400/20',
  dismissed: 'bg-gray-400/10 text-gray-400 inset-ring-gray-400/20',
}

export default function AlertList() {
  const { state } = useTracking()
  const [searchParams] = useSearchParams()
  const paperFilter = searchParams.get('paper')
  const [expandedId, setExpandedId] = useState<number | null>(null)

  return (
    <div>
      <div className="mb-5 flex items-start justify-between gap-4">
        <div>
          <h2 className="text-base/7 font-semibold text-white">Research alerts</h2>
          <p className="mt-1 text-sm/6 text-gray-400">Monitor changes that may affect your research.</p>
        </div>
        {paperFilter && (
          <Link to="/alerts" className="mt-1 text-sm font-semibold text-indigo-400 hover:text-indigo-300">
            Show all
          </Link>
        )}
      </div>

      {state.status === 'loading' && <p className="text-sm text-gray-400">Loading alerts…</p>}
      {state.status === 'error' && (
        <p className="rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">
          {state.message}
        </p>
      )}
      {state.status === 'ready' && (
        <AlertRows
          papers={state.papers}
          alerts={state.alerts}
          paperFilter={paperFilter}
          expandedId={expandedId}
          onToggle={(id) => setExpandedId((current) => (current === id ? null : id))}
        />
      )}
    </div>
  )
}

function AlertRows({
  papers,
  alerts,
  paperFilter,
  expandedId,
  onToggle,
}: {
  papers: Paper[]
  alerts: Alert[]
  paperFilter: string | null
  expandedId: number | null
  onToggle: (id: number) => void
}) {
  const paperTitle = new Map(papers.map((paper) => [paper.id, paper.title ?? 'Untitled paper']))
  const rows = alerts
    .filter((alert) => !paperFilter || alert.paper_id === paperFilter)
    .toSorted(compareAlerts)

  if (rows.length === 0) {
    return <p className="text-sm/6 text-gray-400">No alerts to review.</p>
  }

  return (
    <ul role="list" className="space-y-3">
      {rows.map((alert) => (
        <li
          key={alert.id}
          className="overflow-hidden bg-gray-800/50 px-4 py-4 shadow-none outline -outline-offset-1 outline-white/10 sm:rounded-md sm:px-6"
        >
          <div className="flex items-start justify-between gap-x-6">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
                <p className="text-sm/6 font-semibold text-white">
                  {CHANGE_TYPE_LABEL[alert.change_type]} — {paperTitle.get(alert.paper_id) ?? 'Unknown paper'}
                </p>
                <span
                  className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${SEVERITY_STYLES[alert.severity]}`}
                >
                  {SEVERITY_LABEL[alert.severity]}
                </span>
                {alert.status !== 'new' && (
                  <span
                    className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${STATUS_STYLES[alert.status]}`}
                  >
                    {ALERT_STATUS_LABEL[alert.status]}
                  </span>
                )}
              </div>

              <p className="mt-1.5 max-w-4xl text-sm/6 text-gray-400">{alert.description}</p>

              <div className="mt-2 flex flex-wrap items-center gap-x-2 text-xs/5 text-gray-400">
                <p className="whitespace-nowrap">
                  Detected <time dateTime={alert.detected_at}>{formatDateTime(alert.detected_at)}</time>
                </p>
                {alert.notice_doi && (
                  <>
                    <svg viewBox="0 0 2 2" className="size-0.5 fill-current">
                      <circle r={1} cx={1} cy={1} />
                    </svg>
                    <p className="truncate">Notice DOI {alert.notice_doi}</p>
                  </>
                )}
              </div>
            </div>

            <div className="flex flex-none items-center gap-x-4">
              <button
                type="button"
                onClick={() => onToggle(alert.id)}
                className="rounded-md bg-white/10 px-2.5 py-1.5 text-sm font-semibold text-white shadow-none inset-ring inset-ring-white/10 hover:bg-white/20"
              >
                {expandedId === alert.id ? 'Hide details' : 'View details'}
                <span className="sr-only">, {CHANGE_TYPE_LABEL[alert.change_type]}</span>
              </button>
            </div>
          </div>

          {expandedId === alert.id && (
            <div className="mt-4 border-t border-white/10 pt-4 text-sm/6">
              <p className="font-semibold text-white">Recommendation</p>
              <p className="mt-1 text-gray-400">{alert.recommendation}</p>
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}
