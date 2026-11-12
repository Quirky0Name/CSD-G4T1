import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import {
  CheckCircleIcon,
  DocumentTextIcon,
  EllipsisHorizontalIcon,
} from '@heroicons/react/20/solid'
import { Link, useNavigate } from 'react-router-dom'
import type { Alert, Paper } from '../api'
import { ALERT_SEVERITY_DOT_CLASS, ALERT_SEVERITY_LABEL, formatDateTime, highestNewAlertSeverity } from '../format'

type TrackedPaperListProps = {
  papers: Paper[]
  alerts: Alert[]
  onDelete: (paperId: string) => void
}

function TrackedPaperList({ papers, alerts, onDelete }: TrackedPaperListProps) {
  const navigate = useNavigate()

  if (papers.length === 0) {
    return (
      <div className="mt-10 rounded-xl border border-dashed border-white/10 px-6 py-12 text-center">
        <DocumentTextIcon aria-hidden="true" className="mx-auto size-10 text-gray-500" />
        <h3 className="mt-4 text-sm/6 font-semibold text-white">No tracked papers yet</h3>
        <p className="mt-2 text-sm/6 text-gray-400">
          Upload a PDF or enter a DOI to start tracking a paper.
        </p>
      </div>
    )
  }

  return (
    <div className="mt-10">
      <div className="mb-4 flex items-center justify-between gap-4">
        <h3 className="text-sm/6 font-semibold text-white">Tracked papers</h3>
      </div>

      <ul role="list" className="grid grid-cols-1 gap-x-6 gap-y-8 lg:grid-cols-3 xl:gap-x-8">
        {papers.map((paper) => {
          const title = paper.title ?? 'Untitled (no DOI found)'
          const alertSeverity = highestNewAlertSeverity(alerts.filter((alert) => alert.paper_id === paper.id))

          return (
            <li
              key={paper.id}
              className="relative overflow-visible rounded-xl outline outline-white/10 focus-within:outline-indigo-500 hover:outline-white/25"
            >
              <div className="flex items-center gap-x-4 rounded-t-xl border-b border-white/10 bg-gray-800/50 p-6">
                <div className="flex size-12 flex-none items-center justify-center rounded-lg bg-gray-700 ring-1 ring-white/10">
                  <DocumentTextIcon aria-hidden="true" className="size-6 text-gray-300" />
                </div>
                <div className="min-w-0 text-sm/6 font-medium text-white">
                  {/* the link's ::after stretches over the whole card, so the card opens the paper */}
                  <Link
                    to={`/papers/${paper.id}`}
                    title={title}
                    className="block truncate after:absolute after:inset-0 after:rounded-xl focus:outline-none"
                  >
                    {title}
                  </Link>
                  <p className="truncate text-xs/5 font-normal text-gray-400">
                    {paper.journal ?? 'Journal not available'}
                  </p>
                </div>
                <Menu as="div" className="relative z-10 ml-auto">
                  <MenuButton className="relative block text-gray-400 hover:text-white">
                    <span className="absolute -inset-2.5" />
                    <span className="sr-only">Open options for {title}</span>
                    <EllipsisHorizontalIcon aria-hidden="true" className="size-5" />
                  </MenuButton>
                  <MenuItems
                    transition
                    className="absolute right-0 z-10 mt-0.5 w-36 origin-top-right rounded-md bg-gray-800 py-2 shadow-lg outline outline-white/10 transition data-closed:scale-95 data-closed:transform data-closed:opacity-0 data-enter:duration-100 data-enter:ease-out data-leave:duration-75 data-leave:ease-in"
                  >
                    <MenuItem>
                      <Link
                        to={`/papers/${paper.id}`}
                        className="block w-full px-3 py-1 text-left text-sm/6 text-white data-focus:bg-white/5"
                      >
                        View checks<span className="sr-only">, {title}</span>
                      </Link>
                    </MenuItem>
                    <MenuItem>
                      <button
                        type="button"
                        onClick={() => {
                          if (
                            window.confirm(
                              `Delete "${title}" and all its alerts and reports? This can't be undone.`,
                            )
                          ) {
                            onDelete(paper.id)
                          }
                        }}
                        className="block w-full px-3 py-1 text-left text-sm/6 text-red-400 data-focus:bg-white/5"
                      >
                        Delete<span className="sr-only">, {title}</span>
                      </button>
                    </MenuItem>
                  </MenuItems>
                </Menu>
              </div>
              {alertSeverity ? (
                <button
                  type="button"
                  onClick={() => navigate(`/alerts?paper=${paper.id}`)}
                  aria-label={`View alerts for ${title} (${ALERT_SEVERITY_LABEL[alertSeverity].toLowerCase()} alert severity)`}
                  title={`Change detected: ${ALERT_SEVERITY_LABEL[alertSeverity].toLowerCase()} alert severity`}
                  className="absolute -top-2 right-3 z-10 inline-flex cursor-pointer items-center gap-x-1.5 rounded-full bg-gray-900/95 px-2 py-1 text-[11px] font-medium text-gray-200 shadow-md inset-ring inset-ring-white/10"
                >
                  <svg viewBox="0 0 6 6" aria-hidden="true" className={`size-1.5 ${ALERT_SEVERITY_DOT_CLASS[alertSeverity]}`}>
                    <circle r={3} cx={3} cy={3} />
                  </svg>
                  {ALERT_SEVERITY_LABEL[alertSeverity]} alert severity
                </button>
              ) : (
                <div
                  title="No changes detected since the last poll"
                  className="absolute -top-2 right-3 z-10 inline-flex items-center gap-x-1.5 rounded-full bg-gray-900/95 px-2 py-1 text-[11px] font-medium text-gray-400 shadow-md inset-ring inset-ring-white/10"
                >
                  <CheckCircleIcon aria-hidden="true" className="size-3 text-emerald-500/80" />
                  No recent changes
                </div>
              )}
              <dl className="-my-3 divide-y divide-white/10 rounded-b-xl px-6 py-4 text-sm/6">
                <div className="flex justify-between gap-x-4 py-3">
                  <dt className="text-gray-500">DOI</dt>
                  <dd className="max-w-[65%] truncate text-right text-gray-300">
                    {paper.doi ?? 'Not available'}
                  </dd>
                </div>
                <div className="flex justify-between gap-x-4 py-3">
                  <dt className="text-gray-500">Added</dt>
                  <dd className="text-gray-300">
                    <time dateTime={paper.created_at}>{formatDateTime(paper.created_at)}</time>
                  </dd>
                </div>
              </dl>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

export default TrackedPaperList