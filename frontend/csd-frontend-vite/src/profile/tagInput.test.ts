import { describe, expect, it } from 'vitest'
import { addTag, customTagAllowed, keyAction, matchOptions, moveActive, removeTag } from './tagInput'

const FIELDS = ['Medicine', 'Neuroscience', 'Nursing', 'Social Sciences', 'Veterinary']
const fields = { options: FIELDS, allowCustom: false }
const interests = { options: ['Oncology', 'Cancer Research', 'Ageing'], allowCustom: true, maxTags: 5, maxTagLength: 60 }

describe('matchOptions', () => {
  it('matches part of an option in any letter case, after trimming, in list order', () => {
    expect(matchOptions(FIELDS, '  SCI ', [])).toEqual(['Neuroscience', 'Social Sciences'])
    expect(matchOptions(FIELDS, 'n', [])).toEqual(['Medicine', 'Neuroscience', 'Nursing', 'Social Sciences', 'Veterinary'])
  })

  it('lists every unselected option for an empty query', () => {
    expect(matchOptions(FIELDS, '   ', ['Nursing'])).toEqual(['Medicine', 'Neuroscience', 'Social Sciences', 'Veterinary'])
  })

  it('leaves out selected options, whatever their case', () => {
    expect(matchOptions(FIELDS, 'sci', ['neuroscience'])).toEqual(['Social Sciences'])
  })
})

describe('customTagAllowed', () => {
  it('allows a query that is not exactly a listed option or a selected tag', () => {
    expect(customTagAllowed('cancer', interests.options, [], true)).toBe(true)
  })

  it('is not blocked by a partial match', () => {
    // "Cancer Research" contains "cancer", and that doesn't stop "cancer" being added
    expect(matchOptions(interests.options, 'cancer', [])).toEqual(['Cancer Research'])
    expect(customTagAllowed('cancer', interests.options, [], true)).toBe(true)
  })

  it('refuses a query equal to a listed option or a selected tag, ignoring case and spaces', () => {
    expect(customTagAllowed(' oncology ', interests.options, [], true)).toBe(false)
    expect(customTagAllowed('VACCINES', interests.options, ['vaccines'], true)).toBe(false)
  })

  it('refuses an empty query, and anything when custom tags are off', () => {
    expect(customTagAllowed('  ', interests.options, [], true)).toBe(false)
    expect(customTagAllowed('Astrology', FIELDS, [], false)).toBe(false)
  })
})

describe('addTag', () => {
  it('adds a listed option', () => {
    expect(addTag(['Medicine'], 'Nursing', fields)).toEqual({ tags: ['Medicine', 'Nursing'], outcome: 'added' })
  })

  it("can't add an unlisted value without custom tags", () => {
    expect(addTag(['Medicine'], 'Astrology', fields)).toEqual({ tags: ['Medicine'], outcome: 'not_listed' })
  })

  it('adds a trimmed custom interest', () => {
    expect(addTag([], '  vaccine safety ', interests)).toEqual({ tags: ['vaccine safety'], outcome: 'added' })
  })

  it('refuses a custom interest over 60 characters', () => {
    expect(addTag([], 'x'.repeat(61), interests)).toEqual({ tags: [], outcome: 'too_long' })
    expect(addTag([], 'x'.repeat(60), interests).outcome).toBe('added')
  })

  it('leaves the list unchanged on a sixth tag', () => {
    const five = ['a', 'b', 'c', 'd', 'e']
    expect(addTag(five, 'f', interests)).toEqual({ tags: five, outcome: 'limit' })
  })

  it('ignores a tag already there in another letter case', () => {
    expect(addTag(['Vaccines'], 'vaccines', interests)).toEqual({ tags: ['Vaccines'], outcome: 'duplicate' })
    expect(addTag(['Medicine'], 'medicine', fields).tags).toEqual(['Medicine'])
  })

  it('adds nothing for an empty value', () => {
    expect(addTag(['a'], '   ', interests)).toEqual({ tags: ['a'], outcome: 'empty' })
  })
})

describe('removeTag', () => {
  it('removes only that tag', () => {
    expect(removeTag(['a', 'b', 'c'], 'b')).toEqual(['a', 'c'])
    expect(removeTag(['a', 'b'], 'z')).toEqual(['a', 'b'])
  })
})

describe('moveActive', () => {
  it('moves down and up, wrapping at each end', () => {
    expect(moveActive(null, 3, 1)).toBe(0)
    expect(moveActive(0, 3, 1)).toBe(1)
    expect(moveActive(2, 3, 1)).toBe(0)
    expect(moveActive(null, 3, -1)).toBe(2)
    expect(moveActive(0, 3, -1)).toBe(2)
  })

  it('has nothing to make active in an empty list', () => {
    expect(moveActive(null, 0, 1)).toBeNull()
    expect(moveActive(1, 0, -1)).toBeNull()
  })
})

describe('keyAction', () => {
  const state = { query: 'onc', active: null, matchCount: 2, customAllowed: true }

  it('opens the list and moves on ArrowDown and ArrowUp', () => {
    expect(keyAction('ArrowDown', state)).toEqual({ type: 'move', direction: 1, preventDefault: true })
    expect(keyAction('ArrowUp', state)).toEqual({ type: 'move', direction: -1, preventDefault: true })
  })

  it('adds the active option on Enter, and never submits the form', () => {
    expect(keyAction('Enter', { ...state, active: 1 })).toEqual({ type: 'add-active', preventDefault: true })
  })

  it('adds the custom tag on Enter with nothing active when it is the only option', () => {
    expect(keyAction('Enter', { ...state, matchCount: 0 })).toEqual({ type: 'add-custom', preventDefault: true })
  })

  it('does nothing on Enter with nothing active and several options, or none', () => {
    expect(keyAction('Enter', state)).toEqual({ type: 'none', preventDefault: true })
    expect(keyAction('Enter', { ...state, matchCount: 0, customAllowed: false })).toEqual({
      type: 'none',
      preventDefault: true,
    })
  })

  it('closes the list on Escape, keeping the query', () => {
    expect(keyAction('Escape', state)).toEqual({ type: 'close', preventDefault: true })
  })

  it('removes the last tag on Backspace only when the query is empty', () => {
    expect(keyAction('Backspace', { ...state, query: '' })).toEqual({ type: 'remove-last', preventDefault: false })
    expect(keyAction('Backspace', state)).toEqual({ type: 'none', preventDefault: false })
  })

  it('closes the list on Tab and lets focus move on, adding nothing', () => {
    expect(keyAction('Tab', { ...state, active: 0 })).toEqual({ type: 'close', preventDefault: false })
  })

  it('leaves other keys to the input', () => {
    expect(keyAction('a', state)).toEqual({ type: 'none', preventDefault: false })
  })
})
