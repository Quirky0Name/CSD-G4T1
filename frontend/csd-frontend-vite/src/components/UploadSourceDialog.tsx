import { Dialog, DialogBackdrop, DialogPanel, DialogTitle } from '@headlessui/react'
import { ArrowUpTrayIcon, DocumentTextIcon, XMarkIcon } from '@heroicons/react/20/solid'
import { useEffect, useState, type DragEvent, type ChangeEvent } from 'react'

type UploadSourceDialogProps = {
  open: boolean
  onClose: () => void
}

function UploadSourceDialog({ open, onClose }: UploadSourceDialogProps) {
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [isDragging, setIsDragging] = useState(false)
  const [doi, setDoi] = useState('')

  useEffect(() => {
    if (!open) {
      setSelectedFile(null)
      setIsDragging(false)
      setDoi('')
    }
  }, [open])

  const acceptFile = (file: File | undefined) => {
    if (file?.type === 'application/pdf' || file?.name.toLowerCase().endsWith('.pdf')) {
      setSelectedFile(file)
      setDoi('')
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
    if (event.target.value.trim().length > 0) {
      setSelectedFile(null)
    }
  }

  const canImport = selectedFile !== null || doi.trim().length > 0

  return (
    <Dialog open={open} onClose={onClose} className="relative z-50">
      <DialogBackdrop className="fixed inset-0 bg-black/70 backdrop-blur-sm" />
      <div className="fixed inset-0 flex items-center justify-center p-4 sm:p-6">
        <DialogPanel className="w-full max-w-xl rounded-2xl border border-white/10 bg-gray-900 p-6 text-gray-100 shadow-2xl sm:p-8">
          <div className="flex items-start justify-between gap-4">
            <div>
              <DialogTitle className="text-lg font-semibold text-white">Add your PDF</DialogTitle>
              <p className="mt-1 text-sm text-gray-400">
                Upload a paper to extract its DOI and track its citation updates.
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              className="rounded-md p-1 text-gray-400 hover:bg-white/5 hover:text-white"
            >
              <span className="sr-only">Close upload dialog</span>
              <XMarkIcon aria-hidden="true" className="size-5" />
            </button>
          </div>

          <label
            htmlFor="source-pdf"
            onDragOver={(event) => {
              event.preventDefault()
              setIsDragging(true)
            }}
            onDragLeave={() => setIsDragging(false)}
            onDrop={handleDrop}
            className={`mt-8 flex min-h-56 cursor-pointer flex-col items-center justify-center rounded-xl border border-dashed px-6 py-8 text-center transition-colors ${
              doi.trim().length > 0
                ? 'cursor-not-allowed border-white/10 bg-gray-950/40 opacity-50'
                : isDragging
                  ? 'border-indigo-400 bg-indigo-500/10'
                  : 'border-white/15 bg-gray-950/60 hover:border-indigo-400/70 hover:bg-white/[0.03]'
            }`}
          >
            <input
              id="source-pdf"
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
                <ArrowUpTrayIcon aria-hidden="true" className="size-10 text-gray-300" />
                <p className="mt-4 text-sm font-medium text-white">
                  Drag and drop a PDF, or choose a file
                </p>
                <p className="mt-1 text-sm text-gray-400">PDF files up to 25 MB</p>
              </>
            )}
          </label>

          <div className="mt-6 flex items-center gap-3">
            <div className="h-px flex-1 bg-white/10" />
            <span className="text-xs font-medium uppercase tracking-wide text-gray-500">or</span>
            <div className="h-px flex-1 bg-white/10" />
          </div>

          <div className="mt-6">
            <label htmlFor="source-doi" className="block text-sm font-medium text-white">
              Upload by DOI
            </label>
            <input
              id="source-doi"
              type="text"
              value={doi}
              onChange={handleDoiChange}
              disabled={selectedFile !== null}
              placeholder="e.g. 10.1000/xyz123"
              className="mt-2 block w-full rounded-md border border-white/15 bg-gray-950/60 px-3 py-2 text-sm text-white placeholder:text-gray-500 outline-none focus:border-indigo-400 disabled:cursor-not-allowed disabled:opacity-50"
            />
          </div>

          <div className="mt-8 flex justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md bg-white/10 px-3 py-2 text-sm font-semibold text-white shadow-xs inset-ring inset-ring-white/10 hover:bg-white/20"
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={!canImport}
              onClick={onClose}
              className="rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white shadow-xs hover:bg-indigo-400 disabled:cursor-not-allowed disabled:opacity-40"
            >
              Import
            </button>
          </div>
        </DialogPanel>
      </div>
    </Dialog>
  )
}

export default UploadSourceDialog