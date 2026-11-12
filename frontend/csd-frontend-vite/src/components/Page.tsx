import { Fragment, type ComponentType, type ReactNode, type SVGProps } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeftIcon } from '@heroicons/react/20/solid'
import Mascot, { type MascotPose } from './Mascot'
import { ghostPill, pageColumn, riseDelay } from '../ui'

// The signed-in pages. Working pages (projects, a project, a paper, alerts, reports) open with a plain
// header: a back link, the title over a short indigo rule, and one line of metadata set like a
// citation ("Science · 2014 · doi:…"), with the page's actions to the right. Their content follows as
// section cards in one centred column. Only the profile, which is about a person, gets the profile
// layout: a library banner, a centred card with a round portrait breaking its edge, and a strip of
// facts along its foot. The penguin stays out of both; it appears where a page is empty or missing.

// one column width for the header and everything under it, so their edges line up
export function Page({ hero, children }: { hero?: ReactNode; children: ReactNode }) {
  return (
    <main className="pb-12">
      {hero}
      <div className={`${pageColumn} ${hero ? 'mt-8 sm:mt-10' : 'pt-6 sm:pt-10 short:sm:pt-5'}`}>
        {children}
      </div>
    </main>
  )
}

export function PageHeader({
  title,
  meta,
  description,
  actions,
  back,
}: {
  title: ReactNode
  // the citation line under the title; empty entries are dropped
  meta?: ReactNode[]
  description?: ReactNode
  actions?: ReactNode
  back?: { to: string; label: string }
}) {
  const shown = (meta ?? []).filter((item) => item !== null && item !== undefined && item !== false && item !== '')
  return (
    <header className="animate-rise mb-8 short:mb-5">
      {back && (
        <Link
          to={back.to}
          className="-ml-2 mb-3 inline-flex items-center gap-1.5 rounded-full py-1 pr-3 pl-2 text-sm font-semibold text-gray-400 transition-colors hover:bg-white/5 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-400"
        >
          <ArrowLeftIcon aria-hidden="true" className="size-4" />
          {back.label}
        </Link>
      )}
      <div className="flex flex-col gap-5 md:flex-row md:items-end md:justify-between">
        <div className="min-w-0">
          <h1 className="max-w-4xl text-2xl/8 font-bold tracking-tight text-balance break-words text-white sm:text-3xl/10">{title}</h1>
          <span aria-hidden="true" className="mt-3 block h-1 w-12 rounded-full bg-indigo-400" />
          {shown.length > 0 && (
            <p className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm/6 text-gray-400 tabular-nums">
              {shown.map((item, i) => (
                <Fragment key={i}>
                  {i > 0 && (
                    <span aria-hidden="true" className="text-gray-600">
                      ·
                    </span>
                  )}
                  <span className="min-w-0 break-words">{item}</span>
                </Fragment>
              ))}
            </p>
          )}
          {description && <p className="mt-2 max-w-[65ch] text-sm/6 text-gray-400">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2 sm:gap-3">{actions}</div>}
      </div>
    </header>
  )
}

export type Fact = { label: string; value: ReactNode }

// The profile layout, for the profile alone
export function PageHero({
  title,
  description,
  tag,
  initial,
  actions,
  facts,
  factsEnd,
}: {
  title: ReactNode
  description?: ReactNode
  // a small outlined chip in the card's corner naming the account's role
  tag?: string
  // the person's initial, for the round portrait
  initial: string
  actions?: ReactNode
  // the strip along the card's foot; factsEnd sits at its right
  facts?: Fact[]
  factsEnd?: ReactNode
}) {
  return (
    <header>
      <div className="relative h-36 overflow-hidden sm:h-52 short:h-24">
        <LibraryBanner />
      </div>

      <div className={`relative -mt-16 sm:-mt-24 short:-mt-10 ${pageColumn}`}>
        <div className="animate-rise relative rounded-3xl bg-gray-900 pt-16 shadow-[0_30px_80px_-30px_rgba(0,0,0,0.8)] outline-1 -outline-offset-1 outline-white/10 sm:pt-20 short:pt-12">
          <Portrait initial={initial} />
          {tag && (
            <span className="absolute top-4 left-4 rounded-full px-2.5 py-0.5 text-xs font-semibold text-indigo-300 outline-1 -outline-offset-1 outline-indigo-400/50 sm:top-5 sm:left-6">
              {tag}
            </span>
          )}

          <div className="flex flex-col items-center px-5 pb-6 text-center sm:px-10 sm:pb-8 short:pb-4">
            <h1 className="max-w-3xl text-2xl/8 font-semibold tracking-tight text-balance break-words text-white sm:text-4xl/11">
              {title}
            </h1>
            <span aria-hidden="true" className="mt-3 h-1 w-12 rounded-full bg-indigo-400" />
            {description && <p className="mt-3 max-w-[60ch] text-sm/6 text-balance text-gray-400">{description}</p>}
            {actions && <div className="mt-5 flex flex-wrap items-center justify-center gap-2 sm:gap-3">{actions}</div>}
          </div>

          {(facts?.length || factsEnd) && (
            <div className="flex flex-col gap-3 rounded-b-3xl border-t border-white/10 bg-indigo-500/[0.07] px-5 py-4 text-sm/6 sm:flex-row sm:items-end sm:justify-between sm:px-10 sm:py-5 short:py-3">
              {facts && facts.length > 0 && (
                <dl className="grid min-w-0 gap-x-8 gap-y-1 sm:grid-flow-col sm:grid-rows-2 sm:justify-start">
                  {facts.map(({ label, value }) => (
                    <div key={label} className="flex min-w-0 gap-1.5">
                      <dt className="shrink-0 font-semibold text-gray-300">{label}:</dt>
                      <dd className="min-w-0 truncate text-gray-400 tabular-nums">{value}</dd>
                    </div>
                  ))}
                </dl>
              )}
              {factsEnd && <div className="shrink-0 text-gray-400">{factsEnd}</div>}
            </div>
          )}
        </div>
      </div>
    </header>
  )
}

// the person's round portrait, breaking the top edge of the hero card: their initial on indigo
function Portrait({ initial }: { initial: string }) {
  return (
    <div className="absolute top-0 left-1/2 size-28 -translate-x-1/2 -translate-y-1/2 sm:size-36 short:size-20">
      <div
        aria-hidden="true"
        className="grid size-full place-items-center rounded-full bg-[radial-gradient(circle_at_50%_30%,#4f46e5,#1e1b4b_75%)] text-5xl font-semibold text-white shadow-[0_16px_40px_-16px_rgba(0,0,0,0.9)] ring-4 ring-gray-900 outline-2 outline-offset-4 outline-indigo-400/30 sm:text-6xl short:text-3xl"
      >
        {initial}
      </div>
    </div>
  )
}

// A shelf of book spines under a night-indigo sky, drawn in the desk's slates with a few indigo
// volumes: the reading room the desk sits in. Fixed, so it never shifts between renders.
const SPINES = (() => {
  const books: { x: number; w: number; h: number; tone: string; tilt: number; band: boolean }[] = []
  const tones = ['#1f2937', '#273244', '#374151', '#1e1b4b', '#312e81', '#4338ca', '#1f2937', '#2a3346']
  let seed = 7
  const rand = () => {
    seed = (seed * 9301 + 49297) % 233280
    return seed / 233280
  }
  for (let x = -10; x < 1460; ) {
    const w = 14 + Math.round(rand() * 22)
    const gap = rand() < 0.1 ? 26 + rand() * 50 : 2
    books.push({
      x,
      w,
      h: 70 + Math.round(rand() * 80),
      tone: tones[Math.floor(rand() * tones.length)],
      tilt: rand() < 0.07 ? -12 + rand() * 24 : 0,
      band: rand() < 0.45,
    })
    x += w + gap
  }
  return books
})()

function LibraryBanner() {
  return (
    <svg aria-hidden="true" viewBox="0 0 1440 260" preserveAspectRatio="xMidYMax slice" className="absolute inset-0 size-full">
      <defs>
        <linearGradient id="banner-sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#1e1b4b" />
          <stop offset="1" stopColor="#0b1020" />
        </linearGradient>
        <radialGradient id="banner-lamp" cx="0.78" cy="0.15" r="0.5">
          <stop offset="0" stopColor="#818cf8" stopOpacity="0.35" />
          <stop offset="1" stopColor="#818cf8" stopOpacity="0" />
        </radialGradient>
      </defs>
      <rect width="1440" height="260" fill="url(#banner-sky)" />
      <rect width="1440" height="260" fill="url(#banner-lamp)" />
      <path d="M-40 70 C 220 -10, 380 150, 620 60" stroke="#818cf8" strokeOpacity="0.22" strokeWidth="2" strokeDasharray="6 10" fill="none" />
      <path d="M1480 40 C 1260 140, 1120 0, 880 90" stroke="#818cf8" strokeOpacity="0.18" strokeWidth="2" strokeDasharray="6 10" fill="none" />
      {[90, 260, 470, 980, 1190, 1330].map((x, i) => (
        <circle key={x} cx={x} cy={24 + ((i * 37) % 70)} r={i % 2 ? 1.6 : 2.4} fill="#c7d2fe" fillOpacity={0.35} />
      ))}
      <g>
        {SPINES.map((b, i) => (
          <g key={i} transform={b.tilt ? `rotate(${b.tilt} ${b.x + b.w / 2} 236)` : undefined}>
            <rect x={b.x} y={236 - b.h} width={b.w} height={b.h} rx="2" fill={b.tone} />
            {b.band && <rect x={b.x} y={236 - b.h + 12} width={b.w} height="4" fill="#c7d2fe" fillOpacity="0.14" />}
            {b.band && <rect x={b.x} y={236 - 22} width={b.w} height="3" fill="#c7d2fe" fillOpacity="0.1" />}
          </g>
        ))}
      </g>
      <rect x="0" y="236" width="1440" height="24" fill="#111827" />
      <rect x="0" y="236" width="1440" height="2" fill="#ffffff" fillOpacity="0.08" />
    </svg>
  )
}

// a section heading without a card around it, for a section that is itself a grid of cards
export function SectionHeading({ title, icon: Icon, count, index = 1 }: { title: ReactNode; icon?: ComponentType<SVGProps<SVGSVGElement>>; count?: number; index?: number }) {
  return (
    <div style={riseDelay(index)} className="animate-rise mb-5">
      <h2 className="flex items-center gap-2.5 text-lg/7 font-semibold text-white sm:text-xl/8">
        {Icon && <Icon aria-hidden="true" className="size-5 text-indigo-400 sm:size-6" />}
        {title}
        {count !== undefined && <span className="text-sm font-medium text-gray-500 tabular-nums">{count}</span>}
      </h2>
      <span aria-hidden="true" className="mt-2 block h-1 w-10 rounded-full bg-indigo-400" />
    </div>
  )
}

// A section of a page: an outlined card with an icon, a heading and a short indigo rule under it
export function Section({
  title,
  icon: Icon,
  action,
  index = 1,
  className = '',
  children,
  id,
}: {
  title: ReactNode
  icon?: ComponentType<SVGProps<SVGSVGElement>>
  action?: ReactNode
  index?: number
  className?: string
  children: ReactNode
  id?: string
}) {
  return (
    <section
      aria-labelledby={id}
      style={riseDelay(index)}
      className={`animate-rise min-w-0 rounded-3xl bg-gray-900 p-5 outline-1 -outline-offset-1 outline-white/10 sm:p-8 ${className}`}
    >
      <div className="mb-5 flex items-start justify-between gap-4">
        <div>
          <h2 id={id} className="flex items-center gap-2.5 text-lg/7 font-semibold text-white sm:text-xl/8">
            {Icon && <Icon aria-hidden="true" className="size-5 text-indigo-400 sm:size-6" />}
            {title}
          </h2>
          <span aria-hidden="true" className="mt-2 block h-1 w-10 rounded-full bg-indigo-400" />
        </div>
        {action && <div className="shrink-0">{action}</div>}
      </div>
      {children}
    </section>
  )
}

// an empty or missing state: the penguin acting it out, a heading, a line, and what to do next
export function EmptyState({
  pose,
  title,
  children,
  action,
  index = 1,
  compact = false,
}: {
  pose: MascotPose
  title: string
  children?: ReactNode
  action?: ReactNode
  index?: number
  compact?: boolean
}) {
  return (
    <div
      style={riseDelay(index)}
      className={`animate-rise flex flex-col items-center rounded-3xl border border-dashed border-white/10 bg-gray-900/40 px-6 text-center sm:flex-row sm:gap-8 sm:text-left ${
        compact ? 'py-6' : 'py-10 sm:px-10'
      }`}
    >
      <Mascot pose={pose} className={`shrink-0 ${compact ? 'w-20' : 'w-28 sm:w-36'}`} />
      <div className="mt-4 max-w-md sm:mt-0">
        <h2 className="text-lg/7 font-semibold text-white">{title}</h2>
        {children && <div className="mt-1 text-sm/6 text-gray-400">{children}</div>}
        {action && <div className="mt-4 flex flex-wrap justify-center gap-3 sm:justify-start">{action}</div>}
      </div>
    </div>
  )
}

export function NotFound({ message = "This page doesn't exist.", back = { to: '/', label: 'Back to projects' } }: { message?: string; back?: { to: string; label: string } }) {
  return (
    <Page>
      <EmptyState
        pose="think"
        title="Nothing here"
        index={0}
        action={
          <Link to={back.to} className={ghostPill}>
            <ArrowLeftIcon aria-hidden="true" className="size-4" />
            {back.label}
          </Link>
        }
      >
        {message}
      </EmptyState>
    </Page>
  )
}

// a page's load in progress: a quiet line with the indigo spinner, not a blank screen
export function Loading({ label }: { label: string }) {
  return (
    <div role="status" className="animate-rise flex items-center gap-2 text-sm text-gray-400">
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" className="size-4 animate-spin text-indigo-400">
        <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" className="opacity-25" />
        <path fill="currentColor" d="M4 12a8 8 0 0 1 8-8v4a4 4 0 0 0-4 4H4z" className="opacity-75" />
      </svg>
      {label}
    </div>
  )
}
