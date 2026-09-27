import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  ArrowLeftIcon,
  ArrowPathIcon,
  CalendarIcon,
  ChevronDownIcon,
  PlusIcon,
} from '@heroicons/react/20/solid'
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import CitedSourcesList, { type CitedSource } from '../components/CitedSourcesList'
import UploadSourceDialog from '../components/UploadSourceDialog'

const placeholderSources: CitedSource[] = [
  {
    id: 'placeholder-1',
    title: 'A placeholder research paper',
    doi: '10.1234/example.paper',
    journal: 'Example Journal',
    severity: 'High',
    createdAt: '2026-09-26',
  },
  {
    id: 'placeholder-2',
    title: 'Another tracked source',
    doi: '10.5678/another.source',
    journal: 'Research Review',
    severity: 'Medium',
    createdAt: '2026-09-26',
  },
  {
    id: 'placeholder-3',
    title: 'Long-term outcomes in clinical research',
    doi: '10.1000/clinical.outcomes',
    journal: 'The Lancet',
    severity: 'Low',
    createdAt: '2026-09-25',
  },
  {
    id: 'placeholder-4',
    title: 'Methods for evidence-based reviews',
    doi: '10.1000/evidence.methods',
    journal: 'Nature Reviews',
    severity: 'Medium',
    createdAt: '2026-09-24',
  },
  {
    id: 'placeholder-5',
    title: 'Evaluating changes in public health data',
    doi: '10.1000/public.health',
    journal: 'Journal of Public Health',
    severity: 'High',
    createdAt: '2026-09-23',
  },
  {
    id: 'placeholder-6',
    title: 'A systematic review of emerging treatments',
    doi: null,
    journal: 'Medical Science Quarterly',
    severity: 'Low',
    createdAt: '2026-09-22',
  },
  {
    id: 'placeholder-7',
    title: 'Understanding reproducibility in science',
    doi: '10.1000/reproducibility',
    journal: 'Open Research Journal',
    severity: null,
    createdAt: '2026-09-21',
  },
]

function CitationSources() {
  const navigate = useNavigate()
  const [uploadOpen, setUploadOpen] = useState(false)
  const [showPopulatedPreview, setShowPopulatedPreview] = useState(true)
  const sources = showPopulatedPreview ? placeholderSources : []

  return (
    <main className="px-4 py-10 sm:px-6 lg:px-8">
      <div className="lg:flex lg:items-center lg:justify-between">
        <div className="min-w-0 flex-1">
          <h2 className="text-2xl/7 font-bold text-white sm:truncate sm:text-3xl sm:tracking-tight">
            Sources cited
          </h2>
          <div className="mt-1 flex flex-col sm:mt-0 sm:flex-row sm:flex-wrap sm:space-x-6">
            <div className="mt-2 flex items-center text-sm text-gray-400">
              <CalendarIcon
                aria-hidden="true"
                className="mr-1.5 size-5 shrink-0 text-gray-500"
              />
              Updated on [date]
            </div>
          </div>
        </div>

        <div className="mt-5 flex flex-wrap items-center gap-3 lg:mt-0 lg:ml-4">
          <button
            type="button"
            onClick={() => navigate('/')}
            className="inline-flex items-center rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-xs inset-ring inset-ring-white/10 hover:bg-white/20"
          >
            <ArrowLeftIcon aria-hidden="true" className="mr-1.5 -ml-0.5 size-5 text-gray-300" />
            Back to folders
          </button>

          <span>
            <Menu as="div" className="relative">
              <MenuButton className="inline-flex items-center rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-xs inset-ring inset-ring-white/10 hover:bg-white/20">
                Schedule updates
                <ChevronDownIcon
                  aria-hidden="true"
                  className="-mr-1 ml-1.5 size-5 text-gray-300"
                />
              </MenuButton>
              <MenuItems
                transition
                className="absolute right-0 z-10 mt-2 w-40 origin-top-right rounded-md bg-gray-800 py-1 shadow-lg outline outline-white/10 transition data-closed:scale-95 data-closed:transform data-closed:opacity-0 data-enter:duration-200 data-enter:ease-out data-leave:duration-75 data-leave:ease-in"
              >
                {['Daily', 'Weekly', 'Monthly'].map((schedule) => (
                  <MenuItem key={schedule}>
                    <button
                      type="button"
                      className="block w-full px-4 py-2 text-left text-sm text-gray-300 data-focus:bg-white/5"
                    >
                      {schedule}
                    </button>
                  </MenuItem>
                ))}
              </MenuItems>
            </Menu>
          </span>

          <span>
            <button
              type="button"
              className="inline-flex items-center rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white shadow-xs hover:bg-indigo-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400"
            >
              <ArrowPathIcon aria-hidden="true" className="mr-1.5 -ml-0.5 size-5" />
              Update now
            </button>
          </span>

          <button
            type="button"
            onClick={() => setUploadOpen(true)}
            aria-label="Upload PDF"
            title="Upload PDF"
            className="cursor-pointer rounded-full bg-indigo-500 p-2 text-white shadow-xs hover:bg-indigo-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400"
          >
            <PlusIcon aria-hidden="true" className="size-5" />
          </button>
        </div>
      </div>

      <div className="mt-8 flex flex-wrap items-center gap-3 border-t border-white/10 pt-5">
        <span className="text-sm text-gray-400">Preview state</span>
        <button
          type="button"
          onClick={() => setShowPopulatedPreview(true)}
          className={`rounded-md px-3 py-2 text-sm font-semibold ${
            showPopulatedPreview
              ? 'bg-white/10 text-white'
              : 'text-gray-400 hover:bg-white/5 hover:text-white'
          }`}
        >
          Populated
        </button>
        <button
          type="button"
          onClick={() => setShowPopulatedPreview(false)}
          className={`rounded-md px-3 py-2 text-sm font-semibold ${
            !showPopulatedPreview
              ? 'bg-white/10 text-white'
              : 'text-gray-400 hover:bg-white/5 hover:text-white'
          }`}
        >
          Empty
        </button>
      </div>

      <CitedSourcesList sources={sources} />
      <UploadSourceDialog open={uploadOpen} onClose={() => setUploadOpen(false)} />
    </main>
  )
}

export default CitationSources
