import { describe, expect, it } from 'vitest'
import { emptyAnswers, fromProfile, toProfile, validateAnswers } from './profileForm'

describe('emptyAnswers', () => {
  it('starts every answer empty', () => {
    expect(emptyAnswers()).toEqual({
      occupation: '',
      occupationOther: '',
      researchFields: [],
      researchInterests: [],
      workTypes: [],
      responsePreferences: '',
    })
  })
})

describe('validateAnswers', () => {
  it('accepts every answer empty', () => {
    expect(validateAnswers(emptyAnswers())).toEqual({})
  })

  it('accepts answers at each limit', () => {
    expect(
      validateAnswers({
        ...emptyAnswers(),
        occupation: 'Other',
        occupationOther: 'x'.repeat(60),
        researchInterests: ['a', 'b', 'c', 'd', 'x'.repeat(60)],
        responsePreferences: 'x'.repeat(500),
      }),
    ).toEqual({})
  })

  it('names each answer over its limit', () => {
    expect(
      validateAnswers({
        ...emptyAnswers(),
        occupation: 'Other',
        occupationOther: 'x'.repeat(61),
        researchInterests: ['a', 'b', 'c', 'd', 'e', 'f'],
        responsePreferences: '😀'.repeat(250) + 'x',
      }),
    ).toEqual({
      occupationOther: 'Please use at most 60 characters.',
      researchInterests: 'You can add up to 5 interests.',
      responsePreferences: 'Please use at most 500 characters.',
    })
    expect(validateAnswers({ ...emptyAnswers(), researchInterests: ['x'.repeat(61)] })).toEqual({
      researchInterests: 'Each interest can be at most 60 characters.',
    })
  })

  it('counts lengths after trimming, as User Management does', () => {
    expect(validateAnswers({ ...emptyAnswers(), responsePreferences: `  ${'x'.repeat(500)}  ` })).toEqual({})
  })

  it("doesn't check the other occupation's text unless Other is chosen, since it isn't sent", () => {
    expect(validateAnswers({ ...emptyAnswers(), occupation: 'PhD student', occupationOther: 'x'.repeat(61) })).toEqual({})
  })
})

describe('toProfile', () => {
  it('trims text, sends null for blank text and empty choices', () => {
    expect(
      toProfile({
        occupation: '',
        occupationOther: '',
        researchFields: [],
        researchInterests: [' vaccine safety '],
        workTypes: ['Clinical'],
        responsePreferences: '   ',
      }),
    ).toEqual({
      occupation: null,
      occupation_other: null,
      research_fields: [],
      research_interests: ['vaccine safety'],
      work_types: ['Clinical'],
      response_preferences: null,
    })
  })

  it('sends the other occupation only with Other', () => {
    const answers = { ...emptyAnswers(), occupationOther: '  Librarian ' }
    expect(toProfile({ ...answers, occupation: 'Other' }).occupation_other).toBe('Librarian')
    expect(toProfile({ ...answers, occupation: 'PhD student' }).occupation_other).toBeNull()
    expect(toProfile({ ...answers, occupation: 'Other', occupationOther: '  ' }).occupation_other).toBeNull()
  })

  it('keeps preferences trimmed, inner lines included', () => {
    expect(toProfile({ ...emptyAnswers(), responsePreferences: '\n Brief.\nThen this. \n' }).response_preferences).toBe(
      'Brief.\nThen this.',
    )
  })
})

describe('fromProfile', () => {
  it("fills the form from a saved profile, a saved Other's text included", () => {
    expect(
      fromProfile({
        occupation: 'Other',
        occupation_other: 'Librarian',
        research_fields: ['Medicine'],
        research_interests: ['vaccine safety'],
        work_types: [],
        response_preferences: null,
      }),
    ).toEqual({
      occupation: 'Other',
      occupationOther: 'Librarian',
      researchFields: ['Medicine'],
      researchInterests: ['vaccine safety'],
      workTypes: [],
      responsePreferences: '',
    })
  })

  it('turns a cleared profile back into the empty form', () => {
    expect(
      fromProfile({
        occupation: null,
        occupation_other: null,
        research_fields: [],
        research_interests: [],
        work_types: [],
        response_preferences: null,
      }),
    ).toEqual(emptyAnswers())
  })
})
