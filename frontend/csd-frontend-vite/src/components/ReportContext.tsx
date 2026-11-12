// What impact read beside a report (its `context_chunks`, resolved by context/resolve.ts), shown the way
// the report shows its quotes: a list on the left, the chosen item in a pane on the right.
// Documents fetched from outside are laid down as paper, like any source; the desk's own records (an
// alert, an earlier report, the researcher's feedback or profile) keep the desk's look, so they read as ours.
// Publisher and model text throughout: rendered as text, never as HTML.
import {
  BellAlertIcon,
  ChatBubbleBottomCenterTextIcon,
  ClipboardDocumentListIcon,
  DocumentTextIcon,
  QuestionMarkCircleIcon,
  UserCircleIcon,
} from '@heroicons/react/20/solid'
import type { ComponentType, ReactNode, SVGProps } from 'react'
import { Link } from 'react-router-dom'
import type { UserDocument, UserReport } from '../api'
import { entryMeta, entryTitle, MISSING_LABEL, profileAnswers, VERDICT_LABEL } from '../context/describe'
import { parseJsonText, type ContextEntry } from '../context/resolve'
import { ALERT_SEVERITY_LABEL, LEVEL_LABEL, LEVEL_STYLES, formatDateTime } from '../format'
import PdfPane from '../highlight/PdfPane'
import { FAKE_PDF_BASE } from '../highlight/sources'
import TextPane from '../highlight/TextPane'
import { isFake } from '../mocks/fakeReport'
import { badge, signalLink } from '../ui'

const ALERT_SEVERITY_STYLES = {
  high: 'bg-red-400/10 text-red-400 inset-ring-red-400/20',
  medium: 'bg-yellow-400/10 text-yellow-500 inset-ring-yellow-400/20',
  low: 'bg-gray-400/10 text-gray-400 inset-ring-gray-400/20',
} as const

const NEUTRAL_BADGE = 'bg-gray-400/10 text-gray-300 inset-ring-gray-400/20'

type Icon = ComponentType<SVGProps<SVGSVGElement>>

const ICON: Record<ContextEntry['kind'], Icon> = {
  document: DocumentTextIcon,
  alert: BellAlertIcon,
  report: ClipboardDocumentListIcon,
  feedback: ChatBubbleBottomCenterTextIcon,
  profile: UserCircleIcon,
  missing: QuestionMarkCircleIcon,
}

// ---- the list, in the claim column

export function ContextList({
  entries,
  selected,
  onPick,
}: {
  entries: ContextEntry[]
  selected: ContextEntry | undefined
  onPick: (entry: ContextEntry) => void
}) {
  const past = entries.filter((e) => e.kind !== 'feedback' && e.kind !== 'profile')
  const feedback = entries.filter((e) => e.kind === 'feedback')
  const profile = entries.filter((e) => e.kind === 'profile')
  return (
    <div className="space-y-5">
      <p className="px-3 text-sm/6 text-gray-400">
        What the assessment read beside this report, most relevant first.
      </p>
      {past.length > 0 && (
        <ContextGroup label="From this paper's past" entries={past} selected={selected} onPick={onPick} />
      )}
      {feedback.length > 0 && (
        <ContextGroup label="From your feedback" entries={feedback} selected={selected} onPick={onPick} />
      )}
      {profile.length > 0 && (
        <ContextGroup label="From your profile" entries={profile} selected={selected} onPick={onPick} />
      )}
    </div>
  )
}

function ContextGroup({
  label,
  entries,
  selected,
  onPick,
}: {
  label: string
  entries: ContextEntry[]
  selected: ContextEntry | undefined
  onPick: (entry: ContextEntry) => void
}) {
  return (
    <section aria-label={label}>
      <h3 className="px-3 text-xs/5 font-medium tracking-wide text-gray-400">{label}</h3>
      <ul role="list" className="mt-1.5 space-y-1">
        {entries.map((entry) => (
          <li key={entry.key}>
            <ContextRow entry={entry} active={entry.key === selected?.key} onPick={() => onPick(entry)} />
          </li>
        ))}
      </ul>
    </section>
  )
}

