import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { DocumentArrowUpIcon, EllipsisHorizontalIcon } from '@heroicons/react/20/solid'
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import { deleteDraft, errorMessage, getDraft, uploadDraft, type Draft } from '../api'
import { formatDateTime } from '../format'
import { useToast } from './Toasts'
import { ActionButton } from './ActionButton'
import { Loading, Section } from './Page'
import { PencilSquareIcon } from '@heroicons/react/24/outline'
import { errorPanel, iconButton } from '../ui'

type LoadState = { status: 'loading' } | { status: 'ready'; draft: Draft | null }

const DraftCard = ({ projectId }: { projectId?: string }) => {
  const showToast = useToast()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [state, setState] = useState<LoadState>({ status: 'loading' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    getDraft(projectId)
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
  }, [projectId, showToast])

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
      const draft = await uploadDraft(file, projectId)
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
      await deleteDraft(projectId)
      setState({ status: 'ready', draft: null })
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const draft = state.status === 'ready' ? state.draft : null

  return (
    <Section id="draft-heading" title="Your draft" icon={PencilSquareIcon} index={1}>

      {/* Hidden input shared by both "Upload draft" and "Replace draft" */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".pdf"
        className="hidden"
        onChange={(event) => void handleFileSelected(event)}
      />

      {state.status === 'loading' ? (
        <Loading label="Loading your draft…" />
      ) : !draft ? (
        <div className="flex flex-col items-start gap-4 sm:flex-row sm:items-center">
          <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-white/5 text-gray-400">
            <DocumentArrowUpIcon aria-hidden="true" className="size-6" />
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="text-sm/6 font-semibold text-white">No draft uploaded yet</h3>
            <p className="text-sm/6 text-gray-400">Upload your draft as a PDF so impact can be judged against it.</p>
          </div>
          <ActionButton disabled={busy} onClick={handleUploadClick}>
            {busy ? 'Uploading…' : 'Upload draft'}
          </ActionButton>
        </div>
      ) : (
        <div className="flex items-start gap-x-4">
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
            <MenuButton disabled={busy} className={iconButton}>
              <span className="sr-only">Open options for {draft.filename}</span>
              <EllipsisHorizontalIcon aria-hidden="true" className="size-5" />
            </MenuButton>
            <MenuItems
              transition
              className="absolute right-0 z-10 mt-1 w-40 origin-top-right rounded-2xl bg-gray-800 p-1.5 shadow-lg outline-1 -outline-offset-1 outline-white/10 transition data-closed:scale-95 data-closed:transform data-closed:opacity-0 data-enter:duration-100 data-enter:ease-out data-leave:duration-75 data-leave:ease-in"
            >
              <MenuItem>
                <button
                  type="button"
                  onClick={handleUploadClick}
                  className="block w-full rounded-xl px-3 py-2 text-left text-sm/6 text-white data-focus:bg-white/5"
                >
                  Replace draft<span className="sr-only">, {draft.filename}</span>
                </button>
              </MenuItem>
              <MenuItem>
                <button
                  type="button"
                  onClick={() => void handleRemove()}
                  className="block w-full rounded-xl px-3 py-2 text-left text-sm/6 text-white data-focus:bg-white/5"
                >
                  Remove<span className="sr-only">, {draft.filename}</span>
                </button>
              </MenuItem>
            </MenuItems>
          </Menu>
        </div>
      )}

      {error && (
        <p className={`mt-3 ${errorPanel}`}>{error}</p>
      )}
    </Section>
  )
}

export default DraftCard
