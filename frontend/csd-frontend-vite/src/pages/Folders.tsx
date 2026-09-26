import { useNavigate } from 'react-router-dom'
import { ArrowRightIcon } from '@heroicons/react/20/solid'

function Folders() {
  const navigate = useNavigate()

  return (
    <main className="px-4 py-10 sm:px-6 lg:px-8">
      <h2 className="text-2xl/7 font-bold text-white sm:truncate sm:text-3xl sm:tracking-tight">
        Folders
      </h2>
      <button
        type="button"
        onClick={() => navigate('/citation-sources')}
        className="mt-5 inline-flex items-center rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-xs inset-ring inset-ring-white/10 hover:bg-white/20"
      >
        Go to Sources cited
        <ArrowRightIcon aria-hidden="true" className="ml-1.5 -mr-0.5 size-5 text-gray-300" />
      </button>
    </main>
  )
}

export default Folders