function ContextRow({ entry, active, onPick }: { entry: ContextEntry; active: boolean; onPick: () => void }) {
  const Icon = ICON[entry.kind]
  const meta = entryMeta(entry)
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onPick}
      className={`flex w-full gap-3 rounded-xl px-3.5 py-2.5 text-left transition-colors focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-indigo-400 ${
        active
          ? 'bg-indigo-500/12 outline-1 -outline-offset-1 outline-indigo-400'
          : 'outline-1 -outline-offset-1 outline-transparent hover:bg-white/5 hover:outline-white/15'
      }`}
    >
      <Icon aria-hidden="true" className={`mt-1 size-4 shrink-0 ${active ? 'text-indigo-300' : 'text-gray-500'}`} />
      <span className="min-w-0">
        <span className={`line-clamp-2 block text-sm/6 ${active ? 'text-white' : 'text-gray-300'} ${entry.kind === 'missing' ? 'italic' : ''}`}>
          {entryTitle(entry)}
        </span>
        {meta.length > 0 && (
          <span className="mt-0.5 block text-xs/5 text-gray-400">{meta.join(' · ')}</span>
        )}
      </span>
    </button>
  )
}

// ---- the pane, in the quotes column

export function ContextPane({ entry, seq }: { entry: ContextEntry; seq: number }) {
  const Icon = ICON[entry.kind]
  const title = entryTitle(entry)
  const page = entry.kind === 'document' ? entry.page : null
  return (
    <div className="flex h-[70dvh] min-h-[26rem] flex-col overflow-hidden rounded-2xl bg-gray-900 outline-1 -outline-offset-1 outline-white/10 lg:h-auto lg:min-h-40 lg:flex-1 lg:basis-0">
      <div className="flex min-h-9 items-center justify-between gap-3 border-b border-white/10 px-4 py-0.5">
        <h2 title={title} className="flex min-w-0 items-center gap-2 text-sm/6 font-semibold text-white">
          <Icon aria-hidden="true" className="size-4 shrink-0 text-gray-400" />
          <span className="truncate">{title}</span>
        </h2>
        {page !== null && <span className="shrink-0 text-xs text-gray-400 tabular-nums">p. {page}</span>}
      </div>
      <div className="min-h-0 flex-1">
        <PaneBody entry={entry} seq={seq} />
      </div>
    </div>
  )
}

function PaneBody({ entry, seq }: { entry: ContextEntry; seq: number }) {
  switch (entry.kind) {
    case 'document':
      return <DocumentBody entry={entry} seq={seq} />
    case 'alert':
      return <AlertBody entry={entry} />
    case 'report':
      return <ReportBody report={entry.report} />
    case 'feedback':
      return <FeedbackBody entry={entry} />
    case 'profile':
      return <ProfileBody entry={entry} />
    case 'missing':
      return (
        <DeskBody>
          <p className="text-sm/6 text-gray-400">
            {MISSING_LABEL[entry.sourceType]} the assessment read has been removed since, so there's nothing left to show.
          </p>
        </DeskBody>
      )
  }
}

// the dark desk under the desk's own records, scrolling like a source pane
function DeskBody({ children }: { children: ReactNode }) {
  return (
    <div
      tabIndex={0}
      className="h-full overflow-y-auto overscroll-contain p-4 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-indigo-500 sm:p-6"
    >
      <div className="max-w-[75ch] space-y-4">{children}</div>
    </div>
  )
}

