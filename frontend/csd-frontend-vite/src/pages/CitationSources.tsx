import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeftIcon, ArrowPathIcon, CalendarIcon, PlusIcon } from '@heroicons/react/20/solid'
import { deletePaper, errorMessage, runPoll, type PollSummary } from '../api'
import { formatDateTime } from '../format'
import { useTracking } from '../tracking'
import { useToast, type ToastTone } from '../components/Toasts'
import CitedSourcesList from '../components/CitedSourcesList'
import UploadSourceDialog from '../components/UploadSourceDialog'
import UserPaper from '../components/UserPaper'

function pollSummaryToast(summary: PollSummary): { tone: ToastTone; message: string } {
  const checked = summary.stored.length
  const failed = summary.source_errors.length + summary.store_errors.length
  const parts = [`Checked ${checked} paper${checked === 1 ? '' : 's'}`]
  if (failed > 0) parts.push(`${failed} couldn't be checked, retried next poll`)
  if (summary.skipped_no_doi.length > 0) parts.push(`${summary.skipped_no_doi.length} skipped (no DOI)`)
  if (summary.nudge_error) parts.push(`Evaluation failed: ${summary.nudge_error.reason}`)
  return { tone: failed > 0 || summary.nudge_error ? 'error' : 'success', message: parts.join(' · ') }
}

function CitationSources() {
  const navigate = useNavigate()
  const { state, refresh } = useTracking()
  const showToast = useToast()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [polling, setPolling] = useState(false)
  const [lastPoll, setLastPoll] = useState<PollSummary | null>(null)

  const runCheck = async (paperId?: string) => {
    if (polling) return
    setPolling(true)
    try {
      const summary = await runPoll(paperId)
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

  return (
    <main className="px-4 py-10 sm:px-6 lg:px-8">
      <div className="lg:flex lg:items-center lg:justify-between">
        <div className="min-w-0 flex-1">
          <h2 className="text-2xl/7 font-bold text-white sm:truncate sm:text-3xl sm:tracking-tight">
            Sources cited
          </h2>
          {lastPoll && (
            <div className="mt-1 flex flex-col sm:mt-0 sm:flex-row sm:flex-wrap sm:space-x-6">
              <div className="mt-2 flex items-center text-sm text-gray-400">
                <CalendarIcon aria-hidden="true" className="mr-1.5 size-5 shrink-0 text-gray-500" />
                Checked {formatDateTime(lastPoll.finished_at)}
              </div>
            </div>
          )}
        </div>

        <div className="mt-5 flex flex-wrap items-center gap-3 lg:mt-0 lg:ml-4">
          <button
            type="button"
            onClick={() => navigate('/')}
            className="inline-flex items-center rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-xs inset-ring inset-ring-white/10 hover:bg-white/20"
          >
            <ArrowLeftIcon aria-hidden="true" className="mr-1.5 -ml-0.5 size-5 text-gray-300" />
            Back to folders
          </button>

          <button
            type="button"
            disabled
            title="Set on the server (POLL_INTERVAL_HOURS)"
            className="inline-flex items-center rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-xs inset-ring inset-ring-white/10 disabled:cursor-not-allowed disabled:opacity-50"
          >
            Schedule updates
          </button>

          <button
            type="button"
            disabled={polling}
            onClick={() => void runCheck()}
            className="inline-flex items-center rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white shadow-xs hover:bg-indigo-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <ArrowPathIcon aria-hidden="true" className={`mr-1.5 -ml-0.5 size-5 ${polling ? 'animate-spin' : ''}`} />
            {polling ? 'Checking…' : 'Update now'}
          </button>

          <button
            type="button"
            onClick={() => setUploadOpen(true)}
            aria-label="Upload PDF"
            title="Upload PDF"
            className="cursor-pointer rounded-full bg-indigo-500 p-2 text-white shadow-xs hover:bg-indigo-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400"
          >
            <PlusIcon aria-hidden="true" className="size-5" />
          </button>
        </div>
      </div>

      <UserPaper />

      {state.status === 'loading' && <p className="mt-10 text-sm text-gray-400">Loading tracked papers…</p>}
      {state.status === 'error' && (
        <p className="mt-10 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">
          {state.message}
        </p>
      )}
      {state.status === 'ready' && (
        <CitedSourcesList
          papers={state.papers.filter((paper) => paper.folder_id === null)}
          alerts={state.alerts}
          onRefreshCheck={(paperId) => void runCheck(paperId)}
          onDelete={(paperId) => void handleDelete(paperId)}
        />
      )}

      <UploadSourceDialog open={uploadOpen} onClose={() => setUploadOpen(false)} />
    </main>
  )
}

export default CitationSources