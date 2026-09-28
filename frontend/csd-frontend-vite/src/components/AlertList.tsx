import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import { EllipsisVerticalIcon } from '@heroicons/react/20/solid'
import {
  addAlertNote,
  errorMessage,
  listAlertNotes,
  setAlertStatus,
  type Alert,
  type AlertNote,
  type AlertStatus,
  type Paper,
  type Severity,
} from '../api'
import { ALERT_STATUS_LABEL, CHANGE_TYPE_LABEL, SEVERITY_LABEL, compareAlerts, formatDateTime } from '../format'
import { useTracking } from '../tracking'
import { useToast } from './Toasts'

// docs/CONTRACTS.md, POST /alerts/{id}/notes: "1 to 2000 characters".
const ALERT_NOTE_MAX_LENGTH = 2000

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
          <h2 className="text-base/7 font-semibold text-white">Alerts</h2>
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
  const { refresh } = useTracking()
  const showToast = useToast()
  const paperTitle = new Map(papers.map((paper) => [paper.id, paper.title ?? 'Untitled paper']))
  const rows = alerts
    .filter((alert) => !paperFilter || alert.paper_id === paperFilter)
    .toSorted(compareAlerts)

  const changeStatus = async (alertId: number, status: Exclude<AlertStatus, 'new'>) => {
    try {
      await setAlertStatus(alertId, status)
      await refresh()
    } catch (err) {
      showToast({ tone: 'error', message: errorMessage(err) })
    }
  }

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

              <Menu as="div" className="relative">
                <MenuButton className="relative block text-gray-400 hover:text-white">
                  <span className="absolute -inset-2.5" />
                  <span className="sr-only">Open options</span>
                  <EllipsisVerticalIcon aria-hidden="true" className="size-5" />
                </MenuButton>
                <MenuItems
                  transition
                  className="absolute right-0 z-10 mt-2 w-40 origin-top-right rounded-md bg-gray-800 py-2 shadow-lg outline outline-white/10 transition data-closed:scale-95 data-closed:transform data-closed:opacity-0 data-enter:duration-100 data-enter:ease-out data-leave:duration-75 data-leave:ease-in"
                >
                  <MenuItem>
                    <button
                      type="button"
                      onClick={() => void changeStatus(alert.id, 'acknowledged')}
                      className="block w-full px-3 py-1 text-left text-sm/6 text-white data-focus:bg-white/5"
                    >
                      Acknowledge<span className="sr-only">, {CHANGE_TYPE_LABEL[alert.change_type]}</span>
                    </button>
                  </MenuItem>
                  <MenuItem>
                    <button
                      type="button"
                      onClick={() => void changeStatus(alert.id, 'dismissed')}
                      className="block w-full px-3 py-1 text-left text-sm/6 text-white data-focus:bg-white/5"
                    >
                      Dismiss<span className="sr-only">, {CHANGE_TYPE_LABEL[alert.change_type]}</span>
                    </button>
                  </MenuItem>
                </MenuItems>
              </Menu>
            </div>
          </div>

          {expandedId === alert.id && (
            <div className="mt-4 border-t border-white/10 pt-4 text-sm/6">
              <p className="font-semibold text-white">Recommendation</p>
              <p className="mt-1 text-gray-400">{alert.recommendation}</p>
              <AlertNotes alertId={alert.id} />
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}

function AlertNotes({ alertId }: { alertId: number }) {
  const [notes, setNotes] = useState<AlertNote[] | null>(null)
  const [notesError, setNotesError] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  // AlertNotes is only rendered for the currently expanded alert (below),
  // so a mount is always exactly one alertId for its whole lifetime — no
  // reset-on-change needed, just the one fetch.
  useEffect(() => {
    let active = true
    listAlertNotes(alertId)
      .then((result) => {
        if (active) setNotes(result)
      })
      .catch((err: unknown) => {
        if (active) setNotesError(errorMessage(err))
      })
    return () => {
      active = false
    }
  }, [alertId])

  const trimmed = text.trim()
  const canSubmit = trimmed.length > 0 && trimmed.length <= ALERT_NOTE_MAX_LENGTH && !submitting

  const handleSubmit = async () => {
    setSubmitting(true)
    setSubmitError(null)
    try {
      const note = await addAlertNote(alertId, trimmed)
      setNotes((current) => [note, ...(current ?? [])])
      setText('')
    } catch (err) {
      setSubmitError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="mt-4 border-t border-white/10 pt-4">
      <p className="font-semibold text-white">Notes</p>
      {notesError && <p className="mt-1 text-red-400">{notesError}</p>}
      {notes === null && !notesError && <p className="mt-1 text-gray-400">Loading notes…</p>}
      {notes && notes.length === 0 && <p className="mt-1 text-gray-400">No notes yet.</p>}
      {notes && notes.length > 0 && (
        <ul className="mt-2 space-y-2">
          {notes.map((note, index) => (
            // AlertNote has no id (docs/CONTRACTS.md); notes can't be
            // edited or deleted, so position + created_at is a stable key.
            <li key={`${index}-${note.created_at}`} className="rounded-md bg-gray-900/60 px-3 py-2 text-gray-300">
              <p>{note.text}</p>
              <p className="mt-1 text-xs text-gray-500">{formatDateTime(note.created_at)}</p>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-3">
        <textarea
          value={text}
          onChange={(event) => setText(event.target.value)}
          rows={2}
          maxLength={ALERT_NOTE_MAX_LENGTH}
          placeholder="What did you do about this?"
          className="block w-full rounded-md border border-white/15 bg-gray-950/60 px-3 py-2 text-white placeholder:text-gray-500 outline-none focus:border-indigo-400"
        />
        {submitError && <p className="mt-1 text-red-400">{submitError}</p>}
        <div className="mt-2 flex items-center justify-between">
          <p className="text-xs text-gray-500">
            {trimmed.length}/{ALERT_NOTE_MAX_LENGTH}
          </p>
          <button
            type="button"
            disabled={!canSubmit}
            onClick={() => void handleSubmit()}
            className="rounded-md bg-indigo-500 px-3 py-1.5 text-sm font-semibold text-white shadow-xs hover:bg-indigo-400 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {submitting ? 'Adding…' : 'Add note'}
          </button>
        </div>
      </div>
    </div>
  )
}
