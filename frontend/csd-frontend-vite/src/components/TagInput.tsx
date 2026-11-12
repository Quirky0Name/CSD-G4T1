import { useState, type KeyboardEvent } from 'react'
import { XMarkIcon } from '@heroicons/react/20/solid'
import { addTag, customTagAllowed, keyAction, matchOptions, moveActive, removeTag } from '../profile/tagInput'

// A searchable tag input, for research fields (listed values only) and research interests
// (suggestions or custom tags, with limits). The behaviour is in profile/tagInput.ts; this is the
// markup, the ARIA combobox pattern and the messages. It makes no request: every list is bundled.

type TagInputProps = {
  id: string
  label: string
  value: string[]
  onChange: (tags: string[]) => void
  options: readonly string[]
  allowCustom: boolean
  maxTags?: number
  maxTagLength?: number
  error?: string
}

type Item = { value: string; custom: boolean }

const inputClasses =
  'block w-full rounded-md bg-white px-3 py-1.5 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-600 sm:text-sm/6 dark:bg-white/5 dark:text-white dark:outline-white/10 dark:placeholder:text-gray-500 dark:focus:outline-indigo-500'

export default function TagInput({
  id,
  label,
  value,
  onChange,
  options,
  allowCustom,
  maxTags,
  maxTagLength,
  error,
}: TagInputProps) {
  const listId = `${id}-options`
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState<number | null>(null)
  const [message, setMessage] = useState('')

  const matches = matchOptions(options, query, value)
  const custom = customTagAllowed(query, options, value, allowCustom)
  const items: Item[] = [
    ...matches.map((option) => ({ value: option, custom: false })),
    ...(custom ? [{ value: query.trim(), custom: true }] : []),
  ]
  const atLimit = maxTags !== undefined && value.length >= maxTags
  const noMatch = !allowCustom && query.trim() !== '' && matches.length === 0
  const expanded = open && (items.length > 0 || noMatch)
  // the limit message is about interests, the only use with a limit
  const limitMessage = `You can add up to ${maxTags} interests. Remove one to add another.`

  const close = () => {
    setOpen(false)
    setActive(null)
  }

  const add = (tag: string) => {
    const result = addTag(value, tag, { options, allowCustom, maxTags, maxTagLength })
    switch (result.outcome) {
      case 'added':
        onChange(result.tags)
        setQuery('')
        setActive(null)
        setMessage(`Added ${result.tags[result.tags.length - 1]}.`)
        break
      case 'duplicate':
        setQuery('')
        setActive(null)
        setMessage(`${tag.trim()} is already added.`)
        break
      case 'limit':
        setMessage(limitMessage)
        break
      case 'too_long':
        setMessage(`Each tag can be at most ${maxTagLength} characters.`)
        break
      case 'empty':
      case 'not_listed':
        break
    }
  }

  const remove = (tag: string) => {
    onChange(removeTag(value, tag))
    setMessage(`Removed ${tag}.`)
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    const action = keyAction(e.key, { query, active, matchCount: matches.length, customAllowed: custom })
    if (action.preventDefault) e.preventDefault()
    switch (action.type) {
      case 'move':
        setOpen(true)
        setActive(moveActive(active, items.length, action.direction))
        break
      case 'add-active':
        if (active !== null && items[active]) add(items[active].value)
        break
      case 'add-custom':
        add(query)
        break
      case 'close':
        close()
        break
      case 'remove-last':
        if (value.length > 0) remove(value[value.length - 1])
        break
      case 'none':
        break
    }
  }

  const status = !expanded
    ? ''
    : noMatch
      ? 'No matching field'
      : `${items.length} ${items.length === 1 ? 'option' : 'options'}${
          active !== null && items[active] ? `, ${optionText(items[active])} active` : ''
        }`

  return (
    <div>
      <label htmlFor={id} className="block text-sm/6 font-medium text-gray-900 dark:text-gray-100">
        {label}
      </label>

      {value.length > 0 && (
        <ul className="mt-2 flex flex-wrap gap-2" aria-label={`${label}: selected`}>
          {value.map((tag) => (
            <li
              key={tag}
              className="inline-flex max-w-full items-center gap-1 rounded-md bg-indigo-50 py-1 pr-1 pl-2 text-sm text-indigo-700 dark:bg-indigo-500/10 dark:text-indigo-300"
            >
              <span className="break-words">{tag}</span>
              <button
                type="button"
                onClick={() => remove(tag)}
                aria-label={`Remove ${tag}`}
                className="rounded p-0.5 hover:bg-indigo-100 focus-visible:outline-2 focus-visible:outline-indigo-600 dark:hover:bg-indigo-500/20"
              >
                <XMarkIcon aria-hidden="true" className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="relative mt-2">
        <input
          id={id}
          type="text"
          role="combobox"
          aria-expanded={expanded}
          aria-controls={listId}
          aria-activedescendant={expanded && active !== null ? `${listId}-${active}` : undefined}
          aria-autocomplete="list"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          autoComplete="off"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
            setActive(null)
          }}
          onFocus={() => setOpen(true)}
          onBlur={close}
          onKeyDown={onKeyDown}
          className={inputClasses}
        />

        <ul
          id={listId}
          role="listbox"
          aria-label={label}
          hidden={!expanded}
          className="absolute z-10 mt-1 max-h-60 w-full overflow-auto rounded-md bg-white py-1 text-sm shadow-lg outline-1 outline-black/5 dark:bg-gray-800 dark:outline-white/10"
        >
          {items.map((item, index) => (
            <li
              key={item.custom ? `custom:${item.value}` : item.value}
              id={`${listId}-${index}`}
              role="option"
              aria-selected={index === active}
              // mousedown, so the input doesn't blur and close the list before the click lands
              onMouseDown={(e) => {
                e.preventDefault()
                add(item.value)
              }}
              className={`cursor-default px-3 py-2 ${
                index === active
                  ? 'bg-indigo-600 text-white'
                  : 'text-gray-900 hover:bg-gray-100 dark:text-gray-100 dark:hover:bg-white/5'
              }`}
            >
              {optionText(item)}
            </li>
          ))}
          {noMatch && <li className="px-3 py-2 text-gray-500 dark:text-gray-400">No matching field</li>}
        </ul>
      </div>

      {atLimit && <p className="mt-2 text-sm text-gray-600 dark:text-gray-400">{limitMessage}</p>}
      {error && (
        <p id={`${id}-error`} className="mt-2 text-sm text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
      {/* what just happened and how many options are listed, for screen readers */}
      <p aria-live="polite" className="sr-only">
        {message} {status}
      </p>
    </div>
  )
}

function optionText(item: Item): string {
  return item.custom ? `Add "${item.value}"` : item.value
}
