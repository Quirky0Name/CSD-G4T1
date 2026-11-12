import '@fontsource/chango/400.css'
import '@fontsource-variable/nunito'
import './DevMode.css'
import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from 'react'
import {
  addFakePaper,
  addFakeReport,
  clearPaperHistory,
  editSnapshot,
  errorMessage,
  isNotFound,
  listAccounts,
  listOwnerPapers,
  listPaperAlerts,
  listPaperReports,
  listPaperSnapshots,
  type Account,
  type AdminPaper,
  type Alert,
  type ReportStatus,
  type Snapshot,
  type SnapshotEdit,
  type UserReport,
} from '../api'
import { useAuth } from '../auth'
import { BellyDetail, GuyBehind, GuyInFront, HeadSmall } from '../components/dev/Guy'
import { useBounce } from '../components/dev/useBounce'
import { isFake } from '../mocks/fakeReport'

type Tab = 'flags' | 'snapshots' | 'alerts' | 'reports'
type Say = { tone: 'info' | 'ok' | 'error'; text: string }
type History = { snapshots: Snapshot[]; alerts: Alert[]; reports: UserReport[] }

const EMPTY_HISTORY: History = { snapshots: [], alerts: [], reports: [] }
const REPORT_STATUSES: { value: ReportStatus; label: string }[] = [
  { value: 'investigating', label: 'Investigating' },
  { value: 'investigated', label: 'Investigated' },
  { value: 'assessed', label: 'Assessed' },
]
// Controls keep their own click. Anywhere else on the belly bounces it.
const CONTROLS = 'button, a, input, select, textarea, label, option'

function formatTime(iso: string | null): string {
  if (!iso) return 'Not yet'
  const date = new Date(iso)
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
}

function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

function yesNo(value: boolean | null): string {
  if (value === null) return 'Unknown'
  return value ? 'Yes' : 'No'
}

function paperLabel(paper: AdminPaper): string {
  const name = paper.title ?? paper.doi ?? paper.id
  return paper.publication_year ? `${name} (${paper.publication_year})` : name
}

function noticeTypes(snapshot: Snapshot): { type: string; count: number }[] {
  const counts = new Map<string, number>()
  for (const update of snapshot.crossref_updates ?? []) {
    if (update.type) counts.set(update.type, (counts.get(update.type) ?? 0) + 1)
  }
  return [...counts].map(([type, count]) => ({ type, count }))
}

function Switch({
  label,
  checked,
  disabled,
  onChange,
}: {
  label: string
  checked: boolean
  disabled: boolean
  onChange: (next: boolean) => void
}) {
  return (
    <label className="dev-switch">
      <input
        type="checkbox"
        role="switch"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="dev-switch-track" aria-hidden="true" />
      <span className="dev-switch-state">{checked ? 'Yes' : 'No'}</span>
      <span className="sr-only">{label}</span>
    </label>
  )
}

