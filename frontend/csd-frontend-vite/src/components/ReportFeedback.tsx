// The researcher's verdict on an assessed report, at its end: a tick for fair, a cross that asks which
// way it was off (overstated or understated), and an optional note. One verdict covers the report as a
// whole: how serious its changes, impact and recommendation made the change out to be
// (docs/CONTRACTS.md, PUT and DELETE /papers/{paperId}/reports/{reportId}/feedback). Saving it lets
// later assessments of the researcher's papers read it; it never changes this report's ratings.
import {
  ArrowTrendingDownIcon,
  ArrowTrendingUpIcon,
  CheckIcon,
  XMarkIcon,
} from '@heroicons/react/20/solid'
import { useId, useState, type ComponentType, type KeyboardEvent, type ReactNode, type SVGProps } from 'react'
import {
  deleteReportFeedback,
  errorMessage,
  FEEDBACK_NOTE_MAX,
  saveReportFeedback,
  type FeedbackVerdict,
  type ReportFeedback as Feedback,
  type UserReport,
} from '../api'
import { formatDateTime } from '../format'
import { useTracking } from '../tracking'
import { card, errorPanel, inputClasses } from '../ui'
import { ActionButton } from './ActionButton'
import { useToast } from './Toasts'

type Draft = {
  verdict: FeedbackVerdict | null
  // the cross is down but neither way is picked yet
  off: boolean
  note: string
}

type Busy = 'idle' | 'saving' | 'removing'

const OFF_WAYS: { verdict: Exclude<FeedbackVerdict, 'fair'>; label: string; hint: string; Icon: Icon }[] = [
  {
    verdict: 'overstated',
    label: 'Overstated',
    hint: 'It made the change seem more serious than it is',
    Icon: ArrowTrendingUpIcon,
  },
  {
    verdict: 'understated',
    label: 'Understated',
    hint: 'It made the change seem less serious than it is',
    Icon: ArrowTrendingDownIcon,
  },
]

type Icon = ComponentType<SVGProps<SVGSVGElement>>

function draftOf(feedback: Feedback | null): Draft {
  return { verdict: feedback?.verdict ?? null, off: false, note: feedback?.note ?? '' }
}

