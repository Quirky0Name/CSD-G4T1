import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import {
  CheckCircleIcon,
  DocumentTextIcon,
  EllipsisHorizontalIcon,
  PlusIcon,
} from '@heroicons/react/20/solid'
import { Link, useNavigate } from 'react-router-dom'
import type { Alert, Paper } from '../api'
import { ActionButton } from './ActionButton'
import { EmptyState, SectionHeading } from './Page'
import { BookmarkIcon } from '@heroicons/react/24/outline'
import { cardInteractive, iconButton, riseDelay } from '../ui'
import { ALERT_SEVERITY_DOT_CLASS, ALERT_SEVERITY_LABEL, formatDateTime, highestNewAlertSeverity } from '../format'

type TrackedPaperListProps = {
  papers: Paper[]
  alerts: Alert[]
  onDelete: (paperId: string) => void
  onTrack: () => void
}

function TrackedPaperList({ papers, alerts, onDelete, onTrack }: TrackedPaperListProps) {
  const navigate = useNavigate()

  if (papers.length === 0) {
    return (
      <div className="mt-10">
        <EmptyState
          pose="laptop"
          title="No tracked papers yet"
          index={3}
          action={
            <ActionButton tone="primary" onClick={onTrack}>
              <PlusIcon aria-hidden="true" className="size-4" />
              Track a paper
            </ActionButton>
          }
        >
          Upload a PDF or enter a DOI, and the penguin keeps watch for retractions, corrections and delistings.
        </EmptyState>
      </div>
    )
  }

  return (
    <div className="mt-10">
      <SectionHeading title="Tracked papers" icon={BookmarkIcon} count={papers.length} index={3} />

      <ul role="list" className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,20rem),1fr))] gap-x-5 gap-y-7">
        {papers.map((paper, index) => {
          const title = paper.title ?? 'Untitled (no DOI found)'
          const alertSeverity = highestNewAlertSeverity(alerts.filter((alert) => alert.paper_id === paper.id))

          return (
            <li
              key={paper.id}
              style={riseDelay(index + 4)}
              className={`${cardInteractive} animate-rise group relative overflow-visible`}
            >
              <div className="flex items-center gap-x-4 rounded-t-2xl border-b border-white/10 p-5">
                <div className="flex size-11 flex-none items-center justify-center rounded-2xl bg-indigo-500/10 text-indigo-300 transition-colors group-hover:bg-indigo-500/20">
                  <DocumentTextIcon aria-hidden="true" className="size-6" />
                </div>
                <div className="min-w-0 text-sm/6 font-medium text-white">
                  {/* the link's ::after stretches over the whole card, so the card opens the paper */}
                  <Link
                    to={`/papers/${paper.id}`}
                    title={title}
                    className="block truncate after:absolute after:inset-0 after:rounded-2xl focus:outline-none"
                  >
                    {title}
                  </Link>
                  <p className="truncate text-xs/5 font-normal text-gray-400">
                    {paper.journal ?? 'Journal not available'}
                  </p>
                </div>
                <Menu as="div" className="relative z-10 ml-auto">
                  <MenuButton className={iconButton}>
                    <span className="sr-only">Open options for {title}</span>
                    <EllipsisHorizontalIcon aria-hidden="true" className="size-5" />
                  </MenuButton>
                  <MenuItems
                    transition
                    className="absolute right-0 z-10 mt-1 w-40 origin-top-right rounded-2xl bg-gray-800 p-1.5 shadow-lg outline-1 -outline-offset-1 outline-white/10 transition data-closed:scale-95 data-closed:transform data-closed:opacity-0 data-enter:duration-100 data-enter:ease-out data-leave:duration-75 data-leave:ease-in"
                  >
                    <MenuItem>
                      <Link
                        to={`/papers/${paper.id}`}
                        className="block w-full rounded-xl px-3 py-2 text-left text-sm/6 text-white data-focus:bg-white/5"
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
                        className="block w-full rounded-xl px-3 py-2 text-left text-sm/6 text-red-400 data-focus:bg-white/5"
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
                  className="absolute -top-2.5 right-4 z-10 inline-flex cursor-pointer items-center gap-x-1.5 rounded-full bg-gray-950 px-2.5 py-1 text-[11px] font-medium text-gray-200 shadow-md inset-ring inset-ring-white/15 transition-colors hover:text-white hover:inset-ring-white/30 focus-visible:outline-2 focus-visible:outline-indigo-400"
                >
                  <svg viewBox="0 0 6 6" aria-hidden="true" className={`size-1.5 ${ALERT_SEVERITY_DOT_CLASS[alertSeverity]}`}>
                    <circle r={3} cx={3} cy={3} />
                  </svg>
                  {/* sized on a span: index.css gives buttons font: inherit */}
                  <span className="text-[11px]">{ALERT_SEVERITY_LABEL[alertSeverity]} alert severity</span>
                </button>
              ) : (
                <div
                  title="No changes detected since the last poll"
                  className="absolute -top-2.5 right-4 z-10 inline-flex items-center gap-x-1.5 rounded-full bg-gray-950 px-2.5 py-1 text-[11px] font-medium text-gray-400 shadow-md inset-ring inset-ring-white/10"
                >
                  <CheckCircleIcon aria-hidden="true" className="size-3 text-emerald-500/80" />
                  No recent changes
                </div>
              )}
              <dl className="-my-3 divide-y divide-white/10 rounded-b-2xl px-5 py-4 text-sm/6">
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