import { describe, expect, it } from 'vitest'
import { locate } from './locate'
import { pageText, type PageText } from './pageText'

const doc: PageText[] = [
  pageText([{ str: 'Page one is about methods.' }]),
  pageText([{ str: 'Our slope of 0.41 is close to that of Park', hasEOL: true }, { str: 'and Singh [7]. The end of' }]),
  pageText([{ str: 'page two runs on here.' }]),
]
const read = async (page: number) => doc[page - 1] ?? null

describe('locate', () => {
  it('finds a quote on its page, as slices of its text items', async () => {
    const located = await locate('close to that of Park and Singh', 2, read)
    expect(located).toEqual({
      found: true,
      slices: new Map([[2, [{ item: 0, from: 21, to: 42 }, { item: 1, from: 0, to: 9 }]]]),
    })
  })

  it('follows a quote onto the next page', async () => {
    const located = await locate('The end of page two', 2, read)
    expect(located.found).toBe(true)
    if (!located.found) return
    expect(located.slices.get(2)).toEqual([{ item: 1, from: 15, to: 25 }])
    expect(located.slices.get(3)).toEqual([{ item: 0, from: 0, to: 8 }])
  })

  it("doesn't find a quote that starts on another page", async () => {
    expect(await locate('page two runs on', 2, read)).toEqual({ found: false })
    expect(await locate('about methods', 2, read)).toEqual({ found: false })
  })

  it("doesn't find anything past the last page", async () => {
    expect(await locate('anything', 9, read)).toEqual({ found: false })
  })
})
