import { useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { CheckIcon, XMarkIcon } from '@heroicons/react/20/solid'
import {
  errorMessage,
  setAlertStatus,
  type Alert,
  type AlertStatus,
  type Paper,
  type AlertSeverity,
} from '../api'
import { ALERT_STATUS_LABEL, CHANGE_TYPE_LABEL, ALERT_SEVERITY_LABEL, compareAlerts, formatDateTime } from '../format'
import { useTracking } from '../tracking'
import { useToast } from './Toasts'

const ALERT_SEVERITY_STYLES: Record<AlertSeverity, string> = {
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

  const filteredTitle =
    paperFilter && state.status === 'ready' ? state.papers.find((paper) => paper.id === paperFilter)?.title : null

  return (
    <div>
      <div className="mb-6 flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl/7 font-bold text-white sm:text-3xl sm:tracking-tight">Alerts</h1>
          <p className="mt-2 text-sm/6 text-gray-400">
            {filteredTitle ? `For ${filteredTitle}.` : 'Monitor changes that may affect your research.'}
          </p>
        </div>
        {paperFilter && (
          <Link to="/alerts" className="mt-1 shrink-0 text-sm font-semibold text-indigo-400 hover:text-indigo-300">
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
        <AlertRows papers={state.papers} alerts={state.alerts} paperFilter={paperFilter} />
      )}
    </div>
  )
}

function AlertRows({
  papers,
  alerts,
  paperFilter,
}: {
  papers: Paper[]
  alerts: Alert[]
  paperFilter: string | null
}) {
  const { refresh } = useTracking()
  const showToast = useToast()
  const [busyId, setBusyId] = useState<number | null>(null)
  const paperTitle = new Map(papers.map((paper) => [paper.id, paper.title ?? 'Untitled paper']))
  const rows = alerts
    .filter((alert) => !paperFilter || alert.paper_id === paperFilter)
    .toSorted(compareAlerts)

  const changeStatus = async (alertId: number, status: Exclude<AlertStatus, 'new'>) => {
    setBusyId(alertId)
    try {
      await setAlertStatus(alertId, status)
      await refresh()
      // a dismissed alert leaves the list. It can't go back to new, so it comes back acknowledged.
      if (status === 'dismissed') {
        showToast({
          tone: 'info',
          message: 'Alert dismissed',
          action: { label: 'Bring back', onClick: () => void changeStatus(alertId, 'acknowledged') },
        })
      }
    } catch (err) {
      showToast({ tone: 'error', message: errorMessage(err) })
    } finally {
      setBusyId(null)
    }
  }

  if (rows.length === 0) {
    return <p className="text-sm/6 text-gray-400">No alerts to review.</p>
  }

  return (
    <ul role="list" className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,34rem),1fr))] items-start gap-3">
      {rows.map((alert) => (
        <li
          key={alert.id}
          className="overflow-hidden bg-gray-800/50 px-4 py-4 shadow-none outline -outline-offset-1 outline-white/10 sm:rounded-md sm:px-6"
        >
          <div className="flex items-start gap-3">
            <div className="flex min-w-0 flex-1 flex-wrap items-start gap-x-3 gap-y-1">
              <p className="min-w-0 text-sm/6 font-semibold text-white">
                {CHANGE_TYPE_LABEL[alert.change_type]} — {paperTitle.get(alert.paper_id) ?? 'Unknown paper'}
              </p>
              <span
                className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${ALERT_SEVERITY_STYLES[alert.alert_severity]}`}
              >
                {ALERT_SEVERITY_LABEL[alert.alert_severity]}
              </span>
              {alert.status !== 'new' && (
                <span
                  className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${STATUS_STYLES[alert.status]}`}
                >
                  {ALERT_STATUS_LABEL[alert.status]}
                </span>
              )}
            </div>

            <div className="-mt-1 -mr-1.5 flex shrink-0 items-center">
              {alert.status === 'new' && (
                <AlertAction
                  label={`Acknowledge, ${CHANGE_TYPE_LABEL[alert.change_type]}`}
                  title="Acknowledge"
                  disabled={busyId === alert.id}
                  onClick={() => void changeStatus(alert.id, 'acknowledged')}
                >
                  <CheckIcon aria-hidden="true" className="size-5" />
                </AlertAction>
              )}
              <AlertAction
                label={`Dismiss, ${CHANGE_TYPE_LABEL[alert.change_type]}`}
                title="Dismiss"
                disabled={busyId === alert.id}
                onClick={() => void changeStatus(alert.id, 'dismissed')}
              >
                <XMarkIcon aria-hidden="true" className="size-5" />
              </AlertAction>
            </div>
          </div>

          <p className="mt-1.5 text-sm/6 text-gray-400">{alert.description}</p>
          <p className="mt-1.5 text-sm/6 text-gray-200">{alert.recommendation}</p>

          <div className="mt-2 flex min-w-0 flex-wrap items-center gap-x-2 text-xs/5 text-gray-400">
            <p className="whitespace-nowrap">
              Detected <time dateTime={alert.detected_at}>{formatDateTime(alert.detected_at)}</time>
            </p>
            {alert.notice_doi && (
              <>
                <svg viewBox="0 0 2 2" aria-hidden="true" className="hidden size-0.5 fill-current sm:block">
                  <circle r={1} cx={1} cy={1} />
                </svg>
                <p className="min-w-0 truncate">Notice DOI {alert.notice_doi}</p>
              </>
            )}
          </div>
        </li>
      ))}
    </ul>
  )
}

function AlertAction({
  label,
  title,
  disabled,
  onClick,
  children,
}: {
  label: string
  title: string
  disabled: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={title}
      disabled={disabled}
      onClick={onClick}
      className="flex size-10 items-center justify-center rounded-md text-gray-400 hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-500 disabled:cursor-not-allowed disabled:opacity-50 sm:size-8"
    >
      {children}
    </button>
  )
}
