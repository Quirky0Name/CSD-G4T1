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
import { EmptyState, Loading, Page, PageHeader } from './Page'
import { badge, card, errorPanel, iconButton, riseDelay, smallPill } from '../ui'

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
  const shown =
    state.status === 'ready' ? state.alerts.filter((alert) => !paperFilter || alert.paper_id === paperFilter) : []

  const count = (status: AlertStatus) => shown.filter((alert) => alert.status === status).length

  return (
    <Page>
      <PageHeader
        title="Alerts"
        meta={
          state.status === 'ready'
            ? [`${count('new')} new`, `${count('acknowledged')} acknowledged`, filteredTitle && `For ${filteredTitle}`]
            : undefined
        }
        description={filteredTitle ? undefined : 'Changes to the papers you cite that may affect your research, newest first.'}
        actions={
          paperFilter && (
            <Link to="/alerts" className={smallPill}>
              Show all alerts
            </Link>
          )
        }
      />

      {state.status === 'loading' && <Loading label="Loading alerts…" />}
      {state.status === 'error' && <p className={errorPanel}>{state.message}</p>}
      {state.status === 'ready' && (
        <AlertRows papers={state.papers} alerts={state.alerts} paperFilter={paperFilter} />
      )}
    </Page>
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
    return (
      <EmptyState pose="happy" title="All quiet">
        No alerts to review. When a paper you track is retracted, corrected or delisted, it shows up here.
      </EmptyState>
    )
  }

  return (
    <ul role="list" className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,30rem),1fr))] items-start gap-4">
      {rows.map((alert, index) => (
        <li
          key={alert.id}
          style={riseDelay(index + 1)}
          className={`${card} animate-rise overflow-hidden p-5 sm:px-6 ${alert.status === 'new' ? 'outline-white/15' : ''}`}
        >
          <div className="flex items-start gap-3">
            <div className="flex min-w-0 flex-1 flex-wrap items-start gap-x-3 gap-y-1">
              <p className="min-w-0 text-sm/6 font-semibold text-white">
                {CHANGE_TYPE_LABEL[alert.change_type]} — {paperTitle.get(alert.paper_id) ?? 'Unknown paper'}
              </p>
              <span
                className={`mt-0.5 ${badge} ${ALERT_SEVERITY_STYLES[alert.alert_severity]}`}
              >
                {ALERT_SEVERITY_LABEL[alert.alert_severity]}
              </span>
              {alert.status !== 'new' && (
                <span
                  className={`mt-0.5 ${badge} ${STATUS_STYLES[alert.status]}`}
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

          <p className="mt-2 text-sm/6 text-gray-400">{alert.description}</p>
          <p className="mt-3 rounded-xl bg-white/[0.03] px-3.5 py-2.5 text-sm/6 text-gray-200 outline-1 -outline-offset-1 outline-white/5">
            {alert.recommendation}
          </p>

          <div className="mt-3 flex min-w-0 flex-wrap items-center gap-x-2 text-xs/5 text-gray-400">
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
      className={iconButton}
    >
      {children}
    </button>
  )
}
