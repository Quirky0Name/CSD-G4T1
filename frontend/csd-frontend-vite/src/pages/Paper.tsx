import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeftIcon } from '@heroicons/react/20/solid'
import type { Paper as PaperRecord, UserReport } from '../api'
import { LEVEL_LABEL, LEVEL_STYLES, REPORT_STATUS_LABEL, REPORT_STATUS_STYLES, formatDateTime } from '../format'
import { ActionLink } from '../components/ActionButton'
import { AlertSummary, ReportDetails } from '../components/ReportList'
import { latestCheck, type LatestCheck } from '../paper/latestCheck'
import { useTracking } from '../tracking'

const LINK = 'text-indigo-400 hover:text-indigo-300'

function Paper() {
  const { paperId } = useParams()
  const { state } = useTracking()

  if (state.status === 'loading') {
    return (
      <main className="px-4 py-10 sm:px-6 lg:px-8">
        <p className="text-sm/6 text-gray-400">Loading paper…</p>
      </main>
    )
  }
  if (state.status === 'error') {
    return (
      <main className="px-4 py-10 sm:px-6 lg:px-8">
        <p className="rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">
          {state.message}
        </p>
      </main>
    )
  }

  // GET /papers only ever lists the caller's papers, so someone else's is the same as a missing one
  const paper = state.papers.find((candidate) => candidate.id === paperId)
  if (!paper) {
    return (
      <main className="px-4 py-10 sm:px-6 lg:px-8">
        <p className="text-sm/6 text-gray-300">This paper doesn't exist or isn't yours.</p>
        <Link to="/" className="mt-2 inline-block text-sm font-semibold text-indigo-400 hover:text-indigo-300">
          Back to projects
        </Link>
      </main>
    )
  }

  const check = latestCheck(paper.id, state.alerts, state.reports)

  return (
    <main className="px-4 py-10 sm:px-6 lg:px-8">
      <ActionLink tone="quiet" to={paper.project_id ? `/project/${paper.project_id}` : '/project'}>
        <ArrowLeftIcon aria-hidden="true" className="size-4" />
        Back to project
      </ActionLink>

      <h1 className="mt-4 max-w-5xl text-2xl/8 font-bold text-balance break-words text-white sm:text-3xl/10 sm:tracking-tight">
        {paper.title ?? 'Untitled (no DOI found)'}
      </h1>

      <div className="mt-8 grid grid-cols-1 gap-x-12 gap-y-10 lg:grid-cols-[20rem_minmax(0,1fr)]">
        <PaperDetails paper={paper} />
        <BackgroundCheck paperId={paper.id} check={check} />
      </div>
    </main>
  )
}

function PaperDetails({ paper }: { paper: PaperRecord }) {
  return (
    <section aria-labelledby="paper-details">
      <h2 id="paper-details" className="text-sm/6 font-semibold text-white">
        Details
      </h2>
      <dl className="mt-2 divide-y divide-white/10 border-y border-white/10 text-sm/6">
        <Detail label="DOI">
          {paper.doi ? (
            <a href={`https://doi.org/${paper.doi}`} target="_blank" rel="noreferrer" className={LINK}>
              {paper.doi}
            </a>
          ) : (
            'Not available'
          )}
        </Detail>
        <Detail label="Journal">{paper.journal ?? 'Not available'}</Detail>
        <Detail label="Year">{paper.publication_year ?? 'Not available'}</Detail>
        <Detail label="ISSN">{paper.issn ?? 'Not available'}</Detail>
        <Detail label="OpenAlex">{paper.openalex_id ?? 'Not available'}</Detail>
        <Detail label="PDF">{paper.file_available ? 'Stored' : 'Not stored'}</Detail>
        <Detail label="Added">
          <time dateTime={paper.created_at}>{formatDateTime(paper.created_at)}</time>
        </Detail>
      </dl>
    </section>
  )
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-x-6 py-2.5">
      <dt className="shrink-0 text-gray-400">{label}</dt>
      <dd className="min-w-0 text-right break-words text-gray-200 tabular-nums">{children}</dd>
    </div>
  )
}

function BackgroundCheck({ paperId, check }: { paperId: string; check: LatestCheck | null }) {
  return (
    <section aria-labelledby="latest-report" className="min-w-0">
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
        <h2 id="latest-report" className="text-sm/6 font-semibold text-white">
          Most recent report
        </h2>
        {check && (
          <div className="flex gap-x-4 text-sm/6 font-semibold">
            <Link to={`/alerts?paper=${paperId}`} className={LINK}>
              All alerts ({check.alertCount})
            </Link>
            <Link to={`/reports?paper=${paperId}`} className={LINK}>
              All reports ({check.reportCount})
            </Link>
          </div>
        )}
      </div>

      {!check && <p className="mt-2 text-sm/6 text-gray-400">No alerts or reports for this paper.</p>}
      {check?.report && <LatestReport report={check.report} />}
      {check && check.unreportedAlerts.length > 0 && (
        <div className="mt-4 text-sm/6">
          <p className="font-semibold text-white">Alerts not in a report yet</p>
          <ul role="list" className="mt-2 space-y-2">
            {check.unreportedAlerts.map((alert) => (
              <AlertSummary key={alert.id} alert={alert} />
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

function LatestReport({ report }: { report: UserReport }) {
  return (
    <div className="mt-2">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span
            className={`rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${REPORT_STATUS_STYLES[report.status]}`}
          >
            {REPORT_STATUS_LABEL[report.status]}
          </span>
          {report.change_severity && report.change_severity !== 'none' && (
            <span
              className={`rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${LEVEL_STYLES[report.change_severity]}`}
            >
              {LEVEL_LABEL[report.change_severity]} severity
            </span>
          )}
          <p className="text-xs/5 text-gray-400">
            Opened <time dateTime={report.created_at}>{formatDateTime(report.created_at)}</time>
          </p>
        </div>
        {report.status === 'assessed' && report.claims && (
          <ActionLink tone="primary" to={`/reports/${report.paper_id}/${report.id}`}>
            Open report
          </ActionLink>
        )}
      </div>
      {report.change_summary && <p className="mt-3 max-w-3xl text-sm/6 text-gray-200">{report.change_summary}</p>}
      <ReportDetails report={report} />
    </div>
  )
}

export default Paper
