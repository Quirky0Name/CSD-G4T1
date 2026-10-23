import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { DocumentArrowUpIcon, EllipsisHorizontalIcon } from '@heroicons/react/20/solid'
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import { deleteDraft, errorMessage, getDraft, uploadDraft, type Draft } from '../api'
import { formatDateTime } from '../format'
import { useToast } from './Toasts'

type LoadState = { status: 'loading' } | { status: 'ready'; draft: Draft | null }

const DraftCard = () => {
  const showToast = useToast()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    getDraft()
      .then((draft) => {
        if (active) setState({ status: 'ready', draft })
      })
      .catch((err: unknown) => {
        if (!active) return
        setState({ status: 'ready', draft: null })
        showToast({ tone: 'error', message: errorMessage(err) })
      })
    return () => {
      active = false
    }
  }, [showToast])

  const handleUploadClick = () => {
    fileInputRef.current?.click()
  }

  const handleFileSelected = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    // reset the input so selecting the same file again still fires onChange
    event.target.value = ''
    if (!file) return

    setBusy(true)
    setError(null)
    try {
      const draft = await uploadDraft(file)
      setState({ status: 'ready', draft })
      showToast({ tone: 'success', message: `Saved "${draft.filename}".` })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const handleRemove = async () => {
    setBusy(true)
    setError(null)
    try {
      await deleteDraft()
      setState({ status: 'ready', draft: null })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const draft = state.status === 'ready' ? state.draft : null

  return (
    <div className="mt-10">
      <div className="mb-4 flex items-center justify-between gap-4">
        <h3 className="text-sm/6 font-semibold text-white">Draft</h3>
      </div>

      {/* Hidden input shared by both "Upload draft" and "Replace draft" */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".pdf"
        className="hidden"
        onChange={(event) => void handleFileSelected(event)}
      />

      {state.status === 'loading' ? (
        <div className="flex min-h-32 items-center justify-center rounded-xl border border-dashed border-white/10 px-6 text-center">
          <p className="text-sm/6 text-gray-400">Loading your draft…</p>
        </div>
      ) : !draft ? (
        <div className="flex min-h-32 flex-col items-center justify-center rounded-xl border border-dashed border-white/10 px-6 text-center">
          <DocumentArrowUpIcon aria-hidden="true" className="mx-auto size-8 text-gray-500" />
          <h3 className="mt-3 text-sm/6 font-semibold text-white">No draft uploaded yet</h3>
          <p className="mt-1 text-sm/6 text-gray-400">Upload your draft as a PDF so impact can be judged against it.</p>
          <button
            type="button"
            disabled={busy}
            onClick={handleUploadClick}
            className="mt-4 inline-flex items-center rounded-md bg-indigo-500 px-3 py-2 text-xs font-semibold text-white shadow-sm hover:bg-indigo-400 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy ? 'Uploading…' : 'Upload draft'}
          </button>
        </div>
      ) : (
        <div className="flex min-h-32 items-start gap-x-4 rounded-xl bg-gray-800/50 p-6 outline outline-white/10">
          {/* Draft preview thumbnail */}
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
            <p className="truncate font-medium text-white">{draft.filename}</p>
            <p className="mt-0.5 flex items-center gap-x-1.5 text-xs/5 text-gray-400">
              <span className="inline-flex items-center rounded-full bg-yellow-400/10 px-1.5 py-0.5 text-[10px] font-medium text-yellow-400 ring-1 ring-inset ring-yellow-400/20">
                Draft
              </span>
              Uploaded {formatDateTime(draft.uploaded_at)}
            </p>
          </div>

          <Menu as="div" className="relative ml-auto flex-none">
            <MenuButton disabled={busy} className="relative block text-gray-400 hover:text-white disabled:opacity-50">
              <span className="absolute -inset-2.5" />
              <span className="sr-only">Open options for {draft.filename}</span>
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
                  Replace draft<span className="sr-only">, {draft.filename}</span>
                </button>
              </MenuItem>
              <MenuItem>
                <button
                  type="button"
                  onClick={() => void handleRemove()}
                  className="block w-full px-3 py-1 text-left text-sm/6 text-white data-focus:bg-white/5"
                >
                  Remove<span className="sr-only">, {draft.filename}</span>
                </button>
              </MenuItem>
            </MenuItems>
          </Menu>
        </div>
      )}

      {error && (
        <p className="mt-3 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">
          {error}
        </p>
      )}
    </div>
  )
}

export default DraftCard
