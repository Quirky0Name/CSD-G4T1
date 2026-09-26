import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import {
  CheckCircleIcon,
  DocumentTextIcon,
  EllipsisHorizontalIcon,
} from '@heroicons/react/20/solid'
import { useNavigate } from 'react-router-dom'

type Severity = 'High' | 'Medium' | 'Low'

export type CitedSource = {
  id: string
  title: string
  doi: string | null
  journal: string | null
  severity: Severity | null
  createdAt: string
}

type CitedSourcesListProps = {
  sources: CitedSource[]
}

function CitedSourcesList({ sources }: CitedSourcesListProps) {
  const navigate = useNavigate()

  if (sources.length === 0) {
    return (
      <div className="mt-10 rounded-xl border border-dashed border-white/10 px-6 py-12 text-center">
        <DocumentTextIcon aria-hidden="true" className="mx-auto size-10 text-gray-500" />
        <h3 className="mt-4 text-sm/6 font-semibold text-white">No cited sources yet</h3>
        <p className="mt-2 text-sm/6 text-gray-400">
          Upload a PDF or enter a DOI to start tracking a source.
        </p>
      </div>
    )
  }

  return (
    <div className="mt-10">
      <div className="mb-4 flex items-center justify-between gap-4">
        <h3 className="text-sm/6 font-semibold text-white">Cited sources</h3>
      </div>

      <ul role="list" className="grid grid-cols-1 gap-x-6 gap-y-8 lg:grid-cols-3 xl:gap-x-8">
        {sources.map((source) => (
          <li
            key={source.id}
            className="relative overflow-visible rounded-xl outline outline-white/10"
          >
            <div className="relative flex items-center gap-x-4 rounded-t-xl border-b border-white/10 bg-gray-800/50 p-6">
              <div className="flex size-12 flex-none items-center justify-center rounded-lg bg-gray-700 ring-1 ring-white/10">
                <DocumentTextIcon aria-hidden="true" className="size-6 text-gray-300" />
              </div>
              <div className="min-w-0 text-sm/6 font-medium text-white">
                <p className="truncate">{source.title}</p>
                <p className="truncate text-xs/5 font-normal text-gray-400">
                  {source.journal ?? 'Journal not available'}
                </p>
              </div>
              <Menu as="div" className="relative ml-auto">
                <MenuButton className="relative block text-gray-400 hover:text-white">
                  <span className="absolute -inset-2.5" />
                  <span className="sr-only">Open options for {source.title}</span>
                  <EllipsisHorizontalIcon aria-hidden="true" className="size-5" />
                </MenuButton>
                <MenuItems
                  transition
                  className="absolute right-0 z-10 mt-0.5 w-32 origin-top-right rounded-md bg-gray-800 py-2 shadow-lg outline outline-white/10 transition data-closed:scale-95 data-closed:transform data-closed:opacity-0 data-enter:duration-100 data-enter:ease-out data-leave:duration-75 data-leave:ease-in"
                >
                  <MenuItem>
                    <button
                      type="button"
                      className="block w-full px-3 py-1 text-left text-sm/6 text-white data-focus:bg-white/5"
                    >
                      View<span className="sr-only">, {source.title}</span>
                    </button>
                  </MenuItem>
                  <MenuItem>
                    <button
                      type="button"
                      className="block w-full px-3 py-1 text-left text-sm/6 text-white data-focus:bg-white/5"
                    >
                      Remove<span className="sr-only">, {source.title}</span>
                    </button>
                  </MenuItem>
                </MenuItems>
              </Menu>
            </div>
            {source.severity ? (
              <button
                type="button"
                onClick={() => navigate('/alerts')}
                aria-label={`View ${source.severity.toLowerCase()} severity alert for ${source.title}`}
                title={`Change detected: ${source.severity} severity`}
                className="absolute -top-2 right-3 z-10 inline-flex cursor-pointer items-center gap-x-1.5 rounded-full bg-gray-900/95 px-2 py-1 text-[11px] font-medium text-gray-200 shadow-md inset-ring inset-ring-white/10"
              >
                <svg
                  viewBox="0 0 6 6"
                  aria-hidden="true"
                  className={`size-1.5 ${
                    source.severity === 'High'
                      ? 'fill-red-400'
                      : source.severity === 'Medium'
                        ? 'fill-yellow-400'
                        : 'fill-blue-400'
                  }`}
                >
                  <circle r={3} cx={3} cy={3} />
                </svg>
                {source.severity} severity
              </button>
            ) : (
              <div className="absolute top-4 right-4 inline-flex items-center gap-x-1.5 text-xs font-medium text-emerald-400">
                <CheckCircleIcon aria-hidden="true" className="size-4" />
                No recent changes
              </div>
            )}
            <dl className="-my-3 divide-y divide-white/10 rounded-b-xl px-6 py-4 text-sm/6">
              <div className="flex justify-between gap-x-4 py-3">
                <dt className="text-gray-500">DOI</dt>
                <dd className="max-w-[65%] truncate text-right text-gray-300">
                  {source.doi ?? 'Not available'}
                </dd>
              </div>
              <div className="flex justify-between gap-x-4 py-3">
                <dt className="text-gray-500">Added</dt>
                <dd className="text-gray-300">
                  <time dateTime={source.createdAt}>{source.createdAt}</time>
                </dd>
              </div>
            </dl>
          </li>
        ))}
      </ul>
    </div>
  )
}

export default CitedSourcesList
