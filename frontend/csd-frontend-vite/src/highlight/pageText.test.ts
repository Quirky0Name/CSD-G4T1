import { describe, expect, it } from 'vitest'
import { joinPages, pageText, slicesOf } from './pageText'

describe('pageText', () => {
  it('joins the pieces, breaking lines where pdf.js says, and skips marked content', () => {
    const page = pageText([{ str: 'close to that of Park', hasEOL: true }, {}, { str: 'and Singh [7]' }])
    expect(page.text).toBe('close to that of Park\nand Singh [7]')
    expect(page.items).toEqual([
      { item: 0, start: 0, end: 21 },
      { item: 1, start: 22, end: 35 },
    ])
  })

  it('gives the part of each item a match covers', () => {
    const page = pageText([{ str: 'close to that of Park', hasEOL: true }, { str: 'and Singh [7]' }])
    const start = page.text.indexOf('Park')
    const end = page.text.indexOf('Singh') + 'Singh'.length
    expect(slicesOf(page, start, end)).toEqual([
      { item: 0, from: 17, to: 21 },
      { item: 1, from: 0, to: 9 },
    ])
  })

  it('puts the next page after a line break', () => {
    const joined = joinPages(pageText([{ str: 'end of one' }]), pageText([{ str: 'start of two' }]))
    expect(joined).toEqual({ text: 'end of one\nstart of two', split: 11 })
  })
})
