import type { ComponentProps } from 'react'
import { Link } from 'react-router-dom'

type Tone = 'primary' | 'secondary' | 'quiet' | 'danger'

const BASE =
  'inline-flex items-center gap-1 rounded-md px-2.5 py-2 text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500 disabled:cursor-not-allowed disabled:opacity-50 sm:py-1.5'

const TONES: Record<Tone, string> = {
  primary: 'bg-indigo-500 hover:bg-indigo-400',
  secondary: 'bg-white/10 inset-ring inset-ring-white/10 hover:bg-white/20',
  quiet: 'text-gray-300 hover:bg-white/10 hover:text-white',
  danger: 'bg-red-500 hover:bg-red-400',
}

// The size sits on the span: index.css gives buttons font: inherit, which outranks utilities on the button
export function ActionButton({
  tone = 'secondary',
  children,
  ...props
}: ComponentProps<'button'> & { tone?: Tone }) {
  return (
    <button type="button" {...props} className={`${BASE} ${TONES[tone]}`}>
      <span className="inline-flex items-center gap-1 text-sm/5 font-semibold">{children}</span>
    </button>
  )
}

export function ActionLink({
  tone = 'secondary',
  children,
  ...props
}: ComponentProps<typeof Link> & { tone?: Tone }) {
  return (
    <Link {...props} className={`${BASE} ${TONES[tone]}`}>
      <span className="inline-flex items-center gap-1 text-sm/5 font-semibold">{children}</span>
    </Link>
  )
}
