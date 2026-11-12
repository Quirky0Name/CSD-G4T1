import type { ReactNode } from 'react'
import Mascot, { type MascotPose } from './Mascot'

// The signed-out card, shared by sign-in and sign-up: the penguin's panel on the left (a short band
// above the form on a phone), the form's pane on the right. On a phone held sideways the card keeps
// both panels but drops its tall minimum, so nothing has to scroll past an empty panel.

export function AuthShell({
  pose,
  poseKey,
  badge,
  caption,
  hideCaptionOnPhone = false,
  footer,
  topRight,
  children,
}: {
  pose: MascotPose
  // a change of key replays the pose's hop, so each sign-up step greets the visitor again
  poseKey?: string
  badge?: ReactNode
  caption: string
  hideCaptionOnPhone?: boolean
  footer?: ReactNode
  topRight: ReactNode
  children: ReactNode
}) {
  return (
    <div className="signup relative grid min-h-svh justify-items-center overflow-hidden bg-gray-950 px-4 py-6 sm:px-6 sm:py-12 md:items-start md:pt-[max(3rem,6vh)] short:py-4 short:md:pt-4">
      <Backdrop />

      <div className="relative grid w-full max-w-5xl self-start rounded-3xl bg-gray-900 shadow-[0_30px_80px_-30px_rgba(0,0,0,0.8)] outline-1 -outline-offset-1 outline-white/10 md:min-h-[46rem] md:grid-cols-[minmax(0,9fr)_minmax(0,11fr)] short:md:min-h-0">
        <aside className="relative flex items-center gap-4 rounded-t-3xl bg-gray-800/40 px-5 pt-5 pb-4 sm:flex-col sm:items-stretch sm:gap-0 sm:px-6 sm:pb-6 md:rounded-l-3xl md:rounded-tr-none md:px-10 md:pt-8 md:pb-10 short:md:px-6 short:md:pt-5 short:md:pb-5">
          <Wordmark className="hidden sm:flex" />
          <div className="relative order-first mx-auto w-20 shrink-0 sm:order-none sm:mt-4 sm:w-44 md:mt-14 md:w-60 short:md:mt-3 short:md:w-32">
            <div key={poseKey ?? pose} className="animate-mascot-pop">
              <Mascot pose={pose} className="w-full" />
            </div>
            {badge}
          </div>
          <div className="flex min-w-0 flex-1 flex-col">
            <Wordmark className="mb-1 flex sm:hidden" />
            <p
              className={`text-sm/5 font-medium text-balance text-gray-200 sm:mx-auto sm:mt-4 sm:max-w-[34ch] sm:text-center md:max-w-[26ch] md:text-base/6 short:md:mt-2 short:md:text-sm/5 ${
                hideCaptionOnPhone ? 'hidden sm:block' : ''
              }`}
            >
              {caption}
            </p>
            {footer && <div className="mt-2 sm:mt-0 md:mt-auto md:pt-6">{footer}</div>}
          </div>
        </aside>

        <section className="flex min-w-0 flex-col overflow-x-clip px-5 pt-5 pb-7 sm:px-10 md:pt-8 md:pb-10 short:md:pt-5 short:md:pb-6">
          <div className="flex flex-wrap items-center justify-end gap-x-3 gap-y-1 text-sm text-gray-400">
            {topRight}
          </div>
          {children}
        </section>
      </div>
    </div>
  )
}

// the desk's name beside the penguin's head, the app's one mark
export function Wordmark({ className }: { className: string }) {
  return (
    <p className={`items-center gap-2 text-sm font-semibold text-white ${className}`}>
      <MascotHead className="size-6" />
      Research desk
    </p>
  )
}

// just the head, for marks too small for the whole penguin (the rail, the wordmark)
export function MascotHead({ className = '' }: { className?: string }) {
  return (
    <svg viewBox="0 0 48 44" aria-hidden="true" className={className}>
      <path d="M24 2 C38 2 46 12 46 24 C46 36 36 42 24 42 C12 42 2 36 2 24 C2 12 10 2 24 2 Z" fill="#000" stroke="#e5e7eb" strokeWidth="2.5" />
      <circle cx="16.5" cy="20" r="4.4" fill="#fff" />
      <circle cx="31.5" cy="20" r="4.4" fill="#fff" />
      <circle cx="17.3" cy="20.4" r="2.2" fill="#000" />
      <circle cx="30.7" cy="20.4" r="2.2" fill="#000" />
      <ellipse cx="10" cy="29.5" rx="3.4" ry="2.8" fill="#a5b4fc" fillOpacity="0.85" />
      <ellipse cx="38" cy="29.5" rx="3.4" ry="2.8" fill="#a5b4fc" fillOpacity="0.85" />
      <path d="M19 27 Q24 24.6 29 27 Q27.4 32.4 24 32.4 Q20.6 32.4 19 27 Z" fill="#fff" stroke="#000" strokeWidth="1.3" />
    </svg>
  )
}

// the reference's loose dashed curves, drawn faintly on the app's own ground
export function Backdrop() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 1440 900"
      preserveAspectRatio="xMidYMid slice"
      className="pointer-events-none absolute inset-0 size-full"
    >
      <path d="M-40 180 C 220 60, 380 300, 620 170" stroke="#818cf8" strokeOpacity="0.18" strokeWidth="2" strokeDasharray="6 10" fill="none" />
      <path d="M1480 640 C 1260 760, 1120 520, 900 700" stroke="#818cf8" strokeOpacity="0.18" strokeWidth="2" strokeDasharray="6 10" fill="none" />
      <circle cx="1240" cy="120" r="160" fill="#6366f1" fillOpacity="0.06" />
      <circle cx="160" cy="820" r="200" fill="#6366f1" fillOpacity="0.05" />
    </svg>
  )
}

// the step's icon on a round indigo badge at the penguin's shoulder, popping in on each change
export function PoseBadge({ stepKey, children }: { stepKey: string; children: ReactNode }) {
  return (
    <span
      key={stepKey}
      className="animate-badge-pop absolute top-0 -right-1 grid size-8 place-items-center rounded-full bg-indigo-500 text-white shadow-[0_10px_24px_-8px_rgba(99,102,241,0.7)] ring-[3px] ring-gray-900 sm:top-2 sm:-right-3 sm:size-12 sm:ring-4 md:size-14"
    >
      {children}
    </span>
  )
}
