// One report with its claims, and each claim's passages highlighted: the sentence in the researcher's
// draft beside the passage in the source it relies on. The report comes from the tracking state, which
// already loads every report; the selected claim is kept in ?claim= so a reload opens it again.
import { Tab, TabGroup, TabList, TabPanel, TabPanels } from '@headlessui/react'
import { ArrowLeftIcon, ChevronLeftIcon, ChevronRightIcon, MinusCircleIcon } from '@heroicons/react/20/solid'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import type { Claim, ClaimKind, Passage, Paper, UserReport } from '../api'
import { LEVEL_LABEL, LEVEL_STYLES, REPORT_STATUS_LABEL, REPORT_STATUS_STYLES } from '../format'
import { scrollMotion } from '../highlight/motion'
import { findQuote } from '../highlight/normalise'
import PdfPane, { type PaneResult } from '../highlight/PdfPane'
import { sourceFor } from '../highlight/sources'
import TextPane from '../highlight/TextPane'
import { useTracking } from '../tracking'

const KIND_ORDER: ClaimKind[] = ['change', 'impact', 'action']
const KIND_TAB: Record<ClaimKind, string> = {
  change: 'Changes',
  impact: 'Impact',
  action: 'Recommendation',
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
        <BackLink to="/reports" />
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
      Reports
    </Link>
  )
}

function ReportBody({ report, paper }: { report: UserReport; paper: Paper }) {
  const [params, setParams] = useSearchParams()
  const [picked, setPicked] = useState<Picked | null>(null)
  // bumped on every selection, so choosing the same passage again scrolls back to it
  const [seq, setSeq] = useState(0)
  // null: follow the selected claim
  const [chosenTab, setTab] = useState<PaneName | null>(null)
  const passages = useRef<HTMLElement>(null)
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
    setTab(null)
  }

  // below lg the panes sit under the claim list, so choosing a claim moves down to its quotes
  const openClaim = (claim: Claim) => {
    pickClaim(claim)
    if (!window.matchMedia('(min-width: 64rem)').matches) {
      passages.current?.scrollIntoView({ behavior: scrollMotion(), block: 'start' })
    }
  }

  const pickPassage = (pane: PaneName, passage: Passage) => {
    if (!selected) return
    setPicked((current) => ({ ...(current?.claim === selected.id ? current : {}), claim: selected.id, [pane]: passage }))
    setSeq((n) => n + 1)
  }

  const chosen = picked && picked.claim === selected?.id ? picked : null
  const draftPassage = chosen?.draft ?? panePassage(selected, 'draft')
  const sourcePassage = chosen?.source ?? panePassage(selected, 'source')
  const both = Boolean(draftPassage && sourcePassage)
  const tab = chosenTab ?? (draftPassage ? 'draft' : 'source')
  const showClaims = report.status === 'assessed' && report.claims !== null

  return (
    <main
      className={`flex flex-col px-4 pt-6 pb-4 text-gray-100 sm:px-6 lg:px-6 ${showClaims ? 'lg:h-dvh lg:pt-3 lg:pb-3' : ''}`}
    >
      <header className="flex flex-col gap-x-4 gap-y-2 lg:flex-row lg:items-center">
        <div className="shrink-0">
          <BackLink to={`/reports?paper=${report.paper_id}`} />
        </div>
        <h1 title={title} className="min-w-0 text-xl/7 font-bold text-balance text-white lg:truncate lg:text-lg/7">
          {title}
        </h1>
        <div className="flex flex-wrap items-center gap-2 lg:shrink-0">
          {report.status !== 'assessed' && (
            <Badge className={REPORT_STATUS_STYLES[report.status]}>{REPORT_STATUS_LABEL[report.status]}</Badge>
          )}
          {noImpact && <Badge className={LEVEL_STYLES.none}>This change doesn't affect your work</Badge>}
          {report.change_severity && !noImpact && (
            <Badge className={LEVEL_STYLES[report.change_severity]}>{LEVEL_LABEL[report.change_severity]} severity</Badge>
          )}
          {report.impact_level && (
            <Badge className={LEVEL_STYLES[report.impact_level]}>{LEVEL_LABEL[report.impact_level]} impact on your draft</Badge>
          )}
        </div>
      </header>
      {!showClaims && report.change_summary && (
        <div className="mt-2">
          <Summary text={report.change_summary} />
        </div>
      )}

      {report.status !== 'assessed' ? (
        <p className="mt-8 text-sm/6 text-gray-400">This report is still being assessed.</p>
      ) : report.claims === null ? (
        <NoClaims report={report} />
      ) : (
        <div className="mt-4 grid gap-6 lg:mt-3 lg:min-h-0 lg:flex-1 lg:grid-cols-[minmax(18rem,22rem)_minmax(0,1fr)] lg:gap-4">
          <div className="min-w-0 lg:overflow-y-auto">
            <ClaimList claims={claims} summary={report.change_summary} selected={selected} onTab={pickClaim} onClaim={openClaim} />
          </div>

          <section ref={passages} aria-label="Quotes" className="flex min-w-0 scroll-mt-16 flex-col lg:min-h-0">
            {selected && (
              <p aria-live="polite" title={selected.text} className="mb-3 line-clamp-2 text-sm/6 text-gray-200 lg:sr-only">
                {selected.text}
              </p>
            )}
            {both && (
              <div role="group" aria-label="Document to show" className="mb-3 flex gap-2 lg:hidden">
                {(['draft', 'source'] as const).map((name) => (
                  <button
                    key={name}
                    type="button"
                    aria-pressed={tab === name}
                    onClick={() => setTab(name)}
                    className="rounded-md px-3 py-1.5 text-gray-300 hover:text-white aria-pressed:bg-white/10 aria-pressed:text-white focus-visible:outline-2 focus-visible:outline-indigo-500"
                  >
                    <span className="text-sm font-semibold">{name === 'draft' ? 'Your draft' : 'Cited source'}</span>
                  </button>
                ))}
              </div>
            )}
            <div className="flex flex-col gap-4 lg:min-h-0 lg:flex-1 lg:gap-3">
              {draftPassage && (
                <PassagePane
                  name="Your draft"
                  hidden={both && tab !== 'draft'}
                  report={report}
                  passage={draftPassage}
                  options={paneOptions(selected, 'draft')}
                  onPick={(passage) => pickPassage('draft', passage)}
                  seq={seq}
                />
              )}
              {sourcePassage && (
                <PassagePane
                  name="Cited source"
                  hidden={both && tab !== 'source'}
                  report={report}
                  passage={sourcePassage}
                  options={paneOptions(selected, 'source')}
                  onPick={(passage) => pickPassage('source', passage)}
                  seq={seq}
                />
              )}
              {selected && !draftPassage && !sourcePassage && (
                <div className="rounded-md bg-gray-900 outline -outline-offset-1 outline-white/10">
                  <Muted>No quote to show for this point.</Muted>
                </div>
              )}
            </div>
          </section>
        </div>
      )}
    </main>
  )
}

