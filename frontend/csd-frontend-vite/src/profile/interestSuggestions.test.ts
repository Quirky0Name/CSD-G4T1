import { describe, expect, it } from 'vitest'
import { INTEREST_SUGGESTIONS } from './interestSuggestions'
import { MAX_INTEREST_LENGTH } from './options'

describe('the interest suggestions', () => {
  it('are unique, ignoring letter case', () => {
    const lower = INTEREST_SUGGESTIONS.map((s) => s.toLowerCase())
    expect(new Set(lower).size).toBe(INTEREST_SUGGESTIONS.length)
  })

  it('each fit within the interest length limit, so any one can be added', () => {
    for (const suggestion of INTEREST_SUGGESTIONS) {
      expect(suggestion.length).toBeLessThanOrEqual(MAX_INTEREST_LENGTH)
      expect(suggestion.trim()).toBe(suggestion)
    }
  })

  it("are OpenAlex's 252 subfields, sorted", () => {
    expect(INTEREST_SUGGESTIONS).toHaveLength(252)
    expect([...INTEREST_SUGGESTIONS]).toEqual([...INTEREST_SUGGESTIONS].sort())
  })
})
