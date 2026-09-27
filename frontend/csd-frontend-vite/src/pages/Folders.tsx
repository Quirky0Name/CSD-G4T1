import { useNavigate } from 'react-router-dom'
import { FolderIcon, ChevronDownIcon } from '@heroicons/react/24/solid'

type Folder = {
  id: string
  name: string
  updatedAt?: string
}

const folders: Folder[] = [
  { id: '1', name: 'Demo folder', updatedAt: 'Updated today' },
]

function Folders() {
  const navigate = useNavigate()

  return (
    <main className="px-4 py-10 sm:px-6 lg:px-8">
      <div className="min-w-0 flex-1 border-b border-white/10 pt-5 pb-4">
        <h2 className="text-2xl/7 font-bold text-white sm:truncate sm:text-3xl sm:tracking-tight">
          Folders
        </h2>
      </div>

      <ul role="list" className="mt-6 grid grid-cols-1 justify-items-start gap-x-6 gap-y-8 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 xl:gap-x-8">
        {folders.map((folder) => (
          <li
            key={folder.id}
            className="relative flex w-full flex-col items-start overflow-visible rounded-xl p-6"
          >
            <button
              type="button"
              onClick={() => navigate('/citation-sources')}
              className="group relative flex h-24 w-24 items-start justify-start"
            >
              <FolderIcon
                aria-hidden="true"
                className="w-full h-auto text-gray-400 transition group-hover:text-gray-300"
              />
            </button>

            <div className="mt-2 flex items-center gap-x-1 text-sm font-medium text-white">
              {folder.name}
              <ChevronDownIcon aria-hidden="true" className="size-3.5 text-gray-400" />
            </div>
            {folder.updatedAt && (
              <p className="mt-0.5 text-xs text-gray-500">{folder.updatedAt}</p>
            )}
          </li>
        ))}
      </ul>
    </main>
  )
}

export default Folders