import { useState } from 'react'
import { ChevronDownIcon } from '@heroicons/react/20/solid'
import { Link, useSearchParams } from 'react-router-dom'
import {
  type Alert,
  type DocumentKind,
  type Paper,
  type UserDocument,
  type UserReport,
} from '../api'
import {
  CHANGE_TYPE_LABEL,
  ALERT_SEVERITY_LABEL,
  LEVEL_LABEL,
  LEVEL_STYLES,
  REPORT_STATUS_LABEL,
  REPORT_STATUS_STYLES,
  formatDateTime,
} from '../format'
import { useTracking } from '../tracking'
import { ActionButton, ActionLink } from './ActionButton'
import { EmptyState, Loading, Page, PageHeader } from './Page'
import { badge, card, errorPanel, riseDelay, smallPill } from '../ui'

const DOCUMENT_KIND_LABEL: Record<DocumentKind, string> = {
  notice: 'Notice',
  new_version: 'Newer version',
  current_version: 'Current copy',
  related: 'Related document',
}

const Spinner = Loading

export default function ReportList() {
  const { state } = useTracking()
  const [searchParams] = useSearchParams()
  const paperFilter = searchParams.get('paper')
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const reports =
    state.status === 'ready'
      ? paperFilter
        ? state.reports.filter((report) => report.paper_id === paperFilter)
        : state.reports
      : null

  const filteredTitle =
    paperFilter && state.status === 'ready' ? state.papers.find((paper) => paper.id === paperFilter)?.title : null
  // flagged in the header when a report found a change that hits hard
  const serious = (reports ?? []).some((report) => report.change_severity === 'high' || report.impact_level === 'high')

  const assessed = (reports ?? []).filter((report) => report.status === 'assessed').length

  return (
    <Page>
      <PageHeader
        title="Reports"
        meta={
          reports
            ? [
                `${assessed} assessed`,
                `${reports.length - assessed} in progress`,
                serious && <span className="text-red-300">Includes a high-severity change</span>,
                filteredTitle && `For ${filteredTitle}`,
              ]
            : undefined
        }
        description={filteredTitle ? undefined : 'What each change means for your work, one report per change.'}
        actions={
          paperFilter && (
            <Link to="/reports" className={smallPill}>
              Show all reports
            </Link>
          )
        }
      />

      {state.status === 'loading' && <Spinner label="Loading reports…" />}
      {state.status === 'error' && <p className={errorPanel}>{state.message}</p>}
      {state.status === 'ready' && reports && (
        <ReportRows
          papers={state.papers}
          reports={reports}
          expandedId={expandedId}
          onToggle={(id) => setExpandedId((current) => (current === id ? null : id))}
        />
      )}
    </Page>
  )
}

