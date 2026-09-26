import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import { EllipsisVerticalIcon } from '@heroicons/react/20/solid'

const alerts = [
  {
    id: 1,
    name: 'Unreviewed article replaced by peer-reviewed version',
    href: '#',
    severity: 'High',
    summary:
      'Withdrawal Notice confirms the original article was withdrawn due to an editorial office error that led to publication without peer review; a fully peer-reviewed version now exists under a new DOI.',
    doiFrom: '10.1002/cam4.5306',
    doiTo: '10.1002/cam4.6309',
    detectedAt: '2 hours ago',
    detectedAtTime: '2026-09-27T00:23:00Z',
  },
  {
    id: 2,
    name: 'Dataset version updated with corrected measurements',
    href: '#',
    severity: 'Medium',
    summary:
      'The authors released a revised dataset after identifying measurement inconsistencies in several experimental samples. The updated version changes values used in the original analysis.',
    doiFrom: '10.5281/zenodo.8123456',
    doiTo: '10.5281/zenodo.8234567',
    detectedAt: '5 hours ago',
    detectedAtTime: '2026-09-26T21:10:00Z',
  },
  {
    id: 3,
    name: 'Article expression of concern issued',
    href: '#',
    severity: 'High',
    summary:
      'The journal has issued an expression of concern regarding the reliability of several reported experimental results while an institutional investigation is ongoing.',
    doiFrom: '10.1038/s41586-024-01234-5',
    doiTo: 'N/A',
    detectedAt: '1 day ago',
    detectedAtTime: '2026-09-26T02:45:00Z',
  },
  {
    id: 4,
    name: 'Author correction changes statistical results',
    href: '#',
    severity: 'Medium',
    summary:
      'A publisher correction updates several numerical values in the results and supplementary material. The authors state that the correction does not change the overall conclusions.',
    doiFrom: '10.1016/j.cell.2025.01.015',
    doiTo: '10.1016/j.cell.2025.01.015',
    detectedAt: '2 days ago',
    detectedAtTime: '2026-09-25T11:30:00Z',
  },
  {
    id: 5,
    name: 'Preprint published as peer-reviewed article',
    href: '#',
    severity: 'Low',
    summary:
      'A previously tracked preprint has now been published in a peer-reviewed journal. The published version contains minor changes to the methodology and discussion.',
    doiFrom: '10.48550/arXiv.2401.12345',
    doiTo: '10.1145/3691234.3695678',
    detectedAt: '3 days ago',
    detectedAtTime: '2026-09-24T16:20:00Z',
  },
  {
    id: 6,
    name: 'Supplementary material removed by publisher',
    href: '#',
    severity: 'Medium',
    summary:
      'The publisher has removed a supplementary file from the article record. The reason for removal is not specified in the latest publication metadata.',
    doiFrom: '10.1109/TSE.2025.3456789',
    doiTo: 'N/A',
    detectedAt: '4 days ago',
    detectedAtTime: '2026-09-23T09:15:00Z',
  },
  {
    id: 7,
    name: 'Minor metadata correction detected',
    href: '#',
    severity: 'Low',
    summary:
      'The publisher updated the article metadata to correct an author affiliation. No changes were detected in the article content or experimental results.',
    doiFrom: '10.1145/3623456.3627890',
    doiTo: '10.1145/3623456.3627890',
    detectedAt: '5 days ago',
    detectedAtTime: '2026-09-22T14:05:00Z',
  },
  {
    id: 8,
    name: 'Article formally retracted',
    href: '#',
    severity: 'High',
    summary:
      'The journal has issued a formal retraction following concerns about the reliability of the reported findings. The original article remains accessible with a retraction notice.',
    doiFrom: '10.1016/j.neuron.2024.05.019',
    doiTo: 'N/A',
    detectedAt: '6 days ago',
    detectedAtTime: '2026-09-21T18:40:00Z',
  },
]

const severityStyles: Record<string, string> = {
  High: 'bg-red-50 text-red-700 inset-ring-red-600/10 dark:bg-red-400/10 dark:text-red-400 dark:inset-ring-red-400/20',
  Medium:
    'bg-yellow-50 text-yellow-800 inset-ring-yellow-600/20 dark:bg-yellow-400/10 dark:text-yellow-500 dark:inset-ring-yellow-400/20',
  Low: 'bg-gray-50 text-gray-600 inset-ring-gray-500/10 dark:bg-gray-400/10 dark:text-gray-400 dark:inset-ring-gray-400/20',
}

