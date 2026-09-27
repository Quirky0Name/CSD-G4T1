// One shared fetch loop for tracked papers and their alerts, so the sidebar
// toaster and both pages read the same data instead of polling three times.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { listAlerts, listPapers, errorMessage, type Alert, type Paper } from './api'
import { CHANGE_TYPE_LABEL, SEVERITY_LABEL } from './format'
import { useToast } from './components/Toasts'

const ALERT_REFRESH_MS = 60_000

export type TrackingState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; papers: Paper[]; alerts: Alert[] }

type TrackingContextValue = {
  state: TrackingState
  refresh: () => Promise<void>
}

const TrackingContext = createContext<TrackingContextValue | null>(null)

export function TrackingProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<TrackingState>({ status: 'loading' })
  const showToast = useToast()
  const navigate = useNavigate()
  // null until the first successful load, which only seeds it — never toasts.
  const seenAlertIds = useRef<Set<number> | null>(null)
  const refreshRef = useRef<() => Promise<void>>(() => Promise.resolve())

  useEffect(() => {
    let mounted = true
    let running: Promise<void> | null = null
    let queued: Promise<void> | null = null

    function noticeNewAlerts(papers: Paper[], alerts: Alert[]) {
      const seen = seenAlertIds.current
      if (seen === null) {
        seenAlertIds.current = new Set(alerts.map((alert) => alert.id))
        return
      }
      const paperTitle = new Map(papers.map((paper) => [paper.id, paper.title ?? 'a tracked paper']))
      for (const alert of alerts) {
        if (alert.status !== 'new' || seen.has(alert.id)) continue
        seen.add(alert.id)
        showToast({
          tone: 'error',
          message: `${CHANGE_TYPE_LABEL[alert.change_type]} on "${paperTitle.get(alert.paper_id)}" · ${SEVERITY_LABEL[alert.severity]}`,
          action: { label: 'View', onClick: () => navigate(`/alerts?paper=${alert.paper_id}`) },
        })
      }
    }

    async function runOnce() {
      try {
        const papers = await listPapers()
        const alerts = (await Promise.all(papers.map((paper) => listAlerts(paper.id)))).flat()
        if (!mounted) return
        setState({ status: 'ready', papers, alerts })
        noticeNewAlerts(papers, alerts)
      } catch (err) {
        if (!mounted) return
        setState({ status: 'error', message: errorMessage(err) })
      }
    }

    // A manual refresh (after an upload, a status change, a poll) always
    // waits for one full run to start after it's called, even if a run was
    // already in flight, so it sees what just changed. An interval tick
    // that lands mid-refresh is just skipped (see below), not queued.
    function refresh(): Promise<void> {
      if (!running) {
        running = runOnce().finally(() => {
          running = null
        })
        return running
      }
      if (!queued) {
        queued = running.then(runOnce).finally(() => {
          queued = null
        })
      }
      return queued
    }

    refreshRef.current = refresh
    void refresh()
    const interval = setInterval(() => {
      if (running) return
      void refresh()
    }, ALERT_REFRESH_MS)

    return () => {
      mounted = false
      clearInterval(interval)
    }
  }, [showToast, navigate])

  const refresh = useCallback(() => refreshRef.current(), [])
  const value = useMemo(() => ({ state, refresh }), [state, refresh])

  return <TrackingContext.Provider value={value}>{children}</TrackingContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components -- context hook, not a component
export function useTracking(): TrackingContextValue {
  const value = useContext(TrackingContext)
  if (!value) throw new Error('useTracking must be used within a TrackingProvider')
  return value
}