function FlagsPanel({
  snapshot,
  busy,
  onSave,
}: {
  snapshot: Snapshot
  busy: boolean
  onSave: (edit: SnapshotEdit) => Promise<void>
}) {
  const [retracted, setRetracted] = useState(snapshot.is_retracted ?? false)
  const [doaj, setDoaj] = useState(snapshot.in_doaj ?? false)
  const [citations, setCitations] = useState(String(snapshot.cited_by_count ?? ''))
  const [removed, setRemoved] = useState<string[]>([])

  const openalexOk = snapshot.source_status.openalex === 'ok'
  const crossrefOk = snapshot.source_status.crossref === 'ok'
  const notices = noticeTypes(snapshot)

  const citationCount = citations === '' ? null : Number(citations)
  const citationsValid = citationCount === null || (Number.isInteger(citationCount) && citationCount >= 0)

  const edit: SnapshotEdit = {}
  const effects: string[] = []
  if (snapshot.is_retracted !== null && retracted !== snapshot.is_retracted) {
    edit.is_retracted = retracted
    if (!retracted) effects.push('If it is really retracted, the next poll raises a retraction.')
  }
  if (snapshot.in_doaj !== null && doaj !== snapshot.in_doaj) {
    edit.in_doaj = doaj
    if (doaj) effects.push('If it is really out of DOAJ, the next poll raises a delisting.')
  }
  if (citationCount !== null && citationsValid && citationCount !== snapshot.cited_by_count) {
    edit.cited_by_count = citationCount
    effects.push('If the real count is far higher, the next poll raises a citation jump.')
  }
  if (removed.length > 0) {
    edit.removed_crossref_update_types = removed
    effects.push(`The next poll finds ${removed.join(', ')} again as new.`)
  }
  const dirty = Object.keys(edit).length > 0

  return (
    <form
      className="dev-flags"
      onSubmit={(e) => {
        e.preventDefault()
        if (dirty && citationsValid) void onSave(edit)
      }}
    >
      <div className="dev-flag-rows">
        <div className="dev-flag-row">
          <span className="dev-flag-name">Retracted</span>
          <Switch label="Retracted" checked={retracted} disabled={!openalexOk || snapshot.is_retracted === null} onChange={setRetracted} />
        </div>
        <div className="dev-flag-row">
          <span className="dev-flag-name">In DOAJ</span>
          <Switch label="In DOAJ" checked={doaj} disabled={!openalexOk || snapshot.in_doaj === null} onChange={setDoaj} />
        </div>
        <div className="dev-flag-row">
          <label className="dev-flag-name" htmlFor="dev-citations">
            Citations
          </label>
          <input
            id="dev-citations"
            className="dev-input dev-number"
            type="number"
            min="0"
            step="1"
            value={citations}
            disabled={!openalexOk}
            aria-invalid={!citationsValid}
            onChange={(e) => setCitations(e.target.value)}
          />
        </div>
        {!openalexOk && <p className="dev-note">OpenAlex answered {snapshot.source_status.openalex}, so these are never compared.</p>}
      </div>

      <fieldset className="dev-notices">
        <legend>Remove Crossref notices</legend>
        {!crossrefOk && <p className="dev-note">Crossref answered {snapshot.source_status.crossref}, so notices are never compared.</p>}
        {crossrefOk && notices.length === 0 && <p className="dev-note">The latest snapshot has no notices.</p>}
        {crossrefOk &&
          notices.map(({ type, count }) => (
            <label key={type} className="dev-check">
              <input
                type="checkbox"
                checked={removed.includes(type)}
                onChange={(e) => setRemoved((now) => (e.target.checked ? [...now, type] : now.filter((t) => t !== type)))}
              />
              <span>
                {type}
                {count > 1 ? ` (${count})` : ''}
              </span>
            </label>
          ))}
      </fieldset>

      <div className="dev-flags-foot">
        <p className="dev-effects" aria-live="polite">
          {!citationsValid
            ? 'Citations must be a whole number, 0 or more.'
            : (effects.join(' ') || `Latest snapshot from ${formatTime(snapshot.fetched_at)}. Change something to store a new one.`)}
        </p>
        <button type="submit" className="dev-button dev-primary" disabled={!dirty || !citationsValid || busy}>
          {busy ? 'Storing…' : 'Store new snapshot'}
        </button>
      </div>
    </form>
  )
}

