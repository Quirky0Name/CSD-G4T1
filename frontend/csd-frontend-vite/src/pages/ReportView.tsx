// One report with its claims, and each claim's passages highlighted: the sentence in the researcher's
// draft beside the passage in the source it relies on. The report comes from the tracking state, which
// already loads every report; the selected claim is kept in ?claim= so a reload opens it again.
import { ArrowLeftIcon, CheckCircleIcon, MinusCircleIcon } from '@heroicons/react/20/solid'
import { useEffect, useState, type ReactNode } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import type { Claim, ClaimKind, Passage, Paper, UserReport } from '../api'
import { LEVEL_STYLES, REPORT_STATUS_LABEL, REPORT_STATUS_STYLES } from '../format'
import { findQuote } from '../highlight/normalise'
import PdfPane, { type PaneResult } from '../highlight/PdfPane'
import { sourceFor } from '../highlight/sources'
import TextPane from '../highlight/TextPane'
import { useTracking } from '../tracking'

const KIND_ORDER: ClaimKind[] = ['change', 'impact', 'action']
const KIND_HEADING: Record<ClaimKind, string> = {
  change: 'What changed',
  impact: 'How it affects your draft',
  action: 'What to do',
}
const NOT_PLACED: Record<Exclude<Passage['verified'], 'verbatim'>, string> = {
  not_found: 'Not found in the source',
  no_text: 'Source had no readable text',
}

type PaneName = 'draft' | 'source'
type Picked = { claim: string; draft?: Passage; source?: Passage }

export default function ReportView() {
  const { paperId, reportId } = useParams()
  const { state } = useTracking()

  if (state.status === 'loading') {
    return (
      <Shell>
        <p role="status" className="text-sm/6 text-gray-400">Loading the report…</p>
      </Shell>
    )
  }
  if (state.status === 'error') {
    return (
      <Shell>
        <p className="rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">{state.message}</p>
      </Shell>
    )
  }

  const report = state.reports.find((r) => r.paper_id === paperId && String(r.id) === reportId)
  const paper = state.papers.find((p) => p.id === paperId)
  if (!report || !paper) {
    return (
      <Shell>
        <p className="text-sm/6 text-gray-300">This report doesn't exist or isn't yours.</p>
        <BackLink to="/alerts" />
      </Shell>
    )
  }
  return <ReportBody key={report.id} report={report} paper={paper} />
}

function Shell({ children }: { children: ReactNode }) {
  return <main className="px-4 py-10 text-gray-100 sm:px-6 lg:px-8">{children}</main>
}

function BackLink({ to }: { to: string }) {
  return (
    <Link to={to} className="inline-flex items-center gap-1 text-sm font-semibold text-indigo-400 hover:text-indigo-300">
      <ArrowLeftIcon aria-hidden="true" className="size-4" />
      Alerts
    </Link>
  )
}