function Summary({ text }: { text: string }) {
  const [open, setOpen] = useState(false)
  // roughly what two clamped lines hold, so a short summary gets no toggle
  const long = text.length > 180
  return (
    <div className="max-w-[75ch] text-sm/6 text-gray-300">
      <p className={long && !open ? 'line-clamp-2' : ''}>{text}</p>
      {long && (
        <button
          type="button"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
          className="rounded text-indigo-400 hover:text-indigo-300 focus-visible:outline-2 focus-visible:outline-indigo-500"
        >
          <span className="text-sm/6 font-semibold">{open ? 'Show less' : 'Read more'}</span>
        </button>
      )}
    </div>
  )
}

// The researcher's question is how their draft is affected, so open on that when nothing is picked
function defaultClaim(claims: Claim[]): Claim | undefined {
  return claims.find((c) => c.kind === 'impact' && c.supported) ?? firstClaim(claims)
}

function firstClaim(claims: Claim[]): Claim | undefined {
  return claims.find((c) => c.supported) ?? claims[0]
}

// A pane's passage for a claim: the first verbatim one, or else the first that failed, so the pane
// can say why there's no highlight instead of implying the claim quoted nothing
function panePassage(claim: Claim | undefined, pane: PaneName): Passage | undefined {
  const mine = paneOptions(claim, pane)
  return mine.find((p) => p.verified === 'verbatim') ?? mine[0]
}

function paneOptions(claim: Claim | undefined, pane: PaneName): Passage[] {
  return claim?.passages.filter((p) => (p.target === 'draft') === (pane === 'draft')) ?? []
}

// by value: tracking reloads every 30 s, so a picked passage outlives the objects it was picked from
function samePassage(a: Passage, b: Passage | undefined): boolean {
  return (
    b !== undefined &&
    a.target === b.target &&
    a.document_id === b.document_id &&
    a.alert_id === b.alert_id &&
    a.quote === b.quote &&
    a.page === b.page
  )
}

