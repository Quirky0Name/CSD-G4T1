import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { ArrowPathIcon, CalendarIcon, PlusIcon } from '@heroicons/react/20/solid'
import { deletePaper, errorMessage, listProjects, runPoll, type PollSummary } from '../api'
import { formatDateTime } from '../format'
import { useTracking } from '../tracking'
import { useToast, type ToastTone } from '../components/Toasts'
import TrackedPaperList from '../components/TrackedPaperList'
import TrackPaperDialog from '../components/TrackPaperDialog'
import DraftCard from '../components/DraftCard'
import { ActionButton } from '../components/ActionButton'
import { Loading, NotFound, Page, PageHeader } from '../components/Page'
import { NewAlertsNote } from './Projects'
import { errorPanel } from '../ui'

function pollSummaryToast(summary: PollSummary): { tone: ToastTone; message: string } {
  const polled = summary.stored.length
  const failed = summary.source_errors.length + summary.store_errors.length
  const parts = [`Polled ${polled} paper${polled === 1 ? '' : 's'}`]
  if (failed > 0) parts.push(`${failed} couldn't be polled, retried next poll`)
  if (summary.skipped_no_doi.length > 0) parts.push(`${summary.skipped_no_doi.length} skipped (no DOI)`)
  if (summary.nudge_error) parts.push(`Evaluation failed: ${summary.nudge_error.reason}`)
  return { tone: failed > 0 || summary.nudge_error ? 'error' : 'success', message: parts.join(' · ') }
}

// the default project has no row, so this is the only name it has
const DEFAULT_PROJECT_NAME = 'Default project'

function Project() {
  const { projectId } = useParams()
  const { state, refresh } = useTracking()
  const showToast = useToast()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [polling, setPolling] = useState(false)
  const [lastPoll, setLastPoll] = useState<PollSummary | null>(null)
  // the project's name, or what went wrong finding it
  const [found, setFound] = useState<{ id: string; name: string | null; error?: string } | null>(null)

  useEffect(() => {
    if (!projectId) return
    let active = true
    listProjects()
      .then((projects) => {
        if (!active) return
        const project = projects.find((p) => p.id === projectId)
        setFound({ id: projectId, name: project?.name ?? null })
      })
      .catch((err: unknown) => {
        if (active) setFound({ id: projectId, name: null, error: errorMessage(err) })
      })
    return () => {
      active = false
    }
  }, [projectId])

  const current = found?.id === projectId ? found : null
  const name = projectId ? current?.name : DEFAULT_PROJECT_NAME

  const poll = async () => {
    if (polling) return
    if (state.status === 'ready' && state.papers.length === 0) {
      showToast({ tone: 'info', message: 'Nothing to poll. Track a paper first.' })
      return
    }
    setPolling(true)
    try {
      const summary = await runPoll()
      setLastPoll(summary)
      await refresh()
      showToast(pollSummaryToast(summary))
    } catch (err) {
      showToast({ tone: 'error', message: errorMessage(err) })
    } finally {
      setPolling(false)
    }
  }

  const handleDelete = async (paperId: string) => {
    try {
      await deletePaper(paperId)
      await refresh()
      showToast({ tone: 'success', message: 'Paper deleted' })
    } catch (err) {
      showToast({ tone: 'error', message: errorMessage(err) })
    }
  }

  if (projectId && current && !current.name) {
    return <NotFound message={current.error ?? "This project doesn't exist or isn't yours."} />
  }

  const papers = state.status === 'ready' ? state.papers.filter((paper) => paper.project_id === (projectId ?? null)) : []
  const paperIds = new Set(papers.map((paper) => paper.id))
  const newAlerts = state.status === 'ready' ? state.alerts.filter((alert) => paperIds.has(alert.paper_id) && alert.status === 'new').length : 0

  return (
    <Page>
      <PageHeader
        back={{ to: '/', label: 'Projects' }}
        title={name ?? '\u00a0'}
        meta={[
          state.status === 'ready' && `${papers.length} paper${papers.length === 1 ? '' : 's'}`,
          lastPoll && (
            <span className="inline-flex items-center gap-1">
              <CalendarIcon aria-hidden="true" className="size-4 shrink-0 text-gray-500" />
              Last polled {formatDateTime(lastPoll.finished_at)}
            </span>
          ),
          state.status === 'ready' && <NewAlertsNote count={newAlerts} />,
        ]}
        actions={
          <>
            <ActionButton disabled title="Set on the server (POLL_INTERVAL_HOURS)">
              Schedule polls
            </ActionButton>
            <ActionButton onClick={() => setUploadOpen(true)}>
              <PlusIcon aria-hidden="true" className="size-4" />
              Track paper
            </ActionButton>
            <ActionButton tone="primary" disabled={polling} onClick={() => void poll()}>
              <ArrowPathIcon aria-hidden="true" className={`size-4 ${polling ? 'animate-spin' : ''}`} />
              {polling ? 'Polling…' : 'Poll now'}
            </ActionButton>
          </>
        }
      />

      <DraftCard projectId={projectId} />

      {state.status === 'loading' && (
        <div className="mt-10">
          <Loading label="Loading tracked papers…" />
        </div>
      )}
      {state.status === 'error' && <p className={`mt-10 ${errorPanel}`}>{state.message}</p>}
      {state.status === 'ready' && (
        <TrackedPaperList
          papers={papers}
          alerts={state.alerts}
          onDelete={(paperId) => void handleDelete(paperId)}
          onTrack={() => setUploadOpen(true)}
        />
      )}

      <TrackPaperDialog open={uploadOpen} onClose={() => setUploadOpen(false)} projectId={projectId} />
    </Page>
  )
}

export default Project