function SnapshotsPanel({ snapshots }: { snapshots: Snapshot[] }) {
  if (snapshots.length === 0) return <p className="dev-empty">No snapshots yet. The researcher polls this paper once first.</p>
  return (
    <table className="dev-table">
      <thead>
        <tr>
          <th scope="col">Fetched</th>
          <th scope="col">Retracted</th>
          <th scope="col">In DOAJ</th>
          <th scope="col" className="dev-num">
            Citations
          </th>
          <th scope="col">Notices</th>
        </tr>
      </thead>
      <tbody>
        {[...snapshots].reverse().map((snapshot) => (
          <tr key={snapshot.snapshot_id}>
            <td>{formatTime(snapshot.fetched_at)}</td>
            <td>{yesNo(snapshot.is_retracted)}</td>
            <td>{yesNo(snapshot.in_doaj)}</td>
            <td className="dev-num">{snapshot.cited_by_count ?? 'Unknown'}</td>
            <td>{noticeTypes(snapshot).map((n) => n.type).join(', ') || 'None'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function AlertsPanel({ alerts, reports }: { alerts: Alert[]; reports: UserReport[] }) {
  const fakeIds = new Set(reports.filter(isFake).flatMap((r) => r.alerts.map((a) => a.id)))
  if (alerts.length === 0) return <p className="dev-empty">No alerts on this paper.</p>
  return (
    <ul className="dev-list">
      {alerts.map((alert) => (
        <li key={alert.id}>
          <div className="dev-list-head">
            <strong>{alert.change_type.replaceAll('_', ' ')}</strong>
            <span className={`dev-tag dev-tag-${alert.alert_severity}`}>{alert.alert_severity}</span>
            <span className="dev-tag">{alert.status}</span>
            {fakeIds.has(alert.id) && <span className="dev-tag dev-tag-fake">fake</span>}
            <time className="dev-when">{formatTime(alert.detected_at)}</time>
          </div>
          <p>{alert.description}</p>
        </li>
      ))}
    </ul>
  )
}

function ReportsPanel({ reports }: { reports: UserReport[] }) {
  if (reports.length === 0) return <p className="dev-empty">No reports on this paper.</p>
  return (
    <ul className="dev-list">
      {reports.map((report) => (
        <li key={report.id}>
          <div className="dev-list-head">
            <strong>Report {report.id}</strong>
            <span className="dev-tag">{report.status}</span>
            {isFake(report) && <span className="dev-tag dev-tag-fake">fake</span>}
            <span className="dev-when">
              {plural(report.alerts.length, 'alert')}, {plural(report.documents.length, 'document')}
            </span>
            <time className="dev-when">{formatTime(report.created_at)}</time>
          </div>
          {report.evaluation && <p>{report.evaluation}</p>}
          <p className="dev-links">
            {report.documents
              .filter((doc) => doc.pdf_status === 'ok' && doc.pdf_source_url)
              .map((doc) => (
                <a key={doc.id} href={doc.pdf_source_url ?? undefined} target="_blank" rel="noreferrer">
                  {doc.kind.replaceAll('_', ' ')} PDF
                </a>
              ))}
          </p>
        </li>
      ))}
    </ul>
  )
}

export default function DevMode() {
  const { logout } = useAuth()
  const stageRef = useRef<HTMLDivElement>(null)
  const bellyRef = useRef<HTMLDivElement>(null)
  const kick = useBounce(stageRef, bellyRef)

  const [say, setSay] = useState<Say>({ tone: 'info', text: 'Pick a researcher, then one of their papers.' })
  const [accounts, setAccounts] = useState<Account[] | null>(null)
  const [devOff, setDevOff] = useState(false)
  const [ownerId, setOwnerId] = useState('')
  const [papers, setPapers] = useState<AdminPaper[] | null>(null)
  const [paperId, setPaperId] = useState('')
  const [history, setHistory] = useState<History>(EMPTY_HISTORY)
  const [tab, setTab] = useState<Tab>('flags')
  const [busy, setBusy] = useState(false)
  const [fakeStatus, setFakeStatus] = useState<ReportStatus>('assessed')
  const [confirmClear, setConfirmClear] = useState(false)
  // bumped after each store, so the flags form restarts from the new latest snapshot
  const [revision, setRevision] = useState(0)

  const paper = useMemo(() => papers?.find((p) => p.id === paperId) ?? null, [papers, paperId])

  useEffect(() => {
    document.title = 'Dev mode'
    let live = true
    listAccounts()
      .then((users) => {
        if (live) setAccounts(users)
      })
      .catch((err: unknown) => {
        if (!live) return
        if (isNotFound(err)) {
          setDevOff(true)
          setSay({ tone: 'error', text: 'Dev mode is off.' })
        }
        else setSay({ tone: 'error', text: errorMessage(err) })
      })
    return () => {
      live = false
    }
  }, [])

  const refreshHistory = useCallback(async (id: string) => {
    const [snapshots, alerts, reports] = await Promise.all([listPaperSnapshots(id), listPaperAlerts(id), listPaperReports(id)])
    return { snapshots, alerts, reports }
  }, [])

  useEffect(() => {
    if (!ownerId) return
    let live = true
    listOwnerPapers(ownerId)
      .then((list) => {
        if (live) setPapers(list)
      })
      .catch((err: unknown) => {
        if (live) setSay({ tone: 'error', text: errorMessage(err) })
      })
    return () => {
      live = false
    }
  }, [ownerId])

  useEffect(() => {
    if (!paperId) return
    let live = true
    refreshHistory(paperId)
      .then((next) => {
        if (live) setHistory(next)
      })
      .catch((err: unknown) => {
        if (live) setSay({ tone: 'error', text: errorMessage(err) })
      })
    return () => {
      live = false
    }
  }, [paperId, refreshHistory])

  // After a change: read the paper's latest snapshot and history again
  const reload = useCallback(async () => {
    const [list, next] = await Promise.all([listOwnerPapers(ownerId), refreshHistory(paperId)])
    setPapers(list)
    setHistory(next)
  }, [ownerId, paperId, refreshHistory])

  const run = useCallback(
    async (action: () => Promise<string>) => {
      setBusy(true)
      try {
        setSay({ tone: 'ok', text: await action() })
      } catch (err) {
        setSay({ tone: 'error', text: errorMessage(err) })
      } finally {
        setBusy(false)
      }
    },
    [],
  )

  const saveFlags = useCallback(
    (edit: SnapshotEdit) =>
      run(async () => {
        await editSnapshot(paperId, edit)
        await reload()
        setRevision((r) => r + 1)
        return 'Stored. Now the researcher polls this paper from the app.'
      }),
    [paperId, reload, run],
  )

  const addFake = () =>
    run(async () => {
      await addFakeReport(paperId, fakeStatus)
      await reload()
      setTab('reports')
      return fakeStatus === 'assessed'
        ? 'Fake report added, with an earlier report, feedback and a profile for its Context tab. The researcher gets the toast within 30 s.'
        : `Fake report added, stuck at ${fakeStatus}. No toast for this one.`
    })

  // the example's paper.pdf as a new paper of the researcher, so a fake report has the paper it quotes
  const addPaper = () =>
    run(async () => {
      const added = await addFakePaper(ownerId)
      setPapers(await listOwnerPapers(ownerId))
      setPaperId(added.id)
      setHistory(EMPTY_HISTORY)
      setConfirmClear(false)
      return 'Fake paper added. Now add a fake report on it.'
    })

  const clearHistory = () =>
    run(async () => {
      await clearPaperHistory(paperId)
      setConfirmClear(false)
      await reload()
      return 'History cleared. The next poll is a baseline and never alerts.'
    })

  const bellyClick = (e: MouseEvent<HTMLDivElement>) => {
    if ((e.target as Element).closest(CONTROLS)) return
    if (window.getSelection()?.toString()) return
    const box = e.currentTarget.getBoundingClientRect()
    kick(((e.clientX - box.left) / box.width) * 2 - 1)
  }

  const pickOwner = (id: string) => {
    setOwnerId(id)
    setPapers(null)
    setPaperId('')
    setHistory(EMPTY_HISTORY)
    setConfirmClear(false)
    setSay(id ? { tone: 'info', text: 'Now pick one of their papers.' } : { tone: 'info', text: 'Pick a researcher, then one of their papers.' })
  }

  const pickPaper = (id: string) => {
    setPaperId(id)
    setHistory(EMPTY_HISTORY)
    setConfirmClear(false)
    if (id) setSay({ tone: 'info', text: 'Change a flag, add a fake report, or clear its history.' })
  }

  const counts: Record<Exclude<Tab, 'flags'>, number> = {
    snapshots: history.snapshots.length,
    alerts: history.alerts.length,
    reports: history.reports.length,
  }

  return (
    <main className="dev-page">
      <div className="dev-stage" ref={stageRef}>
        <HeadSmall />
        <GuyBehind />

        <div className={`dev-bubble dev-bubble-${say.tone}`} role="status">
          {say.text}
        </div>

        <div className="dev-account">
          <h1>Dev mode</h1>
          <span>Signed in as admin</span>
          <button type="button" className="dev-button dev-quiet" onClick={logout}>
            Sign out
          </button>
        </div>

        <div className="dev-belly" ref={bellyRef} onClick={bellyClick}>
          <div className="dev-belly-skin">
            <BellyDetail />
          </div>
          <div className="dev-ui">
            {devOff ? (
              <p className="dev-empty dev-off">Dev mode is off on this server. Set DEV_MODE=true and restart it.</p>
            ) : (
              <>
                <div className="dev-pickers">
                  <div className="dev-field">
                    <label htmlFor="dev-owner">Researcher</label>
                    <select id="dev-owner" className="dev-input" value={ownerId} disabled={!accounts} onChange={(e) => pickOwner(e.target.value)}>
                      <option value="">{accounts ? (accounts.length ? 'Choose an account' : 'No accounts yet') : 'Loading…'}</option>
                      {accounts?.map((account) => (
                        <option key={account.id} value={account.id}>
                          {account.email}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="dev-field">
                    <label htmlFor="dev-paper">Paper</label>
                    <select id="dev-paper" className="dev-input" value={paperId} disabled={!papers} onChange={(e) => pickPaper(e.target.value)}>
                      <option value="">
                        {!ownerId ? 'Choose a researcher first' : !papers ? 'Loading…' : papers.length ? 'Choose a paper' : 'This researcher has no papers'}
                      </option>
                      {papers?.map((p) => (
                        <option key={p.id} value={p.id}>
                          {paperLabel(p)}
                        </option>
                      ))}
                    </select>
                  </div>
                  <button type="button" className="dev-button dev-quiet-ink dev-add-paper" disabled={!ownerId || busy} onClick={() => void addPaper()}>
                    Add fake paper
                  </button>
                </div>

                <div className="dev-tabs" role="tablist" aria-label="Paper data">
                  {(['flags', 'snapshots', 'alerts', 'reports'] as const).map((name) => (
                    <button
                      key={name}
                      type="button"
                      role="tab"
                      id={`dev-tab-${name}`}
                      aria-selected={tab === name}
                      aria-controls="dev-panel"
                      className="dev-tab"
                      onClick={() => setTab(name)}
                    >
                      {name[0].toUpperCase() + name.slice(1)}
                      {name !== 'flags' && paper && <span className="dev-count">{counts[name]}</span>}
                    </button>
                  ))}
                </div>

                <div className="dev-panel" id="dev-panel" role="tabpanel" aria-labelledby={`dev-tab-${tab}`}>
                  {paper && <p className="dev-paper-title">{paperLabel(paper)}</p>}
                  {!paper ? (
                    <p className="dev-empty">Pick a paper to see its {tab}.</p>
                  ) : tab === 'flags' ? (
                    paper.latest_snapshot ? (
                      <FlagsPanel key={`${paper.id}-${revision}`} snapshot={paper.latest_snapshot} busy={busy} onSave={saveFlags} />
                    ) : (
                      <p className="dev-empty">No snapshot yet. The researcher polls this paper once first.</p>
                    )
                  ) : tab === 'snapshots' ? (
                    <SnapshotsPanel snapshots={history.snapshots} />
                  ) : tab === 'alerts' ? (
                    <AlertsPanel alerts={history.alerts} reports={history.reports} />
                  ) : (
                    <ReportsPanel reports={history.reports} />
                  )}
                </div>

                <div className="dev-actions">
                  <fieldset className="dev-segments" disabled={!paper}>
                    <legend>Fake report</legend>
                    {REPORT_STATUSES.map(({ value, label }) => (
                      <label key={value}>
                        <input type="radio" name="dev-fake-status" value={value} checked={fakeStatus === value} onChange={() => setFakeStatus(value)} />
                        <span>{label}</span>
                      </label>
                    ))}
                  </fieldset>
                  <button type="button" className="dev-button dev-primary" disabled={!paper || busy} onClick={() => void addFake()}>
                    {busy ? 'Working…' : 'Add fake report'}
                  </button>
                </div>

                <div className="dev-clear">
                  {confirmClear ? (
                    <>
                      <span className="dev-clear-ask">
                        Delete {plural(counts.snapshots, 'snapshot')}, {plural(counts.alerts, 'alert')}, {plural(counts.reports, 'report')}?
                      </span>
                      <button type="button" className="dev-button dev-danger" disabled={busy} onClick={() => void clearHistory()}>
                        Yes, clear
                      </button>
                      <button type="button" className="dev-button dev-quiet-ink" onClick={() => setConfirmClear(false)}>
                        Keep
                      </button>
                    </>
                  ) : (
                    <button type="button" className="dev-button dev-danger-quiet" disabled={!paper || busy} onClick={() => setConfirmClear(true)}>
                      Clear history
                    </button>
                  )}
                </div>
              </>
            )}
          </div>
        </div>

        <GuyInFront />

        {!devOff && (
          <aside className="dev-steps" aria-label="Demo steps">
          <h2>To raise a change</h2>
          <ol>
            <li>Researcher polls the paper.</li>
            <li>Set its flag back here.</li>
            <li>Researcher polls again.</li>
          </ol>
          <h2>To raise it again</h2>
          <p>Clear history, then do all three.</p>
        </aside>
        )}
      </div>
    </main>
  )
}