function Badges({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap items-center gap-2">{children}</div>
}

function Inset({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-xl bg-white/[0.03] px-3.5 py-2.5 text-sm/6 text-gray-200 outline-1 -outline-offset-1 outline-white/5">
      {children}
    </p>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <h3 className="text-sm/6 font-semibold text-white">{label}</h3>
      <div className="mt-1 text-sm/6 text-gray-300">{children}</div>
    </div>
  )
}

function Footnote({ children }: { children: ReactNode }) {
  return <div className="flex min-w-0 flex-wrap items-center gap-x-2 text-xs/5 text-gray-400">{children}</div>
}

function AlertBody({ entry }: { entry: Extract<ContextEntry, { kind: 'alert' }> }) {
  const { alert } = entry
  return (
    <DeskBody>
      <Badges>
        <span className={`${badge} ${ALERT_SEVERITY_STYLES[alert.alert_severity]}`}>
          {ALERT_SEVERITY_LABEL[alert.alert_severity]}
        </span>
      </Badges>
      <p className="text-sm/6 text-gray-300">{alert.description}</p>
      {alert.recommendation && <Inset>{alert.recommendation}</Inset>}
      <Footnote>
        <span>
          Detected <time dateTime={alert.detected_at}>{formatDateTime(alert.detected_at)}</time>
        </span>
        {alert.notice_doi && <span className="min-w-0 truncate">· Notice DOI {alert.notice_doi}</span>}
      </Footnote>
    </DeskBody>
  )
}

function ReportBody({ report }: { report: UserReport }) {
  const noImpact = report.change_severity === 'none'
  return (
    <DeskBody>
      <Badges>
        {noImpact && <span className={`${badge} ${LEVEL_STYLES.none}`}>Didn't affect your work</span>}
        {report.change_severity && !noImpact && (
          <span className={`${badge} ${LEVEL_STYLES[report.change_severity]}`}>
            {LEVEL_LABEL[report.change_severity]} severity
          </span>
        )}
        {report.impact_level && (
          <span className={`${badge} ${LEVEL_STYLES[report.impact_level]}`}>
            {LEVEL_LABEL[report.impact_level]} impact on your draft
          </span>
        )}
      </Badges>
      {report.change_summary && <p className="text-sm/6 text-gray-200">{report.change_summary}</p>}
      {report.evaluation && <Field label="Evaluation">{report.evaluation}</Field>}
      {report.recommendation && (
        <div>
          <h3 className="text-sm/6 font-semibold text-white">Recommendation</h3>
          <div className="mt-1">
            <Inset>{report.recommendation}</Inset>
          </div>
        </div>
      )}
      <Footnote>
        <span>
          Created <time dateTime={report.created_at}>{formatDateTime(report.created_at)}</time>
        </span>
        <Link to={`/reports/${report.paper_id}/${report.id}`} className={`${signalLink} text-xs/5`}>
          Open this report
        </Link>
      </Footnote>
    </DeskBody>
  )
}

function FeedbackBody({ entry }: { entry: Extract<ContextEntry, { kind: 'feedback' }> }) {
  const link = entry.paperId ? `/reports/${entry.paperId}/${entry.reportId}` : null
  return (
    <DeskBody>
      <p className="text-sm/6 text-gray-300">
        The assessment read what you said about an earlier report while it wrote this one.
      </p>
      <dl className="divide-y divide-white/10 rounded-xl outline-1 -outline-offset-1 outline-white/10">
        <Fact label="Paper">{entry.paperTitle ?? 'A paper no longer tracked'}</Fact>
        <Fact label="Your verdict">
          <span className={`${badge} ${NEUTRAL_BADGE}`}>{VERDICT_LABEL[entry.verdict]}</span>
        </Fact>
        {entry.date && (
          <Fact label="Given">
            <time dateTime={entry.date}>{formatDateTime(entry.date)}</time>
          </Fact>
        )}
      </dl>
      {/* the item carries no note, so it's read off the report it names (its source id): the note as
          saved now, which may have been edited since the assessment read it */}
      {entry.report?.feedback?.note && (
        <Field label="Your note">
          <Inset>{entry.report.feedback.note}</Inset>
        </Field>
      )}
      {entry.report?.change_summary && (
        <Field label="That report said">
          <p>{entry.report.change_summary}</p>
        </Field>
      )}
      {link && (
        <Link to={link} className={`${signalLink} inline-block text-sm/6`}>
          Open the report you gave feedback on
        </Link>
      )}
    </DeskBody>
  )
}

// The profile the item carried, which is never fetched: only dev mode's fake report sends one
function ProfileBody({ entry }: { entry: Extract<ContextEntry, { kind: 'profile' }> }) {
  const answers = profileAnswers(entry.profile)
  return (
    <DeskBody>
      <p className="text-sm/6 text-gray-300">
        The assessment read these answers from your profile while it wrote this report.
      </p>
      {answers.length > 0 ? (
        <dl className="divide-y divide-white/10 rounded-xl outline-1 -outline-offset-1 outline-white/10">
          {answers.map((answer) => (
            <Fact key={answer.label} label={answer.label}>
              <span className="whitespace-pre-line">{answer.value}</span>
            </Fact>
          ))}
        </dl>
      ) : (
        <p className="text-sm/6 text-gray-400">The profile it read had no answers.</p>
      )}
    </DeskBody>
  )
}

// one label and value of a record on the desk
function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-3.5 py-2.5 text-sm/6">
      <dt className="text-gray-400">{label}</dt>
      <dd className="min-w-0 text-right text-gray-200">{children}</dd>
    </div>
  )
}

// ---- a fetched document: paper, as every source is

