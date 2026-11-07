import { describe, expect, it } from 'vitest'
import { findQuote, joinHyphens, norm } from './normalise'

const found = (quote: string, text: string) => findQuote(quote, text) !== null
const matched = (quote: string, text: string) => {
  const match = findQuote(quote, text)
  return match && text.slice(match.start, match.end)
}

// the cases of backend/tests/research_evaluation/impact/test_quotes.py, so both matchers agree
describe('the rules quotes.py uses', () => {
  it('matches a ligature to the letters it stands for', () => {
    expect(found('the final model', 'in the ﬁnal model')).toBe(true)
  })

  it('matches curly quotes to straight ones', () => {
    expect(found('the authors\' "main" result', 'the authors’ “main” result')).toBe(true)
  })

  it('ignores case and whitespace', () => {
    expect(found('Improved  N-back\naccuracy', 'caffeine improved n-back accuracy')).toBe(true)
  })

  it('lets an ellipsis skip words but keeps their order', () => {
    const text = 'Caffeine improved accuracy in the second session only.'
    expect(found('Caffeine improved ... second session', text)).toBe(true)
    expect(found('Caffeine improved … second session', text)).toBe(true)
    expect(found('second session ... Caffeine improved', text)).toBe(false)
  })

  it("doesn't find a quote that isn't there", () => {
    expect(findQuote('caffeine impaired accuracy', 'caffeine improved accuracy')).toBeNull()
  })

  it('joins a word hyphenated across a line break', () => {
    expect(joinHyphens('cell pro-\nliferation')).toBe('cell proliferation')
  })

  it('keeps a hyphen before a capital or a digit', () => {
    expect(joinHyphens('COVID-\n19 and Spot-\nTest')).toBe('COVID-\n19 and Spot-\nTest')
  })

  it('normalises as quotes.norm does', () => {
    expect(norm('  The  ﬁnal\n“Model”  ')).toBe('the final "model"')
  })
})

describe('findQuote', () => {
  it('gives offsets into the original text', () => {
    const text = 'Results: the slope was 0.45 points.'
    expect(matched('THE SLOPE was 0.45', text)).toBe('the slope was 0.45')
  })

  it('finds a quote across a line break and gives the original span', () => {
    const text = 'is close to that of Park\nand Singh [7] (0.45 per 10 mg)'
    expect(matched('Park and Singh [7]', text)).toBe('Park\nand Singh [7]')
  })

  it('finds a quote across a hyphenated line break and keeps the hyphen in the span', () => {
    const text = 'the dose-response calcu-\nlation was wrong'
    expect(matched('calculation was wrong', text)).toBe('calcu-\nlation was wrong')
  })

  it('maps a ligature back to its one original character', () => {
    const text = 'in the ﬁnal model'
    expect(matched('final model', text)).toBe('ﬁnal model')
  })

  it('spans the whole ellipsis quote', () => {
    const text = 'Caffeine improved accuracy in the second session only.'
    expect(matched('improved ... second', text)).toBe('improved accuracy in the second')
  })

  it('gives null for an empty quote or one that is only an ellipsis', () => {
    expect(findQuote('', 'anything')).toBeNull()
    expect(findQuote('...', 'anything')).toBeNull()
  })
})