function ReportRows({
  papers,
  reports,
  expandedId,
  onToggle,
}: {
  papers: Paper[]
  reports: UserReport[]
  expandedId: number | null
  onToggle: (id: number) => void
}) {
  const paperTitle = new Map(papers.map((paper) => [paper.id, paper.title ?? 'Untitled paper']))

  if (reports.length === 0) {
    return (
      <EmptyState pose="laptop" title="No reports yet">
        When an alert comes in, the change is investigated and assessed against your draft, and the report lands here.
      </EmptyState>
    )
  }

  return (
    <ul role="list" className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,30rem),1fr))] items-start gap-4">
      {reports.map((report, index) => (
        <li key={report.id} style={riseDelay(index + 1)} className={`${card} animate-rise overflow-hidden p-5 sm:px-6`}>
          <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
            <p className="min-w-0 text-sm/6 font-semibold text-white">
              {paperTitle.get(report.paper_id) ?? 'Unknown paper'}
            </p>
            <span
              className={`mt-0.5 ${badge} ${REPORT_STATUS_STYLES[report.status]}`}
            >
              {REPORT_STATUS_LABEL[report.status]}
            </span>
            {report.change_severity && report.change_severity !== 'none' && (
              <span
                className={`mt-0.5 ${badge} ${LEVEL_STYLES[report.change_severity]}`}
              >
                {LEVEL_LABEL[report.change_severity]} severity
              </span>
            )}
          </div>

          {report.status === 'assessed' ? (
            <p className={`mt-1.5 text-sm/6 text-gray-400 ${expandedId === report.id ? '' : 'line-clamp-2'}`}>
              {report.change_summary ?? report.evaluation ?? 'No evaluation stored.'}
            </p>
          ) : (
            <div className="mt-1.5">
              <Spinner label="Assessment pending…" />
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
            <p className="text-xs/5 text-gray-400">
              Opened <time dateTime={report.created_at}>{formatDateTime(report.created_at)}</time>
              {' · '}
              {report.alerts.length} alert{report.alerts.length === 1 ? '' : 's'}
              {' · '}
              {report.documents.length} document{report.documents.length === 1 ? '' : 's'}
            </p>

            <div className="flex flex-wrap items-center gap-2">
              <ActionButton tone="quiet" aria-expanded={expandedId === report.id} onClick={() => onToggle(report.id)}>
                {expandedId === report.id ? 'Hide details' : 'View details'}
                <ChevronDownIcon
                  aria-hidden="true"
                  className={`size-4 motion-safe:transition-transform ${expandedId === report.id ? 'rotate-180' : ''}`}
                />
              </ActionButton>
              {report.status === 'assessed' && report.claims && (
                <ActionLink tone="primary" to={`/reports/${report.paper_id}/${report.id}`}>
                  Open report
                </ActionLink>
              )}
            </div>
          </div>

          {expandedId === report.id && (
            <div className="animate-reveal">
              <ReportDetails report={report} />
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}

export function ReportDetails({ report }: { report: UserReport }) {
  return (
    <div className="mt-4 space-y-4 border-t border-white/10 pt-4 text-sm/6">
      {report.status === 'investigating' && <Spinner label="Research Evaluation is still fetching documents…" />}
      {report.status === 'investigated' && <Spinner label="Waiting for the impact assessment…" />}
      {report.status === 'assessed' && (
        <>
          {report.change_severity === 'none' && (
            <p className="text-gray-400">Research Evaluation judged this change not meaningful for your work.</p>
          )}
          {report.impact_level && (
            <div>
              <p className="font-semibold text-white">Impact on your research</p>
              <p className="mt-1 capitalize text-gray-400">{report.impact_level}</p>
            </div>
          )}
          {report.evaluation && (
            <div>
              <p className="font-semibold text-white">Evaluation</p>
              <p className="mt-1 max-w-3xl text-gray-400">{report.evaluation}</p>
            </div>
          )}
          {report.recommendation && (
            <div>
              <p className="font-semibold text-white">Recommendation</p>
              <p className="mt-1 max-w-3xl text-gray-400">{report.recommendation}</p>
            </div>
          )}
        </>
      )}

      <div>
        <p className="font-semibold text-white">Alerts in this report</p>
        {report.alerts.length === 0 ? (
          <p className="mt-1 text-gray-400">None.</p>
        ) : (
          <ul className="mt-2 space-y-2">
            {report.alerts.map((alert) => (
              <AlertSummary key={alert.id} alert={alert} />
            ))}
          </ul>
        )}
      </div>

      <div>
        <p className="font-semibold text-white">Documents</p>
        {report.documents.length === 0 ? (
          <p className="mt-1 text-gray-400">None fetched yet.</p>
        ) : (
          <ul className="mt-2 space-y-2">
            {report.documents.map((doc) => (
              <DocumentSummary key={doc.id} doc={doc} />
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function DocumentSummary({ doc }: { doc: UserDocument }) {
  const record = doc.crossref_record
  const details = [record?.journal, record?.published].filter(Boolean).join(' · ')

  return (
    <li className="rounded-xl bg-white/[0.03] px-3.5 py-2.5 text-gray-300 outline-1 -outline-offset-1 outline-white/5">
      <p className="text-xs font-medium text-gray-400">{DOCUMENT_KIND_LABEL[doc.kind]}</p>
      <p className="mt-0.5 font-medium text-white">{record?.title ?? doc.doi}</p>
      {details && <p className="text-xs text-gray-400">{details}</p>}

      <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
        <a
          href={`https://doi.org/${doc.doi}`}
          target="_blank"
          rel="noreferrer"
          className="font-semibold text-indigo-400 hover:text-indigo-300"
        >
          DOI {doc.doi}
        </a>
        {doc.pdf_status === 'ok' && doc.pdf_source_url && (
          <a
            href={doc.pdf_source_url}
            target="_blank"
            rel="noreferrer"
            className="font-semibold text-indigo-400 hover:text-indigo-300"
          >
            Open PDF
          </a>
        )}
        {doc.pdf_status === 'pending' && <Spinner label="Downloading PDF…" />}
        {doc.pdf_status === 'not_found' && <span className="text-gray-500">No open-access PDF</span>}
      </div>

      {doc.text_status === 'ok' && doc.text && (
        <details className="mt-2">
          <summary className="cursor-pointer text-xs font-semibold text-gray-300 hover:text-white">
            Read the text{doc.text_truncated ? ' (shortened)' : ''}
          </summary>
          {/* publisher text: rendered as plain text, never HTML (docs/CONTRACTS.md) */}
          <p className="mt-2 max-h-64 overflow-y-auto whitespace-pre-line text-gray-400">{doc.text}</p>
        </details>
      )}
    </li>
  )
}

export function AlertSummary({ alert }: { alert: Alert }) {
  return (
    <li className="rounded-xl bg-white/[0.03] px-3.5 py-2.5 text-gray-300 outline-1 -outline-offset-1 outline-white/5">
      <p className="font-medium text-white">
        {CHANGE_TYPE_LABEL[alert.change_type]}{' '}
        <span className="text-xs text-gray-400">({ALERT_SEVERITY_LABEL[alert.alert_severity]} alert severity)</span>
      </p>
      <p className="mt-1 text-gray-400">{alert.description}</p>
      <p className="mt-1 text-xs text-gray-500">{formatDateTime(alert.detected_at)}</p>
    </li>
  )
}