function DocumentBody({ entry, seq }: { entry: Extract<ContextEntry, { kind: 'document' }>; seq: number }) {
  const { doc, page, range } = entry
  const pdf = pdfUrl(doc, entry.report)
  if (page !== null && pdf) {
    return <PdfPane key={pdf} url={pdf} highlight={null} focus={{ page, seq }} />
  }
  if (doc.text) {
    const json = parseJsonText(doc.text)
    return (
      <div className="flex h-full flex-col">
        {doc.text_truncated && (
          <PaneNote>Only the first 60,000 characters of this text were kept, and only they were read.</PaneNote>
        )}
        <div className="min-h-0 flex-1">
          {json ? <JsonPane value={json} /> : <TextPane text={doc.text} quote={null} range={range} seq={seq} />}
        </div>
      </div>
    )
  }
  return (
    <DeskBody>
      <p className="text-sm/6 text-gray-400">
        {page !== null ? `Page ${page} of this document was read, but` : 'This document was read, but'} no copy of
        it is stored to show here.
      </p>
      {doc.pdf_source_url && (
        <a href={doc.pdf_source_url} target="_blank" rel="noreferrer" className={`${signalLink} text-sm/6`}>
          Open the original
        </a>
      )}
    </DeskBody>
  )
}

// the dev mode example's PDFs are on the public route; a researcher's own come from Storage with a token
function pdfUrl(doc: UserDocument, report: UserReport | null): string | null {
  if (doc.pdf_status !== 'ok') return null
  if (report && isFake(report) && doc.pdf_source_url?.startsWith(FAKE_PDF_BASE)) return doc.pdf_source_url
  return `/api/documents/${doc.id}/pdf`
}

function PaneNote({ children }: { children: ReactNode }) {
  return <p className="border-b border-white/10 bg-white/[0.03] px-4 py-2 text-xs/5 text-gray-400">{children}</p>
}

// A text that turned out to be a JSON record, set as a readable record on paper: keys as quiet labels,
// nesting as indented rules, rather than braces and quotes
function JsonPane({ value }: { value: object }) {
  return (
    <div
      tabIndex={0}
      aria-label="Source record"
      className="pdf-pane h-full overflow-y-auto overscroll-contain bg-gray-950/60 p-3 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-indigo-500 sm:p-4"
    >
      <div className="paper-column rounded-md bg-white px-5 py-4 shadow-md shadow-black/40 sm:px-8 sm:py-6">
        <div className="mx-auto max-w-[75ch] text-sm/6 text-gray-800">
          <JsonValue value={value} />
        </div>
      </div>
    </div>
  )
}

function humanKey(key: string): string {
  const words = key.replace(/[_-]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').trim()
  return words ? words[0].toUpperCase() + words.slice(1) : key
}

function JsonValue({ value }: { value: unknown }) {
  if (value === null || value === undefined) return <span className="text-gray-400 italic">none</span>
  if (typeof value === 'boolean') return <span>{value ? 'Yes' : 'No'}</span>
  if (typeof value === 'number') return <span className="tabular-nums">{value}</span>
  if (typeof value === 'string') {
    return value.trim() ? <span className="break-words whitespace-pre-line">{value}</span> : <span className="text-gray-400 italic">empty</span>
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-gray-400 italic">none</span>
    if (value.every((item) => item === null || typeof item !== 'object')) {
      return (
        <ul role="list" className="flex flex-wrap gap-1.5">
          {value.map((item, i) => (
            <li key={i} className="rounded-md bg-gray-100 px-2 py-0.5 text-[0.8125rem]/5 text-gray-700">
              <JsonValue value={item} />
            </li>
          ))}
        </ul>
      )
    }
    return (
      <ol role="list" className="space-y-3">
        {value.map((item, i) => (
          <li key={i} className="flex gap-3">
            <span className="w-5 shrink-0 pt-px text-right text-xs/6 text-gray-400 tabular-nums">{i + 1}</span>
            <div className="min-w-0 flex-1">
              <JsonValue value={item} />
            </div>
          </li>
        ))}
      </ol>
    )
  }
  const entries = Object.entries(value as Record<string, unknown>)
  if (entries.length === 0) return <span className="text-gray-400 italic">empty</span>
  return (
    <dl className="space-y-2.5">
      {entries.map(([key, item]) => {
        const nested = item !== null && typeof item === 'object' && !(Array.isArray(item) && item.every((x) => x === null || typeof x !== 'object'))
        return (
          <div key={key} className={nested ? '' : 'grid gap-x-4 sm:grid-cols-[minmax(7rem,12rem)_minmax(0,1fr)]'}>
            <dt className="text-xs/6 font-medium tracking-wide text-gray-500">{humanKey(key)}</dt>
            <dd className={nested ? 'mt-1.5 border-l border-gray-200 pl-4' : 'min-w-0'}>
              <JsonValue value={item} />
            </dd>
          </div>
        )
      })}
    </dl>
  )
}