export default function AlertList() {
  return (
    <div>
      <div className="mb-5">
        <h2 className="text-base/7 font-semibold text-gray-900 dark:text-white">
          Research alerts
        </h2>
        <p className="mt-1 text-sm/6 text-gray-500 dark:text-gray-400">
          Monitor changes that may affect your research.
        </p>
      </div>

      <ul role="list" className="space-y-3">
        {alerts.map((alert) => (
          <li
            key={alert.id}
            className="overflow-hidden bg-white px-4 py-4 shadow-sm sm:rounded-md sm:px-6 dark:bg-gray-800/50 dark:shadow-none dark:outline dark:-outline-offset-1 dark:outline-white/10"
          >
            <div className="flex items-start justify-between gap-x-6">
              <div className="min-w-0 flex-1">
                <div className="flex items-start gap-x-3">
                  <p className="text-sm/6 font-semibold text-gray-900 dark:text-white">
                    {alert.name}
                  </p>

                  <span
                    className={`mt-0.5 shrink-0 rounded-md px-1.5 py-0.5 text-xs font-medium inset-ring ${severityStyles[alert.severity]}`}
                  >
                    {alert.severity}
                  </span>
                </div>

                <p className="mt-1.5 max-w-4xl text-sm/6 text-gray-500 dark:text-gray-400">
                  {alert.summary}
                </p>

                <div className="mt-2 flex flex-wrap items-center gap-x-2 text-xs/5 text-gray-500 dark:text-gray-400">
                  <p className="whitespace-nowrap">
                    Detected{' '}
                    <time dateTime={alert.detectedAtTime}>
                      {alert.detectedAt}
                    </time>
                  </p>

                  <svg
                    viewBox="0 0 2 2"
                    className="size-0.5 fill-current"
                  >
                    <circle r={1} cx={1} cy={1} />
                  </svg>

                  <p className="truncate">
                    DOI {alert.doiFrom}{' '}
                    <span aria-hidden="true">→</span> {alert.doiTo}
                  </p>
                </div>
              </div>

              <div className="flex flex-none items-center gap-x-4">
                <a
                  href={alert.href}
                  className="hidden rounded-md bg-white px-2.5 py-1.5 text-sm font-semibold text-gray-900 shadow-sm inset-ring inset-ring-gray-300 hover:bg-gray-50 sm:block dark:bg-white/10 dark:text-white dark:shadow-none dark:inset-ring-white/10 dark:hover:bg-white/20"
                >
                  View details
                  <span className="sr-only">, {alert.name}</span>
                </a>

                <Menu as="div" className="relative flex-none">
                  <MenuButton className="relative block text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white">
                    <span className="absolute -inset-2.5" />
                    <span className="sr-only">Open options</span>
                    <EllipsisVerticalIcon
                      aria-hidden="true"
                      className="size-5"
                    />
                  </MenuButton>

                  <MenuItems
                    transition
                    className="absolute right-0 z-10 mt-2 w-56 origin-top-right rounded-md bg-white py-2 shadow-lg outline-1 outline-gray-900/5 transition data-closed:scale-95 data-closed:transform data-closed:opacity-0 data-enter:duration-100 data-enter:ease-out data-leave:duration-75 data-leave:ease-in dark:bg-gray-800 dark:shadow-none dark:-outline-offset-1 dark:outline-white/10"
                  >
                    <MenuItem>
                      <a
                        href="#"
                        className="block px-3 py-1 text-sm/6 text-gray-900 data-focus:bg-gray-50 data-focus:outline-hidden dark:text-white dark:data-focus:bg-white/5"
                      >
                        Update citation records
                        <span className="sr-only">, {alert.name}</span>
                      </a>
                    </MenuItem>

                    <MenuItem>
                      <a
                        href="#"
                        className="block px-3 py-1 text-sm/6 text-gray-900 data-focus:bg-gray-50 data-focus:outline-hidden dark:text-white dark:data-focus:bg-white/5"
                      >
                        Delete outdated file
                        <span className="sr-only">, {alert.name}</span>
                      </a>
                    </MenuItem>

                    <MenuItem>
                      <a
                        href="#"
                        className="block px-3 py-1 text-sm/6 text-gray-900 data-focus:bg-gray-50 data-focus:outline-hidden dark:text-white dark:data-focus:bg-white/5"
                      >
                        Verify experimental details
                        <span className="sr-only">, {alert.name}</span>
                      </a>
                    </MenuItem>
                  </MenuItems>
                </Menu>
              </div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}