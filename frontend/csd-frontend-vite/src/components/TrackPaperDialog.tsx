import { Dialog, DialogBackdrop, DialogPanel, DialogTitle } from '@headlessui/react'
import { ArrowUpTrayIcon, DocumentTextIcon, XMarkIcon } from '@heroicons/react/20/solid'
import { useState, type DragEvent, type ChangeEvent } from 'react'
import { errorMessage, trackPaperByDoi, uploadPaperPdf } from '../api'
import { useTracking } from '../tracking'
import { useToast } from './Toasts'
import { DIALOG_FRAME, DIALOG_PANEL, errorPanel, ghostPill, iconButton, inputClasses, labelClasses, primaryPill } from '../ui'

type TrackPaperDialogProps = {
  open: boolean
  onClose: () => void
  // left out: the default project
  projectId?: string
}

function TrackPaperDialog({ open, onClose, projectId }: TrackPaperDialogProps) {
  const { refresh } = useTracking()
  const showToast = useToast()
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [isDragging, setIsDragging] = useState(false)
  const [doi, setDoi] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Reset on close, rather than in an effect watching `open`: this only
  // ever needs to run in response to the close itself.
  const handleClose = () => {
    setSelectedFile(null)
    setIsDragging(false)
    setDoi('')
    setSubmitting(false)
    setError(null)
    onClose()
  }

  const acceptFile = (file: File | undefined) => {
    if (file?.type === 'application/pdf' || file?.name.toLowerCase().endsWith('.pdf')) {
      setSelectedFile(file)
      setDoi('')
      setError(null)
    }
  }

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    acceptFile(event.target.files?.[0])
  }

  const handleDrop = (event: DragEvent<HTMLLabelElement>) => {
    event.preventDefault()
    setIsDragging(false)
    acceptFile(event.dataTransfer.files[0])
  }

  const handleDoiChange = (event: ChangeEvent<HTMLInputElement>) => {
    setDoi(event.target.value)
    setError(null)
    if (event.target.value.trim().length > 0) {
      setSelectedFile(null)
    }
  }

  const canImport = (selectedFile !== null || doi.trim().length > 0) && !submitting

  const handleImport = async () => {
    setSubmitting(true)
    setError(null)
    try {
      const paper = selectedFile
        ? await uploadPaperPdf(selectedFile, projectId)
        : await trackPaperByDoi(doi.trim(), projectId)
      await refresh()
      showToast({ tone: 'success', message: `Now tracking "${paper.title ?? paper.doi ?? 'this paper'}".` })
      handleClose()
    } catch (err) {
      setError(errorMessage(err))
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} className="relative z-50">
      <DialogBackdrop transition className="fixed inset-0 bg-black/70 backdrop-blur-sm transition-opacity duration-200 data-closed:opacity-0" />
      <div className={DIALOG_FRAME}>
        <DialogPanel className={`${DIALOG_PANEL} max-w-xl`}>
          <div className="flex items-start gap-4">
            <div className="min-w-0 flex-1">
              <DialogTitle className="text-xl/8 font-semibold tracking-tight text-white">Track a paper</DialogTitle>
              <p className="mt-1 text-sm/6 text-gray-400">
                Upload a paper's PDF or enter its DOI to track it for changes.
              </p>
            </div>
            <button
              type="button"
              onClick={handleClose}
              className={`-mt-1 -mr-2 ${iconButton}`}
            >
              <span className="sr-only">Close upload dialog</span>
              <XMarkIcon aria-hidden="true" className="size-5" />
            </button>
          </div>

          <label
            htmlFor="paper-pdf"
            onDragOver={(event) => {
              event.preventDefault()
              setIsDragging(true)
            }}
            onDragLeave={() => setIsDragging(false)}
            onDrop={handleDrop}
            className={`mt-6 flex min-h-44 cursor-pointer flex-col items-center justify-center rounded-2xl border border-dashed px-6 py-8 text-center transition-colors focus-within:outline-2 focus-within:outline-indigo-400 sm:min-h-52 short:min-h-32 short:py-4 ${
              doi.trim().length > 0
                ? 'cursor-not-allowed border-white/10 bg-gray-950/40 opacity-50'
                : isDragging
                  ? 'border-indigo-400 bg-indigo-500/10'
                  : 'border-white/15 bg-gray-950/60 hover:border-indigo-400/70 hover:bg-white/[0.03]'
            }`}
          >
            <input
              id="paper-pdf"
              type="file"
              accept="application/pdf,.pdf"
              onChange={handleFileChange}
              disabled={doi.trim().length > 0}
              className="sr-only"
            />
            {selectedFile ? (
              <>
                <DocumentTextIcon aria-hidden="true" className="size-10 text-indigo-400" />
                <p className="mt-4 max-w-full truncate text-sm font-medium text-white">
                  {selectedFile.name}
                </p>
                <p className="mt-1 text-sm text-gray-400">PDF selected and ready to import</p>
              </>
            ) : (
              <>
                <span className="grid size-12 place-items-center rounded-full bg-indigo-500/10 text-indigo-300">
                  <ArrowUpTrayIcon aria-hidden="true" className="size-6" />
                </span>
                <p className="mt-4 text-sm font-medium text-white">
                  Drag and drop a PDF, or choose a file
                </p>
                <p className="mt-1 text-sm text-gray-400">PDF files up to 25 MB</p>
              </>
            )}
          </label>

          <div className="mt-6 flex items-center gap-3">
            <div className="h-px flex-1 bg-white/10" />
            <span className="text-xs font-medium text-gray-400">or</span>
            <div className="h-px flex-1 bg-white/10" />
          </div>

          <div className="mt-6">
            <label htmlFor="paper-doi" className={labelClasses}>
              Track by DOI
            </label>
            <input
              id="paper-doi"
              type="text"
              value={doi}
              onChange={handleDoiChange}
              disabled={selectedFile !== null}
              placeholder="e.g. 10.1000/xyz123"
              className={`mt-1.5 ${inputClasses}`}
            />
          </div>

          {error && (
            <p role="alert" className={`animate-reveal mt-4 ${errorPanel}`}>
              {error}
            </p>
          )}

          <div className="mt-8 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end sm:gap-3 short:mt-5">
            <button type="button" onClick={handleClose} disabled={submitting} className={ghostPill}>
              Cancel
            </button>
            <button type="button" disabled={!canImport} onClick={() => void handleImport()} className={`${primaryPill} disabled:cursor-not-allowed`}>
              {submitting ? 'Importing…' : 'Track paper'}
            </button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  )
}

export default TrackPaperDialog