function Badge({ className, children }: { className: string; children: ReactNode }) {
  return (
    <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${className}`}>{children}</span>
  )
}

function NoClaims({ report }: { report: UserReport }) {
  return (
    <div className="mt-8 max-w-[75ch] space-y-4 text-sm/6">
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

// One group at a time, with the change summary heading Changes: the tabs follow the selected claim, so the URL stays the only record of what's open
function ClaimList({
  claims,
  summary,
  selected,
  onTab,
  onClaim,
}: {
  claims: Claim[]
  summary: string | null
  selected: Claim | undefined
  onTab: (claim: Claim) => void
  onClaim: (claim: Claim) => void
}) {
  const lead = summary && <Summary text={summary} />
  if (claims.length === 0) {
    return lead ?? <p className="text-sm/6 text-gray-400">Nothing to show in this report.</p>
  }
  const kinds = KIND_ORDER.filter((kind) => claims.some((c) => c.kind === kind))
  const groupOf = (kind: ClaimKind) => claims.filter((c) => c.kind === kind)
  const selectTab = (index: number) => {
    const claim = firstClaim(groupOf(kinds[index]))
    if (claim) onTab(claim)
  }

  return (
    <TabGroup selectedIndex={selected ? kinds.indexOf(selected.kind) : 0} onChange={selectTab}>
      <TabList className="flex gap-1 rounded-lg bg-white/5 p-1">
        {kinds.map((kind) => (
          <Tab
            key={kind}
            className="flex-auto rounded-md px-2 py-1.5 whitespace-nowrap text-gray-400 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-500 data-selected:bg-white/10 data-selected:text-white"
          >
            <span className="text-sm/5 font-semibold">{KIND_TAB[kind]}</span>{' '}
            <span className="text-xs/5 tabular-nums opacity-70">{groupOf(kind).length}</span>
          </Tab>
        ))}
      </TabList>
      <TabPanels className="mt-3">
        {kinds.map((kind) => (
          <TabPanel key={kind} className="focus-visible:outline-none">
            {kind === (kinds.includes('change') ? 'change' : kinds[0]) && lead && <div className="mb-3 px-3">{lead}</div>}
            <ul role="list" className="space-y-1">
              {groupOf(kind).map((claim) => (
                <ClaimItem
                  key={claim.id}
                  claim={claim}
                  active={claim.id === selected?.id}
                  onClaim={() => onClaim(claim)}
                />
              ))}
            </ul>
          </TabPanel>
        ))}
      </TabPanels>
    </TabGroup>
  )
}

function ClaimItem({ claim, active, onClaim }: { claim: Claim; active: boolean; onClaim: () => void }) {
  return (
    <li>
      <button
        type="button"
        aria-pressed={active}
        onClick={onClaim}
        className={`w-full rounded-md px-3 py-2.5 text-left focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-indigo-500 ${
          active ? 'bg-white/10 outline -outline-offset-1 outline-indigo-400/60' : 'hover:bg-white/5'
        }`}
      >
        <span className={`block text-sm/6 ${active ? 'text-white' : 'text-gray-300'}`}>{claim.text}</span>
        {!claim.supported && (
          <span className="mt-1 flex items-center gap-1 text-xs/5 font-medium text-yellow-500">
            <MinusCircleIcon aria-hidden="true" className="size-3.5" />
            No quote found
          </span>
        )}
      </button>
    </li>
  )
}

function PassagePane({
  name,
  hidden,
  report,
  passage,
  options,
  onPick,
  seq,
}: {
  name: string
  hidden: boolean
  report: UserReport
  passage: Passage
  // every quote this claim has in this pane: more than one gets a pager in the header
  options: Passage[]
  onPick: (passage: Passage) => void
  seq: number
}) {
  const source = sourceFor(report, passage)
  const placed = passage.verified === 'verbatim' && source.kind !== 'none'
  const page = placed && source.kind === 'pdf' ? passage.page : null
  const isDraft = name === 'Your draft'
  const heading = isDraft || source.kind === 'none' ? name : source.label
  const position = Math.max(
    options.findIndex((option) => samePassage(option, passage)),
    0,
  )
  const step = (by: number) => onPick(options[(position + by + options.length) % options.length])

  return (
    <div
      className={`${hidden ? 'hidden lg:flex' : 'flex'} h-[70dvh] min-h-[26rem] flex-col overflow-hidden rounded-md bg-gray-900 outline -outline-offset-1 outline-white/10 lg:h-auto lg:min-h-40 lg:flex-1 lg:basis-0`}
    >
      <div className="flex min-h-9 items-center justify-between gap-3 border-b border-white/10 px-4 py-0.5">
        <h2 title={heading} className="min-w-0 truncate text-sm/6 font-semibold text-white">
          {heading}
          {isDraft && source.kind === 'pdf' && <span className="font-normal text-gray-400"> (example)</span>}
        </h2>
        <div className="flex shrink-0 items-center gap-3 text-xs text-gray-400 tabular-nums">
          {page !== null && <span>p. {page}</span>}
          {options.length > 1 && (
            <div role="group" aria-label={`Quotes in ${name}`} className="flex items-center gap-1">
              <PagerButton label="Previous quote" onClick={() => step(-1)}>
                <ChevronLeftIcon aria-hidden="true" className="size-4" />
              </PagerButton>
              <span aria-live="polite">
                {position + 1} of {options.length}
              </span>
              <PagerButton label="Next quote" onClick={() => step(1)}>
                <ChevronRightIcon aria-hidden="true" className="size-4" />
              </PagerButton>
            </div>
          )}
        </div>
      </div>
      <div className="min-h-0 flex-1">
        {placed && <p className="sr-only">Highlighted passage: {passage.quote}</p>}
        {!placed ? (
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

function PagerButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="flex size-7 items-center justify-center rounded text-gray-300 hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-500"
    >
      {children}
    </button>
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
        <blockquote className="mt-3 rounded-md bg-white/5 px-3 py-2 text-sm/6 text-gray-300">“{quote}”</blockquote>
      )}
    </div>
  )
}
