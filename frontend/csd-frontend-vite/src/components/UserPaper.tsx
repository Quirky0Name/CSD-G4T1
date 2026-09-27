import { useRef, useState } from 'react'
import {
  DocumentArrowUpIcon,
  EllipsisHorizontalIcon,
} from '@heroicons/react/20/solid'
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'

const dummyPaper = {
  id: '1',
  title: 'The Impact of Attention Mechanisms on Transformer Efficiency (Draft)',
  status: 'Draft · Last edited 2 days ago',
}

const UserPaper = () => {
  const [hasPaper, setHasPaper] = useState(true)
  const [fileName, setFileName] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Opens the native file picker. Wire this up to an actual upload call later.
  const handleUploadClick = () => {
    fileInputRef.current?.click()
  }

  // Fires once a file is picked. Currently just stores the name locally —
  // swap this out for a real upload/replace request.
  const handleFileSelected = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    setFileName(file.name)
    setHasPaper(true)

    // reset the input so selecting the same file again still fires onChange
    e.target.value = ''
  }

  // Does nothing yet — plug in a real delete call later.
  const handleRemove = () => {
    setHasPaper(false)
    setFileName(null)
  }

  return (
    <div className="mt-10">
      <div className="mb-4 flex items-center justify-between gap-4">
        <h3 className="text-sm/6 font-semibold text-white">Current research paper</h3>
      </div>

      {/* Hidden input shared by both "Upload" and "Replace paper" */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".pdf"
        className="hidden"
        onChange={handleFileSelected}
      />

      {!hasPaper ? (
        <div className="flex min-h-32 flex-col items-center justify-center rounded-xl border border-dashed border-white/10 px-6 text-center">
          <DocumentArrowUpIcon aria-hidden="true" className="mx-auto size-8 text-gray-500" />
          <h3 className="mt-3 text-sm/6 font-semibold text-white">No paper uploaded yet</h3>
          <p className="mt-1 text-sm/6 text-gray-400">
            Upload a PDF to start tracking your paper.
          </p>
          <button
            type="button"
            onClick={handleUploadClick}
            className="mt-4 inline-flex items-center rounded-md bg-indigo-500 px-3 py-2 text-xs font-semibold text-white shadow-sm hover:bg-indigo-400"
          >
            Upload paper
          </button>
        </div>
      ) : (
        <div className="flex min-h-32 items-start gap-x-4 rounded-xl bg-gray-800/50 p-6 outline outline-white/10">
          {/* Paper preview thumbnail */}
          <div className="flex h-20 w-16 flex-none flex-col gap-1 rounded-sm bg-white p-2 shadow-md">
            <div className="h-1 w-full rounded-full bg-gray-300" />
            <div className="h-1 w-4/5 rounded-full bg-gray-300" />
            <div className="h-1 w-full rounded-full bg-gray-200" />
            <div className="h-1 w-3/5 rounded-full bg-gray-200" />
            <div className="mt-1 h-1 w-full rounded-full bg-gray-200" />
            <div className="h-1 w-4/5 rounded-full bg-gray-200" />
            <div className="h-1 w-full rounded-full bg-gray-200" />
          </div>

          <div className="min-w-0 flex-1 text-sm/6">
            <p className="truncate font-medium text-white">
              {fileName ?? dummyPaper.title}
            </p>
            <p className="mt-0.5 flex items-center gap-x-1.5 text-xs/5 text-gray-400">
              <span className="inline-flex items-center rounded-full bg-yellow-400/10 px-1.5 py-0.5 text-[10px] font-medium text-yellow-400 ring-1 ring-inset ring-yellow-400/20">
                Draft
              </span>
              Last edited 2 days ago
            </p>
          </div>

          <Menu as="div" className="relative ml-auto flex-none">
            <MenuButton className="relative block text-gray-400 hover:text-white">
              <span className="absolute -inset-2.5" />
              <span className="sr-only">Open options for {dummyPaper.title}</span>
              <EllipsisHorizontalIcon aria-hidden="true" className="size-5" />
            </MenuButton>
            <MenuItems
              transition
              className="absolute right-0 z-10 mt-0.5 w-36 origin-top-right rounded-md bg-gray-800 py-2 shadow-lg outline outline-white/10 transition data-closed:scale-95 data-closed:transform data-closed:opacity-0 data-enter:duration-100 data-enter:ease-out data-leave:duration-75 data-leave:ease-in"
            >
              <MenuItem>
                <button
                  type="button"
                  onClick={handleUploadClick}
                  className="block w-full px-3 py-1 text-left text-sm/6 text-white data-focus:bg-white/5"
                >
                  Replace paper<span className="sr-only">, {dummyPaper.title}</span>
                </button>
              </MenuItem>
              <MenuItem>
                <button
                  type="button"
                  onClick={handleRemove}
                  className="block w-full px-3 py-1 text-left text-sm/6 text-white data-focus:bg-white/5"
                >
                  Remove<span className="sr-only">, {dummyPaper.title}</span>
                </button>
              </MenuItem>
            </MenuItems>
          </Menu>
        </div>
      )}
    </div>
  )
}

export default UserPaper