// The tag input's behaviour, kept apart from TagInput.tsx so it's testable without a DOM: matching,
// adding and removing tags, and what each key does. Letter case never makes two tags different.

export type TagRules = {
  options: readonly string[]
  allowCustom: boolean
  maxTags?: number
  maxTagLength?: number
}

export type AddOutcome = 'added' | 'empty' | 'duplicate' | 'limit' | 'not_listed' | 'too_long'

export type KeyState = {
  query: string
  // the active option's index in the list: the matches, then the custom option when allowed
  active: number | null
  matchCount: number
  customAllowed: boolean
}

export type KeyAction =
  | { type: 'move'; direction: 1 | -1; preventDefault: true }
  | { type: 'add-active' | 'add-custom'; preventDefault: true }
  | { type: 'close'; preventDefault: boolean }
  | { type: 'remove-last'; preventDefault: false }
  | { type: 'none'; preventDefault: boolean }

const same = (a: string, b: string) => a.toLowerCase() === b.toLowerCase()

/** The unselected options containing the trimmed query, in any letter case, in list order. */
export function matchOptions(options: readonly string[], query: string, selected: readonly string[]): string[] {
  const needle = query.trim().toLowerCase()
  return options.filter(
    (option) => !selected.some((tag) => same(tag, option)) && option.toLowerCase().includes(needle),
  )
}

/**
 * Whether `Add "<query>"` is offered: custom tags are on and the trimmed query isn't empty and
 * isn't exactly a listed option or a selected tag, ignoring letter case. A partial match doesn't
 * block it.
 */
export function customTagAllowed(
  query: string,
  options: readonly string[],
  selected: readonly string[],
  allowCustom: boolean,
): boolean {
  const value = query.trim()
  return (
    allowCustom &&
    value !== '' &&
    !options.some((option) => same(option, value)) &&
    !selected.some((tag) => same(tag, value))
  )
}

/** The tags after trying to add `value`, and what happened; the tags are unchanged unless added. */
export function addTag(tags: readonly string[], value: string, rules: TagRules): { tags: string[]; outcome: AddOutcome } {
  const tag = value.trim()
  const unchanged = (outcome: AddOutcome) => ({ tags: [...tags], outcome })
  if (tag === '') return unchanged('empty')
  if (tags.some((existing) => same(existing, tag))) return unchanged('duplicate')
  if (rules.maxTags !== undefined && tags.length >= rules.maxTags) return unchanged('limit')
  const listed = rules.options.find((option) => same(option, tag))
  if (listed === undefined && !rules.allowCustom) return unchanged('not_listed')
  if (rules.maxTagLength !== undefined && tag.length > rules.maxTagLength) return unchanged('too_long')
  return { tags: [...tags, listed ?? tag], outcome: 'added' }
}

export function removeTag(tags: readonly string[], tag: string): string[] {
  return tags.filter((existing) => existing !== tag)
}

/** The next active option, wrapping at both ends; null when the list is empty. */
export function moveActive(active: number | null, count: number, direction: 1 | -1): number | null {
  if (count === 0) return null
  if (active === null) return direction === 1 ? 0 : count - 1
  return (active + direction + count) % count
}

/** What a key pressed in the input does. Enter never submits the form. */
export function keyAction(key: string, state: KeyState): KeyAction {
  switch (key) {
    case 'ArrowDown':
      return { type: 'move', direction: 1, preventDefault: true }
    case 'ArrowUp':
      return { type: 'move', direction: -1, preventDefault: true }
    case 'Enter':
      if (state.active !== null) return { type: 'add-active', preventDefault: true }
      if (state.matchCount === 0 && state.customAllowed) return { type: 'add-custom', preventDefault: true }
      return { type: 'none', preventDefault: true }
    case 'Escape':
      return { type: 'close', preventDefault: true }
    case 'Backspace':
      return state.query === '' ? { type: 'remove-last', preventDefault: false } : { type: 'none', preventDefault: false }
    case 'Tab':
      return { type: 'close', preventDefault: false }
    default:
      return { type: 'none', preventDefault: false }
  }
}