export default function ReportFeedback({ report, className = '' }: { report: UserReport; className?: string }) {
  const { refresh } = useTracking()
  const showToast = useToast()
  const ids = useId()
  const [saved, setSaved] = useState<Feedback | null>(report.feedback)
  const [draft, setDraft] = useState<Draft>(() => draftOf(report.feedback))
  const [busy, setBusy] = useState<Busy>('idle')
  const [error, setError] = useState<string | null>(null)

  const note = draft.note.trim()
  const dirty = draft.verdict !== (saved?.verdict ?? null) || note !== (saved?.note ?? '')
  const tooLong = note.length > FEEDBACK_NOTE_MAX

  // Tracking reloads every 30 s. Take a newer server copy (another tab, or our own save coming back),
  // but never over what the researcher is still typing.
  const serverKey = report.feedback?.updated_at ?? null
  const [seenKey, setSeenKey] = useState(serverKey)
  if (serverKey !== seenKey) {
    setSeenKey(serverKey)
    if (!dirty && busy === 'idle') {
      setSaved(report.feedback)
      setDraft(draftOf(report.feedback))
    }
  }

  const offDown = draft.off || draft.verdict === 'overstated' || draft.verdict === 'understated'
  const canSave = draft.verdict !== null && dirty && !tooLong && busy === 'idle'

  const put = async (body: { verdict: FeedbackVerdict; note: string | null }) => {
    const stored = await saveReportFeedback(report.paper_id, report.id, body)
    setSaved(stored)
    setDraft(draftOf(stored))
    void refresh()
    return stored
  }

  const save = async () => {
    if (!canSave || draft.verdict === null) return
    setBusy('saving')
    setError(null)
    try {
      await put({ verdict: draft.verdict, note: note || null })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy('idle')
    }
  }

  const remove = async () => {
    const previous = saved
    if (!previous) return
    setBusy('removing')
    setError(null)
    try {
      await deleteReportFeedback(report.paper_id, report.id)
      setSaved(null)
      setDraft(draftOf(null))
      void refresh()
      showToast({
        tone: 'info',
        message: 'Feedback removed from this report.',
        action: {
          label: 'Undo',
          onClick: () => {
            put({ verdict: previous.verdict, note: previous.note }).catch((err: unknown) =>
              showToast({ tone: 'error', message: `Couldn't restore your feedback: ${errorMessage(err)}` }),
            )
          },
        },
      })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy('idle')
    }
  }

  const onNoteKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
      event.preventDefault()
      void save()
    }
  }

  const headingId = `${ids}-heading`
  const noteId = `${ids}-note`
  const countId = `${ids}-count`

  return (
    <section aria-labelledby={headingId} className={`${card} p-4 sm:px-5 ${className}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <h2 id={headingId} className="text-sm/6 font-semibold text-white">
          Was this report fair?
        </h2>
        {saved && (
          <p className="text-xs/5 text-gray-400">
            Saved <time dateTime={saved.updated_at}>{formatDateTime(saved.updated_at)}</time>
          </p>
        )}
      </div>
      <p className="mt-0.5 text-xs/5 text-pretty text-gray-400">
        Judge how serious its changes, impact and recommendation made this out to be. Later assessments of
        your papers read your verdict.
      </p>

      <div role="group" aria-label="Verdict" className="mt-3 flex gap-2">
        <VerdictButton
          pressed={draft.verdict === 'fair'}
          Icon={CheckIcon}
          onClick={() => setDraft((d) => ({ ...d, verdict: 'fair', off: false }))}
        >
          Fair
        </VerdictButton>
        <VerdictButton
          pressed={offDown}
          Icon={XMarkIcon}
          onClick={() =>
            setDraft((d) => (d.verdict === 'fair' || d.verdict === null ? { ...d, verdict: null, off: true } : d))
          }
        >
          Not quite
        </VerdictButton>
      </div>

      {offDown && (
        <fieldset className="animate-reveal mt-3">
          <legend className="text-xs/5 text-gray-400">Which way was it off?</legend>
          <div className="mt-1.5 flex flex-wrap gap-2">
            {OFF_WAYS.map(({ verdict, label, hint, Icon }) => {
              const chosen = draft.verdict === verdict
              return (
                <button
                  key={verdict}
                  type="button"
                  aria-pressed={chosen}
                  title={hint}
                  onClick={() => setDraft((d) => ({ ...d, verdict, off: false }))}
                  className={`inline-flex min-h-10 items-center gap-1.5 rounded-full px-3.5 py-1.5 outline-1 -outline-offset-1 transition-[background-color,color,outline-color] duration-150 focus-visible:outline-2 focus-visible:outline-indigo-400 sm:min-h-8 sm:py-1 ${
                    chosen
                      ? 'bg-indigo-500/12 text-white outline-indigo-400'
                      : 'text-gray-300 outline-white/15 hover:bg-white/5 hover:text-white hover:outline-white/30'
                  }`}
                >
                  <Icon aria-hidden="true" className={`size-4 ${chosen ? 'text-indigo-300' : 'text-gray-400'}`} />
                  <span className="text-sm/5 font-medium">{label}</span>
                </button>
              )
            })}
          </div>
          <p aria-live="polite" className="mt-1.5 min-h-5 text-xs/5 text-gray-400">
            {OFF_WAYS.find((way) => way.verdict === draft.verdict)?.hint.concat('.') ?? ''}
          </p>
        </fieldset>
      )}

      {(draft.verdict !== null || saved) && (
        <div className="animate-reveal mt-3">
          <label htmlFor={noteId} className="flex items-baseline gap-1.5 text-sm/6 font-medium text-gray-200">
            Note <span className="text-xs/5 font-normal text-gray-400">optional</span>
          </label>
          {/* index.css gives textareas font: inherit, which outranks utilities on the field, so the size sits here */}
          <div className="mt-1 text-sm/6">
          <textarea
            id={noteId}
            value={draft.note}
            onChange={(event) => setDraft((d) => ({ ...d, note: event.target.value }))}
            onKeyDown={onNoteKey}
            rows={2}
            aria-invalid={tooLong || undefined}
            aria-describedby={countId}
            placeholder="What should later reports know? e.g. we only use their dataset, not their method."
            className={`${inputClasses} max-h-48 min-h-16 resize-none [field-sizing:content]`}
          />
          </div>
          <p id={countId} className={`mt-1 text-right text-xs/5 tabular-nums ${tooLong ? 'text-red-400' : 'text-gray-500'}`}>
            {tooLong ? `${note.length - FEEDBACK_NOTE_MAX} over the limit · ` : ''}
            {note.length} / {FEEDBACK_NOTE_MAX}
          </p>
        </div>
      )}

      {error && (
        <p role="alert" className={`${errorPanel} mt-3`}>
          {error}
        </p>
      )}

      {(dirty || saved) && (
        <div className="mt-3 flex flex-wrap items-center justify-end gap-2">
          {saved && (
            <ActionButton tone="quiet" onClick={() => void remove()} disabled={busy !== 'idle'}>
              {busy === 'removing' ? 'Removing…' : 'Remove'}
            </ActionButton>
          )}
          {dirty && (
            <ActionButton tone="secondary" onClick={() => setDraft(draftOf(saved))} disabled={busy !== 'idle'}>
              Cancel
            </ActionButton>
          )}
          <ActionButton tone="primary" onClick={() => void save()} disabled={!canSave}>
            {busy === 'saving' ? 'Saving…' : saved ? 'Update' : 'Save'}
          </ActionButton>
        </div>
      )}
    </section>
  )
}

function VerdictButton({
  pressed,
  Icon,
  onClick,
  children,
}: {
  pressed: boolean
  Icon: Icon
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={`inline-flex min-h-10 flex-1 items-center justify-center gap-1.5 rounded-full px-4 py-2 outline-1 -outline-offset-1 transition-[background-color,color,outline-color,transform] duration-150 active:scale-[0.97] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400 sm:min-h-9 sm:flex-none sm:py-1.5 ${
        pressed
          ? 'bg-indigo-500 text-white shadow-[0_8px_20px_-8px_rgba(99,102,241,0.8)] outline-transparent'
          : 'text-gray-200 outline-white/15 hover:bg-white/5 hover:text-white'
      }`}
    >
      <Icon aria-hidden="true" className="size-4" />
      <span className="text-sm/5 font-semibold">{children}</span>
    </button>
  )
}
