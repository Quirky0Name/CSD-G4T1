import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import type { Paper as PaperRecord, UserReport } from '../api'
import { LEVEL_LABEL, LEVEL_STYLES, REPORT_STATUS_LABEL, REPORT_STATUS_STYLES, formatDateTime } from '../format'
import { ActionLink } from '../components/ActionButton'
import { Loading, NotFound, Page, PageHeader, Section } from '../components/Page'
import { BookOpenIcon, DocumentMagnifyingGlassIcon } from '@heroicons/react/24/outline'
import { badge, errorPanel, signalLink } from '../ui'
import { AlertSummary, ReportDetails } from '../components/ReportList'
import { latestCheck, type LatestCheck } from '../paper/latestCheck'
import { useTracking } from '../tracking'

const LINK = signalLink

function Paper() {
  const { paperId } = useParams()
  const { state } = useTracking()

  if (state.status === 'loading') {
    return (
      <Page>
        <Loading label="Loading paper…" />
      </Page>
    )
  }
  if (state.status === 'error') {
    return (
      <Page>
        <p className={errorPanel}>{state.message}</p>
      </Page>
    )
  }

  // GET /papers only ever lists the caller's papers, so someone else's is the same as a missing one
  const paper = state.papers.find((candidate) => candidate.id === paperId)
  if (!paper) {
    return <NotFound message="This paper doesn't exist or isn't yours." />
  }

  const check = latestCheck(paper.id, state.alerts, state.reports)

  return (
    <Page>
      {/* the paper's own record, set as a citation: journal, year, DOI */}
      <PageHeader
        back={{ to: paper.project_id ? `/project/${paper.project_id}` : '/project', label: 'Back to project' }}
        title={paper.title ?? 'Untitled (no DOI found)'}
        meta={[
          paper.journal && <i className="text-gray-300">{paper.journal}</i>,
          paper.publication_year,
          paper.doi && (
            <a href={`https://doi.org/${paper.doi}`} target="_blank" rel="noreferrer" className={LINK}>
              doi:{paper.doi}
            </a>
          ),
        ]}
      />

      {/* the most recent report leads; the paper's record sits beside it */}
      <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_20rem] lg:gap-6">
        <BackgroundCheck paperId={paper.id} check={check} />
        <PaperDetails paper={paper} />
      </div>
    </Page>
  )
}

function PaperDetails({ paper }: { paper: PaperRecord }) {
  return (
    <Section id="paper-details" title="Record" icon={BookOpenIcon} index={2}>
      <dl className="-mt-1 divide-y divide-white/10 text-sm/6">
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
    </Section>
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
    <Section
      id="latest-report"
      title="Most recent report"
      icon={DocumentMagnifyingGlassIcon}
      index={1}
      action={
        check && (
          <div className="flex flex-col items-end gap-0.5 text-sm/6 font-semibold sm:flex-row sm:gap-x-4">
            <Link to={`/alerts?paper=${paperId}`} className={LINK}>
              All alerts ({check.alertCount})
            </Link>
            <Link to={`/reports?paper=${paperId}`} className={LINK}>
              All reports ({check.reportCount})
            </Link>
          </div>
        )
      }
    >
      {!check && (
        <p className="text-sm/6 text-gray-400">No alerts or reports for this paper. Nothing has changed since it was tracked.</p>
      )}
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
    </Section>
  )
}

function LatestReport({ report }: { report: UserReport }) {
  return (
    <div className="mt-2">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span
            className={`${badge} ${REPORT_STATUS_STYLES[report.status]}`}
          >
            {REPORT_STATUS_LABEL[report.status]}
          </span>
          {report.change_severity && report.change_severity !== 'none' && (
            <span
              className={`${badge} ${LEVEL_STYLES[report.change_severity]}`}
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
