import type { CSSProperties } from 'react'

// The desk's shared control styles, first set on the sign-up card and now used on every page:
// fully round pills, white-hairline fields, 12-24px cards. One place, so a page can't drift.

export const inputClasses =
  'block w-full rounded-lg bg-white/5 px-3 py-2 text-base text-white outline-1 -outline-offset-1 outline-white/10 placeholder:text-gray-400 transition-[outline-color] focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-500 aria-invalid:outline-red-400 disabled:cursor-not-allowed disabled:opacity-50 sm:text-sm/6'

export const labelClasses = 'block text-xs/5 font-medium tracking-wide text-gray-400'

export const primaryPill =
  'inline-flex items-center justify-center gap-1.5 rounded-full bg-indigo-500 px-6 py-2.5 text-sm font-semibold text-white shadow-[0_8px_20px_-8px_rgba(99,102,241,0.8)] transition-[background-color,transform] duration-150 hover:bg-indigo-400 active:scale-[0.97] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400 disabled:cursor-wait disabled:opacity-60'

export const ghostPill =
  'inline-flex items-center justify-center gap-1.5 rounded-full px-4 py-2.5 text-sm font-semibold text-gray-300 outline-1 -outline-offset-1 outline-white/15 transition-[background-color,color,transform] duration-150 hover:bg-white/5 hover:text-white active:scale-[0.97] focus-visible:outline-2 focus-visible:outline-indigo-400 disabled:opacity-50'

export const textLink =
  'rounded text-sm font-medium text-gray-400 underline-offset-4 hover:text-white hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400'

// an indigo link inside running copy or a header
export const signalLink =
  'rounded font-semibold text-indigo-400 underline-offset-4 hover:text-indigo-300 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400'

// a raised surface on the page: the sign-up card's slate and hairline, at a list's scale
// The column every signed-in page sits in, the report included, so a page's edges never jump. 6xl
// leaves the report's quote pane room for a PDF page at a readable size beside its claim list.
export const pageColumn = 'mx-auto w-full max-w-6xl px-4 sm:px-6'

export const card = 'rounded-2xl bg-gray-900 outline-1 -outline-offset-1 outline-white/10'

// the same card when the whole of it opens something
export const cardInteractive = `${card} transition-[outline-color,background-color,transform] duration-200 hover:bg-gray-800/70 hover:outline-white/20 focus-within:outline-2 focus-within:outline-indigo-400 motion-safe:hover:-translate-y-0.5`

export const badge = 'inline-flex shrink-0 items-center rounded-full px-2 py-0.5 text-xs font-medium inset-ring'

export const errorPanel = 'rounded-xl bg-red-500/10 px-4 py-3 text-sm text-red-300 outline-1 -outline-offset-1 outline-red-500/25'

// icon-only buttons on cards and rows: round, 40px on touch, 32px from sm
export const iconButton =
  'relative z-10 flex size-10 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-400 disabled:cursor-not-allowed disabled:opacity-50 sm:size-8'

// a list item's place in its entrance, capped so a long list doesn't keep arriving
export const riseDelay = (index: number) => ({ '--i': Math.min(index, 8) }) as CSSProperties

// the small outlined pill in a card's corner ("Sign in", "Create account")
export const smallPill =
  'rounded-full px-3.5 py-1.5 text-sm font-semibold text-gray-200 outline-1 -outline-offset-1 outline-white/20 transition-colors hover:bg-white/5 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-400'

// dialogs: a sheet from the bottom edge on a phone, a centred card from sm up
export const DIALOG_FRAME =
  'fixed inset-0 flex items-end justify-center p-0 pt-10 sm:items-center sm:p-6'
export const DIALOG_PANEL =
  'animate-dialog max-h-full w-full overflow-y-auto rounded-t-3xl bg-gray-900 p-6 pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-gray-100 shadow-[0_30px_80px_-30px_rgba(0,0,0,0.8)] outline-1 -outline-offset-1 outline-white/10 sm:rounded-3xl sm:p-8'