function ReportBody({ report, paper }: { report: UserReport; paper: Paper }) {
  const [params, setParams] = useSearchParams()
  const [picked, setPicked] = useState<Picked | null>(null)
  // bumped on every selection, so choosing the same passage again scrolls back to it
  const [seq, setSeq] = useState(0)
  const [tab, setTab] = useState<PaneName>('draft')
  const title = paper.title ?? 'Untitled paper'

  useEffect(() => {
    document.title = `${title} · Report`
  }, [title])

  const noImpact = report.change_severity === 'none'
  const claims = (report.claims ?? []).filter((claim) => !noImpact || claim.kind === 'change')
  const selected = claims.find((c) => c.id === params.get('claim')) ?? defaultClaim(claims)

  const pickClaim = (claim: Claim) => {
    setParams({ claim: claim.id }, { replace: true })
    setPicked(null)
    setSeq((n) => n + 1)
  }

  const pickPassage = (claim: Claim, passage: Passage) => {
    const pane: PaneName = passage.target === 'draft' ? 'draft' : 'source'
    if (claim.id !== selected?.id) setParams({ claim: claim.id }, { replace: true })
    setPicked((current) => ({ ...(current?.claim === claim.id ? current : {}), claim: claim.id, [pane]: passage }))
    setSeq((n) => n + 1)
    setTab(pane)
  }

  const chosen = picked && picked.claim === selected?.id ? picked : null
  const draftPassage = chosen?.draft ?? selected?.passages.find((p) => p.target === 'draft' && p.verified === 'verbatim')
  const sourcePassage = chosen?.source ?? selected?.passages.find((p) => p.target !== 'draft' && p.verified === 'verbatim')

  return (
    <main className="px-4 pt-8 pb-6 text-gray-100 sm:px-6 lg:px-8">
      <BackLink to={`/alerts?paper=${report.paper_id}`} />

      <header className="mt-4">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <h1 className="text-2xl/8 font-bold text-balance text-white">{title}</h1>
          <Badge className={REPORT_STATUS_STYLES[report.status]}>{REPORT_STATUS_LABEL[report.status]}</Badge>
          {report.change_severity && (
            <Badge className={LEVEL_STYLES[report.change_severity]}>{sentence(report.change_severity)} change</Badge>
          )}
          {report.impact_level && <Badge className={LEVEL_STYLES[report.impact_level]}>{sentence(report.impact_level)} impact</Badge>}
        </div>
        {report.change_summary && <p className="mt-3 max-w-3xl text-sm/6 text-gray-300">{report.change_summary}</p>}
        {noImpact && <p className="mt-2 text-sm/6 font-medium text-gray-200">This change doesn't affect your work.</p>}
      </header>

      {report.status !== 'assessed' ? (
        <p className="mt-8 text-sm/6 text-gray-400">This report is still being assessed.</p>
      ) : report.claims === null ? (
        <NoClaims report={report} />
      ) : (
        <div className="mt-8 grid gap-6 lg:grid-cols-[minmax(17rem,22rem)_minmax(0,1fr)] lg:items-start">
          <ClaimList claims={claims} report={report} selected={selected} onClaim={pickClaim} onPassage={pickPassage} />

          <section aria-label="Passages" className="min-w-0 lg:sticky lg:top-4">
            <div role="tablist" aria-label="Pane" className="mb-3 flex gap-2 xl:hidden">
              {(['draft', 'source'] as const).map((name) => (
                <button
                  key={name}
                  type="button"
                  role="tab"
                  aria-selected={tab === name}
                  onClick={() => setTab(name)}
                  className="rounded-md px-3 py-1.5 text-gray-300 aria-selected:bg-white/10 aria-selected:text-white hover:text-white"
                >
                  <span className="text-sm font-semibold">{name === 'draft' ? 'Your draft' : 'Cited source'}</span>
                </button>
              ))}
            </div>
            <div className="grid gap-4 xl:grid-cols-2">
              <PassagePane
                name="Your draft"
                hidden={tab !== 'draft'}
                report={report}
                passage={draftPassage ?? null}
                seq={seq}
                empty={selected ? 'This claim quotes nothing from your draft.' : 'Pick a claim to see its passages.'}
              />
              <PassagePane
                name="Cited source"
                hidden={tab !== 'source'}
                report={report}
                passage={sourcePassage ?? null}
                seq={seq}
                empty={selected ? 'This claim quotes no source.' : 'Pick a claim to see its passages.'}
              />
            </div>
          </section>
        </div>
      )}
    </main>
  )
}

// The researcher's question is how their draft is affected, so open on that when nothing is picked
function defaultClaim(claims: Claim[]): Claim | undefined {
  return claims.find((c) => c.kind === 'impact' && c.supported) ?? claims.find((c) => c.supported) ?? claims[0]
}

function Badge({ className, children }: { className: string; children: ReactNode }) {
  return (
    <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${className}`}>{children}</span>
  )
}

function sentence(word: string): string {
  return word[0].toUpperCase() + word.slice(1)
}

function NoClaims({ report }: { report: UserReport }) {
  return (
    <div className="mt-8 max-w-3xl space-y-4 text-sm/6">
      <p className="text-gray-400">There are no highlighted passages for this report.</p>
      {report.evaluation && (
        <div>
          <h2 className="font-semibold text-white">Evaluation</h2>
          <p className="mt-1 text-gray-300">{report.evaluation}</p>
        </div>
      )}
      {report.recommendation && (
        <div>
          <h2 className="font-semibold text-white">Recommendation</h2>
          <p className="mt-1 text-gray-300">{report.recommendation}</p>
        </div>
      )}
    </div>
  )
}

function ClaimList({
  claims,
  report,
  selected,
  onClaim,
  onPassage,
}: {
  claims: Claim[]
  report: UserReport
  selected: Claim | undefined
  onClaim: (claim: Claim) => void
  onPassage: (claim: Claim, passage: Passage) => void
}) {
  if (claims.length === 0) return <p className="text-sm/6 text-gray-400">No claims in this report.</p>
  return (
    <nav aria-label="Claims" className="space-y-6">
      {KIND_ORDER.map((kind) => {
        const group = claims.filter((c) => c.kind === kind)
        if (group.length === 0) return null
        return (
          <section key={kind}>
            <h2 className="text-sm/6 font-semibold text-white">{KIND_HEADING[kind]}</h2>
            <ul role="list" className="mt-2 space-y-2">
              {group.map((claim) => (
                <ClaimItem
                  key={claim.id}
                  claim={claim}
                  report={report}
                  active={claim.id === selected?.id}
                  onClaim={() => onClaim(claim)}
                  onPassage={(passage) => onPassage(claim, passage)}
                />
              ))}
            </ul>
          </section>
        )
      })}
    </nav>
  )
}

function ClaimItem({
  claim,
  report,
  active,
  onClaim,
  onPassage,
}: {
  claim: Claim
  report: UserReport
  active: boolean
  onClaim: () => void
  onPassage: (passage: Passage) => void
}) {
  return (
    <li
      className={`rounded-md outline -outline-offset-1 ${
        active ? 'bg-white/10 outline-indigo-400/60' : 'bg-gray-800/50 outline-white/10 hover:bg-gray-800'
      }`}
    >
      <button
        type="button"
        aria-pressed={active}
        onClick={onClaim}
        className="w-full rounded-md px-3 py-2.5 text-left focus-visible:outline-2 focus-visible:outline-indigo-500"
      >
        <span className="block text-sm/6 text-gray-100">{claim.text}</span>
        <span
          className={`mt-1 inline-flex items-center gap-1 text-xs font-medium ${claim.supported ? 'text-emerald-400' : 'text-gray-400'}`}
        >
          {claim.supported ? (
            <CheckCircleIcon aria-hidden="true" className="size-3.5" />
          ) : (
            <MinusCircleIcon aria-hidden="true" className="size-3.5" />
          )}
          {claim.supported ? 'Backed by a quote' : 'No quote found'}
        </span>
      </button>

      {active && claim.passages.length > 0 && (
        <ul role="list" className="space-y-1 border-t border-white/10 px-2 py-2">
          {claim.passages.map((passage, index) => (
            <li key={index}>
              <button
                type="button"
                onClick={() => onPassage(passage)}
                className="flex w-full flex-col rounded px-2 py-1.5 text-left hover:bg-white/5 focus-visible:outline-2 focus-visible:outline-indigo-500"
              >
                {/* sizes sit on the spans: index.css gives buttons font: inherit, which outranks utilities */}
                <span className="text-xs/5 font-semibold text-indigo-300">{passageLabel(report, passage)}</span>
                {passage.verified !== 'verbatim' && (
                  <span className="text-xs/5 text-gray-400">{NOT_PLACED[passage.verified]}</span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}

function passageLabel(report: UserReport, passage: Passage): string {
  const source = sourceFor(report, passage)
  const where = passage.target === 'draft' && source.kind === 'pdf' ? 'Your draft' : source.label
  return passage.page !== null ? `${where}, p. ${passage.page}` : where
}

function PassagePane({
  name,
  hidden,
  report,
  passage,
  seq,
  empty,
}: {
  name: string
  hidden: boolean
  report: UserReport
  passage: Passage | null
  seq: number
  empty: string
}) {
  const source = passage ? sourceFor(report, passage) : null
  const placed = passage && source && passage.verified === 'verbatim' && source.kind !== 'none'
  const page = placed && source.kind === 'pdf' ? passage.page : null
  const isDraft = name === 'Your draft'
  const heading = isDraft || !source || source.kind === 'none' ? name : source.label

  return (
    <div
      className={`${hidden ? 'hidden xl:flex' : 'flex'} h-[70dvh] min-h-[26rem] flex-col overflow-hidden rounded-md bg-gray-900 outline -outline-offset-1 outline-white/10 lg:h-[calc(100dvh-9rem)]`}
    >
      <div className="flex items-baseline justify-between gap-3 border-b border-white/10 px-4 py-2.5">
        <h2 className="truncate text-sm/6 font-semibold text-white">
          {heading}
          {isDraft && source?.kind === 'pdf' && <span className="font-normal text-gray-400"> (example)</span>}
        </h2>
        {page !== null && <span className="shrink-0 text-xs text-gray-400 tabular-nums">p. {page}</span>}
      </div>
      <div className="min-h-0 flex-1">
        {!passage || !source ? (
          <Muted>{empty}</Muted>
        ) : !placed ? (
          <Muted quote={passage.quote}>
            {passage.verified !== 'verbatim'
              ? NOT_PLACED[passage.verified]
              : source.kind === 'none'
                ? source.label
                : ''}
          </Muted>
        ) : source.kind === 'pdf' ? (
          passage.page === null ? (
            <Muted quote={passage.quote}>Quoted from the paper's details, not from a page.</Muted>
          ) : (
            <PdfWithNote key={source.key} url={source.url} quote={passage.quote} page={passage.page} seq={seq} />
          )
        ) : (
          <TextWithNote text={source.text} quote={passage.quote} seq={seq} />
        )}
      </div>
    </div>
  )
}

function PdfWithNote({ url, quote, page, seq }: { url: string; quote: string; page: number; seq: number }) {
  const key = `${page}#${quote}`
  const [result, setResult] = useState<{ key: string; value: PaneResult } | null>(null)
  const current = result?.key === key ? result.value : null
  return (
    <div className="flex h-full flex-col">
      {current === 'not_on_page' && (
        <NotOnPage>
          Couldn't find this passage on page {page}: <q>{quote}</q>
        </NotOnPage>
      )}
      <div className="min-h-0 flex-1">
        <PdfPane url={url} highlight={{ quote, page, seq }} onResult={(value) => setResult({ key, value })} />
      </div>
    </div>
  )
}

function TextWithNote({ text, quote, seq }: { text: string; quote: string; seq: number }) {
  const found = findQuote(quote, text) !== null
  return (
    <div className="flex h-full flex-col">
      {!found && (
        <NotOnPage>
          Couldn't find this passage in the text: <q>{quote}</q>
        </NotOnPage>
      )}
      <div className="min-h-0 flex-1">
        <TextPane text={text} quote={quote} seq={seq} />
      </div>
    </div>
  )
}

function NotOnPage({ children }: { children: ReactNode }) {
  return <p className="border-b border-white/10 bg-yellow-400/10 px-4 py-2 text-xs/5 text-yellow-200">{children}</p>
}

function Muted({ children, quote }: { children: ReactNode; quote?: string }) {
  return (
    <div className="p-4">
      {children && <p className="text-sm/6 text-gray-400">{children}</p>}
      {quote && (
        <blockquote className="mt-3 rounded-md bg-white/5 px-3 py-2 text-sm/6 text-gray-300 italic">“{quote}”</blockquote>
      )}
    </div>
  )
}
