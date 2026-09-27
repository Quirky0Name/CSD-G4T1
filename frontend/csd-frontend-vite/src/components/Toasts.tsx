import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react'
import { Transition } from '@headlessui/react'
import {
  CheckCircleIcon,
  ExclamationCircleIcon,
  InformationCircleIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline'

export type ToastTone = 'info' | 'success' | 'error'

type ToastAction = {
  label: string
  onClick: () => void
}

type ToastInput = {
  tone?: ToastTone
  message: string
  action?: ToastAction
}

type Toast = ToastInput & { id: number; tone: ToastTone }

const TOAST_MS = 6000

const TONE_ICON = {
  info: InformationCircleIcon,
  success: CheckCircleIcon,
  error: ExclamationCircleIcon,
} as const satisfies Record<ToastTone, typeof CheckCircleIcon>

const TONE_ICON_CLASS: Record<ToastTone, string> = {
  info: 'text-indigo-400',
  success: 'text-emerald-400',
  error: 'text-red-400',
}

const ToastContext = createContext<((toast: ToastInput) => void) | null>(null)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const nextId = useRef(0)

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id))
  }, [])

  const showToast = useCallback(
    (toast: ToastInput) => {
      const id = nextId.current++
      setToasts((current) => [...current, { tone: 'info', ...toast, id }])
      setTimeout(() => dismiss(id), TOAST_MS)
    },
    [dismiss],
  )

  return (
    <ToastContext.Provider value={showToast}>
      {children}
      <div
        aria-live="assertive"
        className="pointer-events-none fixed inset-0 z-50 flex flex-col items-end gap-3 px-4 py-6 sm:p-6"
      >
        {toasts.map((toast) => {
          const Icon = TONE_ICON[toast.tone]
          return (
            <Transition
              key={toast.id}
              appear
              show
              enter="transform ease-out duration-300 transition"
              enterFrom="translate-y-2 opacity-0 sm:translate-y-0 sm:translate-x-2"
              enterTo="translate-y-0 opacity-100 sm:translate-x-0"
              leave="transition ease-in duration-100"
              leaveFrom="opacity-100"
              leaveTo="opacity-0"
            >
              <div className="pointer-events-auto w-full max-w-sm rounded-lg bg-gray-800 shadow-lg outline outline-white/10">
                <div className="flex items-start gap-3 p-4">
                  <Icon
                    aria-hidden="true"
                    className={`mt-0.5 size-5 flex-none ${TONE_ICON_CLASS[toast.tone]}`}
                  />
                  <div className="flex-1 text-sm/6 text-gray-100">
                    {toast.message}
                    {toast.action && (
                      <button
                        type="button"
                        onClick={() => {
                          toast.action?.onClick()
                          dismiss(toast.id)
                        }}
                        className="mt-1 block font-semibold text-indigo-400 hover:text-indigo-300"
                      >
                        {toast.action.label}
                      </button>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => dismiss(toast.id)}
                    className="flex-none text-gray-500 hover:text-white"
                  >
                    <span className="sr-only">Dismiss</span>
                    <XMarkIcon aria-hidden="true" className="size-5" />
                  </button>
                </div>
              </div>
            </Transition>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components -- context hook, not a component
export function useToast(): (toast: ToastInput) => void {
  const showToast = useContext(ToastContext)
  if (!showToast) throw new Error('useToast must be used within a ToastProvider')
  return showToast
}
