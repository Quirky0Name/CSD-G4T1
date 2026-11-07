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

const DOCUMENT_KIND_LABEL: Record<DocumentKind, string> = {
  notice: 'Notice',
  new_version: 'Newer version',
  current_version: 'Current copy',
  related: 'Related document',
}

function Spinner({ label }: { label: string }) {
  return (
    <div role="status" className="flex items-center gap-2 text-sm text-gray-400">
      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" className="size-4 animate-spin text-indigo-400">
        <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" className="opacity-25" />
        <path fill="currentColor" d="M4 12a8 8 0 0 1 8-8v4a4 4 0 0 0-4 4H4z" className="opacity-75" />
      </svg>
      {label}
    </div>
  )
}

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

  return (
    <div>
      <div className="mb-6 flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl/7 font-bold text-white sm:text-3xl sm:tracking-tight">Reports</h1>
          <p className="mt-2 text-sm/6 text-gray-400">
            {filteredTitle ? `For ${filteredTitle}.` : 'Changes that may affect your research, grouped per paper.'}
          </p>
        </div>
        {paperFilter && (
          <Link to="/reports" className="mt-1 shrink-0 text-sm font-semibold text-indigo-400 hover:text-indigo-300">
            Show all
          </Link>
        )}
      </div>

      {state.status === 'loading' && <Spinner label="Loading reports…" />}
      {state.status === 'error' && (
        <p className="rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">
          {state.message}
        </p>
      )}
      {state.status === 'ready' && reports && (
        <ReportRows
          papers={state.papers}
          reports={reports}
          expandedId={expandedId}
          onToggle={(id) => setExpandedId((current) => (current === id ? null : id))}
        />
      )}
    </div>
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
    return <p className="text-sm/6 text-gray-400">No reports yet.</p>
  }

  return (
    <ul role="list" className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,34rem),1fr))] items-start gap-3">
      {reports.map((report) => (
        <li
          key={report.id}
          className="overflow-hidden bg-gray-800/50 px-4 py-4 shadow-none outline -outline-offset-1 outline-white/10 sm:rounded-md sm:px-6"
        >
          <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
            <p className="min-w-0 text-sm/6 font-semibold text-white">
              {paperTitle.get(report.paper_id) ?? 'Unknown paper'}
            </p>
            <span
              className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${REPORT_STATUS_STYLES[report.status]}`}
            >
              {REPORT_STATUS_LABEL[report.status]}
            </span>
            {report.change_severity && report.change_severity !== 'none' && (
              <span
                className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${LEVEL_STYLES[report.change_severity]}`}
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

          <div className="mt-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
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

          {expandedId === report.id && <ReportDetails report={report} />}
        </li>
      ))}
    </ul>
  )
}

function ReportDetails({ report }: { report: UserReport }) {
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
              <p className="mt-1 text-gray-400">{report.evaluation}</p>
            </div>
          )}
          {report.recommendation && (
            <div>
              <p className="font-semibold text-white">Recommendation</p>
              <p className="mt-1 text-gray-400">{report.recommendation}</p>
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
    <li className="rounded-md bg-gray-900/60 px-3 py-2 text-gray-300">
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

function AlertSummary({ alert }: { alert: Alert }) {
  return (
    <li className="rounded-md bg-gray-900/60 px-3 py-2 text-gray-300">
      <p className="font-medium text-white">
        {CHANGE_TYPE_LABEL[alert.change_type]}{' '}
        <span className="text-xs text-gray-400">({ALERT_SEVERITY_LABEL[alert.alert_severity]} alert severity)</span>
      </p>
      <p className="mt-1 text-gray-400">{alert.description}</p>
      <p className="mt-1 text-xs text-gray-500">{formatDateTime(alert.detected_at)}</p>
    </li>
  )
}