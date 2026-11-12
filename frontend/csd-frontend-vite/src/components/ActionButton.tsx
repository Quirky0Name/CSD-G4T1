import type { ComponentProps } from 'react'
import { Link } from 'react-router-dom'

type Tone = 'primary' | 'secondary' | 'quiet' | 'danger'

// the sign-up card's pills, at the app's working size: 40px tall on touch, 36px from sm
const BASE =
  'inline-flex min-h-10 items-center justify-center gap-1 rounded-full px-4 py-2 text-white transition-[background-color,color,transform] duration-150 active:scale-[0.97] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400 disabled:cursor-not-allowed disabled:opacity-50 disabled:active:scale-100 sm:min-h-9 sm:py-1.5'

const TONES: Record<Tone, string> = {
  primary: 'bg-indigo-500 shadow-[0_8px_20px_-8px_rgba(99,102,241,0.8)] hover:bg-indigo-400',
  secondary: 'text-gray-200 outline-1 -outline-offset-1 outline-white/15 hover:bg-white/5 hover:text-white',
  quiet: 'text-gray-300 hover:bg-white/10 hover:text-white',
  danger: 'bg-red-500 hover:bg-red-400',
}

// The size sits on the span: index.css gives buttons font: inherit, which outranks utilities on the button
export function ActionButton({
  tone = 'secondary',
  children,
  className = '',
  ...props
}: ComponentProps<'button'> & { tone?: Tone }) {
  return (
    <button type="button" {...props} className={`${BASE} ${TONES[tone]} ${className}`}>
      <span className="inline-flex items-center gap-1 text-sm/5 font-semibold">{children}</span>
    </button>
  )
}

export function ActionLink({
  tone = 'secondary',
  children,
  className = '',
  ...props
}: ComponentProps<typeof Link> & { tone?: Tone }) {
  return (
    <Link {...props} className={`${BASE} ${TONES[tone]} ${className}`}>
      <span className="inline-flex items-center gap-1 text-sm/5 font-semibold">{children}</span>
    </Link>
  )
}
